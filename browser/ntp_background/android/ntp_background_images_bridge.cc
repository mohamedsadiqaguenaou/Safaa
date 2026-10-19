/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "brave/browser/ntp_background/android/ntp_background_images_bridge.h"

#include <stddef.h>
#include <stdint.h>

#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "base/android/callback_android.h"
#include "base/android/jni_array.h"
#include "base/android/jni_string.h"
#include "base/files/file_path.h"
#include "base/functional/bind.h"
#include "base/no_destructor.h"
#include "base/values.h"
#include "brave/browser/brave_browser_process.h"
#include "brave/browser/ntp_background/view_counter_service_factory.h"
#include "brave/components/brave_referrals/browser/brave_referrals_service.h"
#include "brave/components/brave_stats/browser/brave_stats_updater_util.h"
#include "brave/components/ntp_background_images/browser/ntp_background_images_data.h"
#include "brave/components/ntp_background_images/browser/sponsored_content/new_tab_takeover/ntp_sponsored_content_data.h"
#include "brave/components/ntp_background_images/browser/url_constants.h"
#include "brave/components/ntp_background_images/browser/view_counter_service.h"
#include "chrome/android/chrome_jni_headers/NTPBackgroundImagesBridge_jni.h"
#include "chrome/browser/profiles/profile.h"
#include "components/keyed_service/content/browser_context_dependency_manager.h"
#include "content/public/browser/browser_context.h"
#include "content/public/browser/browser_thread.h"

using base::android::AttachCurrentThread;
using base::android::ConvertUTF8ToJavaString;
using base::android::JavaRef;
using base::android::ScopedJavaLocalRef;
using content::BrowserThread;
using ntp_background_images::ViewCounterServiceFactory;

namespace ntp_background_images {

NTPBackgroundImagesBridgeFactory::NTPBackgroundImagesBridgeFactory()
    : BrowserContextKeyedServiceFactory(
          "NTPBackgroundImagesBridge",
          BrowserContextDependencyManager::GetInstance()) {}
NTPBackgroundImagesBridgeFactory::~NTPBackgroundImagesBridgeFactory() {}

// static
NTPBackgroundImagesBridge* NTPBackgroundImagesBridgeFactory::GetForProfile(
    Profile* profile) {
  return static_cast<NTPBackgroundImagesBridge*>(
      GetInstance()->GetServiceForBrowserContext(profile, true));
}

// static
NTPBackgroundImagesBridgeFactory*
NTPBackgroundImagesBridgeFactory::GetInstance() {
  static base::NoDestructor<NTPBackgroundImagesBridgeFactory> instance;
  return instance.get();
}

std::unique_ptr<KeyedService>
NTPBackgroundImagesBridgeFactory::BuildServiceInstanceForBrowserContext(
    content::BrowserContext* context) const {
  return std::make_unique<NTPBackgroundImagesBridge>(
      Profile::FromBrowserContext(context));
}

bool NTPBackgroundImagesBridgeFactory::ServiceIsCreatedWithBrowserContext()
    const {
  return true;
}

}  // namespace ntp_background_images

NTPBackgroundImagesBridge::NTPBackgroundImagesBridge(Profile* profile)
    : profile_(profile),
      view_counter_service_(
          ViewCounterServiceFactory::GetForProfile(profile_)) {
  DCHECK_CURRENTLY_ON(BrowserThread::UI);

  java_object_.Reset(Java_NTPBackgroundImagesBridge_create(
      AttachCurrentThread(), reinterpret_cast<intptr_t>(this)));

  if (g_brave_browser_process->ntp_background_images_service()) {
    ntp_background_images_service_observation_.Observe(
        g_brave_browser_process->ntp_background_images_service());
  }
}

NTPBackgroundImagesBridge::~NTPBackgroundImagesBridge() {
  Java_NTPBackgroundImagesBridge_destroy(AttachCurrentThread(), java_object_);
}

static base::android::ScopedJavaLocalRef<jobject>
JNI_NTPBackgroundImagesBridge_GetInstance(JNIEnv* env,
                                          const JavaRef<jobject>& j_profile) {
  auto* profile = Profile::FromJavaObject(j_profile);
  return ntp_background_images::NTPBackgroundImagesBridgeFactory::GetInstance()
      ->GetForProfile(profile)
      ->GetJavaObject();
}

base::android::ScopedJavaLocalRef<jobject>
NTPBackgroundImagesBridge::GetJavaObject() {
  return base::android::ScopedJavaLocalRef<jobject>(java_object_);
}

base::android::ScopedJavaLocalRef<jobject>
NTPBackgroundImagesBridge::CreateWallpaper(const base::DictValue& data) {
  JNIEnv* env = AttachCurrentThread();

  auto* image_path =
      data.FindString(ntp_background_images::kWallpaperFilePathKey);
  auto* author = data.FindString(ntp_background_images::kImageAuthorKey);
  auto* link = data.FindString(ntp_background_images::kImageLinkKey);

  return Java_NTPBackgroundImagesBridge_createWallpaper(
      env, ConvertUTF8ToJavaString(env, *image_path),
      ConvertUTF8ToJavaString(env, author ? *author : ""),
      ConvertUTF8ToJavaString(env, link ? *link : ""));
}

void NTPBackgroundImagesBridge::GetCurrentWallpaper(
    JNIEnv* env,
    const JavaRef<jobject>& obj,
    const JavaRef<jobject>& jcallback) {
  DCHECK_CURRENTLY_ON(BrowserThread::UI);
  if (!view_counter_service_) {
    base::android::RunObjectCallbackAndroid(jcallback, /*wallpaper=*/nullptr);
    return;
  }

  base::android::ScopedJavaGlobalRef<jobject> callback(jcallback);
  view_counter_service_->GetCurrentWallpaperForDisplay(
      base::BindOnce(&NTPBackgroundImagesBridge::GetCurrentWallpaperCallback,
                     weak_ptr_factory_.GetWeakPtr(), std::move(callback)),
      /*allow_sponsored_content=*/false);
}

void NTPBackgroundImagesBridge::GetCurrentWallpaperCallback(
    base::android::ScopedJavaGlobalRef<jobject> callback,
    std::optional<base::DictValue> data) {
  DCHECK_CURRENTLY_ON(BrowserThread::UI);
  if (!data) {
    base::android::RunObjectCallbackAndroid(callback, /*wallpaper=*/nullptr);
    return;
  }

  view_counter_service_->RegisterPageView();

  const bool is_background =
      data->FindBool(ntp_background_images::kIsBackgroundKey).value_or(false);
  if (!is_background) {
    base::android::RunObjectCallbackAndroid(callback, /*wallpaper=*/nullptr);
    return;
  }
  base::android::RunObjectCallbackAndroid(callback, CreateWallpaper(*data));
}

void NTPBackgroundImagesBridge::OnBackgroundImagesDataDidUpdate(
    ntp_background_images::NTPBackgroundImagesData* data) {
  JNIEnv* env = AttachCurrentThread();
  Java_NTPBackgroundImagesBridge_onUpdated(env, java_object_);
}

void NTPBackgroundImagesBridge::DeprecatedOnSponsoredContentDidUpdate(
    ntp_background_images::NTPSponsoredContentData* data) {
  JNIEnv* env = AttachCurrentThread();
  Java_NTPBackgroundImagesBridge_onUpdated(env, java_object_);
}

DEFINE_JNI(NTPBackgroundImagesBridge)
