/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.widget.FrameLayout;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.phone.StatusBarWindowView;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

/**
 * Controls showing and hiding of the brightness mirror.
 */
public class BrightnessMirrorController {

    private final NotificationStackScrollLayout mStackScroller;
    public long TRANSITION_DURATION_OUT = 150;
    public long TRANSITION_DURATION_IN = 200;

    private final StatusBarWindowView mStatusBarWindow;
    private final ScrimView mScrimBehind;
    private final View mNotificationPanel;
    private final int[] mInt2Cache = new int[2];
    private View mBrightnessMirror;
    private boolean mShowing;

    public BrightnessMirrorController(StatusBarWindowView statusBarWindow) {
        mStatusBarWindow = statusBarWindow;
        mScrimBehind = (ScrimView) statusBarWindow.findViewById(R.id.scrim_behind);
        mBrightnessMirror = statusBarWindow.findViewById(R.id.brightness_mirror);
        mNotificationPanel = statusBarWindow.findViewById(R.id.notification_panel);
        mStackScroller = (NotificationStackScrollLayout) statusBarWindow.findViewById(
                R.id.notification_stack_scroller);
    }

    public void showMirror() {
        mShowing = true;
        mBrightnessMirror.removeCallbacks(mHideFallback);
        mBrightnessMirror.setVisibility(View.VISIBLE);
        mStackScroller.setFadingOut(true);
        mScrimBehind.animateViewAlpha(0.0f, TRANSITION_DURATION_OUT, Interpolators.ALPHA_OUT);
        outAnimation(mNotificationPanel.animate())
                .withLayer();
    }

    public void hideMirror() {
        mShowing = false;
        mScrimBehind.animateViewAlpha(1.0f, TRANSITION_DURATION_IN, Interpolators.ALPHA_IN);
        inAnimation(mNotificationPanel.animate())
                .withLayer()
                .withEndAction(mFinishHide);
        // KiroOS: the end action belongs to the panel's animator, and another animation of the
        // panel replaces it: the mirror then stayed over everything, with the panel invisible
        // behind it. If it's still up once the fade is surely over, finish on our own.
        mBrightnessMirror.removeCallbacks(mHideFallback);
        mBrightnessMirror.postDelayed(mHideFallback, TRANSITION_DURATION_IN + 150);
    }

    /** KiroOS: the panel closed while the slider was held: put everything back at once. */
    public void resetMirror() {
        if (!mShowing && mBrightnessMirror.getVisibility() != View.VISIBLE) {
            return;
        }
        mShowing = false;
        mNotificationPanel.animate().cancel();
        mHideFallback.run();
    }

    private final Runnable mFinishHide = new Runnable() {
        @Override
        public void run() {
            if (mShowing) {
                return;  // the slider was grabbed again
            }
            mBrightnessMirror.setVisibility(View.INVISIBLE);
            mStackScroller.setFadingOut(false);
        }
    };

    private final Runnable mHideFallback = new Runnable() {
        @Override
        public void run() {
            mBrightnessMirror.removeCallbacks(this);
            if (mShowing || mBrightnessMirror.getVisibility() != View.VISIBLE) {
                return;
            }
            mFinishHide.run();
            mNotificationPanel.setAlpha(1f);
            mScrimBehind.setViewAlpha(1f);
        }
    };

    private ViewPropertyAnimator outAnimation(ViewPropertyAnimator a) {
        return a.alpha(0.0f)
                .setDuration(TRANSITION_DURATION_OUT)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .withEndAction(null);
    }
    private ViewPropertyAnimator inAnimation(ViewPropertyAnimator a) {
        return a.alpha(1.0f)
                .setDuration(TRANSITION_DURATION_IN)
                .setInterpolator(Interpolators.ALPHA_IN);
    }


    public void setLocation(View original) {
        original.getLocationInWindow(mInt2Cache);

        // Original is slightly larger than the mirror, so make sure to use the center for the
        // positioning.
        int originalX = mInt2Cache[0] + original.getWidth() / 2;
        int originalY = mInt2Cache[1] + original.getHeight() / 2;
        mBrightnessMirror.setTranslationX(0);
        mBrightnessMirror.setTranslationY(0);
        mBrightnessMirror.getLocationInWindow(mInt2Cache);
        int mirrorX = mInt2Cache[0] + mBrightnessMirror.getWidth() / 2;
        int mirrorY = mInt2Cache[1] + mBrightnessMirror.getHeight() / 2;
        mBrightnessMirror.setTranslationX(originalX - mirrorX);
        mBrightnessMirror.setTranslationY(originalY - mirrorY);
    }

    public View getMirror() {
        return mBrightnessMirror;
    }

    public void updateResources() {
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) mBrightnessMirror.getLayoutParams();
        lp.width = mBrightnessMirror.getResources().getDimensionPixelSize(
                R.dimen.notification_panel_width);
        lp.gravity = mBrightnessMirror.getResources().getInteger(
                R.integer.notification_panel_layout_gravity);
        mBrightnessMirror.setLayoutParams(lp);
    }

    public void onDensityOrFontScaleChanged() {
        int index = mStatusBarWindow.indexOfChild(mBrightnessMirror);
        mStatusBarWindow.removeView(mBrightnessMirror);
        mBrightnessMirror = LayoutInflater.from(mBrightnessMirror.getContext()).inflate(
                R.layout.brightness_mirror, mStatusBarWindow, false);
        mStatusBarWindow.addView(mBrightnessMirror, index);
    }
}
