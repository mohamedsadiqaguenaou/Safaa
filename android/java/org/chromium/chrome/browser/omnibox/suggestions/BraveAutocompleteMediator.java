/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.omnibox.suggestions;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.NonNull;

import org.chromium.base.BraveReflectionUtil;
import org.chromium.base.Callback;
import org.chromium.base.supplier.NonNullObservableSupplier;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.lifecycle.ActivityLifecycleDispatcher;
import org.chromium.chrome.browser.omnibox.DeferredIMEWindowInsetApplicationCallback;
import org.chromium.chrome.browser.omnibox.LocationBarDataProvider;
import org.chromium.chrome.browser.omnibox.LocationBarEmbedderUiOverrides;
import org.chromium.chrome.browser.omnibox.UrlBarEditingTextStateProvider;
import org.chromium.chrome.browser.omnibox.fusebox.FuseboxCoordinator;
import org.chromium.chrome.browser.omnibox.styles.OmniboxResourceProvider;
import org.chromium.chrome.browser.omnibox.suggestions.action.OmniboxActionDelegateImpl;
import org.chromium.chrome.browser.omnibox.suggestions.basic.BasicSuggestionProcessor.BookmarkState;
import org.chromium.chrome.browser.share.ShareDelegate;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.components.omnibox.AutocompleteMatch;
import org.chromium.components.omnibox.OmniboxSuggestionType;
import org.chromium.misc_metrics.mojom.MiscAndroidMetrics;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.ui.modaldialog.ModalDialogManager;
import org.chromium.ui.modelutil.PropertyModel;
import org.chromium.url.GURL;

import java.util.function.Supplier;

@NullMarked
class BraveAutocompleteMediator extends AutocompleteMediator implements BraveSuggestionHost {

    private final AutocompleteDelegate mDelegate;

    /** Will be deleted in bytecode, value from the parent class will be used instead. */
    @SuppressWarnings("NullAway") // Actual instance is at the parent
    private DropdownItemViewInfoListManager mDropdownViewInfoListManager;

    /** Will be deleted in bytecode, value from the parent class will be used instead. */
    private @Nullable DropdownItemViewInfoListBuilder mDropdownViewInfoListBuilder;

    BraveAutocompleteMediator(
            Context context,
            OmniboxResourceProvider resourceProvider,
            AutocompleteDelegate delegate,
            UrlBarEditingTextStateProvider textProvider,
            PropertyModel listPropertyModel,
            Handler handler,
            Supplier<@Nullable ModalDialogManager> modalDialogManagerSupplier,
            Supplier<@Nullable Tab> activityTabSupplier,
            @Nullable Supplier<ShareDelegate> shareDelegateSupplier,
            LocationBarDataProvider locationBarDataProvider,
            Callback<String> bringTabGroupToFrontCallback,
            BookmarkState bookmarkState,
            OmniboxActionDelegateImpl omniboxActionDelegate,
            ActivityLifecycleDispatcher lifecycleDispatcher,
            OmniboxSuggestionsDropdownEmbedder embedder,
            WindowAndroid windowAndroid,
            DeferredIMEWindowInsetApplicationCallback deferredIMEWindowInsetApplicationCallback,
            FuseboxCoordinator fuseboxCoordinator,
            LocationBarEmbedderUiOverrides uiOverrides,
            NonNullObservableSupplier<Boolean> urlTextWrappingSupplier) {
        super(
                context,
                resourceProvider,
                delegate,
                textProvider,
                listPropertyModel,
                handler,
                modalDialogManagerSupplier,
                activityTabSupplier,
                shareDelegateSupplier,
                locationBarDataProvider,
                bringTabGroupToFrontCallback,
                bookmarkState,
                omniboxActionDelegate,
                lifecycleDispatcher,
                embedder,
                windowAndroid,
                deferredIMEWindowInsetApplicationCallback,
                fuseboxCoordinator,
                uiOverrides,
                urlTextWrappingSupplier);

        mDelegate = delegate;
    }

