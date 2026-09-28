/*
 * Copyright (C) 2026 KiroOS
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

package com.android.systemui.kiroos;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.content.Context;
import android.media.MediaMetadata;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.systemui.Interpolators;
import com.android.systemui.R;

import java.util.Objects;

/**
 * Status bar ticker: a new notification or the song that starts playing scrolls smoothly
 * through the status bar for a few seconds, in place of the notification icons and the clock,
 * then the bar comes back. Settings.System kiroos_ticker (default on).
 *
 * The text lies on the status bar itself, over the notification icon area, and the icons and
 * the clock step aside through their transition alpha: their plain alpha is animated by the
 * bar transitions and by the disable flags, which brought the icons back under the text.
 */
public class KiroTicker {

    private static final String SETTING = "kiroos_ticker";
    private static final long MIN_SHOW_MS = 3200;

    private final Context mContext;
    private final Handler mHandler = new Handler();
    private final ViewGroup mBar;
    private final View mIconArea;
    private final View mClock;
    private final TextView mText;
    private String mLastSong;
    private boolean mShowing;
    /** 0 = the bar as usual, 1 = the ticker in place of the icons and the clock */
    private float mAmount;
    private ValueAnimator mAnimator;

    public KiroTicker(Context context, View statusBar) {
        mContext = context;
        // null before the status bar is inflated: then it stays off
        mBar = statusBar instanceof FrameLayout ? (ViewGroup) statusBar : null;
        mIconArea = mBar == null ? null : mBar.findViewById(R.id.notification_icon_area);
        mClock = mBar == null ? null : mBar.findViewById(R.id.clock);
        mText = new TextView(context);
        mText.setSingleLine(true);
        mText.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        mText.setMarqueeRepeatLimit(1);
        mText.setHorizontalFadingEdgeEnabled(true);
        mText.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        mText.setTextAppearance(R.style.TextAppearance_StatusBar_Clock);
        mText.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mText.setAlpha(0f);
        mText.setVisibility(View.GONE);
        if (mBar != null && mIconArea != null) {
            mBar.addView(mText, new FrameLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START | Gravity.TOP));
        }
    }

    private boolean enabled() {
        return mBar != null && mIconArea != null
                && Settings.System.getInt(mContext.getContentResolver(), SETTING, 1) != 0;
    }

    public void onNotification(StatusBarNotification sbn) {
        if (!enabled() || sbn == null || sbn.isOngoing()) {
            return;
        }
        final Notification n = sbn.getNotification();
        CharSequence text = n.tickerText;
        if (TextUtils.isEmpty(text)) {
            final Bundle x = n.extras;
            final CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
            final CharSequence body = x.getCharSequence(Notification.EXTRA_TEXT);
            text = TextUtils.isEmpty(body) ? title
                    : TextUtils.isEmpty(title) ? body : title + ": " + body;
        }
        if (!TextUtils.isEmpty(text) && n.visibility != Notification.VISIBILITY_SECRET) {
            show(text.toString().replace('\n', ' '));
        }
    }

    public void onMedia(MediaMetadata m) {
        if (!enabled() || m == null) {
            return;
        }
        final String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        final String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(title)) {
            return;
        }
        final String song = TextUtils.isEmpty(artist) ? title : title + " · " + artist;
        if (Objects.equals(song, mLastSong)) {
            return;             // the same song: metadata updates do not repeat it
        }
        mLastSong = song;
        show("♪ " + song);
    }

    /** puts the text exactly over the notification icon area (it moves with the clock setting) */
    private void place() {
        int left = mIconArea.getLeft() + mIconArea.getPaddingStart();
        for (ViewParent p = mIconArea.getParent(); p instanceof View && p != mBar;
                p = p.getParent()) {
            left += ((View) p).getLeft();
        }
        final int width = Math.max(0, mIconArea.getWidth() - mIconArea.getPaddingStart());
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mText.getLayoutParams();
        if (lp.leftMargin != left || lp.width != width) {
            lp.leftMargin = left;
            lp.width = width;
            mText.setLayoutParams(lp);
        }
    }

    private void show(String text) {
        mHandler.removeCallbacks(mHide);
        place();
        // the clock's colour: dark over apps with a light status bar, white otherwise
        if (mClock instanceof TextView) {
            mText.setTextColor(((TextView) mClock).getCurrentTextColor());
        }
        mText.setText(text);
        mText.setVisibility(View.VISIBLE);
        mText.setSelected(true);                // starts the marquee
        if (!mShowing) {
            mShowing = true;
            animateTo(1f, 240);
        }
        // long texts get the time for one pass of the marquee
        final long ms = Math.max(MIN_SHOW_MS, 1600 + text.length() * 90L);
        mHandler.postDelayed(mHide, Math.min(ms, 9000));
    }

    private final Runnable mHide = new Runnable() {
        @Override
        public void run() {
            mShowing = false;
            animateTo(0f, 220);
        }
    };

    private void animateTo(final float target, long duration) {
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        mAnimator = ValueAnimator.ofFloat(mAmount, target);
        mAnimator.setDuration(duration);
        mAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                apply((float) a.getAnimatedValue());
            }
        });
        mAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mCancelled && target == 0f) {
                    mText.setSelected(false);
                    mText.setVisibility(View.GONE);
                }
            }
        });
        mAnimator.start();
    }

    /** the icons and the clock leave upward as the text comes up from below */
    private void apply(float amount) {
        mAmount = amount;
        final float h = mBar.getHeight();
        final float out = Math.max(0f, 1f - amount * 1.6f);   // gone before the text arrives
        final float in = Math.max(0f, amount * 1.6f - 0.6f);
        mIconArea.setTransitionAlpha(out);
        mIconArea.setTranslationY(-h * 0.35f * amount);
        if (mClock != null) {
            mClock.setTransitionAlpha(out);
        }
        mText.setAlpha(in);
        mText.setTranslationY(h * 0.35f * (1f - amount));
    }
}
