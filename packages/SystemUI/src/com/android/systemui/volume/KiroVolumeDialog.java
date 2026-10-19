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

package com.android.systemui.volume;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.volume.VolumeDialogController.State;
import com.android.systemui.volume.VolumeDialogController.StreamState;

import java.io.PrintWriter;

/**
 * KiroOS Beta 4.1: the volume panel, rewritten in the KiroOS design. A glass card at the left
 * edge, next to the S6's volume keys: the ringer button (sound, vibrate, silent), one tall slider
 * for the stream the keys change, and a "more" button that slides out media, ringtone and alarm
 * side by side. It slides in from the edge and away after a few seconds without a touch. The
 * volumes, the ringer mode and the safety warning are the same as before (VolumeDialogController).
 */
public class KiroVolumeDialog {

    private static final long TIMEOUT = 3000;
    private static final long TIMEOUT_WARNING = 5000;

    private final Context mContext;
    private final int mWindowType;
    private final VolumeDialogController mController;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SparseArray<Boolean> mImportant = new SparseArray<>();
    private final Object mSafetyWarningLock = new Object();
    private SafetyWarningDialog mSafetyWarning;

    private Dialog mDialog;
    private LinearLayout mCard;
    private LinearLayout mExtras;
    private KiroVolumePill mMain;
    private ImageView mRinger;
    private ImageView mMore;
    private State mState;
    private int mMainStream = -1;
    private boolean mShowing;
    private boolean mExpanded;
    private boolean mTouching;
    private int mUiNight = -1;

    public KiroVolumeDialog(Context context, int windowType, VolumeDialogController controller,
            ZenModeController zen, VolumeDialog.Callback callback) {
        mContext = context;
        mWindowType = windowType;
        mController = controller;
        controller.addCallback(mCallbacks, mHandler);
        controller.getState();
    }

    public void setStreamImportant(int stream, boolean important) {
        mImportant.put(stream, important);
    }

    public void setAutomute(boolean automute) {
        // the pills mute at their lowest step by themselves (the streams do)
    }

    public void setSilentMode(boolean silentMode) {
        // the ringer button has the silent mode
    }