    @Override
    void loadUrlForOmniboxMatch(
            int matchIndex,
            @NonNull AutocompleteMatch suggestion,
            @NonNull GURL url,
            long inputStart,
            boolean openInNewTab,
            boolean openInNewWindow,
            boolean openInBackground) {
        Context context =
                (Context)
                        BraveReflectionUtil.getField(AutocompleteMediator.class, "mContext", this);
        LocationBarDataProvider dataProvider =
                (LocationBarDataProvider)
                        BraveReflectionUtil.getField(
                                AutocompleteMediator.class, "mDataProvider", this);

        if (dataProvider != null && context != null && context instanceof BraveActivity) {
            MiscAndroidMetrics miscAndroidMetrics =
                    ((BraveActivity) context).getMiscAndroidMetrics();
            if (miscAndroidMetrics != null) {
                int type = suggestion.getType();
                boolean isSearchQuery =
                        type == OmniboxSuggestionType.SEARCH_WHAT_YOU_TYPED
                                || type == OmniboxSuggestionType.SEARCH_HISTORY
                                || type == OmniboxSuggestionType.SEARCH_SUGGEST
                                || type == OmniboxSuggestionType.SEARCH_SUGGEST_ENTITY
                                || type == OmniboxSuggestionType.SEARCH_SUGGEST_TAIL
                                || type == OmniboxSuggestionType.SEARCH_SUGGEST_PERSONALIZED
                                || type == OmniboxSuggestionType.SEARCH_SUGGEST_PROFILE
                                || type == OmniboxSuggestionType.SEARCH_OTHER_ENGINE;

                if (!dataProvider.isIncognito()) {
                    boolean isNewTab = dataProvider.getNewTabPageDelegate().isCurrentlyVisible();
                    miscAndroidMetrics.recordLocationBarChange(isNewTab, isSearchQuery);
                }

                if (isSearchQuery) {
                    boolean isSuggestion = type != OmniboxSuggestionType.SEARCH_WHAT_YOU_TYPED;
                    miscAndroidMetrics.recordOmniboxSearchQuery(url.getSpec(), isSuggestion);
                }

                if (url.getScheme().startsWith("http")) {
                    if (type == OmniboxSuggestionType.URL_WHAT_YOU_TYPED) {
                        miscAndroidMetrics.recordOmniboxDirectNavigation();
                    } else if (type == OmniboxSuggestionType.HISTORY_URL
                            || type == OmniboxSuggestionType.HISTORY_TITLE
                            || type == OmniboxSuggestionType.HISTORY_BODY
                            || type == OmniboxSuggestionType.HISTORY_KEYWORD) {
                        miscAndroidMetrics.recordOmniboxHistoryNavigation();
                    } else if (type == OmniboxSuggestionType.BOOKMARK_TITLE) {
                        miscAndroidMetrics.recordOmniboxBookmarkNavigation();
                    }
                }
            }
        }

        super.loadUrlForOmniboxMatch(
                matchIndex,
                suggestion,
                url,
                inputStart,
                openInNewTab,
                openInNewWindow,
                openInBackground);
    }

    @Override
    public void removeBraveSearchSuggestion() {
        if (mDropdownViewInfoListManager instanceof BraveDropdownItemViewInfoListManager) {
            ((BraveDropdownItemViewInfoListManager) mDropdownViewInfoListManager)
                    .removeBraveSearchSuggestion();
        }
    }

    /** We override parent to move back ability to set AutocompleteDelegate. */
    @Override
    void initDefaultProcessors() {
        if (mDropdownViewInfoListBuilder != null
                && mDropdownViewInfoListBuilder instanceof BraveDropdownItemViewInfoListBuilder) {
            ((BraveDropdownItemViewInfoListBuilder) mDropdownViewInfoListBuilder)
                    .setAutocompleteDelegate(mDelegate);
        }
        super.initDefaultProcessors();
    }
}
