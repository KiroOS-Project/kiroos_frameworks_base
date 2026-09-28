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

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;

import com.android.systemui.SystemUI;

/**
 * Kiro Edge Glow: a soft light along the whole edge of the screen.
 *
 *   kAI       while an answer is being written, a blue-violet light turns slowly round
 *   charging  when the cable goes in, a wave of light runs once round the screen
 *   music     Pulse Music sends its beats; the edge pulses in time
 *   notify    two soft pulses for a new notification, only on the always-on display
 *             (with the screen on the notification shows as a card)
 *
 * Apps talk to it with the broadcast com.kiroos.intent.EDGE_GLOW (package
 * com.android.systemui), extra "mode": kai_start, kai_stop, beat (+ float "level" 0..1).
 * Settings.System kiroos_edge_glow (default on) turns it off. The window exists only while
 * something glows.
 */
public class KiroEdgeGlow extends SystemUI {

    public static final String ACTION = "com.kiroos.intent.EDGE_GLOW";
    private static final String SETTING = "kiroos_edge_glow";

    private static final int KAI = 1;
    private static final int CHARGE = 2;
    private static final int BEAT = 3;
    private static final int NOTIFY = 4;

    private static KiroEdgeGlow sInstance;

    private final Handler mHandler = new Handler();
    private WindowManager mWm;
    private GlowView mView;
    private boolean mKai;
    private ValueAnimator mAnim;
    private long mLastNotify;

