/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.build.annotations.NullMarked;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;

@NullMarked
public class NtpUtil {
    public static final int TOP_SITES_MODE_SHORTCUTS = 0;
    public static final int TOP_SITES_MODE_FREQUENT = 1;

    // Mirror of the Settings-screen pref key of the same name, duplicated (rather than imported)
    // so this class does not depend on the settings package, which lets it live in its own build
    // target. Keep in sync with BackgroundImagesPreferences.PREF_SHOW_TOP_SITES.
    private static final String PREF_SHOW_TOP_SITES = "show_top_sites";

    public static boolean shouldDisplayTopSites() {
        return ChromeSharedPreferences.getInstance().readBoolean(PREF_SHOW_TOP_SITES, true);
    }

    public static void setDisplayTopSites(boolean shouldDisplayTopSites) {
        ChromeSharedPreferences.getInstance()
                .writeBoolean(PREF_SHOW_TOP_SITES, shouldDisplayTopSites);
    }

    public static int getTopSitesDisplayMode() {
        // Default to frequent: shortcuts mode shows only manually-pinned custom links, which are
        // empty until the user adds one, so defaulting to shortcuts would show an empty widget
        // for most users (existing users upgrading, and new installs alike).
        return ChromeSharedPreferences.getInstance()
                .readInt(
                        BravePreferenceKeys.BRAVE_NTP_TOP_SITES_DISPLAY_MODE,
                        TOP_SITES_MODE_FREQUENT);
    }

    public static void setTopSitesDisplayMode(int mode) {
        ChromeSharedPreferences.getInstance()
                .writeInt(BravePreferenceKeys.BRAVE_NTP_TOP_SITES_DISPLAY_MODE, mode);
    }
}
