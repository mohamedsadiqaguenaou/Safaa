/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/android/nomusic/nomusic_tab_helper.h"

#include <string>

#include "base/base64.h"
#include "base/functional/bind.h"
#include "base/functional/callback_helpers.h"
#include "base/memory/weak_ptr.h"
#include "base/no_destructor.h"
#include "base/strings/strcat.h"
#include "base/strings/utf_string_conversions.h"
#include "base/values.h"
#include "brave/components/constants/pref_names.h"
#include "brave/components/nomusic/resources/grit/nomusic_resources.h"
#include "brave/components/script_injector/common/mojom/script_injector.mojom.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/common/chrome_isolated_world_ids.h"
#include "components/prefs/pref_service.h"
#include "content/public/browser/document_user_data.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "mojo/public/cpp/bindings/associated_remote.h"
#include "third_party/blink/public/common/associated_interfaces/associated_interface_provider.h"
#include "ui/base/resource/resource_bundle.h"
#include "url/gurl.h"

namespace nomusic {

namespace {

// Resolves with 'media' once the frame plays media (right away if it already
// does). If the engine is already in the frame it is re-enabled instead and
// 'engine' is returned. Repeated runs in a document share one promise.
constexpr char16_t kWatcherScript[] =
    uR"(
(function() {
  const api = globalThis.__braveNoMusic;
  if (api) {
    api.setEnabled(true);
    return 'engine';
  }
  if (!globalThis.__braveNoMusicWatcher) {
    globalThis.__braveNoMusicWatcher = new Promise((resolve) => {
      for (const media of document.querySelectorAll('video, audio')) {
        if (!media.paused) {
          resolve('media');
          return;
        }
      }
      const onPlay = (event) => {
        if (event.target instanceof HTMLMediaElement) {
          document.removeEventListener('play', onPlay, true);
          resolve('media');
        }
      };
      document.addEventListener('play', onPlay, true);
    });
  }
  return globalThis.__braveNoMusicWatcher;
}());
)";

constexpr char16_t kDisableScript[] =
    uR"(
(function() {
  const api = globalThis.__braveNoMusic;
  if (api) {
    api.setEnabled(false);
  }
}());
)";

PrefService* GetPrefs(content::BrowserContext* context) {
  // NoMusic is one switch shared by regular and private tabs.
  return Profile::FromBrowserContext(context)->GetOriginalProfile()->GetPrefs();
}

bool IsNoMusicEnabled(content::BrowserContext* context) {
  return GetPrefs(context)->GetBoolean(kNoMusicEnabled);
}

// nomusic_engine.js applied to the base64 of fastenhancer_t.wasm and of
// rnnoise.wasm.
const std::u16string& GetEngineScript() {
  static const base::NoDestructor<std::u16string> script([] {
    auto& bundle = ui::ResourceBundle::GetSharedInstance();
    const std::string engine =
        bundle.LoadDataResourceString(IDR_NOMUSIC_ENGINE_JS);
    const std::string fastenhancer =
        bundle.LoadDataResourceString(IDR_NOMUSIC_FASTENHANCER_T_WASM);
    const std::string rnnoise =
        bundle.LoadDataResourceString(IDR_NOMUSIC_RNNOISE_WASM);
    return base::UTF8ToUTF16(
        base::StrCat({"(", engine, ")(\"", base::Base64Encode(fastenhancer),
                      "\", \"", base::Base64Encode(rnnoise), "\");"}));
  }());
  return *script;
}

