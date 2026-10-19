/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp_background_images;

import org.jni_zero.CalledByNative;
import org.jni_zero.NativeMethods;

import org.chromium.base.Callback;
import org.chromium.base.ObserverList;
import org.chromium.base.ThreadUtils;
import org.chromium.chrome.browser.ntp_background_images.model.BackgroundImage;
import org.chromium.chrome.browser.ntp_background_images.model.ImageCredit;
import org.chromium.chrome.browser.ntp_background_images.model.NTPImage;
import org.chromium.chrome.browser.profiles.Profile;

public class NTPBackgroundImagesBridge {
    private long mNativeNTPBackgroundImagesBridge;
    private final ObserverList<NTPBackgroundImageServiceObserver> mObservers =
            new ObserverList<NTPBackgroundImageServiceObserver>();

    public abstract static class NTPBackgroundImageServiceObserver {
        public abstract void onUpdated();
    }

    public NTPBackgroundImagesBridge(long nativeNTPBackgroundImagesBridge) {
        ThreadUtils.assertOnUiThread();
        mNativeNTPBackgroundImagesBridge = nativeNTPBackgroundImagesBridge;
    }

    @CalledByNative
    private static NTPBackgroundImagesBridge create(long nativeNTPBackgroundImagesBridge) {
        return new NTPBackgroundImagesBridge(nativeNTPBackgroundImagesBridge);
    }

    /**
     * Destroys this instance so no further calls can be executed.
     */
    @CalledByNative
    public void destroy() {
        mNativeNTPBackgroundImagesBridge = 0;
        mObservers.clear();
    }

    /**
     * @param observer The observer to be added.
     */
    public void addObserver(NTPBackgroundImageServiceObserver observer) {
        mObservers.addObserver(observer);
    }

    /**
     * @param observer The observer to be removed.
     */
    public void removeObserver(NTPBackgroundImageServiceObserver observer) {
        mObservers.removeObserver(observer);
    }

    public static NTPBackgroundImagesBridge getInstance(Profile profile) {
        return NTPBackgroundImagesBridgeJni.get().getInstance(profile);
    }

    public void getCurrentWallpaper(Callback<NTPImage> callback) {
        ThreadUtils.assertOnUiThread();
        NTPBackgroundImagesBridgeJni.get()
                .getCurrentWallpaper(
                        mNativeNTPBackgroundImagesBridge, NTPBackgroundImagesBridge.this, callback);
    }

    @CalledByNative
    public static BackgroundImage createWallpaper(String imagePath, String author, String link) {
        return new BackgroundImage(imagePath, 0, 0, new ImageCredit(author, link));
    }

    @CalledByNative
    public void onUpdated() {
        for (NTPBackgroundImageServiceObserver observer : mObservers) {
            observer.onUpdated();
        }
    }

    @NativeMethods
    interface Natives {
        NTPBackgroundImagesBridge getInstance(Profile profile);

        void getCurrentWallpaper(
                long nativeNTPBackgroundImagesBridge,
                NTPBackgroundImagesBridge caller,
                Callback<NTPImage> callback);
    }
}
