/**
 * Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.chromium.chrome.browser.omnibox.suggestions;

import android.content.Context;

import androidx.annotation.NonNull;

import org.chromium.base.BraveFeatureList;
import org.chromium.base.supplier.MonotonicObservableSupplier;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.browser_controls.BrowserControlsStateProvider.ControlsPosition;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.omnibox.OmniboxPrefManager;
import org.chromium.chrome.browser.omnibox.UrlBarEditingTextStateProvider;
import org.chromium.chrome.browser.omnibox.styles.OmniboxResourceProvider;
import org.chromium.chrome.browser.omnibox.suggestions.basic.BasicSuggestionProcessor.BookmarkState;
import org.chromium.chrome.browser.omnibox.suggestions.brave_search.BraveSearchBannerProcessor;
import org.chromium.chrome.browser.preferences.BravePref;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.chrome.browser.search_engines.settings.BraveSearchEngineAdapter;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.components.omnibox.AutocompleteInput;
import org.chromium.components.omnibox.AutocompleteResult;
import org.chromium.components.omnibox.GroupsProto.GroupConfig;
import org.chromium.components.omnibox.action.OmniboxActionDelegate;
import org.chromium.components.omnibox.suggestions.OmniboxSuggestionUiType;
import org.chromium.components.user_prefs.UserPrefs;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.modelutil.PropertyModel;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

class BraveDropdownItemViewInfoListBuilder extends DropdownItemViewInfoListBuilder {
    private @Nullable BraveSearchBannerProcessor mBraveSearchBannerProcessor;
    private UrlBarEditingTextStateProvider mUrlBarEditingTextProvider;
    private final @NonNull Supplier<Tab> mActivityTabSupplier;
    private static final List<String> sBraveSearchEngineDefaultRegions =
            Arrays.asList("CA", "DE", "FR", "GB", "US", "AT", "ES", "MX");
    private AutocompleteDelegate mAutocompleteDelegate;
    private @Nullable Profile mProfile;

    BraveDropdownItemViewInfoListBuilder(
            Supplier<@Nullable Tab> tabSupplier,
            BookmarkState bookmarkState,
            MonotonicObservableSupplier<@ControlsPosition Integer> toolbarPositionSupplier,
            OmniboxResourceProvider resourceProvider) {
        super(tabSupplier, bookmarkState, toolbarPositionSupplier, resourceProvider);

        mActivityTabSupplier = tabSupplier;
    }

    public void setAutocompleteDelegate(AutocompleteDelegate autocompleteDelegate) {
        mAutocompleteDelegate = autocompleteDelegate;
    }

    @Override
    void setProfile(Profile profile) {
        super.setProfile(profile);
        mProfile = profile;
    }

    @Override
    void initDefaultProcessors(
            Context context,
            SuggestionHost host,
            UrlBarEditingTextStateProvider textProvider,
            OmniboxActionDelegate actionDelegate) {
        mUrlBarEditingTextProvider = textProvider;
        super.initDefaultProcessors(context, host, textProvider, actionDelegate);
        if (host instanceof BraveSuggestionHost) {
            mBraveSearchBannerProcessor =
                    new BraveSearchBannerProcessor(
                            context,
                            (BraveSuggestionHost) host,
                            textProvider,
                            mAutocompleteDelegate);
        }
    }

    @Override
    void onOmniboxSessionStateChange(boolean activated) {
        super.onOmniboxSessionStateChange(activated);
        mBraveSearchBannerProcessor.onOmniboxSessionStateChange(activated);
    }

    @Override
    void onNativeInitialized() {
        super.onNativeInitialized();
        mBraveSearchBannerProcessor.onNativeInitialized();
    }

    @Override
    @NonNull
    List<DropdownItemViewInfo> buildDropdownViewInfoList(
            AutocompleteInput input, AutocompleteResult autocompleteResult) {
        mBraveSearchBannerProcessor.onSuggestionsReceived();
        List<DropdownItemViewInfo> viewInfoList =
                super.buildDropdownViewInfoList(input, autocompleteResult);

        Tab tab = mActivityTabSupplier.get();
        boolean autocompleteEnabled;
        if (tab != null) {
            autocompleteEnabled = isAutoCompleteEnabled(tab.getWebContents());
        } else {
            // The search widget launches SearchActivity without a tab, so use the profile
            // pushed in via setProfile to honor the "Show browser suggestions" preference.
            // If the profile hasn't been pushed in yet (e.g. cold start from the widget after
            // a force-stop), suppress suggestions until we can read the pref.
            autocompleteEnabled =
                    mProfile != null
                            && UserPrefs.get(mProfile).getBoolean(BravePref.AUTOCOMPLETE_ENABLED);
        }
        if (!autocompleteEnabled && viewInfoList.size() > 0) {
            DropdownItemViewInfo firstObj = viewInfoList.get(0);
            viewInfoList.clear();
            if (firstObj.processor != null
                    && (firstObj.processor.getViewTypeId()
                                    == OmniboxSuggestionUiType.EDIT_URL_SUGGESTION
                            || firstObj.processor.getViewTypeId()
                                    == OmniboxSuggestionUiType.CLIPBOARD_SUGGESTION)) {
                viewInfoList.add(firstObj);
            }
        }

        if (isBraveSearchPromoBanner() && autocompleteEnabled) {
            final PropertyModel model = mBraveSearchBannerProcessor.createModel();
            mBraveSearchBannerProcessor.populateModel(model);
            viewInfoList.add(
                    new DropdownItemViewInfo(
                            mBraveSearchBannerProcessor, model, GroupConfig.getDefaultInstance()));
        }

        return viewInfoList;
    }

    /** Whether the "Show browser suggestions" setting is on for the tab's profile. */
    private static boolean isAutoCompleteEnabled(@Nullable WebContents webContents) {
        if (ProfileManager.isInitialized()) {
            Profile profile = Profile.fromWebContents(webContents);
            if (profile != null
                    && !UserPrefs.get(profile).getBoolean(BravePref.AUTOCOMPLETE_ENABLED)) {
                return false;
            }
        }
        return true;
    }

    private boolean isBraveSearchPromoBanner() {
        Tab activeTab = mActivityTabSupplier.get();
        if (activeTab != null
                && !activeTab.isIncognito()
                && ChromeFeatureList.isEnabled(BraveFeatureList.BRAVE_SEARCH_OMNIBOX_BANNER)
                && mUrlBarEditingTextProvider != null
                && mUrlBarEditingTextProvider.getTextWithoutAutocomplete().length() > 0
                && sBraveSearchEngineDefaultRegions.contains(Locale.getDefault().getCountry())
                && !BraveSearchEngineAdapter.getDSEShortName(
                                Profile.fromWebContents(activeTab.getWebContents()), false, null)
                        .equals("Brave")
                && !OmniboxPrefManager.getInstance().isBraveSearchPromoBannerDismissed()
                && !OmniboxPrefManager.getInstance()
                        .isBraveSearchPromoBannerDismissedCurrentSession()) {
            long expiredDate =
                    OmniboxPrefManager.getInstance().getBraveSearchPromoBannerExpiredDate();

            if (expiredDate == 0) {
                OmniboxPrefManager.getInstance().setBraveSearchPromoBannerExpiredDate();
                return true;
            } else if (expiredDate > System.currentTimeMillis()) {
                return true;
            }
            return false;
        } else {
            return false;
        }
    }
}