// NoMusic state of one document. It owns the injector remote, so a pending
// watcher callback goes away together with the document it was waiting on.
class NoMusicDocumentState
    : public content::DocumentUserData<NoMusicDocumentState> {
 public:
  NoMusicDocumentState(const NoMusicDocumentState&) = delete;
  NoMusicDocumentState& operator=(const NoMusicDocumentState&) = delete;
  ~NoMusicDocumentState() override = default;

  void Enable() {
    if (engine_injected_) {
      // The watcher re-enables an engine that is already there.
      Execute(kWatcherScript);
      return;
    }
    if (watcher_pending_) {
      return;
    }
    watcher_pending_ = true;
    GetInjector()->RequestAsyncExecuteScript(
        ISOLATED_WORLD_ID_BRAVE_INTERNAL, kWatcherScript,
        blink::mojom::UserActivationOption::kDoNotActivate,
        blink::mojom::PromiseResultOption::kAwait,
        base::BindOnce(&NoMusicDocumentState::OnWatcherResult,
                       weak_factory_.GetWeakPtr()));
  }

  void Disable() {
    // A watcher result that arrives while NoMusic is off is ignored.
    if (engine_injected_) {
      Execute(kDisableScript);
    }
  }

 private:
  friend DocumentUserData;

  explicit NoMusicDocumentState(content::RenderFrameHost* rfh)
      : DocumentUserData(rfh) {}

  script_injector::mojom::ScriptInjector* GetInjector() {
    if (!injector_.is_bound()) {
      render_frame_host().GetRemoteAssociatedInterfaces()->GetInterface(
          &injector_);
      injector_.reset_on_disconnect();
    }
    return injector_.get();
  }

  void Execute(const std::u16string& script) {
    GetInjector()->RequestAsyncExecuteScript(
        ISOLATED_WORLD_ID_BRAVE_INTERNAL, script,
        blink::mojom::UserActivationOption::kDoNotActivate,
        blink::mojom::PromiseResultOption::kDoNotWait, base::DoNothing());
  }

  void OnWatcherResult(base::Value result) {
    watcher_pending_ = false;
    const std::string* kind = result.GetIfString();
    if (!kind) {
      return;
    }
    if (*kind == "engine") {
      engine_injected_ = true;
      return;
    }
    if (*kind != "media" || engine_injected_ ||
        !IsNoMusicEnabled(render_frame_host().GetBrowserContext())) {
      return;
    }
    engine_injected_ = true;
    Execute(GetEngineScript());
  }

  mojo::AssociatedRemote<script_injector::mojom::ScriptInjector> injector_;
  bool watcher_pending_ = false;
  bool engine_injected_ = false;
  base::WeakPtrFactory<NoMusicDocumentState> weak_factory_{this};

  DOCUMENT_USER_DATA_KEY_DECL();
};

DOCUMENT_USER_DATA_KEY_IMPL(NoMusicDocumentState);

}  // namespace

NoMusicTabHelper::NoMusicTabHelper(content::WebContents* contents)
    : content::WebContentsObserver(contents),
      content::WebContentsUserData<NoMusicTabHelper>(*contents) {
  pref_change_registrar_.Init(GetPrefs(contents->GetBrowserContext()));
  pref_change_registrar_.Add(
      kNoMusicEnabled, base::BindRepeating(&NoMusicTabHelper::ApplyToPrimaryPage,
                                           base::Unretained(this)));
}

NoMusicTabHelper::~NoMusicTabHelper() = default;

bool NoMusicTabHelper::IsEnabled() const {
  return IsNoMusicEnabled(web_contents()->GetBrowserContext());
}

void NoMusicTabHelper::PrimaryPageChanged(content::Page& page) {
  // Besides new pages, this covers pages coming back from the back/forward
  // cache or from prerendering, which may have missed a pref change.
  ApplyToPrimaryPage();
}

void NoMusicTabHelper::DOMContentLoaded(
    content::RenderFrameHost* render_frame_host) {
  if (IsEnabled()) {
    ApplyToFrame(render_frame_host);
  }
}

void NoMusicTabHelper::ApplyToPrimaryPage() {
  web_contents()->GetPrimaryMainFrame()->ForEachRenderFrameHost(
      [this](content::RenderFrameHost* render_frame_host) {
        ApplyToFrame(render_frame_host);
      });
}

void NoMusicTabHelper::ApplyToFrame(
    content::RenderFrameHost* render_frame_host) {
  if (!render_frame_host->IsActive() ||
      !render_frame_host->IsRenderFrameLive() ||
      !render_frame_host->GetLastCommittedURL().SchemeIsHTTPOrHTTPS()) {
    return;
  }
  if (IsEnabled()) {
    NoMusicDocumentState::GetOrCreateForCurrentDocument(render_frame_host)
        ->Enable();
  } else if (auto* state =
                 NoMusicDocumentState::GetForCurrentDocument(render_frame_host)) {
    state->Disable();
  }
}

WEB_CONTENTS_USER_DATA_KEY_IMPL(NoMusicTabHelper);

}  // namespace nomusic
