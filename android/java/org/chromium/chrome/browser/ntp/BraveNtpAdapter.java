/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import static org.chromium.ui.base.ViewUtils.dpToPx;

import android.app.Activity;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.ntp_background_images.model.BackgroundImage;
import org.chromium.chrome.browser.ntp_background_images.model.NTPImage;
import org.chromium.chrome.browser.ntp_background_images.util.NTPImageUtil;
import org.chromium.chrome.browser.preferences.BravePref;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.chrome.browser.util.BraveTouchUtils;
import org.chromium.chrome.browser.util.TabUtils;
import org.chromium.components.user_prefs.UserPrefs;

/** The New Tab Page rows: the top sites, then the wallpaper credit. */
public class BraveNtpAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final Activity mActivity;
    private final View mMvTilesContainerLayout;
    private NTPImage mNtpImage;
    private boolean mIsTopSitesEnabled;
    private int mRecyclerViewHeight;
    private int mTopSitesHeight;
    private int mTopMarginImageCredit;

    private static final int TYPE_TOP_SITES = 2;
    private static final int TYPE_IMAGE_CREDIT = 4;

    private static final int ONE_ITEM_SPACE = 1;

    // Matches @dimen/mvt_container_lateral_margin used on the New Tab Page layout.
    static final int CARD_MARGIN_DP = 16;

    public BraveNtpAdapter(
            Activity activity,
            View mvTilesContainerLayout,
            NTPImage ntpImage,
            int recyclerViewHeight,
            boolean isTopSitesEnabled) {
        mActivity = activity;
        mMvTilesContainerLayout = mvTilesContainerLayout;
        mNtpImage = ntpImage;
        mRecyclerViewHeight = recyclerViewHeight;
        mIsTopSitesEnabled = isTopSitesEnabled;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof TopSitesViewHolder) {
            LinearLayout.LayoutParams layoutParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            int margin = dpToPx(mActivity, CARD_MARGIN_DP);
            layoutParams.setMargins(margin, margin, margin, 0);

            mMvTilesContainerLayout.setLayoutParams(layoutParams);
            mMvTilesContainerLayout.setBackgroundResource(R.drawable.rounded_dark_bg_alpha);
            mTopSitesHeight = NTPImageUtil.getViewHeight(holder.itemView) + margin;

        } else if (holder instanceof ImageCreditViewHolder) {
            ImageCreditViewHolder imageCreditViewHolder = (ImageCreditViewHolder) holder;

            if (UserPrefs.get(ProfileManager.getLastUsedRegularProfile())
                            .getBoolean(BravePref.NEW_TAB_PAGE_SHOW_BACKGROUND_IMAGE)
                    && NTPImageUtil.shouldEnableNTPFeature()) {
                if (mNtpImage instanceof BackgroundImage) {
                    BackgroundImage backgroundImage = (BackgroundImage) mNtpImage;

                    if (backgroundImage.getImageCredit() != null) {
                        String imageCreditStr =
                                String.format(
                                        mActivity
                                                .getResources()
                                                .getString(
                                                        R.string.photo_by,
                                                        backgroundImage
                                                                .getImageCredit()
                                                                .getName()));

                        SpannableStringBuilder spannableString =
                                new SpannableStringBuilder(imageCreditStr);
                        spannableString.setSpan(
                                new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                ((imageCreditStr.length() - 1)
                                        - (backgroundImage.getImageCredit().getName().length()
                                                - 1)),
                                imageCreditStr.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                        imageCreditViewHolder.mCreditTv.setText(spannableString);
                        imageCreditViewHolder.mCreditTv.setVisibility(View.VISIBLE);

                        imageCreditViewHolder.mCreditTv.setOnClickListener(
                                view -> {
                                    if (backgroundImage.getImageCredit() != null) {
                                        TabUtils.openUrlInSameTab(
                                                backgroundImage.getImageCredit().getUrl());
                                    }
                                });
                    }
                }
            }
            if (mRecyclerViewHeight > 0) {
                LinearLayout.LayoutParams layoutParams =
                        new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);

                mTopMarginImageCredit =
                        mRecyclerViewHeight
                                - NTPImageUtil.getViewHeight(imageCreditViewHolder.itemView)
                                - dpToPx(mActivity, CARD_MARGIN_DP);

                if (mIsTopSitesEnabled) {
                    mTopMarginImageCredit -= mTopSitesHeight;
                }

                if (mTopMarginImageCredit < 0) {
                    mTopMarginImageCredit = 0;
                }

                layoutParams.setMargins(0, mTopMarginImageCredit, 0, 0);

                imageCreditViewHolder.mNtpImageCreditLayout.setLayoutParams(layoutParams);
            }
        }
    }

    @Override
    public int getItemCount() {
        return getTopSitesCount() + ONE_ITEM_SPACE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == TYPE_TOP_SITES) {
            // mMvTilesContainerLayout may have been placed in the NTP layout tree by
            // initializeSiteSectionView (via ViewStub inflation). Detach it first so
            // RecyclerView can adopt it as an item view without an "already has a parent" error.
            ViewGroup existingParent = (ViewGroup) mMvTilesContainerLayout.getParent();
            if (existingParent != null) existingParent.removeView(mMvTilesContainerLayout);
            return new TopSitesViewHolder(mMvTilesContainerLayout);
        }
        View view =
                LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.ntp_image_credit, parent, false);
        return new ImageCreditViewHolder(view);
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0 && getTopSitesCount() == 1) {
            return TYPE_TOP_SITES;
        }
        return TYPE_IMAGE_CREDIT;
    }

    public int getTopSitesCount() {
        return mIsTopSitesEnabled ? 1 : 0;
    }

    /** Rows above the wallpaper credit. */
    public int getTopItemsCount() {
        return getTopSitesCount();
    }

    public void setTopSitesEnabled(boolean isTopSitesEnabled) {
        if (mIsTopSitesEnabled != isTopSitesEnabled) {
            mIsTopSitesEnabled = isTopSitesEnabled;
            if (mIsTopSitesEnabled) {
                notifyItemInserted(0);
            } else {
                notifyItemRemoved(0);
            }
            // Rebind the credit row, whose top margin depends on the top sites.
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    public int getTopMarginImageCredit() {
        return mTopMarginImageCredit;
    }

    public void setNtpImage(NTPImage ntpImage) {
        mNtpImage = ntpImage;
        notifyItemChanged(getTopSitesCount());
    }

    public void setRecyclerViewHeight(int recyclerViewHeight) {
        mRecyclerViewHeight = recyclerViewHeight;
        notifyItemRangeChanged(0, getItemCount());
    }

    public static class TopSitesViewHolder extends RecyclerView.ViewHolder {
        TopSitesViewHolder(View itemView) {
            super(itemView);
        }
    }

    public static class ImageCreditViewHolder extends RecyclerView.ViewHolder {
        LinearLayout mNtpImageCreditLayout;
        TextView mCreditTv;

        ImageCreditViewHolder(View itemView) {
            super(itemView);
            this.mNtpImageCreditLayout =
                    (LinearLayout) itemView.findViewById(R.id.ntp_image_credit_layout);
            this.mCreditTv = (TextView) itemView.findViewById(R.id.credit_text);
            BraveTouchUtils.ensureMinTouchTarget(this.mCreditTv);
        }
    }
}
