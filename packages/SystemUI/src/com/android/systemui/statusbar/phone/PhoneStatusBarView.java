/*
 * Copyright (C) 2008 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.util.EventLog;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;

import com.android.systemui.DejankUtils;
import com.android.systemui.EventLogTags;
import com.android.systemui.R;

public class PhoneStatusBarView extends PanelBar {
    private static final String TAG = "PhoneStatusBarView";
    private static final boolean DEBUG = PhoneStatusBar.DEBUG;
    private static final boolean DEBUG_GESTURES = false;

    PhoneStatusBar mBar;

    private int mBasePaddingBottom;
    private int mBasePaddingLeft;
    private int mBasePaddingRight;
    private int mBasePaddingTop;

    private ViewGroup mStatusBarContents;

    boolean mIsFullyOpenedPanel = false;
    private final PhoneStatusBarTransitions mBarTransitions;
    private ScrimController mScrimController;
    private float mMinFraction;
    private float mPanelFraction;
    private Runnable mHideExpandedRunnable = new Runnable() {
        @Override
        public void run() {
            if (mPanelFraction == 0.0f) {
                mBar.makeExpandedInvisible();
            }
        }
    };

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mBarTransitions = new PhoneStatusBarTransitions(this);
    }

    public BarTransitions getBarTransitions() {
        return mBarTransitions;
    }

    public void setBar(PhoneStatusBar bar) {
        mBar = bar;
    }

    public void setScrimController(ScrimController scrimController) {
        mScrimController = scrimController;
    }

    public void shiftStatusBarItems(int horizontalShift, int verticalShift) {
        if (mStatusBarContents == null) {
            return;
        }

        mStatusBarContents.setPaddingRelative(mBasePaddingLeft + horizontalShift,
                mBasePaddingTop + verticalShift,
                mBasePaddingRight + horizontalShift,
                mBasePaddingBottom - verticalShift);
        invalidate();
    }

    @Override
    public void onFinishInflate() {
        mBarTransitions.init();

        mStatusBarContents = (ViewGroup) findViewById(R.id.status_bar_contents);

        mBasePaddingLeft = mStatusBarContents.getPaddingStart();
        mBasePaddingTop = mStatusBarContents.getPaddingTop();
        mBasePaddingRight = mStatusBarContents.getPaddingEnd();
        mBasePaddingBottom = mStatusBarContents.getPaddingBottom();
    }

    @Override
    public boolean panelEnabled() {
        return mBar.panelsEnabled();
    }

    @Override
    public boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEventInternal(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    @Override
    public void onPanelPeeked() {
        super.onPanelPeeked();
        mBar.makeExpandedVisible(false);
    }

    @Override
    public void onPanelCollapsed() {
        super.onPanelCollapsed();
        // Close the status bar in the next frame so we can show the end of the animation.
        DejankUtils.postAfterTraversal(mHideExpandedRunnable);
        mIsFullyOpenedPanel = false;
    }

    public void removePendingHideExpandedRunnables() {
        DejankUtils.removeCallbacks(mHideExpandedRunnable);
    }

    @Override
    public void onPanelFullyOpened() {
        super.onPanelFullyOpened();
        if (!mIsFullyOpenedPanel) {
            mPanel.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
        mIsFullyOpenedPanel = true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (kiroClockTouch(event)) {
            return true;
        }
        boolean barConsumedEvent = mBar.interceptTouchEvent(event);

        if (DEBUG_GESTURES) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                EventLog.writeEvent(EventLogTags.SYSUI_PANELBAR_TOUCH,
                        event.getActionMasked(), (int) event.getX(), (int) event.getY(),
                        barConsumedEvent ? 1 : 0);
            }
        }

        return barConsumedEvent || super.onTouchEvent(event);
    }

    @Override
    public void onTrackingStarted() {
        super.onTrackingStarted();
        mBar.onTrackingStarted();
        mScrimController.onTrackingStarted();
        removePendingHideExpandedRunnables();
    }

    @Override
    public void onClosingFinished() {
        super.onClosingFinished();
        mBar.onClosingFinished();
    }

    @Override
    public void onTrackingStopped(boolean expand) {
        super.onTrackingStopped(expand);
        mBar.onTrackingStopped(expand);
    }

    @Override
    public void onExpandingFinished() {
        super.onExpandingFinished();
        mScrimController.onExpandingFinished();
    }

    /*
     * KiroOS Beta 5: holding the clock shows the seconds (12:56:10), holding it again hides them.
     * The touch still goes to the panel as usual (pulling down from the clock works); only once
     * the hold fires is the panel told the touch is over, and the rest of it is swallowed.
     */
    private boolean mKiroClockHeld;
    private float mKiroDownX, mKiroDownY;
    private final Runnable mKiroClockHold = new Runnable() {
        @Override
        public void run() {
            mKiroClockHeld = true;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            final com.android.systemui.tuner.TunerService tuner =
                    com.android.systemui.tuner.TunerService.get(getContext());
            final boolean seconds = tuner.getValue(
                    com.android.systemui.statusbar.policy.Clock.CLOCK_SECONDS, 0) != 0;
            tuner.setValue(com.android.systemui.statusbar.policy.Clock.CLOCK_SECONDS,
                    seconds ? 0 : 1);
            // the panel lets go of this touch: a lift where it went down, which closes the panel
            // again (a CANCEL would open it)
            final long now = android.os.SystemClock.uptimeMillis();
            final MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP,
                    mKiroDownX, mKiroDownY, 0);
            mBar.interceptTouchEvent(up);
            PhoneStatusBarView.super.onTouchEvent(up);
            up.recycle();
        }
    };

    private boolean kiroClockTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mKiroClockHeld = false;
                removeCallbacks(mKiroClockHold);
                if (overVisibleClock(event.getX(), event.getY())) {
                    mKiroDownX = event.getX();
                    mKiroDownY = event.getY();
                    postDelayed(mKiroClockHold, android.view.ViewConfiguration.getLongPressTimeout());
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!mKiroClockHeld) {
                    final int slop = android.view.ViewConfiguration.get(getContext())
                            .getScaledTouchSlop();
                    if (Math.abs(event.getX() - mKiroDownX) > slop
                            || Math.abs(event.getY() - mKiroDownY) > slop) {
                        removeCallbacks(mKiroClockHold);
                    }
                }
                return mKiroClockHeld;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(mKiroClockHold);
                final boolean held = mKiroClockHeld;
                mKiroClockHeld = false;
                return held;
            default:
                return mKiroClockHeld;
        }
    }

    private final int[] mKiroLoc = new int[2];

    private boolean overVisibleClock(float x, float y) {
        final int[] ids = { R.id.left_clock, R.id.clock, R.id.center_clock };
        getLocationOnScreen(mKiroLoc);
        final float sx = x + mKiroLoc[0], sy = y + mKiroLoc[1];
        for (int id : ids) {
            final View clock = findViewById(id);
            if (clock == null || !clock.isShown() || clock.getWidth() == 0) {
                continue;
            }
            clock.getLocationOnScreen(mKiroLoc);
            // a little wider than the digits, so the thumb finds it
            final int pad = (int) (8 * getResources().getDisplayMetrics().density);
            if (sx >= mKiroLoc[0] - pad && sx <= mKiroLoc[0] + clock.getWidth() + pad
                    && sy >= mKiroLoc[1] - pad && sy <= mKiroLoc[1] + clock.getHeight() + pad) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mBar.interceptTouchEvent(event) || super.onInterceptTouchEvent(event);
    }

    @Override
    public void panelScrimMinFractionChanged(float minFraction) {
        if (mMinFraction != minFraction) {
            mMinFraction = minFraction;
            if (minFraction != 0.0f) {
                mScrimController.animateNextChange();
            }
            updateScrimFraction();
        }
    }

    @Override
    public void panelExpansionChanged(float frac, boolean expanded) {
        super.panelExpansionChanged(frac, expanded);
        mPanelFraction = frac;
        updateScrimFraction();
    }

    private void updateScrimFraction() {
        float scrimFraction = Math.max(mPanelFraction, mMinFraction);
        mScrimController.setPanelExpansion(scrimFraction);
    }

    public void onDensityOrFontScaleChanged() {
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        layoutParams.height = getResources().getDimensionPixelSize(
                R.dimen.status_bar_height);
        setLayoutParams(layoutParams);
    }
}
