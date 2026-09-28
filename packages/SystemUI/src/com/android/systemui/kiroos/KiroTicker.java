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
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.systemui.R;

import java.util.Objects;

/**
 * Status bar ticker: a new notification or the song that starts playing scrolls smoothly
 * through the status bar for a few seconds, in place of the notification icons and the clock,
 * then the bar comes back. Settings.System kiroos_ticker (default on).
 */
public class KiroTicker {

    private static final String SETTING = "kiroos_ticker";
    private static final long MIN_SHOW_MS = 3200;

    private final Context mContext;
    private final Handler mHandler = new Handler();
    private final ViewGroup mIconArea;
    private final View mClock;
    private final TextView mText;
    private String mLastSong;
    private boolean mShowing;

    public KiroTicker(Context context, View statusBar) {
        mContext = context;
        // null before the status bar is inflated: then it stays off
        mIconArea = statusBar == null ? null
                : (ViewGroup) statusBar.findViewById(R.id.notification_icon_area);
        mClock = statusBar == null ? null : statusBar.findViewById(R.id.clock);
        mText = new TextView(context);
        mText.setSingleLine(true);
        mText.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        mText.setMarqueeRepeatLimit(1);
        mText.setHorizontalFadingEdgeEnabled(true);
        mText.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        mText.setTextAppearance(R.style.TextAppearance_StatusBar_Clock);
        mText.setAlpha(0f);
        mText.setVisibility(View.GONE);
        if (mIconArea != null) {
            mIconArea.addView(mText, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private boolean enabled() {
        return mIconArea != null
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

    private void show(String text) {
        mHandler.removeCallbacks(mHide);
        // the clock's colour: dark over apps with a light status bar, white otherwise
        if (mClock instanceof TextView) {
            mText.setTextColor(((TextView) mClock).getCurrentTextColor());
        }
        mText.setText(text);
        mText.setVisibility(View.VISIBLE);
        mText.setSelected(true);                // starts the marquee
        if (!mShowing) {
            mShowing = true;
            for (int i = 0; i < mIconArea.getChildCount(); i++) {
                final View c = mIconArea.getChildAt(i);
                if (c != mText) {
                    c.animate().alpha(0f).setDuration(150).start();
                }
            }
            if (mClock != null) {
                mClock.animate().alpha(0f).setDuration(150).start();
            }
            mText.setTranslationY(mText.getHeight() * 0.4f);
            mText.animate().alpha(1f).translationY(0f).setDuration(220).start();
        }
        // long texts get the time for one pass of the marquee
        final long ms = Math.max(MIN_SHOW_MS, 1600 + text.length() * 90L);
        mHandler.postDelayed(mHide, Math.min(ms, 9000));
    }

    private final Runnable mHide = new Runnable() {
        @Override
        public void run() {
            mShowing = false;
            mText.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                @Override
                public void run() {
                    mText.setSelected(false);
                    mText.setVisibility(View.GONE);
                }
            }).start();
            for (int i = 0; i < mIconArea.getChildCount(); i++) {
                final View c = mIconArea.getChildAt(i);
                if (c != mText) {
                    c.animate().alpha(1f).setDuration(220).start();
                }
            }
            if (mClock != null) {
                mClock.animate().alpha(1f).setDuration(220).start();
            }
        }
    };
}
