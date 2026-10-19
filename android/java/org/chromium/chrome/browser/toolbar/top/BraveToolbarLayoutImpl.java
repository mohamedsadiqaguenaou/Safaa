/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.toolbar.top;

import static org.chromium.build.NullUtil.assertNonNull;
import static org.chromium.ui.base.ViewUtils.dpToPx;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.brave.playlist.enums.PlaylistOptionsEnum;
import com.brave.playlist.listener.PlaylistOnboardingActionClickListener;
import com.brave.playlist.listener.PlaylistOptionsListener;
import com.brave.playlist.model.PlaylistOptionsModel;
import com.brave.playlist.model.SnackBarActionModel;
import com.brave.playlist.util.ConstantUtils;
import com.brave.playlist.util.PlaylistViewUtils;

import org.chromium.base.ApiCompatibilityUtils;
import org.chromium.base.BraveFeatureList;
import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.BraveReflectionUtil;
import org.chromium.base.Log;
import org.chromium.base.MathUtils;
import org.chromium.base.supplier.MonotonicObservableSupplier;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.BraveConstants;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.custom_layout.popup_window_tooltip.PopupWindowTooltip;
import org.chromium.chrome.browser.customtabs.features.toolbar.CustomTabToolbar;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.media.PictureInPicture;
import org.chromium.chrome.browser.omnibox.BraveLocationBarCoordinator;
import org.chromium.chrome.browser.omnibox.LocationBarCoordinator;
import org.chromium.chrome.browser.playlist.PlaylistServiceFactoryAndroid;
import org.chromium.chrome.browser.playlist.PlaylistServiceObserverImpl;
import org.chromium.chrome.browser.playlist.PlaylistServiceObserverImpl.PlaylistServiceObserverImplDelegate;
import org.chromium.chrome.browser.preferences.BravePref;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings;
import org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettingsObserver;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.chrome.browser.shields.BraveShieldsMenuObserver;
import org.chromium.chrome.browser.shields.BraveShieldsUtils;
import org.chromium.chrome.browser.shields.BraveUnifiedPanelHandler;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabHidingType;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.chrome.browser.tabmodel.IncognitoStateProvider;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorTabModelObserver;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorTabObserver;
import org.chromium.chrome.browser.theme.ThemeColorProvider;
import org.chromium.chrome.browser.theme.ThemeUtils;
import org.chromium.chrome.browser.toolbar.ToolbarDataProvider;
import org.chromium.chrome.browser.toolbar.ToolbarProgressBar;
import org.chromium.chrome.browser.toolbar.ToolbarTabController;
import org.chromium.chrome.browser.toolbar.back_button.BackButtonCoordinator;
import org.chromium.chrome.browser.toolbar.bottom.BottomToolbarConfiguration;
import org.chromium.chrome.browser.toolbar.bottom.BottomToolbarVariationManager;
import org.chromium.chrome.browser.toolbar.forward_button.ForwardButtonCoordinator;
import org.chromium.chrome.browser.toolbar.home_button.HomeButton;
import org.chromium.chrome.browser.toolbar.home_button.HomeButtonCoordinator;
import org.chromium.chrome.browser.toolbar.menu_button.BraveMenuButtonCoordinator;
import org.chromium.chrome.browser.toolbar.menu_button.MenuButtonCoordinator;
import org.chromium.chrome.browser.toolbar.reload_button.ReloadButtonCoordinator;
import org.chromium.chrome.browser.toolbar.signin_button.SigninButtonCoordinator;
import org.chromium.chrome.browser.toolbar.top.NavigationPopup.HistoryDelegate;
import org.chromium.chrome.browser.user_education.UserEducationHelper;
import org.chromium.chrome.browser.util.BraveTouchUtils;
import org.chromium.chrome.browser.youtube_script_injector.BraveYouTubeScriptInjectorNativeHelper;
import org.chromium.components.embedder_support.util.UrlUtilities;
import org.chromium.components.feature_engagement.Tracker;
import org.chromium.components.user_prefs.UserPrefs;
import org.chromium.content_public.browser.NavigationHandle;
import org.chromium.mojo.bindings.ConnectionErrorHandler;
import org.chromium.mojo.system.MojoException;
import org.chromium.playlist.mojom.PlaylistItem;
import org.chromium.playlist.mojom.PlaylistService;
import org.chromium.ui.base.DeviceFormFactor;
import org.chromium.ui.base.ViewUtils;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.ui.interpolators.Interpolators;
import org.chromium.ui.resources.dynamics.ViewResourceAdapter;
import org.chromium.ui.util.ColorUtils;
import org.chromium.ui.widget.Toast;
import org.chromium.url.GURL;
import org.chromium.url.mojom.Url;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

