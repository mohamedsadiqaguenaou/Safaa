/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */
package org.chromium.chrome.browser.firstrun;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.recyclerview.widget.RecyclerView;

import com.airbnb.lottie.LottieAnimationView;
import com.google.android.material.checkbox.MaterialCheckBox;

import org.chromium.brave.browser.firstrun.R;
import org.chromium.build.annotations.NullMarked;

/**
 * RecyclerView adapter for the onboarding ViewPager2, inflating one layout per step and delegating
 * navigation requests to the host via {@link OnboardingNavigationListener}.
 */
@NullMarked
public class OnboardingStepAdapter
        extends RecyclerView.Adapter<OnboardingStepAdapter.OnboardingBaseViewHolder> {

    /**
     * Listener used by onboarding step views to request a forward page change in the host.
     *
     * @see WelcomeOnboardingActivity
     */
    public interface OnboardingNavigationListener {
        void onRequestPageChange(final int position);

        void onDismiss();

        void onCrashReportingPreferenceChanged(final boolean enabled);

        void onP3aPreferenceChanged(final boolean enabled);
    }

    private static final int[] STEPS = {
        R.layout.block_interruptions, R.layout.nomusic_onboarding, R.layout.make_brave_better
    };

    private static final int BLOCK_INTERRUPTIONS_POSITION = 0;
    private static final int NOMUSIC_POSITION = 1;

    /**
     * The usage reports page. The host skips it while it is off, see {@link
     * WelcomeOnboardingActivity}.
     */
    public static final int MAKE_BRAVE_BETTER_POSITION = 2;

    private final OnboardingNavigationListener mListener;

    private boolean mCrashReportingChecked;
    private boolean mCrashReportingManaged;
    private boolean mP3aChecked;
    private boolean mP3aManaged;

    public OnboardingStepAdapter(final OnboardingNavigationListener listener) {
        mListener = listener;
    }

    @Override
    public OnboardingBaseViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view = inflater.inflate(STEPS[viewType], parent, false);
        return switch (viewType) {
            case BLOCK_INTERRUPTIONS_POSITION -> new BlockInterruptionsViewHolder(view, mListener);
            case NOMUSIC_POSITION -> new NoMusicViewHolder(view, mListener);
            case MAKE_BRAVE_BETTER_POSITION ->
                    new MakeBraveBetterViewHolder(
                            view,
                            mCrashReportingManaged,
                            mCrashReportingChecked,
                            mP3aManaged,
                            mP3aChecked,
                            mListener);
            default ->
                    throw new IllegalStateException(
                            String.format("View not found for view type %d.", viewType));
        };
    }

    @Override
    public void onBindViewHolder(OnboardingBaseViewHolder holder, int position) {
        if (holder instanceof MakeBraveBetterViewHolder makeBraveBetterViewHolder) {
            makeBraveBetterViewHolder.bind(
                    mCrashReportingManaged, mCrashReportingChecked, mP3aManaged, mP3aChecked);
        }
    }

    @Override
    public void onViewRecycled(OnboardingBaseViewHolder holder) {
        holder.viewRecycled();
    }

    @Override
    public int getItemViewType(int position) {
        return position;
    }

    @Override
    public int getItemCount() {
        return STEPS.length;
    }

    public void setCrashReportingChecked(final boolean checked) {
        if (mCrashReportingChecked == checked) {
            return;
        }
        mCrashReportingChecked = checked;
        notifyItemChanged(MAKE_BRAVE_BETTER_POSITION);
    }

    public void setCrashReportingManaged(final boolean managed) {
        if (mCrashReportingManaged == managed) {
            return;
        }
        mCrashReportingManaged = managed;
        notifyItemChanged(MAKE_BRAVE_BETTER_POSITION);
    }

    public void setP3aChecked(final boolean checked) {
        if (mP3aChecked == checked) {
            return;
        }
        mP3aChecked = checked;
        notifyItemChanged(MAKE_BRAVE_BETTER_POSITION);
    }

    public void setP3aManaged(final boolean managed) {
        if (mP3aManaged == managed) {
            return;
        }
        mP3aManaged = managed;
        notifyItemChanged(MAKE_BRAVE_BETTER_POSITION);
    }

    public abstract static class OnboardingBaseViewHolder extends RecyclerView.ViewHolder {

        public OnboardingBaseViewHolder(View itemView) {
            super(itemView);
            itemView.setClipToOutline(true);
        }

        public abstract void viewRecycled();
    }

    private static final class BlockInterruptionsViewHolder extends OnboardingBaseViewHolder {
        final Button mContinue;
        final LottieAnimationView mBlockInterruptions;

        private BlockInterruptionsViewHolder(
                final View itemView, final OnboardingNavigationListener listener) {
            super(itemView);
            mContinue = itemView.findViewById(R.id.onboarding_continue);
            mContinue.setClipToOutline(true);
            mContinue.setOnClickListener(view -> listener.onRequestPageChange(NOMUSIC_POSITION));

            mBlockInterruptions = itemView.findViewById(R.id.onboarding_illustration);
        }

        @Override
        public void viewRecycled() {
            mBlockInterruptions.cancelAnimation();
        }
    }

    private static final class NoMusicViewHolder extends OnboardingBaseViewHolder {
        final Button mStartBrowsing;

        private NoMusicViewHolder(
                final View itemView, final OnboardingNavigationListener listener) {
            super(itemView);
            mStartBrowsing = itemView.findViewById(R.id.onboarding_start_browsing);
            mStartBrowsing.setClipToOutline(true);
            mStartBrowsing.setOnClickListener(
                    view -> listener.onRequestPageChange(MAKE_BRAVE_BETTER_POSITION));
        }

        @Override
        public void viewRecycled() {
            /* No-op. */
        }
    }

    private static final class MakeBraveBetterViewHolder extends OnboardingBaseViewHolder {
        final Button mStartBrowsing;
        final MaterialCheckBox mSendCrashReports;
        final MaterialCheckBox mSendP3a;

        private MakeBraveBetterViewHolder(
                final View itemView,
                final boolean crashReportingManaged,
                final boolean crashReportingChecked,
                final boolean p3aManaged,
                final boolean p3aChecked,
                final OnboardingNavigationListener listener) {
            super(itemView);
            mStartBrowsing = itemView.findViewById(R.id.onboarding_start_browsing);
            mStartBrowsing.setClipToOutline(true);
            mStartBrowsing.setOnClickListener(view -> listener.onDismiss());

            mSendCrashReports = itemView.findViewById(R.id.send_crash_reports);
            if (crashReportingManaged) {
                mSendCrashReports.setVisibility(View.INVISIBLE);
            } else {
                mSendCrashReports.setChecked(crashReportingChecked);
                mSendCrashReports.setOnCheckedChangeListener(
                        (buttonView, isChecked) ->
                                listener.onCrashReportingPreferenceChanged(isChecked));
            }

            mSendP3a = itemView.findViewById(R.id.send_p3a_reports);
            if (p3aManaged) {
                mSendP3a.setVisibility(View.INVISIBLE);
            } else {
                mSendP3a.setChecked(p3aChecked);
                mSendP3a.setOnCheckedChangeListener(
                        (buttonView, isChecked) -> listener.onP3aPreferenceChanged(isChecked));
            }
        }

        private void bind(
                boolean crashReportingManaged,
                boolean crashReportingChecked,
                boolean p3aManaged,
                boolean p3aChecked) {
            if (crashReportingManaged) {
                mSendCrashReports.setVisibility(View.INVISIBLE);
            } else {
                mSendCrashReports.setVisibility(View.VISIBLE);
                mSendCrashReports.setChecked(crashReportingChecked);
            }

            if (p3aManaged) {
                mSendP3a.setVisibility(View.INVISIBLE);
            } else {
                mSendP3a.setVisibility(View.VISIBLE);
                mSendP3a.setChecked(p3aChecked);
            }
        }

        @Override
        public void viewRecycled() {
            /* No-op. */
        }
    }
}
