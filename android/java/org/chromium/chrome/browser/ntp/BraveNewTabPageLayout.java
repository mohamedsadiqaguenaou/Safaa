/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import static org.chromium.build.NullUtil.assertNonNull;
import static org.chromium.build.NullUtil.assumeNonNull;
import static org.chromium.ui.base.ViewUtils.getRelativeLayoutPosition;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.build.annotations.EnsuresNonNull;
import org.chromium.build.annotations.Initializer;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.ntp_background_images.NTPBackgroundImagesBridge;
import org.chromium.chrome.browser.ntp_background_images.model.NTPImage;
import org.chromium.chrome.browser.ntp_background_images.util.FetchWallpaperWorkerTask;
import org.chromium.chrome.browser.ntp_background_images.util.NTPImageUtil;
import org.chromium.chrome.browser.preferences.BravePref;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.chrome.browser.settings.BackgroundImagesPreferences;
import org.chromium.chrome.browser.suggestions.tile.BraveMostVisitedTilesLayoutBase;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabAttributes;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelUtils;
import org.chromium.components.user_prefs.UserPrefs;

import java.util.function.Supplier;

@SuppressWarnings("UseSharedPreferencesManagerFromChromeCheck")
@NullMarked
public class BraveNewTabPageLayout extends NewTabPageLayout {
    private static final String TAG = "BraveNewTabPage";

    private @Nullable ViewGroup mMvTilesContainerLayout;

    // Own members.
    private @Nullable ImageView mBgImageView;

    // Own members
    private @Nullable Profile mProfile;

    private @Nullable BitmapDrawable mImageDrawable;

    private @Nullable FetchWallpaperWorkerTask mWorkerTask;
    private boolean mIsFromBottomSheet;
    private @Nullable NTPBackgroundImagesBridge mNTPBackgroundImagesBridge;

    private @Nullable Tab mTab;
    private Activity mActivity;

    private @Nullable BraveNtpAdapter mNtpAdapter;

    private @Nullable RecyclerView mRecyclerView;

    private @Nullable NTPImage mNtpImageGlobal;

    private SharedPreferences.@Nullable OnSharedPreferenceChangeListener mPreferenceListener;
    private boolean mIsTopSitesEnabled;
    private ViewTreeObserver.@Nullable OnGlobalLayoutListener mBgImageViewOnGlobalLayoutListener;

    public BraveNewTabPageLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    protected void updateTileGridPlaceholderVisibility() {
        // This function is kept empty to avoid placeholder implementation
    }