public abstract class BraveToolbarLayoutImpl extends ToolbarLayout
        implements BraveToolbarLayout,
                OnClickListener,
                View.OnLongClickListener,
                ConnectionErrorHandler,
                PlaylistServiceObserverImplDelegate {
    private static final String TAG = "BraveToolbar";

    private static final int URL_FOCUS_TOOLBAR_BUTTONS_TRANSLATION_X_DP = 10;

    public static boolean mShouldShowPlaylistMenu;

    private PlaylistServiceObserverImpl mPlaylistServiceObserver;

    private ImageButton mBraveShieldsButton;
    private ImageButton mYouTubePipButton;
    private ImageButton mNoMusicButton;
    private HomeButton mHomeButton;
    private FrameLayout mShieldsLayout;
    private FrameLayout mYouTubePipLayout;
    private FrameLayout mNoMusicLayout;
    private BraveUnifiedPanelHandler mUnifiedPanelHandler;

    // TabModelSelectorTabObserver setups observer at the ctor
    @SuppressWarnings("UnusedVariable")
    private TabModelSelectorTabObserver mTabModelSelectorTabObserver;

    // TabModelSelectorTabModelObserver setups observer at the ctor
    @SuppressWarnings("UnusedVariable")
    private TabModelSelectorTabModelObserver mTabModelSelectorTabModelObserver;

    private BraveShieldsContentSettings mBraveShieldsContentSettings;
    private BraveShieldsContentSettingsObserver mBraveShieldsContentSettingsObserver;
    private int mCurrentToolbarColor;
    // True once the Brave buttons sit inside the location bar text box, which happens on tablet
    // only. The location bar then paints their background, and the segment drawables the phone
    // toolbar relies on no longer apply.
    private boolean mBraveButtonsInLocationBar;

    @Nullable private final Runnable mToolbarSnapshotCaptureRunnable;

    private PopupWindowTooltip mShieldsPopupWindowTooltip;

    private boolean mIsBottomControlsVisible;

    private ColorStateList mDarkModeTint;
    private ColorStateList mLightModeTint;
    // Tint of the NoMusic icon while it is off; the "on" icon keeps its brand colors.
    private ColorStateList mNoMusicOffTint;

    private PlaylistService mPlaylistService;

    public BraveToolbarLayoutImpl(Context context, AttributeSet attrs) {
        super(context, attrs);

        if (DeviceFormFactor.isNonMultiDisplayContextOnTablet(getContext())) {
            mToolbarSnapshotCaptureRunnable = this::requestToolbarSnapshotCapture;
        } else {
            mToolbarSnapshotCaptureRunnable = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mToolbarSnapshotCaptureRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    public void destroy() {
        if (mUnifiedPanelHandler != null) {
            mUnifiedPanelHandler.destroy();
        }
        if (mBraveShieldsContentSettings != null) {
            mBraveShieldsContentSettings.removeObserver(mBraveShieldsContentSettingsObserver);
        }
        if (mPlaylistService != null) {
            mPlaylistService.close();
        }
        if (mPlaylistServiceObserver != null) {
            mPlaylistServiceObserver.close();
            mPlaylistServiceObserver.destroy();
            mPlaylistServiceObserver = null;
        }
        super.destroy();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mShieldsLayout = findViewById(R.id.brave_shields_button_layout);
        mYouTubePipLayout = findViewById(R.id.brave_youtube_pip_layout);
        mNoMusicLayout = findViewById(R.id.brave_nomusic_button_layout);
        mBraveShieldsButton = findViewById(R.id.brave_shields_button);
        mYouTubePipButton = findViewById(R.id.brave_youtube_pip_button);
        mNoMusicButton = findViewById(R.id.brave_nomusic_button);
        mHomeButton = findViewById(R.id.home_button);

        // Use the same tints as the omnibox status icon, so the icons Brave adds inside the URL
        // bar match the ones upstream puts there.
        mDarkModeTint = ThemeUtils.getThemedToolbarIconTint(getContext(), false);
        mLightModeTint = ThemeUtils.getThemedToolbarIconTint(getContext(), true);
        mNoMusicOffTint = mDarkModeTint;

        if (mHomeButton != null) {
            mHomeButton.setOnLongClickListener(this);
        }

        if (mBraveShieldsButton != null) {
            mBraveShieldsButton.setClickable(true);
            mBraveShieldsButton.setOnClickListener(this);
            mBraveShieldsButton.setOnLongClickListener(this);
            BraveTouchUtils.ensureMinTouchTarget(mBraveShieldsButton);
        }

        if (mYouTubePipButton != null) {
            mYouTubePipButton.setClickable(true);
            mYouTubePipButton.setOnClickListener(this);
            mYouTubePipButton.setOnLongClickListener(this);
            BraveTouchUtils.ensureMinTouchTarget(mYouTubePipButton);
        }

        if (mNoMusicButton != null) {
            mNoMusicButton.setClickable(true);
            mNoMusicButton.setOnClickListener(this);
            mNoMusicButton.setOnLongClickListener(this);
            BraveTouchUtils.ensureMinTouchTarget(mNoMusicButton);
        }

        maybeAnchorBraveButtonsInLocationBar();

        mUnifiedPanelHandler = new BraveUnifiedPanelHandler(getContext());
        mUnifiedPanelHandler.addObserver(
                new BraveShieldsMenuObserver() {
                    @Override
                    public void onMenuTopShieldsChanged(boolean isOn, boolean isTopShield) {
                        Tab currentTab = getToolbarDataProvider().getTab();
                        if (currentTab == null) {
                            return;
                        }
                        if (isTopShield) {
                            updateBraveShieldsButtonState(currentTab);
                        }
                        if (currentTab.isLoading()) {
                            currentTab.stopLoading();
                        }
                        currentTab.reloadIgnoringCache();
                    }
                });
        mBraveShieldsContentSettingsObserver =
                new BraveShieldsContentSettingsObserver() {
                    @Override
                    public void blockEvent(int tabId, String blockType, String subresource) {
                        mUnifiedPanelHandler.addStat(tabId, blockType, subresource);
                    }
                };
        // Initially show shields off image. Shields button state will be updated when tab is
        // shown and loading state is changed.
        updateBraveShieldsButtonState(null);
        if (BraveReflectionUtil.equalTypes(this.getClass(), ToolbarPhone.class)) {
            if (getMenuButtonCoordinator() != null
                    && isMenuButtonOnBottomControls()
                    && BottomToolbarConfiguration.isToolbarTopAnchored()) {
                getMenuButtonCoordinator().setVisibility(false);
            }
        }

        updateShieldsLayoutBackground();
    }

    public String getLocationBarQuery() {
        if (getLocationBar() instanceof BraveLocationBarCoordinator) {
            String query =
                    ((BraveLocationBarCoordinator) getLocationBar())
                            .getUrlBarTextWithoutAutocomplete();
            return query;
        }
        return "";
    }

    public void clearOmniboxFocus() {
        if (getLocationBar() instanceof BraveLocationBarCoordinator) {
            ((BraveLocationBarCoordinator) getLocationBar()).clearOmniboxFocus();
        }
    }

    public boolean isUrlBarFocused() {
        if (getLocationBar() instanceof BraveLocationBarCoordinator) {
            BraveLocationBarCoordinator coordinator =
                    (BraveLocationBarCoordinator) getLocationBar();
            assertNonNull(coordinator.getLocationBarMediator());
            return coordinator.getLocationBarMediator().isUrlBarFocused();
        }
        return false;
    }

    @Override
    public void onConnectionError(MojoException e) {
        if (isPlaylistEnabledByPrefsAndFlags()) {
            mPlaylistService = null;
            initPlaylistService();
        }
    }

    private void initPlaylistService() {
        Tab currentTab = getToolbarDataProvider().getTab();
        if (mPlaylistService != null || currentTab == null) {
            return;
        }

        if (currentTab.isIncognito()) {
            return;
        }

        mPlaylistService =
                PlaylistServiceFactoryAndroid.getInstance()
                        .getPlaylistService(
                                Profile.fromWebContents(currentTab.getWebContents()), this);
    }

    @Override
    protected void onNativeLibraryReady() {
        super.onNativeLibraryReady();
        if (isPlaylistEnabledByPrefsAndFlags()) {
            initPlaylistService();
            mPlaylistServiceObserver = new PlaylistServiceObserverImpl(this);
            mPlaylistService.addObserver(mPlaylistServiceObserver);
        }

        mBraveShieldsContentSettings = BraveShieldsContentSettings.getInstance();
        mBraveShieldsContentSettings.addObserver(mBraveShieldsContentSettingsObserver);

        updateNoMusicButtonState();

        if (mShieldsLayout != null) {
            updateShieldsLayoutBackground();
            mShieldsLayout.setVisibility(View.VISIBLE);
        }
    }

    public void setTabModelSelector(TabModelSelector selector) {
        // We might miss events before calling setTabModelSelector, so we need
        // to proactively update the shields button state here, otherwise shields
        // might sometimes show as disabled while it is actually enabled.
        updateBraveShieldsButtonState(getToolbarDataProvider().getTab());
        // After an activity recreation that reparents the current tab (e.g. the theme switch
        // applied on YouTube PiP exit), the page is preserved without any fresh navigation,
        // page-load, tab-selection, or tab-shown callback, so nothing would re-evaluate the PiP
        // icon and it would stay hidden until a manual reload. The tab model is (re)bound here on
        // every activity start, so proactively evaluate the current tab's icon.
        final Tab pipCurrentTab = getToolbarDataProvider().getTab();
        if (pipCurrentTab != null) {
            showYouTubePipIcon(pipCurrentTab);
        }
        mTabModelSelectorTabObserver =
                new TabModelSelectorTabObserver(selector) {
                    @Override
                    public void onCrash(Tab tab) {
                        super.onCrash(tab);
                        // When a tab crashes it shows a custom view with a reload button.
                        // The PIP layout must be hidden.
                        hideYouTubePipIcon();
                    }

                    @Override
                    public void onShown(Tab tab, @TabSelectionType int type) {
                        if (!PictureInPicture.isEnabled(getContext())) {
                            hideYouTubePipIcon();
                        }
                        // Update shields button state when visible tab is changed.
                        updateBraveShieldsButtonState(tab);
                        // NoMusic may have been switched from another window.
                        updateNoMusicButtonState();
                        findMediaFiles();
                    }

                    @Override
                    public void onHidden(Tab tab, @TabHidingType int reason) {
                        hidePlaylistButton();
                    }

                    @Override
                    public void onPageLoadStarted(Tab tab, GURL url) {
                        hideYouTubePipIcon();
                        if (getToolbarDataProvider().getTab() == tab) {
                            updateBraveShieldsButtonState(tab);
                        }
                        mUnifiedPanelHandler.clearBraveShieldsCount(tab.getId());
                        dismissShieldsTooltip();
                        hidePlaylistButton();
                    }

                    @Override
                    public void onPageLoadFinished(final Tab tab, GURL url) {
                        if (getToolbarDataProvider().getTab() == tab) {
                            updateBraveShieldsButtonState(tab);

                            if (mBraveShieldsButton != null
                                    && mBraveShieldsButton.isShown()
                                    && mUnifiedPanelHandler != null
                                    && !mUnifiedPanelHandler.isShowing()) {
                                checkForTooltip(tab);
                            }

                            // Re-check PiP icon visibility now that the document
                            // load is complete. The earlier check in
                            // onDidFinishNavigationInPrimaryMainFrame may have
                            // returned false because
                            // IsDocumentOnLoadCompletedInPrimaryMainFrame was not
                            // yet true at navigation commit time.
                            showYouTubePipIcon(tab);
                        }

                        String countryCode = Locale.getDefault().getCountry();
                        if (countryCode.equals(BraveConstants.INDIA_COUNTRY_CODE)
                                && url.domainIs(BraveConstants.YOUTUBE_DOMAIN)
                                && ChromeSharedPreferences.getInstance()
                                        .readBoolean(
                                                BravePreferenceKeys.BRAVE_AD_FREE_CALLOUT_DIALOG,
                                                true)) {
                            ChromeSharedPreferences.getInstance()
                                    .writeBoolean(BravePreferenceKeys.BRAVE_OPENED_YOUTUBE, true);
                        }
                    }

                    @Override
                    public void onDidFinishNavigationInPrimaryMainFrame(
                            Tab tab, NavigationHandle navigation) {
                        if (navigation.isInPrimaryMainFrame() && navigation.hasCommitted()) {
                            showYouTubePipIcon(tab);
                        } else {
                            hideYouTubePipIcon();
                        }
                        hidePlaylistButton();
                    }

                    @Override
                    public void onDestroyed(Tab tab) {
                        mUnifiedPanelHandler.removeStat(tab.getId());
                    }
                };

        mTabModelSelectorTabModelObserver =
                new TabModelSelectorTabModelObserver(selector) {
                    @Override
                    public void didSelectTab(Tab tab, @TabSelectionType int type, int lastId) {
                        showYouTubePipIcon(tab);
                    }
                };
    }

    private void showYouTubePipIcon(@NonNull final Tab tab) {
        // The layout could be null in Custom Tabs layout.
        if (mYouTubePipLayout == null) {
            return;
        }

        // Return early if picture in picture is not supported
        // or disabled in the OS settings.
        if (!PictureInPicture.isEnabled(getContext())) {
            if (mYouTubePipLayout.getVisibility() != View.GONE) {
                mYouTubePipLayout.setVisibility(View.GONE);
                invalidateToolbarSnapshotOnTablet();
            }
            return;
        }

        // Hide the layout if the current tab is in a state where it doesn't support or allow PiP
        // mode. This can also happen when a tab is re-selected after a crash and it's showing
        // the crash custom view, or is in a frozen state (likely inactive or unloaded).
        final boolean available =
                BraveYouTubeScriptInjectorNativeHelper.isPictureInPictureAvailable(
                        tab.getWebContents());
        final int newVisibility = available ? View.VISIBLE : View.GONE;
        if (mYouTubePipLayout.getVisibility() != newVisibility) {
            mYouTubePipLayout.setVisibility(newVisibility);
            invalidateToolbarSnapshotOnTablet();
        }
    }

    private void hideYouTubePipIcon() {
        // The layout could be null in Custom Tabs layout.
        if (mYouTubePipLayout == null) {
            return;
        }
        if (mYouTubePipLayout.getVisibility() != View.GONE) {
            mYouTubePipLayout.setVisibility(View.GONE);
            invalidateToolbarSnapshotOnTablet();
        }
    }

    /**
     * Returns the profile holding the NoMusic switch, which is shared by regular and private tabs,
     * or null before the native library is ready.
     */
    private @Nullable Profile getNoMusicProfile() {
        if (!isNativeLibraryReady()) {
            return null;
        }
        Tab tab = getToolbarDataProvider() != null ? getToolbarDataProvider().getTab() : null;
        if (tab != null && tab.getWebContents() != null) {
            Profile profile = Profile.fromWebContents(tab.getWebContents());
            if (profile != null) {
                return profile.getOriginalProfile();
            }
        }
        return ProfileManager.getLastUsedRegularProfile();
    }

    private boolean isNoMusicEnabled() {
        Profile profile = getNoMusicProfile();
        return profile != null && UserPrefs.get(profile).getBoolean(BravePref.NO_MUSIC_ENABLED);
    }

    /** NoMusicTabHelper observes the pref and applies it to the pages of every tab. */
    private void toggleNoMusic() {
        Profile profile = getNoMusicProfile();
        if (profile == null) {
            return;
        }
        boolean enabled = !UserPrefs.get(profile).getBoolean(BravePref.NO_MUSIC_ENABLED);
        UserPrefs.get(profile).setBoolean(BravePref.NO_MUSIC_ENABLED, enabled);
        updateNoMusicButtonState();
        Toast.makeText(
                        getContext(),
                        enabled
                                ? R.string.brave_nomusic_enabled_toast
                                : R.string.brave_nomusic_disabled_toast,
                        Toast.LENGTH_SHORT)
                .show();
    }

    private void updateNoMusicButtonState() {
        // The button is not part of the Custom Tabs toolbar.
        if (mNoMusicButton == null) {
            return;
        }
        boolean enabled = isNoMusicEnabled();
        mNoMusicButton.setImageResource(
                enabled ? R.drawable.ic_brave_nomusic_on : R.drawable.ic_brave_nomusic_off);
        // Like the Shields icon, the "on" icon is brand-colored and stays untinted.
        ImageViewCompat.setImageTintList(mNoMusicButton, enabled ? null : mNoMusicOffTint);
    }

    /**
     * Invalidates old toolbar bitmap snapshot on tablets, so when scrolling it will be shown the
     * right toolbar preview containing the visibility of the icons.
     */
    private void invalidateToolbarSnapshotOnTablet() {
        // mToolbarSnapshotCaptureRunnable is available on tablets only.
        if (!isAttachedToWindow() || mToolbarSnapshotCaptureRunnable == null) {
            return;
        }

        removeCallbacks(mToolbarSnapshotCaptureRunnable);
        post(mToolbarSnapshotCaptureRunnable);
    }

    private void requestToolbarSnapshotCapture() {
        final ToolbarControlContainer toolbarControlContainer =
                getRootView().findViewById(R.id.control_container);
        if (toolbarControlContainer == null) {
            return;
        }

        final ViewResourceAdapter adapter = toolbarControlContainer.getToolbarResourceAdapter();
        if (adapter == null) {
            return;
        }
        adapter.invalidate(null);
        adapter.triggerBitmapCapture();
    }

    private boolean isPlaylistEnabledByPrefsAndFlags() {
        Tab currentTab = getToolbarDataProvider().getTab();
        if (currentTab == null) {
            return false;
        }
        return ChromeFeatureList.isEnabled(BraveFeatureList.BRAVE_PLAYLIST)
                && ChromeSharedPreferences.getInstance()
                        .readBoolean(BravePreferenceKeys.PREF_ENABLE_PLAYLIST, true)
                && !currentTab.isIncognito();
    }

    private void hidePlaylistButton() {
        mShouldShowPlaylistMenu = false;
        try {
            ViewGroup viewGroup =
                    BraveActivity.getBraveActivity().getWindow().getDecorView().findViewById(
                            android.R.id.content);
            View playlistButton = viewGroup.findViewById(R.id.playlist_button_id);
            if (playlistButton != null && playlistButton.getVisibility() == View.VISIBLE) {
                playlistButton.setVisibility(View.GONE);
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "hidePlaylistButton " + e);
        }
    }

    private boolean isPlaylistButtonVisible() {
        try {
            ViewGroup viewGroup =
                    BraveActivity.getBraveActivity().getWindow().getDecorView().findViewById(
                            android.R.id.content);
            View playlistButton = viewGroup.findViewById(R.id.playlist_button_id);
            return playlistButton != null && playlistButton.getVisibility() == View.VISIBLE;
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "isPlaylistButtonVisible " + e);
            return false;
        }
    }

    private void findMediaFiles() {
        if (mPlaylistService != null && isPlaylistEnabledByPrefsAndFlags()) {
            hidePlaylistButton();
            mPlaylistService.findMediaFilesFromActiveTab();
        }
    }

    private void showPlaylistButton(PlaylistItem[] items) {
        try {
            ViewGroup viewGroup =
                    BraveActivity.getBraveActivity().getWindow().getDecorView().findViewById(
                            android.R.id.content);

            PlaylistOptionsListener playlistOptionsListener =
                    new PlaylistOptionsListener() {
                        @Override
                        public void onPlaylistOptionClicked(
                                PlaylistOptionsModel playlistOptionsModel) {
                            try {
                                if (playlistOptionsModel.getOptionType()
                                        == PlaylistOptionsEnum.ADD_MEDIA) {
                                    addMediaToPlaylist(items);
                                } else if (playlistOptionsModel.getOptionType()
                                        == PlaylistOptionsEnum.OPEN_PLAYLIST) {
                                    BraveActivity.getBraveActivity()
                                            .openPlaylistActivity(
                                                    getContext(), ConstantUtils.DEFAULT_PLAYLIST);
                                } else if (playlistOptionsModel.getOptionType()
                                        == PlaylistOptionsEnum.PLAYLIST_SETTINGS) {
                                    BraveActivity.getBraveActivity().openBravePlaylistSettings();
                                }
                            } catch (BraveActivity.BraveActivityNotFoundException e) {
                                Log.e(TAG, "showPlaylistButton onOptionClicked " + e);
                            }
                        }
                    };
            if (!isPlaylistButtonVisible()) {
                PlaylistOnboardingActionClickListener playlistOnboardingActionClickListener =
                        new PlaylistOnboardingActionClickListener() {
                            @Override
                            public void onOnboardingActionClick() {
                                addMediaToPlaylist(items);
                            }
                        };

                PlaylistViewUtils.showPlaylistButton(
                        BraveActivity.getBraveActivity(),
                        viewGroup,
                        playlistOptionsListener,
                        playlistOnboardingActionClickListener);
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "showPlaylistButton " + e);
        }
    }

    private void addMediaToPlaylist(PlaylistItem[] items) {
        if (mPlaylistService == null) {
            return;
        }
        mPlaylistService.getPlaylist(
                ConstantUtils.DEFAULT_PLAYLIST,
                defaultPlaylist -> {
                    Set<String> pageSources = new HashSet<String>();
                    for (PlaylistItem defaultPlaylistItem : defaultPlaylist.items) {
                        pageSources.add(defaultPlaylistItem.pageSource.url);
                    }
                    List<PlaylistItem> playlistItems = new ArrayList<>();
                    for (PlaylistItem playlistItem : items) {
                        // Check for duplicates in default playlist
                        if (!pageSources.contains(playlistItem.pageSource.url)) {
                            playlistItems.add(playlistItem);
                        }
                    }
                    if (playlistItems.size() > 0) {
                        mPlaylistService.addMediaFiles(
                                playlistItems.toArray(new PlaylistItem[0]),
                                ConstantUtils.DEFAULT_PLAYLIST,
                                true,
                                addedItems -> {
                                    if (addedItems.length > 0) {
                                        showAddedToPlaylistSnackBar();
                                    }
                                });
                    } else {
                        showAlreadyAddedToPlaylistSnackBar();
                    }
                });
    }

    public void addMediaToPlaylist() {
        Tab currentTab = getToolbarDataProvider().getTab();
        if (mPlaylistService == null || currentTab == null) {
            return;
        }
        mPlaylistService.addMediaFilesFromActiveTabToPlaylist(
                ConstantUtils.DEFAULT_PLAYLIST,
                true,
                addedItems -> {
                    if (addedItems.length > 0) {
                        showAddedToPlaylistSnackBar();
                    }
                });
    }

    private void showAddedToPlaylistSnackBar() {
        SnackBarActionModel snackBarActionModel =
                new SnackBarActionModel(
                        getContext().getResources().getString(R.string.view_action),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                try {
                                    BraveActivity.getBraveActivity()
                                            .openPlaylistActivity(
                                                    getContext(), ConstantUtils.DEFAULT_PLAYLIST);
                                } catch (BraveActivity.BraveActivityNotFoundException e) {
                                    Log.e(TAG, "showAddedToPlaylistSnackBar onClick ", e);
                                }
                            }
                        });
        try {
            ViewGroup viewGroup =
                    BraveActivity.getBraveActivity().getWindow().getDecorView().findViewById(
                            android.R.id.content);
            String playlistName =
                    getContext().getResources().getString(R.string.playlist_play_later);
            PlaylistViewUtils.showSnackBarWithActions(
                    viewGroup,
                    String.format(
                            getContext().getResources().getString(R.string.added_to_playlist),
                            playlistName),
                    snackBarActionModel);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "showAddedToPlaylistSnackBar ", e);
        }
    }

    private void showAlreadyAddedToPlaylistSnackBar() {
        SnackBarActionModel snackBarActionModel =
                new SnackBarActionModel(
                        getContext().getResources().getString(R.string.close_text),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                // Do nothing
                            }
                        });
        try {
            ViewGroup viewGroup =
                    BraveActivity.getBraveActivity()
                            .getWindow()
                            .getDecorView()
                            .findViewById(android.R.id.content);
            PlaylistViewUtils.showSnackBarWithActions(
                    viewGroup,
                    getContext().getResources().getString(R.string.already_added_in_playlist),
                    snackBarActionModel);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "showAlreadyAddedToPlaylistSnackBar " + e);
        }
    }

    private void checkForTooltip(Tab tab) {
        try {
            if (BraveShieldsUtils.isTooltipShown || BraveActivity.getBraveActivity().mIsDeepLink) {
                return;
            }
            if (!BraveShieldsUtils.hasShieldsTooltipShown(BraveShieldsUtils.PREF_SHIELDS_TOOLTIP)
                    && mUnifiedPanelHandler.getTrackersBlockedCount(tab.getId())
                                    + mUnifiedPanelHandler.getAdsBlockedCount(tab.getId())
                            > 0) {
                showTooltip(BraveShieldsUtils.PREF_SHIELDS_TOOLTIP, tab.getId());
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "checkForTooltip " + e);
        }
    }

    private void showTooltip(String tooltipPref, int tabId) {
        int gravity =
                BottomToolbarConfiguration.isToolbarBottomAnchored() ? Gravity.TOP : Gravity.BOTTOM;
        mShieldsPopupWindowTooltip =
                new PopupWindowTooltip.Builder(getContext())
                        .anchorView(mBraveShieldsButton)
                        .arrowColor(
                                ContextCompat.getColor(getContext(), R.color.primitive_primary_35))
                        .gravity(gravity)
                        .dismissOnOutsideTouch(true)
                        .dismissOnInsideTouch(false)
                        .backgroundDimDisabled(false)
                        .dimAmount(0.2f)
                        .padding(0f)
                        .parentPaddingHorizontal(dpToPx(getContext(), 10))
                        .modal(true)
                        .contentView(R.layout.brave_shields_tooltip_layout)
                        .build();

        int adsTrackersCount =
                mUnifiedPanelHandler.getTrackersBlockedCount(tabId)
                        + mUnifiedPanelHandler.getAdsBlockedCount(tabId);

        TextView tvBlocked = mShieldsPopupWindowTooltip.findViewById(R.id.tv_blocked);
        tvBlocked.setText(
                String.format(
                        getContext().getResources().getString(R.string.shield_tracker_blocked),
                        String.valueOf(adsTrackersCount)));

        if (mBraveShieldsButton != null && mBraveShieldsButton.isShown()) {
            mShieldsPopupWindowTooltip.show();
            BraveShieldsUtils.setShieldsTooltipShown(tooltipPref, true);
            BraveShieldsUtils.isTooltipShown = true;
        }
    }

    public void dismissShieldsTooltip() {
        if (mShieldsPopupWindowTooltip != null && mShieldsPopupWindowTooltip.isShowing()) {
            mShieldsPopupWindowTooltip.dismiss();
            mShieldsPopupWindowTooltip = null;
        }
    }

    public void reopenShieldsPanel() {
        if (mUnifiedPanelHandler != null && mUnifiedPanelHandler.isShowing()) {
            mUnifiedPanelHandler.hide();
            showShieldsMenu();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Context context = getContext();
        if (context instanceof Activity
                && (((Activity) context).isFinishing() || ((Activity) context).isDestroyed())) {
            return;
        }
        dismissShieldsTooltip();
        reopenShieldsPanel();
    }

    @Override
    public void onClickImpl(View v) {
        if (mUnifiedPanelHandler == null) {
            assert false;
            return;
        }
        if (mBraveShieldsButton == v && mBraveShieldsButton != null) {
            showShieldsMenu();
        } else if (mNoMusicButton == v && mNoMusicButton != null) {
            toggleNoMusic();
        } else if (mYouTubePipButton == v && mYouTubePipButton != null) {
            Tab currentTab = getToolbarDataProvider().getTab();
            if (currentTab != null
                    && BraveYouTubeScriptInjectorNativeHelper.isPictureInPictureAvailable(
                            currentTab.getWebContents())) {
                if (!PictureInPicture.isEnabled(getContext())) {
                    hideYouTubePipIcon();
                    return;
                }
                BraveYouTubeScriptInjectorNativeHelper.setFullscreen(currentTab.getWebContents());
            }
        }
    }

    @Override
    public void onClick(View v) {
        onClickImpl(v);
    }

    private void showShieldsMenu() {
        Tab currentTab = getToolbarDataProvider().getTab();
        if (currentTab == null) {
            return;
        }
        try {
            URL url = new URL(currentTab.getUrl().getSpec());
            if (!isValidProtocolForShields(url.getProtocol())) {
                return;
            }
            mUnifiedPanelHandler.show(this, currentTab);
        } catch (Exception e) {
            return;
        }
    }

    @Override
    public boolean onLongClickImpl(View v) {
        // Use null as the default description since Toast.showAnchoredToast
        // will return false if it is null.
        String description = null;
        Context context = getContext();
        Resources resources = context.getResources();

        if (v == mBraveShieldsButton) {
            description = resources.getString(R.string.accessibility_toolbar_btn_brave_shields);
        } else if (v == mHomeButton) {
            description = resources.getString(R.string.accessibility_toolbar_btn_home);
        } else if (v == mYouTubePipButton) {
            description = resources.getString(R.string.accessibility_toolbar_btn_brave_pip);
        } else if (v == mNoMusicButton) {
            description = resources.getString(R.string.accessibility_toolbar_btn_brave_nomusic);
        }

        return Toast.showAnchoredToast(context, v, description);
    }

    @Override
    public boolean onLongClick(View v) {
        return onLongClickImpl(v);
    }

    @Override
    public void populateUrlAnimatorSetImpl(
            boolean showExpandedState,
            int urlFocusToolbarButtonsDuration,
            int urlClearFocusTabStackDelayMs,
            List<Animator> animators) {
        if (mBraveShieldsButton != null) {
            Animator animator;
            if (showExpandedState) {
                float density = getContext().getResources().getDisplayMetrics().density;
                boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                float toolbarButtonTranslationX =
                        MathUtils.flipSignIf(URL_FOCUS_TOOLBAR_BUTTONS_TRANSLATION_X_DP, isRtl)
                        * density;
                animator = ObjectAnimator.ofFloat(
                        mBraveShieldsButton, TRANSLATION_X, toolbarButtonTranslationX);
                animator.setDuration(urlFocusToolbarButtonsDuration);
                animator.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN_INTERPOLATOR);
                animators.add(animator);

                animator = ObjectAnimator.ofFloat(mBraveShieldsButton, ALPHA, 0);
                animator.setDuration(urlFocusToolbarButtonsDuration);
                animator.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN_INTERPOLATOR);
                animators.add(animator);
            } else {
                animator = ObjectAnimator.ofFloat(mBraveShieldsButton, TRANSLATION_X, 0);
                animator.setDuration(urlFocusToolbarButtonsDuration);
                animator.setStartDelay(urlClearFocusTabStackDelayMs);
                animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN_INTERPOLATOR);
                animators.add(animator);

                animator = ObjectAnimator.ofFloat(mBraveShieldsButton, ALPHA, 1);
                animator.setDuration(urlFocusToolbarButtonsDuration);
                animator.setStartDelay(urlClearFocusTabStackDelayMs);
                animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN_INTERPOLATOR);
                animators.add(animator);
            }
        }
    }

    @Override
    public void updateModernLocationBarColorImpl(int color) {
        mCurrentToolbarColor = color;
        // Inside the location bar the buttons have no background of their own to tint.
        if (mBraveButtonsInLocationBar) {
            return;
        }
        if (mShieldsLayout != null) {
            mShieldsLayout.getBackground().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
        if (mYouTubePipLayout != null) {
            mYouTubePipLayout.getBackground().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
        if (mNoMusicLayout != null) {
            mNoMusicLayout.getBackground().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    /**
     * If |tab| is null, set disabled image to shields button and |urlString| is ignored. If
     * |urlString| is null, url is fetched from |tab|.
     */
    public void updateBraveShieldsButtonState(Tab tab) {
        if (mBraveShieldsButton == null) {
            assert false;
            return;
        }

        if (tab == null) {
            mBraveShieldsButton.setImageResource(R.drawable.ic_shields_toolbar_off);
            return;
        }
        mBraveShieldsButton.setImageResource(
                isShieldsOnForTab(tab)
                        ? R.drawable.ic_shields_toolbar_on
                        : R.drawable.ic_shields_toolbar_off);
    }

    private boolean isShieldsOnForTab(Tab tab) {
        if (!isNativeLibraryReady()
                || tab == null
                || Profile.fromWebContents(tab.getWebContents()) == null) {
            return false;
        }

        return BraveShieldsContentSettings.getShields(
                Profile.fromWebContents(tab.getWebContents()),
                tab.getUrl().getSpec(),
                BraveShieldsContentSettings.RESOURCE_IDENTIFIER_BRAVE_SHIELDS);
    }

    private boolean isValidProtocolForShields(String protocol) {
        if (protocol.equals("http") || protocol.equals("https")) {
            return true;
        }

        return false;
    }

    public boolean isShieldsTooltipShown() {
        if (mShieldsPopupWindowTooltip != null) {
            return mShieldsPopupWindowTooltip.isShowing();
        }
        return false;
    }

    private boolean isCustomTab() {
        return BraveReflectionUtil.equalTypes(this.getClass(), CustomTabToolbar.class);
    }

    @Override
    public void onThemeColorChanged(int color, boolean shouldAnimate) {
        // Shields draws its own colors and stays untinted.
        ColorStateList tint =
                ColorUtils.shouldUseLightForegroundOnBackground(color)
                        ? mLightModeTint
                        : mDarkModeTint;
        if (mYouTubePipButton != null) {
            ImageViewCompat.setImageTintList(mYouTubePipButton, tint);
        }
        mNoMusicOffTint = tint;
        updateNoMusicButtonState();

        // On the NTP ToolbarPhone paints the buttons with its own location bar color, which the
        // text box color below does not match, e.g. over the bottom bar colored toolbar.
        if (BraveReflectionUtil.equalTypes(this.getClass(), ToolbarPhone.class)
                && !isIncognito()
                && UrlUtilities.isNtpUrl(getToolbarDataProvider().getCurrentGurl())) {
            return;
        }
        final int textBoxColor =
                ThemeUtils.getTextBoxColorForToolbarBackgroundInNonNativePage(
                        getContext(), color, isIncognito(), isCustomTab());
        updateModernLocationBarColorImpl(textBoxColor);
    }

    public void onBottomControlsVisibilityChanged(boolean isVisible) {
        mIsBottomControlsVisible = isVisible;
        // The tab switcher and menu buttons are only Brave's to move between the top toolbar and
        // the bottom while Brave's own bottom controls carry them. Upstream's bottom bar carries
        // them instead, and ToolbarPhone hides the top ones for it, so showing them back here -
        // which this does whenever the omnibox takes focus - would leave a second pair on top.
        if (BottomToolbarConfiguration.isAndroidBottomBarEnabled()) {
            return;
        }
        if (BraveReflectionUtil.equalTypes(this.getClass(), ToolbarPhone.class)
                && getMenuButtonCoordinator() != null) {
            getMenuButtonCoordinator().setVisibility(!isVisible);
            ToggleTabStackButton toggleTabStackButton = findViewById(R.id.tab_switcher_button);
            if (toggleTabStackButton != null) {
                toggleTabStackButton.setVisibility(
                        isTabSwitcherOnBottomControls() ? GONE : VISIBLE);
            }
        }
    }

    /**
     * Anchors the Brave button row at the trailing end of the tablet location bar, so that the
     * focus ring upstream draws around the text box encloses the Brave buttons as well. The row is
     * inflated as a child of the location bar (see toolbar_tablet.xml), which is a
     * ConstraintLayout, so it has no usable constraints until they are set here.
     */
    private void maybeAnchorBraveButtonsInLocationBar() {
        if (!BraveReflectionUtil.equalTypes(this.getClass(), ToolbarTablet.class)) {
            return;
        }

        View braveButtons = findViewById(R.id.brave_toolbar_container);
        View marginSpacer = findViewById(R.id.margin_spacer);
        if (braveButtons == null || marginSpacer == null) {
            return;
        }
        ConstraintLayout.LayoutParams spacerParams =
                (ConstraintLayout.LayoutParams) marginSpacer.getLayoutParams();

        Resources resources = getResources();
        ConstraintLayout.LayoutParams params =
                new ConstraintLayout.LayoutParams(
                        ConstraintLayout.LayoutParams.WRAP_CONTENT,
                        resources.getDimensionPixelSize(R.dimen.modern_toolbar_background_size));
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
        params.setMarginEnd(
                resources.getDimensionPixelSize(R.dimen.location_bar_url_action_offset));
        braveButtons.setLayoutParams(params);

        // Every upstream action button chain ends at |margin_spacer|, and the barriers that keep
        // the URL text clear of those buttons reference it, so re-anchoring it ahead of the Brave
        // row is enough to make room for the row.
        spacerParams.startToEnd = ConstraintLayout.LayoutParams.UNSET;
        spacerParams.endToStart = braveButtons.getId();
        marginSpacer.setLayoutParams(spacerParams);

        mBraveButtonsInLocationBar = true;
        // The location bar paints the text box behind the buttons now, so the segment drawables
        // that continue it on phones would only double up here.
        for (View layout : new View[] {mYouTubePipLayout, mNoMusicLayout, mShieldsLayout}) {
            if (layout != null) {
                layout.setBackground(null);
            }
        }
    }

    private void updateShieldsLayoutBackground() {
        if (mShieldsLayout == null || mBraveButtonsInLocationBar) {
            return;
        }

        // Shields is the last button of the row, so it closes the segment.
        mShieldsLayout.setBackgroundDrawable(
                ApiCompatibilityUtils.getDrawable(
                        getContext().getResources(),
                        R.drawable.modern_toolbar_background_grey_end_segment));

        updateModernLocationBarColorImpl(mCurrentToolbarColor);
    }

    private boolean isTabSwitcherOnBottomControls() {
        return mIsBottomControlsVisible
                && BottomToolbarVariationManager.isTabSwitcherOnBottomControls();
    }

    private boolean isMenuButtonOnBottomControls() {
        return mIsBottomControlsVisible
                && BottomToolbarVariationManager.isMenuButtonOnBottomControls();
    }

    @Override
    public void initialize(
            ToolbarDataProvider toolbarDataProvider,
            ToolbarTabController tabController,
            MenuButtonCoordinator menuButtonCoordinator,
            @Nullable ToggleTabStackButtonCoordinator tabSwitcherButtonCoordinator,
            HistoryDelegate historyDelegate,
            UserEducationHelper userEducationHelper,
            MonotonicObservableSupplier<Tracker> trackerSupplier,
            ToolbarProgressBar progressBar,
            @Nullable ReloadButtonCoordinator reloadButtonCoordinator,
            @Nullable BackButtonCoordinator backButtonCoordinator,
            @Nullable ForwardButtonCoordinator forwardButtonCoordinator,
            HomeButtonCoordinator homeButtonCoordinator,
            @Nullable SigninButtonCoordinator signinButtonCoordinator,
            ThemeColorProvider themeColorProvider,
            IncognitoStateProvider incognitoStateProvider,
            @Nullable Supplier<Integer> incognitoWindowCountSupplier,
            WindowAndroid windowAndroid) {
        super.initialize(
                toolbarDataProvider,
                tabController,
                menuButtonCoordinator,
                tabSwitcherButtonCoordinator,
                historyDelegate,
                userEducationHelper,
                trackerSupplier,
                progressBar,
                reloadButtonCoordinator,
                backButtonCoordinator,
                forwardButtonCoordinator,
                homeButtonCoordinator,
                signinButtonCoordinator,
                themeColorProvider,
                incognitoStateProvider,
                incognitoWindowCountSupplier,
                windowAndroid);

        BraveMenuButtonCoordinator.setMenuFromBottom(
                isMenuButtonOnBottomControls() || isMenuOnBottomWithBottomAddressBar());
    }

    public void updateMenuButtonState() {
        if (BottomToolbarConfiguration.isBraveBottomControlsEnabled()) {
            BraveMenuButtonCoordinator.setMenuFromBottom(mIsBottomControlsVisible);
        } else {
            BraveMenuButtonCoordinator.setMenuFromBottom(isMenuOnBottomWithBottomAddressBar());
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (BraveReflectionUtil.equalTypes(this.getClass(), CustomTabToolbar.class)
                || BraveReflectionUtil.equalTypes(this.getClass(), ToolbarPhone.class)) {
            updateMenuButtonState();
            Tab tab = getToolbarDataProvider() != null ? getToolbarDataProvider().getTab() : null;
            if (tab != null && tab.getWebContents() != null) {
                updateBraveShieldsButtonState(tab);
            }
        }
        super.onDraw(canvas);
    }

    @Override
    public boolean isLocationBarValid(LocationBarCoordinator locationBar) {
        return locationBar != null && locationBar.getPhoneCoordinator() != null
                && locationBar.getPhoneCoordinator().getViewForDrawing() != null;
    }

    @Override
    public void drawAnimationOverlay(ViewGroup toolbarButtonsContainer, Canvas canvas) {
        if (mNoMusicLayout != null && mNoMusicLayout.getVisibility() != View.GONE) {
            canvas.save();
            ViewUtils.translateCanvasToView(toolbarButtonsContainer, mNoMusicLayout, canvas);
            mNoMusicLayout.draw(canvas);
            canvas.restore();
        }
        if (mShieldsLayout != null && mShieldsLayout.getVisibility() != View.GONE) {
            canvas.save();
            ViewUtils.translateCanvasToView(toolbarButtonsContainer, mShieldsLayout, canvas);
            mShieldsLayout.draw(canvas);
            canvas.restore();
        }
        if (mYouTubePipLayout != null && mYouTubePipLayout.getVisibility() != View.GONE) {
            canvas.save();
            ViewUtils.translateCanvasToView(toolbarButtonsContainer, mYouTubePipLayout, canvas);
            mYouTubePipLayout.draw(canvas);
            canvas.restore();
        }
    }

    @Override
    public void onMediaFilesUpdated(Url pageUrl, PlaylistItem[] playlistItems) {
        Tab currentTab = getToolbarDataProvider().getTab();
        if (currentTab == null || !pageUrl.url.equals(currentTab.getUrl().getSpec())) {
            return;
        }
        if (playlistItems.length > 0 && !UrlUtilities.isNtpUrl(currentTab.getUrl().getSpec())) {
            mShouldShowPlaylistMenu = true;
            if (ChromeSharedPreferences.getInstance()
                    .readBoolean(BravePreferenceKeys.PREF_ADD_TO_PLAYLIST_BUTTON, true)) {
                showPlaylistButton(playlistItems);
            }
        }
    }

    private boolean isMenuOnBottomWithBottomAddressBar() {
        // If address bar is not on bottom, then menu is not on bottom too.
        if (!BottomToolbarConfiguration.isToolbarBottomAnchored()) {
            return false;
        }
        // Menu can be on bottom only with ToolbarPhone.
        if (!BraveReflectionUtil.equalTypes(this.getClass(), ToolbarPhone.class)) {
            return false;
        }
        // In overview mode the menu is on top.
        Context context = getContext();
        if (context instanceof BraveActivity && ((BraveActivity) context).isInOverviewMode()) {
            return false;
        }
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        maybeHideTopTabSwitcherButton();

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * Re-enforces GONE on the top toolbar's tab switcher button when Brave's tab switcher lives on
     * the bottom toolbar. ToolbarPhone.updateButtonVisibility() (and related upstream paths) calls
     * setHasSpaceToShow(true) on the tab switcher coordinator, which forces the button VISIBLE and
     * overrides the GONE state set in onBottomControlsVisibilityChanged.
     */
    private void maybeHideTopTabSwitcherButton() {
        if (!isTabSwitcherOnBottomControls()) {
            return;
        }
        View toggleTabStackButton = findViewById(R.id.tab_switcher_button);
        if (toggleTabStackButton != null && toggleTabStackButton.getVisibility() != GONE) {
            toggleTabStackButton.setVisibility(GONE);
        }
    }
}
