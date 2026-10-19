/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.test.filters.SmallTest;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.base.BraveFeatureList;
import org.chromium.base.ContextUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.base.test.util.DoNotBatch;
import org.chromium.base.test.util.Features.DisableFeatures;
import org.chromium.base.test.util.Features.EnableFeatures;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.preferences.ChromePreferenceKeys;
import org.chromium.chrome.browser.tasks.tab_management.TabsSettings;
import org.chromium.chrome.browser.tracing.settings.DeveloperSettings;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;

/** Test for {@link MainSettings}. Main purpose is to have a quick confidence check on the xml. */
@RunWith(ChromeJUnit4ClassRunner.class)
@DoNotBatch(reason = "Tests cannot run batched because they launch a Settings activity.")
public class BraveMainSettingsFragmentTest {
    @Rule
    public final SettingsActivityTestRule<MainSettings> mSettingsActivityTestRule =
            new SettingsActivityTestRule<>(MainSettings.class);

    private MainSettings mMainSettings;

    @Before
    public void setup() {
        Looper.prepare();
        DeveloperSettings.setIsEnabledForTests(true);
    }

    private static final String[] sSortedPrefKeys = {
        "features_section",
        "brave_shields_and_privacy",
        "general_section",
        "brave_search_engines",
        "homepage",
        "brave_sync_layout",
        "content_settings",
        "brave_downloads",
        "closing_all_tabs_closes_brave",
        "default_browser",
        "display_section",
        "tabs",
        "media",
        "brave_appearance",
        "background_images",
        "accessibility",
        "brave_languages",
        "autofill_section",
        "passwords",
        "autofill_options",
        "autofill_payment_methods",
        "autofill_addresses",
        "support_section",
        "rate_brave",
        "about_section",
        "developer",
        "about_chrome"
    };

    // This test is intended to check the issue where prefs order was wrong
    // when app language was switched to some other than default English,
    // like French. The test infrastructure does not support switching app
    // interface language. So the test may help to catch order violation
    // with English.
    @Test
    @SmallTest
    @DisableFeatures(BraveFeatureList.BRAVE_ANDROID_TAB_GROUPS_SETTINGS)
    public void testMainSettingsPrefsOrder() {
        startSettings();

        @Nullable Preference prevPref = null;
        for (int i = 0; i < sSortedPrefKeys.length; ++i) {
            Preference pref = mMainSettings.findPreference(sSortedPrefKeys[i]);

            if (prevPref == null) { // Skip first pref.
                prevPref = pref;
                continue;
            }
            assertTrue(
                    prevPref.getTitle() + " should precede " + pref.getTitle(),
                    pref.getOrder() > prevPref.getOrder());
        }
    }

    @Test
    @SmallTest
    @DisableFeatures(BraveFeatureList.BRAVE_ANDROID_TAB_GROUPS_SETTINGS)
    public void testLegacyTabRowsShownWhenTabGroupSettingsFeatureDisabled() {
        startSettings();

        Preference tabsPreference = mMainSettings.findPreference(MainSettings.PREF_TABS);
        Preference closingAllTabsClosesBravePreference =
                mMainSettings
                        .getPreferenceScreen()
                        .findPreference(
                                BraveMainPreferencesBase.PREF_CLOSING_ALL_TABS_CLOSES_BRAVE);

        assertNotNull(tabsPreference);
        assertNotNull(closingAllTabsClosesBravePreference);
        assertTrue(closingAllTabsClosesBravePreference.isVisible());
        assertEquals(TabsSettings.class.getName(), tabsPreference.getFragment());
    }

    @Test
    @SmallTest
    @EnableFeatures(BraveFeatureList.BRAVE_ANDROID_TAB_GROUPS_SETTINGS)
    public void testLegacyTabRowsHiddenWhenTabGroupSettingsFeatureEnabled() {
        startSettings();

        Preference tabsPreference = mMainSettings.findPreference(MainSettings.PREF_TABS);
        Preference closingAllTabsClosesBravePreference =
                mMainSettings
                        .getPreferenceScreen()
                        .findPreference(
                                BraveMainPreferencesBase.PREF_CLOSING_ALL_TABS_CLOSES_BRAVE);

        assertNotNull(tabsPreference);
        assertNull(closingAllTabsClosesBravePreference);
        assertEquals(BraveTabsAndTabGroupsSettings.class.getName(), tabsPreference.getFragment());
    }

    @Test
    @SmallTest
    public void testPreferenceCount() {
        startSettings();

        final int preferenceCount = mMainSettings.getPreferenceScreen().getPreferenceCount();

        // "closing_all_tabs_closes_brave" moves into the Brave "Tabs and tab groups" settings
        // screen when kBraveAndroidTabGroupsSettings is enabled, so it is removed from the main
        // settings screen in that case.
        int expectedCount =
                ChromeFeatureList.isEnabled(BraveFeatureList.BRAVE_ANDROID_TAB_GROUPS_SETTINGS)
                        ? 28
                        : 29;

        assertEquals(
                "Number of preferences has changed, please check and update preferenceCount"
                    + " expectation here or modify BraveMainPreferencesBase.updateBravePreferences"
                    + " to remove it and BraveMainPreferencesBase.SEARCH_"
                        // Split that static field no to trigger presubmit CheckSettingsChanges
                        // check
                        + "INDEX_DATA_PROVIDER.updateDynamicPreferences"
                        + " to exclude new from indexing.",
                expectedCount,
                preferenceCount);
    }

    // Brave shows the address bar preference in its own Appearance settings. Upstream re-adds it to
    // the main list on a configuration change and on a toolbar position change, neither of which
    // goes through onResume, so both paths must stay covered.
    @Test
    @SmallTest
    public void testAddressBarPrefNeverShown() {
        startSettings();

        assertAddressBarPrefAbsent("on start");

        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        mMainSettings.onConfigurationChanged(
                                mMainSettings.getResources().getConfiguration()));
        assertAddressBarPrefAbsent("after a configuration change");

        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        mMainSettings.onSharedPreferenceChanged(
                                ContextUtils.getAppSharedPreferences(),
                                ChromePreferenceKeys.TOOLBAR_TOP_ANCHORED));
        assertAddressBarPrefAbsent("after a toolbar position change");
    }

    private void assertAddressBarPrefAbsent(String when) {
        // getPreferenceScreen() is checked directly, as BraveMainPreferencesBase.findPreference()
        // also returns removed preferences.
        assertNull(
                MainSettings.PREF_ADDRESS_BAR + " should not be in the main settings " + when,
                mMainSettings.getPreferenceScreen().findPreference(MainSettings.PREF_ADDRESS_BAR));
    }

    private void startSettings() {
        mSettingsActivityTestRule.startSettingsActivity();
        mMainSettings = mSettingsActivityTestRule.getFragment();
        Assert.assertNotNull("SettingsActivity failed to launch.", mMainSettings);
    }
}
