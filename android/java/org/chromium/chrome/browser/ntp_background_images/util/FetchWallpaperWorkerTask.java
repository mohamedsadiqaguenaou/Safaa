/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp_background_images.util;

import android.graphics.Bitmap;

import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.ntp_background_images.model.NTPImage;

public class FetchWallpaperWorkerTask extends AsyncTask<Bitmap> {
    public interface WallpaperRetrievedCallback {
        void bgWallpaperRetrieved(Bitmap bgWallpaper);
    }

    private final NTPImage mNTPImage;
    private final int mLayoutWidth;
    private final int mLayoutHeight;

    // The callback to use to communicate the results.
    private final WallpaperRetrievedCallback mCallback;

    public FetchWallpaperWorkerTask(
            NTPImage ntpImage,
            int layoutWidth,
            int layoutHeight,
            WallpaperRetrievedCallback callback) {
        mNTPImage = ntpImage;
        mLayoutWidth = layoutWidth;
        mLayoutHeight = layoutHeight;
        mCallback = callback;
    }

    @Override
    protected Bitmap doInBackground() {
        return NTPImageUtil.getWallpaperBitmap(mNTPImage, mLayoutWidth, mLayoutHeight);
    }

    @Override
    protected void onPostExecute(Bitmap wallpaper) {
        assert ThreadUtils.runningOnUiThread();

        if (isCancelled()) return;

        if (wallpaper != null && !wallpaper.isRecycled()) {
            mCallback.bgWallpaperRetrieved(wallpaper);
        }
    }
}