    public void dump(PrintWriter pw) {
        pw.println("KiroVolumeDialog state:");
        pw.print("  mShowing: "); pw.println(mShowing);
        pw.print("  mExpanded: "); pw.println(mExpanded);
        pw.print("  mMainStream: "); pw.println(mMainStream);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                mContext.getResources().getDisplayMetrics()));
    }

    private boolean night() {
        return (mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO;
    }

    // --- window -------------------------------------------------------------------------------

    private void build() {
        mUiNight = mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        mDialog = new Dialog(mContext) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (mShowing && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    dismissH(Events.DISMISS_REASON_TOUCH_OUTSIDE);
                    return true;
                }
                return false;
            }
        };
        final Window w = mDialog.getWindow();
        w.requestFeature(Window.FEATURE_NO_TITLE);
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        mDialog.setCanceledOnTouchOutside(true);
        final WindowManager.LayoutParams lp = w.getAttributes();
        lp.type = mWindowType;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.setTitle("KiroVolumeDialog");
        // the S6 family has its volume keys on the left side: the panel sits right beside them
        lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        lp.x = dp(10);
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.windowAnimations = -1;
        w.setAttributes(lp);

        final boolean dark = night();
        final int cardColor = dark ? 0xEB1C1C20 : 0xF2F7F7FA;
        final int track = dark ? 0x33FFFFFF : 0x1A000000;
        final int fill = mContext.getColor(R.color.kiroos_bright_fill);
        final int iconOnTrack = dark ? 0xCCFFFFFF : 0x99000000;
        final int ink = dark ? 0xFFFFFFFF : 0xFF1C1C1E;

        final FrameLayout root = new FrameLayout(mContext);
        root.setClipChildren(false);
        root.setPadding(dp(4), dp(14), dp(14), dp(14));
        mCard = new LinearLayout(mContext);
        mCard.setOrientation(LinearLayout.HORIZONTAL);
        mCard.setGravity(Gravity.BOTTOM);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor);
        bg.setCornerRadius(dp(30));
        mCard.setBackground(bg);
        mCard.setElevation(dp(14));
        mCard.setPadding(dp(10), dp(12), dp(10), dp(12));
        root.addView(mCard, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START));


        final LinearLayout main = new LinearLayout(mContext);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setGravity(Gravity.CENTER_HORIZONTAL);
        mRinger = roundButton(ink, dark);
        mRinger.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleRinger();
            }
        });
        main.addView(mRinger, new LinearLayout.LayoutParams(dp(44), dp(44)));
        mMain = pill(track, fill, iconOnTrack);
        final LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(52), dp(196));
        mlp.topMargin = dp(10);
        mlp.bottomMargin = dp(10);
        main.addView(mMain, mlp);
        mMore = roundButton(ink, dark);
        mMore.setImageResource(R.drawable.ic_volume_expand);
        mMore.setContentDescription(mContext.getString(R.string.kiroos_volume_more));
        mMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setExpanded(!mExpanded);
            }
        });
        main.addView(mMore, new LinearLayout.LayoutParams(dp(44), dp(44)));
        mCard.addView(main);

        // the other volumes, hidden until "more": they slide out to the right of the main one
        mExtras = new LinearLayout(mContext);
        mExtras.setOrientation(LinearLayout.HORIZONTAL);
        mExtras.setGravity(Gravity.BOTTOM);
        mExtras.setVisibility(View.GONE);
        mCard.addView(mExtras);

        mDialog.setContentView(root);
    }

    private ImageView roundButton(int ink, boolean dark) {
        final ImageView b = new ImageView(mContext);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(11), dp(11), dp(11), dp(11));
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(dark ? 0x26FFFFFF : 0x14000000);
        b.setBackground(bg);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(ink));
        return b;
    }

    private KiroVolumePill pill(int track, int fill, int iconOnTrack) {
        final KiroVolumePill p = new KiroVolumePill(mContext);
        p.setColors(track, fill, Color.WHITE, iconOnTrack);
        p.setListener(mPillListener);
        return p;
    }

    private final KiroVolumePill.Listener mPillListener = new KiroVolumePill.Listener() {
        @Override
        public void onLevel(KiroVolumePill pill, int level) {
            final Object stream = pill.getTag();
            if (stream instanceof Integer) {
                mController.setStreamVolume((Integer) stream, level);
            }
            rescheduleTimeout();
        }

        @Override
        public void onToggleMute(KiroVolumePill pill) {
            final Object tag = pill.getTag();
            if (!(tag instanceof Integer) || mState == null) return;
            final int stream = (Integer) tag;
            if (stream == AudioManager.STREAM_RING) {
                cycleRinger();
                return;
            }
            final StreamState ss = mState.states.get(stream);
            if (ss != null && ss.muteSupported) {
                mController.setStreamMute(stream, !ss.muted);
            }
            rescheduleTimeout();
        }

        @Override
        public void onTracking(KiroVolumePill pill, boolean tracking) {
            mTouching = tracking;
            if (!tracking) rescheduleTimeout();
            else mHandler.removeCallbacks(mTimeout);
        }
    };

    // --- show / dismiss -----------------------------------------------------------------------

    private void showH(int reason) {
        final int night = mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        if (mDialog == null || night != mUiNight) {
            if (mDialog != null && mDialog.isShowing()) mDialog.dismiss();
            build();
            mExpanded = false;
        }
        rescheduleTimeout();
        if (mShowing) return;
        mShowing = true;
        mController.notifyVisible(true);
        if (mState != null) updateH(false);
        mDialog.show();
        mCard.setTranslationX(-dp(90));
        mCard.setAlpha(0f);
        mCard.animate().translationX(0f).alpha(1f).setDuration(260)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f))
                .start();
    }

    private void dismissH(int reason) {
        mHandler.removeCallbacks(mTimeout);
        if (!mShowing) return;
        mShowing = false;
        mController.notifyVisible(false);
        mCard.animate().translationX(-dp(90)).alpha(0f).setDuration(200)
                .setInterpolator(new android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!mShowing && mDialog != null) {
                            mDialog.dismiss();
                            setExpandedNow(false);
                        }
                    }
                }).start();
        synchronized (mSafetyWarningLock) {
            if (mSafetyWarning != null) {
                mSafetyWarning.dismiss();
            }
        }
    }

    private final Runnable mTimeout = new Runnable() {
        @Override
        public void run() {
            if (mTouching) {
                rescheduleTimeout();
                return;
            }
            dismissH(Events.DISMISS_REASON_TIMEOUT);
        }
    };

    private void rescheduleTimeout() {
        mHandler.removeCallbacks(mTimeout);
        mHandler.postDelayed(mTimeout, mSafetyWarning != null ? TIMEOUT_WARNING
                : mExpanded ? TIMEOUT * 2 : TIMEOUT);
    }

    // --- content --------------------------------------------------------------------------------

    /** the stream the keys change now (the system's active one, else ringtone or media) */
    private int mainStream() {
        if (mState != null && mState.activeStream != State.NO_ACTIVE_STREAM
                && mState.states.get(mState.activeStream) != null) {
            return mState.activeStream;
        }
        return Util.isVoiceCapable(mContext) ? AudioManager.STREAM_RING : AudioManager.STREAM_MUSIC;
    }

    private void updateH(boolean animate) {
        if (mDialog == null || mState == null) return;
        final int stream = mainStream();
        if (stream != mMainStream) {
            mMainStream = stream;
            mMain.setTag(stream);
            animate = false;
        }
        bindPill(mMain, stream, animate);
        mRinger.setImageResource(ringerIcon());
        if (mExpanded) {
            for (int i = 0; i < mExtras.getChildCount(); i++) {
                final View col = mExtras.getChildAt(i);
                final KiroVolumePill p = (KiroVolumePill) ((ViewGroup) col).getChildAt(0);
                bindPill(p, (Integer) col.getTag(), animate);
            }
        }
    }

    private void bindPill(KiroVolumePill p, int stream, boolean animate) {
        if (p == null) return;
        final StreamState ss = mState.states.get(stream);
        if (ss == null) return;
        final boolean silent = ss.muted || ss.level == ss.levelMin;
        p.setIcon(mContext.getDrawable(icon(stream, silent)));
        p.setRange(ss.levelMin, ss.levelMax, ss.muted ? ss.levelMin : ss.level, animate);
        p.setContentDescription(ss.name);
    }

    private int icon(int stream, boolean silent) {
        switch (stream) {
            case AudioManager.STREAM_RING:
                if (mState != null
                        && mState.ringerModeInternal == AudioManager.RINGER_MODE_VIBRATE) {
                    return R.drawable.ic_volume_ringer_vibrate;
                }
                return silent ? R.drawable.ic_volume_ringer_mute : R.drawable.ic_volume_ringer;
            case AudioManager.STREAM_NOTIFICATION:
                return silent ? R.drawable.ic_volume_notification_mute
                        : R.drawable.ic_volume_notification;
            case AudioManager.STREAM_ALARM:
                return silent ? R.drawable.ic_volume_alarm_mute : R.drawable.ic_volume_alarm;
            case AudioManager.STREAM_VOICE_CALL:
                return R.drawable.ic_volume_voice;
            case AudioManager.STREAM_BLUETOOTH_SCO:
                return R.drawable.ic_volume_bt_sco;
            case AudioManager.STREAM_SYSTEM:
                return silent ? R.drawable.ic_volume_system_mute : R.drawable.ic_volume_system;
            default:
                if (mState != null && mState.states.get(stream) != null
                        && mState.states.get(stream).routedToBluetooth) {
                    return silent ? R.drawable.ic_volume_media_bt_mute : R.drawable.ic_volume_media_bt;
                }
                return silent ? R.drawable.ic_volume_media_mute : R.drawable.ic_volume_media;
        }
    }

    private int ringerIcon() {
        if (mState == null) return R.drawable.ic_volume_ringer;
        switch (mState.ringerModeInternal) {
            case AudioManager.RINGER_MODE_VIBRATE:
                return R.drawable.ic_volume_ringer_vibrate;
            case AudioManager.RINGER_MODE_SILENT:
                return R.drawable.ic_volume_ringer_mute;
            default:
                return R.drawable.ic_volume_ringer;
        }
    }

    /** sound, then vibrate (if the phone can), then silent, then sound again */
    private void cycleRinger() {
        if (mState == null) return;
        int next;
        switch (mState.ringerModeInternal) {
            case AudioManager.RINGER_MODE_NORMAL:
                next = mController.hasVibrator() ? AudioManager.RINGER_MODE_VIBRATE
                        : AudioManager.RINGER_MODE_SILENT;
                break;
            case AudioManager.RINGER_MODE_VIBRATE:
                next = AudioManager.RINGER_MODE_SILENT;
                break;
            default:
                next = AudioManager.RINGER_MODE_NORMAL;
                break;
        }
        mController.setRingerMode(next, false);
        if (next == AudioManager.RINGER_MODE_VIBRATE) {
            mController.vibrate();
        }
        mRinger.animate().cancel();
        mRinger.setScaleX(0.85f);
        mRinger.setScaleY(0.85f);
        mRinger.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
        rescheduleTimeout();
    }

    // --- more volumes -------------------------------------------------------------------------

    private void setExpanded(boolean expanded) {
        if (expanded == mExpanded) return;
        mExpanded = expanded;
        mMore.animate().rotation(expanded ? 180f : 0f).setDuration(240).start();
        if (expanded) {
            fillExtras();
            mExtras.setVisibility(View.VISIBLE);
            for (int i = 0; i < mExtras.getChildCount(); i++) {
                final View c = mExtras.getChildAt(i);
                c.setAlpha(0f);
                c.setTranslationX(-dp(40));
                c.animate().alpha(1f).translationX(0f).setStartDelay(40L * i)
                        .setDuration(260)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f))
                        .start();
            }
        } else {
            mExtras.animate().alpha(0f).setDuration(140).withEndAction(new Runnable() {
                @Override
                public void run() {
                    setExpandedNow(false);
                }
            }).start();
        }
        rescheduleTimeout();
    }

    private void setExpandedNow(boolean expanded) {
        mExpanded = expanded;
        if (mExtras == null) return;
        mExtras.animate().cancel();
        mExtras.setAlpha(1f);
        mExtras.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (!expanded) mExtras.removeAllViews();
        if (mMore != null) mMore.setRotation(expanded ? 180f : 0f);
    }

    /** media, ringtone (or notifications), alarm: the ones not already in the main slider */
    private void fillExtras() {
        mExtras.removeAllViews();
        if (mState == null) return;
        final boolean dark = night();
        final int track = dark ? 0x33FFFFFF : 0x1A000000;
        final int fill = mContext.getColor(R.color.kiroos_bright_fill);
        final int iconOnTrack = dark ? 0xCCFFFFFF : 0x99000000;
        final int[] streams = {
            AudioManager.STREAM_ALARM,
            Util.isVoiceCapable(mContext) ? AudioManager.STREAM_RING
                    : AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_MUSIC,
        };
        for (int stream : streams) {
            if (stream == mMainStream || mState.states.get(stream) == null) continue;
            final LinearLayout col = new LinearLayout(mContext);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setTag(stream);
            final KiroVolumePill p = pill(track, fill, iconOnTrack);
            p.setTag(stream);

            col.addView(p, new LinearLayout.LayoutParams(dp(44), dp(170)));
            final TextView label = new TextView(mContext);
            final StreamState ss = mState.states.get(stream);
            label.setText(ss.name != null ? ss.name : "");
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setTextColor(dark ? 0xB3FFFFFF : 0x99000000);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            final LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(64),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(8);
            col.addView(label, llp);
            final LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMarginStart(dp(4));
            clp.bottomMargin = dp(54);
            mExtras.addView(col, clp);
            bindPill(p, stream, false);
        }
    }

    // --- safety warning -----------------------------------------------------------------------

    private void showSafetyWarningH(int flags) {
        if ((flags & (AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_SHOW_UI_WARNINGS)) != 0
                || mShowing) {
            synchronized (mSafetyWarningLock) {
                if (mSafetyWarning != null) {
                    return;
                }
                mSafetyWarning = new SafetyWarningDialog(mContext, mController.getAudioManager()) {
                    @Override
                    protected void cleanUp() {
                        synchronized (mSafetyWarningLock) {
                            mSafetyWarning = null;
                        }
                    }
                };
                mSafetyWarning.show();
            }
        }
        rescheduleTimeout();
    }

    // --- controller ---------------------------------------------------------------------------

    private final VolumeDialogController.Callbacks mCallbacks =
            new VolumeDialogController.Callbacks() {
        @Override
        public void onShowRequested(int reason) {
            showH(reason);
        }

        @Override
        public void onDismissRequested(int reason) {
            dismissH(reason);
        }

        @Override
        public void onStateChanged(State state) {
            mState = state;
            if (mShowing) {
                updateH(true);
                if (!mTouching) rescheduleTimeout();
            }
        }

        @Override
        public void onLayoutDirectionChanged(int layoutDirection) {
            if (mCard != null) mCard.setLayoutDirection(layoutDirection);
        }

        @Override
        public void onConfigurationChanged() {
            // a theme or size change: built again on the next show
            if (mDialog != null && !mShowing) {
                mDialog = null;
            }
        }

        @Override
        public void onShowVibrateHint() {
            if (mShowing) updateH(true);
        }

        @Override
        public void onShowSilentHint() {
            if (mShowing) updateH(true);
        }

        @Override
        public void onScreenOff() {
            dismissH(Events.DISMISS_REASON_SCREEN_OFF);
        }

        @Override
        public void onShowSafetyWarning(int flags) {
            showSafetyWarningH(flags);
        }
    };
}
