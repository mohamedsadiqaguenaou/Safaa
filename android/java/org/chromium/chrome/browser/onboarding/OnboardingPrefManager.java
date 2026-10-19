/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */
package org.chromium.chrome.browser.onboarding;

import android.content.SharedPreferences;

import org.chromium.base.ContextUtils;

/**
 * Provides information regarding onboarding.
 */
public class OnboardingPrefManager {

    private static final String PREF_P3A_ONBOARDING = "p3a_onboarding";
    private static final String PREF_P3A_CRASH_REPORTING_MESSAGE_SHOWN =
            "p3a_crash_reporting_message_shown";

    private static final String PREF_NOTIFICATION_PERMISSION_ENABLING_DIALOG_FROM_SETTING =
            "notification_permission_enabling_dialog_from_setting";

    private static OnboardingPrefManager sInstance;

    private final SharedPreferences mSharedPreferences;

    public static final String BRAVE = "Brave";
    public static final String YANDEX = "Yandex";
    public static final String YAHOO_JP = "Yahoo! JAPAN";

    private OnboardingPrefManager() {
        mSharedPreferences = ContextUtils.getAppSharedPreferences();
    }

    /**
     * Returns the singleton instance of OnboardingPrefManager, creating it if needed.
     */
    public static OnboardingPrefManager getInstance() {
        if (sInstance == null) {
            sInstance = new OnboardingPrefManager();
        }
        return sInstance;
    }

    public boolean isP3aCrashReportingMessageShown() {
        return mSharedPreferences.getBoolean(PREF_P3A_CRASH_REPORTING_MESSAGE_SHOWN, false);
    }

    public void setP3aCrashReportingMessageShown(boolean isShown) {
        SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putBoolean(PREF_P3A_CRASH_REPORTING_MESSAGE_SHOWN, isShown);
        sharedPreferencesEditor.apply();
    }

    /** Sets the user preference for whether the onboarding is shown. */
    public void setP3aOnboardingShown(boolean isShown) {
        SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putBoolean(PREF_P3A_ONBOARDING, isShown);
        sharedPreferencesEditor.apply();
    }

    /**
     * Returns the user preference for whether the Notification Permission Enabling dialog is shown
     * From setting.
     */
    public boolean isNotificationPermissionEnablingDialogShownFromSetting() {
        return mSharedPreferences.getBoolean(
                PREF_NOTIFICATION_PERMISSION_ENABLING_DIALOG_FROM_SETTING, false);
    }

    /**
     * Sets the user preference for whether the Notification Permission Enabling dialog is shown
     * From setting.
     */
    public void setNotificationPermissionEnablingDialogShownFromSetting(boolean isShown) {
        SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putBoolean(
                PREF_NOTIFICATION_PERMISSION_ENABLING_DIALOG_FROM_SETTING, isShown);
        sharedPreferencesEditor.apply();
    }
}