    @EnsuresNonNull({"mMvTilesContainerLayout"})
    protected void initializeSiteSectionView() {
        mMvTilesContainerLayout =
                (ViewGroup) ((ViewStub) findViewById(R.id.mv_tiles_layout_stub)).inflate();
        mMvTilesContainerLayout.setPadding(0, 0, 0, 0);
        mMvTilesContainerLayout.setVisibility(View.VISIBLE);

        mMvTilesContainerLayout.post(
                new Runnable() {
                    @Override
                    public void run() {
                        assumeNonNull(mMvTilesContainerLayout);
                        mMvTilesContainerLayout.addOnLayoutChangeListener(
                                (View view,
                                        int left,
                                        int top,
                                        int right,
                                        int bottom,
                                        int oldLeft,
                                        int oldTop,
                                        int oldRight,
                                        int oldBottom) -> {
                                    int oldHeight = oldBottom - oldTop;
                                    int newHeight = bottom - top;

                                    if (oldHeight != newHeight
                                            && mIsTopSitesEnabled
                                            && mNtpAdapter != null) {
                                        new Handler(Looper.getMainLooper())
                                                .post(
                                                        () -> {
                                                            assertNonNull(mNtpAdapter);
                                                            mNtpAdapter.notifyItemRangeChanged(
                                                                    0, mNtpAdapter.getItemCount());
                                                        });
                                    }
                                });
                    }
                });

        // The page contents are initially hidden; otherwise they'll be drawn centered on the
        // page before the tiles are available and then jump upwards to make space once the
        // tiles are available.
        if (getVisibility() != View.VISIBLE) setVisibility(View.VISIBLE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        getAndShowNTPImage();

        initPreferenceObserver();
        if (mPreferenceListener != null) {
            ContextUtils.getAppSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(mPreferenceListener);
        }
        setNtpViews();

        // Show recent tabs dialog for variants B, C, and D if NTP was shown after inactivity
        maybeShowRecentTabsDialog();
    }

    private void setNtpViews() {
        mRecyclerView = findViewById(R.id.recyclerview);
        LinearLayoutManager linearLayoutManager =
                new LinearLayoutManager(mActivity, LinearLayoutManager.VERTICAL, false);
        mRecyclerView.setLayoutManager(linearLayoutManager);
        mRecyclerView.post(
                new Runnable() {
                    @Override
                    public void run() {
                        setNtpRecyclerView();
                    }
                });
    }

    private void setNtpRecyclerView() {
        assertNonNull(mRecyclerView);

        mIsTopSitesEnabled = NtpUtil.shouldDisplayTopSites();

        if (mNtpAdapter == null) {
            if (mActivity != null && !mActivity.isDestroyed() && !mActivity.isFinishing()) {
                mNtpAdapter =
                        new BraveNtpAdapter(
                                mActivity,
                                mMvTilesContainerLayout,
                                mNtpImageGlobal,
                                mRecyclerView.getHeight(),
                                mIsTopSitesEnabled);

                mRecyclerView.setAdapter(mNtpAdapter);

                if (mRecyclerView.getItemAnimator() != null) {
                    RecyclerView.ItemAnimator itemAnimator = mRecyclerView.getItemAnimator();
                    if (itemAnimator instanceof SimpleItemAnimator) {
                        SimpleItemAnimator simpleItemAnimator = (SimpleItemAnimator) itemAnimator;
                        simpleItemAnimator.setSupportsChangeAnimations(false);
                    }
                }
            }
        } else {
            mNtpAdapter.setRecyclerViewHeight(mRecyclerView.getHeight());
            mNtpAdapter.setTopSitesEnabled(mIsTopSitesEnabled);
        }

        if (mNtpAdapter == null) return;

        keepPosition();

        assertNonNull(mRecyclerView);
        mRecyclerView.addOnScrollListener(
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                        super.onScrollStateChanged(recyclerView, newState);
                        if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                            return;
                        }

                        int tabId = -1;
                        try {
                            Tab tab = BraveActivity.getBraveActivity().getActivityTab();
                            tabId = tab != null ? tab.getId() : -1;
                        } catch (BraveActivity.BraveActivityNotFoundException e) {
                            Log.e(TAG, "onScrollStateChanged " + e);
                        }
                        assertNonNull(mRecyclerView);
                        if (tabId != -1 && mRecyclerView.getChildCount() > 0) {
                            View firstChild = mRecyclerView.getChildAt(0);
                            if (firstChild != null) {
                                int firstVisiblePosition =
                                        mRecyclerView.getChildAdapterPosition(firstChild);
                                int verticalOffset = firstChild.getTop();

                                ChromeSharedPreferences.getInstance()
                                        .writeInt(
                                                BravePreferenceKeys
                                                                .BRAVE_RECYCLERVIEW_OFFSET_POSITION
                                                        + tabId,
                                                verticalOffset);

                                ChromeSharedPreferences.getInstance()
                                        .writeInt(
                                                BravePreferenceKeys.BRAVE_RECYCLERVIEW_POSITION
                                                        + tabId,
                                                firstVisiblePosition);
                            }
                        }
                    }
                });
    }

    private void keepPosition() {
        try {
            Tab tab = BraveActivity.getBraveActivity().getActivityTab();
            if (tab != null) {
                int itemPosition =
                        ChromeSharedPreferences.getInstance()
                                .readInt(
                                        BravePreferenceKeys.BRAVE_RECYCLERVIEW_POSITION
                                                + tab.getId(),
                                        0);

                new Handler(Looper.getMainLooper())
                        .postDelayed(
                                () -> {
                                    assertNonNull(mRecyclerView);
                                    if (mNtpAdapter != null
                                            && mNtpAdapter.getItemCount() > itemPosition) {
                                        RecyclerView.LayoutManager manager =
                                                mRecyclerView.getLayoutManager();
                                        if (manager instanceof LinearLayoutManager) {
                                            int offsetPosition =
                                                    ChromeSharedPreferences.getInstance()
                                                            .readInt(
                                                                    BravePreferenceKeys
                                                                                    .BRAVE_RECYCLERVIEW_OFFSET_POSITION // presubmit: ignore-long-line
                                                                            + tab.getId(),
                                                                    0);

                                            if (itemPosition == mNtpAdapter.getTopItemsCount()) {
                                                offsetPosition -=
                                                        mNtpAdapter.getTopMarginImageCredit();
                                            }

                                            LinearLayoutManager linearLayoutManager =
                                                    (LinearLayoutManager) manager;
                                            linearLayoutManager.scrollToPositionWithOffset(
                                                    itemPosition, offsetPosition);
                                        }
                                    }
                                },
                                10);
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "keepPosition " + e);
        }
    }

    private void initPreferenceObserver() {
        mPreferenceListener =
                (prefs, key) -> {
                    if (TextUtils.equals(key, BackgroundImagesPreferences.PREF_SHOW_TOP_SITES)) {
                        assertNonNull(mNtpAdapter);
                        mIsTopSitesEnabled = NtpUtil.shouldDisplayTopSites();
                        mNtpAdapter.setTopSitesEnabled(mIsTopSitesEnabled);
                    }
                };
    }

    @Override
    protected void onDetachedFromWindow() {
        assertNonNull(mRecyclerView);

        if (mWorkerTask != null && mWorkerTask.getStatus() == AsyncTask.Status.RUNNING) {
            mWorkerTask.cancel(true);
            mWorkerTask = null;
        }

        var observer =
                mBgImageView != null && mBgImageViewOnGlobalLayoutListener != null
                        ? mBgImageView.getViewTreeObserver()
                        : null;
        if (observer != null && observer.isAlive()) {
            observer.removeOnGlobalLayoutListener(mBgImageViewOnGlobalLayoutListener);
            mBgImageViewOnGlobalLayoutListener = null;
        }

        if (!mIsFromBottomSheet) {
            setBackgroundResource(0);
            if (mImageDrawable != null && mImageDrawable.getBitmap() != null
                    && !mImageDrawable.getBitmap().isRecycled()) {
                mImageDrawable.getBitmap().recycle();
            }
        }

        // Removes preference listener.
        ContextUtils.getAppSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(mPreferenceListener);
        mPreferenceListener = null;

        mRecyclerView.clearOnScrollListeners();
        super.onDetachedFromWindow();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mActivity != null && (mActivity.isFinishing() || mActivity.isDestroyed())) {
            return;
        }

        if (!NTPImageUtil.shouldEnableNTPFeature()) {
            return;
        }

        // `maybeShowNTPImage()` is a no-op if configuration changes before the NTP image is ready;
        // it will be called again once NTP image is ready.
        maybeShowNTPImage();

        new Handler(Looper.getMainLooper())
                .postDelayed(
                        () -> {
                            assertNonNull(mRecyclerView);
                            if (mNtpAdapter != null) {
                                mNtpAdapter.setRecyclerViewHeight(mRecyclerView.getHeight());
                            }
                            keepPosition();
                        },
                        10);
    }

    @Initializer
    public void initialize(NewTabPageManager manager, Activity activity, Profile profile) {

        mProfile = profile;

        mNTPBackgroundImagesBridge = NTPBackgroundImagesBridge.getInstance(mProfile);

        assert mMvTilesContainerLayout != null : "Something has changed in the upstream!";

        if (mMvTilesContainerLayout != null && useFixedMVTLayout()) {
            ViewGroup tilesLayout = mMvTilesContainerLayout.findViewById(R.id.mv_tiles_layout);

            assert tilesLayout instanceof BraveMostVisitedTilesLayoutBase
                    : "Something has changed in the upstream!";

            if (tilesLayout instanceof BraveMostVisitedTilesLayoutBase) {
                ((BraveMostVisitedTilesLayoutBase) tilesLayout).setUseFixedLayout(true);
            }
        }

        assert (activity instanceof BraveActivity);
        mActivity = activity;
        ((BraveActivity) mActivity).dismissShieldsTooltip();
        ((BraveActivity) mActivity).setNewTabPageManager(manager);
    }

    protected boolean useFixedMVTLayout() {
        return !UserPrefs.get(ProfileManager.getLastUsedRegularProfile())
                .getBoolean(BravePref.NEW_TAB_PAGE_SHOW_BACKGROUND_IMAGE);
    }

    public void setTabProvider(Supplier<@Nullable Tab> unused_tabProvider) {}

    private void maybeShowNTPImage() {
        // Return early if the NTP image is not available yet; this method is called again once it
        // is ready.
        if (mNtpImageGlobal == null) {
            return;
        }

        Display display = mActivity.getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);

        if (mNtpAdapter != null) {
            mNtpAdapter.setNtpImage(mNtpImageGlobal);
        }

        if (UserPrefs.get(ProfileManager.getLastUsedRegularProfile())
                        .getBoolean(BravePref.NEW_TAB_PAGE_SHOW_BACKGROUND_IMAGE)
                && NTPImageUtil.shouldEnableNTPFeature()) {
            setBackgroundImage(mNtpImageGlobal);
        }
    }

    /**
     * Returns the height the wallpaper is cropped to, measured from the top of {@code
     * backgroundView} down to the bottom of the window.
     *
     * <p>The view is shorter than that while the window reserves room at the bottom, and it grows
     * once that room is released, for example when the url bar takes focus. A fresh crop for each
     * of those heights moves the wallpaper, so it is cropped for the tallest layout and the surplus
     * is clipped. {@link ImageView.ScaleType#MATRIX} anchors the image to the top of the view, so
     * only the bottom edge is lost.
     */
    private static int getWallpaperHeight(final View backgroundView) {
        final View rootView = backgroundView.getRootView();
        // Layout position, not draw position: the latter includes the translation the url focus
        // animation applies to this view, which would move the height again.
        final int[] position = new int[2];
        getRelativeLayoutPosition(rootView, backgroundView, position);
        return Math.max(backgroundView.getMeasuredHeight(), rootView.getHeight() - position[1]);
    }

    private void setBackgroundImage(NTPImage ntpImage) {
        mBgImageView = (ImageView) findViewById(R.id.bg_image_view);
        mBgImageView.setScaleType(ImageView.ScaleType.MATRIX);

        if (mBgImageViewOnGlobalLayoutListener == null) {
            mBgImageViewOnGlobalLayoutListener =
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        private int mLastWidth;
                        private int mLastHeight;

                        @Override
                        public void onGlobalLayout() {
                            assertNonNull(mBgImageView);
                            int currentWidth = mBgImageView.getMeasuredWidth();
                            int currentHeight = getWallpaperHeight(mBgImageView);

                            // Only re-fetch if dimensions actually changed
                            if (currentWidth > 0
                                    && currentHeight > 0
                                    && (currentWidth != mLastWidth
                                            || currentHeight != mLastHeight)) {
                                mLastWidth = currentWidth;
                                mLastHeight = currentHeight;

                                if (mWorkerTask != null) {
                                    mWorkerTask.cancel(true);
                                }

                                mWorkerTask =
                                        new FetchWallpaperWorkerTask(
                                                ntpImage,
                                                currentWidth,
                                                currentHeight,
                                                mWallpaperRetrievedCallback);
                                mWorkerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                            }
                        }
                    };
        }
        ViewTreeObserver observer = mBgImageView.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(mBgImageViewOnGlobalLayoutListener);
    }

    private void getAndShowNTPImage() {
        // Each tab keeps the image it first got, so that its NTP does not change images.
        final Tab tab = getTab();
        final String tabKey = String.valueOf(tab.getId());
        NTPImage tabImage = TabAttributes.from(tab).get(tabKey);
        if (tabImage != null) {
            mNtpImageGlobal = tabImage;
            maybeShowNTPImage();
            return;
        }

        assertNonNull(mNTPBackgroundImagesBridge);
        NTPImageUtil.getNTPImage(
                mNTPBackgroundImagesBridge,
                ntpImage -> {
                    TabAttributes.from(tab).set(tabKey, ntpImage);
                    if (mActivity == null || mActivity.isFinishing() || mActivity.isDestroyed()) {
                        return;
                    }
                    mNtpImageGlobal = ntpImage;
                    maybeShowNTPImage();
                });
    }

    private final FetchWallpaperWorkerTask.WallpaperRetrievedCallback mWallpaperRetrievedCallback =
            new FetchWallpaperWorkerTask.WallpaperRetrievedCallback() {
                @Override
                public void bgWallpaperRetrieved(Bitmap bgWallpaper) {
                    if (mBgImageView != null) {
                        mBgImageView.setImageBitmap(bgWallpaper);
                    }
                }
            };

    public void setTab(Tab tab) {
        mTab = tab;
    }

    private Tab getTab() {
        assert mTab != null;
        return mTab;
    }

    /**
     * Shows the recent tabs snackbar if variant B is active, and either OPTION_NEW_TAB or
     * OPTION_NEW_TAB_AFTER_INACTIVITY is selected. For OPTION_NEW_TAB_AFTER_INACTIVITY, only shows
     * when app was returned from background after inactivity threshold.
     */
    private void maybeShowRecentTabsDialog() {
        if (!BraveFreshNtpHelper.isEnabled() || !BraveFreshNtpHelper.getVariant().equals("B")) {
            return;
        }

        // Check if OPTION_NEW_TAB or OPTION_NEW_TAB_AFTER_INACTIVITY is selected
        int openingScreenOption =
                ChromeSharedPreferences.getInstance()
                        .readInt(BravePreferenceKeys.BRAVE_NEW_TAB_PAGE_OPENING_SCREEN, 1);
        if (openingScreenOption
                        != BravePreferenceKeys.BRAVE_OPENING_SCREEN_OPTION_NEW_TAB_AFTER_INACTIVITY
                && openingScreenOption != BravePreferenceKeys.BRAVE_OPENING_SCREEN_OPTION_NEW_TAB) {
            return;
        }

        // Check the flag set by BraveReturnToChromeUtil when NTP was shown
        // For OPTION_NEW_TAB_AFTER_INACTIVITY, flag is set only after inactivity threshold
        // For OPTION_NEW_TAB, flag is set whenever NTP is shown at startup
        boolean shouldShowSnackbar =
                ChromeSharedPreferences.getInstance()
                        .readBoolean(BravePreferenceKeys.BRAVE_SHOW_RECENT_TABS_SNACKBAR, false);
        if (!shouldShowSnackbar) {
            return;
        }

        // Clear the flag so snackbar is only shown once
        ChromeSharedPreferences.getInstance()
                .writeBoolean(BravePreferenceKeys.BRAVE_SHOW_RECENT_TABS_SNACKBAR, false);

        // Wait for tab state to be initialized before showing snackbar.
        // On cold start, tabs are restored asynchronously, so we need to wait
        // for them to be fully loaded before searching for the last active tab.
        BraveActivity braveActivity = (BraveActivity) mActivity;
        TabModelSelector tabModelSelector = braveActivity.getTabModelSelectorSupplier().get();
        assertNonNull(tabModelSelector);
        TabModelUtils.runOnTabStateInitialized(
                tabModelSelector,
                (selector) -> {
                    BraveRecentTabsSnackbarHelper snackbarHelper =
                            new BraveRecentTabsSnackbarHelper();
                    snackbarHelper.showSnackbar(braveActivity);
                });
    }
}