    /** SystemUI's status bar calls this for every new notification */
    public static void onNotification(android.service.notification.StatusBarNotification sbn) {
        // the S6 edge / edge+ light their curved sides instead (KiroEdgeLighting)
        if (sbn == null || sbn.isOngoing() || !sbn.isClearable()
                || android.os.SystemProperties.getBoolean("ro.kiroos.edge", false)) {
            return;
        }
        if (sInstance != null) {
            sInstance.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    sInstance.notifyPulse();
                }
            });
        }
    }

    @Override
    public void start() {
        sInstance = this;
        mWm = mContext.getSystemService(WindowManager.class);
        final IntentFilter f = new IntentFilter(ACTION);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!enabled()) {
                    stop();
                    return;
                }
                if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                    if (!mKai) {
                        play(CHARGE, 1400, 0);
                    }
                    return;
                }
                final String mode = intent.getStringExtra("mode");
                if ("kai_start".equals(mode)) {
                    mKai = true;
                    play(KAI, 2600, ValueAnimator.INFINITE);
                } else if ("kai_stop".equals(mode)) {
                    mKai = false;
                    stop();
                } else if ("beat".equals(mode) && !mKai) {
                    final float level = intent.getFloatExtra("level", 0.6f);
                    ensureView();
                    mView.beat(Math.max(0.15f, Math.min(1f, level)));
                    // the music stopped: the window goes away
                    mHandler.removeCallbacks(mIdle);
                    mHandler.postDelayed(mIdle, 3000);
                }
            }
        }, f, null, mHandler);
    }

    private final Runnable mIdle = new Runnable() {
        @Override
        public void run() {
            if (!mKai && (mAnim == null || !mAnim.isRunning())) {
                stop();
            }
        }
    };

    private boolean enabled() {
        return Settings.System.getInt(mContext.getContentResolver(), SETTING, 1) != 0;
    }

    private void notifyPulse() {
        final long now = SystemClock.uptimeMillis();
        if (!enabled() || mKai || now - mLastNotify < 2500 || !dozing()) {
            return;
        }
        mLastNotify = now;
        play(NOTIFY, 1500, 0);
    }

    /** the always-on display is showing: the screen is on, in its low-power doze state */
    private boolean dozing() {
        final android.view.Display d = mContext.getSystemService(
                android.hardware.display.DisplayManager.class).getDisplay(
                android.view.Display.DEFAULT_DISPLAY);
        final int state = d.getState();
        return state == android.view.Display.STATE_DOZE
                || state == android.view.Display.STATE_DOZE_SUSPEND;
    }

    private void ensureView() {
        if (mView != null) {
            return;
        }
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("KiroEdgeGlow");
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        mView = new GlowView(mContext);
        mWm.addView(mView, lp);
    }

    private void play(final int mode, long duration, int repeat) {
        ensureView();
        if (mAnim != null) {
            mAnim.cancel();
        }
        mView.mMode = mode;
        mAnim = ValueAnimator.ofFloat(0f, 1f);
        mAnim.setDuration(duration);
        mAnim.setRepeatCount(repeat);
        mAnim.setInterpolator(new LinearInterpolator());
        mAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                if (mView != null) {
                    mView.setPhase((float) a.getAnimatedValue());
                }
            }
        });
        mAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!mCancelled && !mKai) {
                    stop();
                }
            }
        });
        mAnim.start();
    }

    private void stop() {
        if (mAnim != null) {
            mAnim.cancel();
            mAnim = null;
        }
        if (mView != null) {
            mView.fadeOutAndRemove();
            mView = null;
        }
    }

    /** the light itself: a few nested strokes along the rounded screen edge */
    private final class GlowView extends View {
        private static final int LAYERS = 7;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final Matrix mMatrix = new Matrix();
        private final float mDensity;
        private SweepGradient mSweep;
        private SweepGradient mWave;
        int mMode;
        private float mPhase;
        private float mBeat;
        private long mBeatAt;

        GlowView(Context c) {
            super(c);
            mDensity = c.getResources().getDisplayMetrics().density;
            mPaint.setStyle(Paint.Style.STROKE);
        }

        void setPhase(float p) {
            mPhase = p;
            invalidate();
        }

        void beat(float level) {
            mMode = BEAT;
            mBeat = level;
            mBeatAt = SystemClock.uptimeMillis();
            invalidate();
        }

        void fadeOutAndRemove() {
            animate().alpha(0f).setDuration(220).withEndAction(new Runnable() {
                @Override
                public void run() {
                    if (isAttachedToWindow()) {
                        mWm.removeViewImmediate(GlowView.this);
                    }
                }
            }).start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            // KiroOS blue -> violet -> magenta -> back, all round the edge
            mSweep = new SweepGradient(w / 2f, h / 2f,
                    new int[] {0xFF3987E5, 0xFF7B5CFF, 0xFFD45CF0, 0xFF7B5CFF, 0xFF3987E5},
                    null);
            // the charging wave: one bright band, dark elsewhere
            mWave = new SweepGradient(w / 2f, h / 2f,
                    new int[] {0x003987E5, 0x003987E5, 0xFF3987E5, 0xFFFFFFFF, 0xFF3987E5, 0x003987E5},
                    new float[] {0f, 0.78f, 0.88f, 0.92f, 0.96f, 1f});
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            float intensity;
            float rotate = 0f;
            switch (mMode) {
                case KAI:
                    // steady breathing light that turns once per cycle
                    intensity = 0.55f + 0.25f * (float) Math.sin(mPhase * 2 * Math.PI);
                    rotate = mPhase * 360f;
                    break;
                case CHARGE:
                    // a band of light runs once round the screen, from the bottom
                    intensity = Math.min(1f, (1f - mPhase) * 6f);
                    rotate = 90f + mPhase * 360f;
                    break;
                case BEAT: {
                    final float t = (SystemClock.uptimeMillis() - mBeatAt) / 260f;
                    intensity = mBeat * Math.max(0f, 1f - t);
                    if (t < 1f) {
                        postInvalidateOnAnimation();
                    }
                    break;
                }
                default: {
                    // two soft pulses
                    final float s = (float) Math.sin(mPhase * Math.PI * 2);
                    intensity = s * s;
                }
            }
            if (intensity <= 0.01f || mSweep == null) {
                return;
            }
            mMatrix.setRotate(rotate, w / 2f, h / 2f);
            final SweepGradient shader = mMode == CHARGE ? mWave : mSweep;
            shader.setLocalMatrix(mMatrix);
            mPaint.setShader(shader);
            final float step = 3.2f * mDensity;
            // the S6 panels have square-ish corners; a small radius follows the glass
            final float radius = 10f * mDensity;
            for (int i = 0; i < LAYERS; i++) {
                final float inset = step * (i + 0.5f);
                mPaint.setStrokeWidth(step * 1.2f);
                final float a = intensity * (1f - i / (float) LAYERS);
                mPaint.setAlpha(Math.round(255 * a * a * 0.9f));
                mRect.set(inset, inset, w - inset, h - inset);
                canvas.drawRoundRect(mRect, radius, radius, mPaint);
            }
        }
    }
}
