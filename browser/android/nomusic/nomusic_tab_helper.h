/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef BRAVE_BROWSER_ANDROID_NOMUSIC_NOMUSIC_TAB_HELPER_H_
#define BRAVE_BROWSER_ANDROID_NOMUSIC_NOMUSIC_TAB_HELPER_H_

#include "components/prefs/pref_change_registrar.h"
#include "content/public/browser/web_contents_observer.h"
#include "content/public/browser/web_contents_user_data.h"

namespace content {
class Page;
class RenderFrameHost;
}  // namespace content

namespace nomusic {

// Removes background music from the media of a tab while the kNoMusicEnabled
// pref is on.
//
// Every web frame of the tab gets a tiny watcher script in Brave's internal
// isolated world. Only once a frame starts playing media does it receive the
// NoMusic engine (nomusic_engine.js with fastenhancer_t.wasm and rnnoise.wasm),
// which reroutes the frame's media elements through the FastEnhancer-T speech
// enhancer and an RNNoise post-filter with Web Audio. Turning the pref off puts
// running engines into bypass.
class NoMusicTabHelper : public content::WebContentsObserver,
                         public content::WebContentsUserData<NoMusicTabHelper> {
 public:
  NoMusicTabHelper(const NoMusicTabHelper&) = delete;
  NoMusicTabHelper& operator=(const NoMusicTabHelper&) = delete;
  ~NoMusicTabHelper() override;

  // content::WebContentsObserver:
  void PrimaryPageChanged(content::Page& page) override;
  void DOMContentLoaded(content::RenderFrameHost* render_frame_host) override;

 private:
  friend class content::WebContentsUserData<NoMusicTabHelper>;

  explicit NoMusicTabHelper(content::WebContents* contents);

  bool IsEnabled() const;
  void ApplyToPrimaryPage();
  void ApplyToFrame(content::RenderFrameHost* render_frame_host);

  PrefChangeRegistrar pref_change_registrar_;

  WEB_CONTENTS_USER_DATA_KEY_DECL();
};

}  // namespace nomusic

#endif  // BRAVE_BROWSER_ANDROID_NOMUSIC_NOMUSIC_TAB_HELPER_H_
