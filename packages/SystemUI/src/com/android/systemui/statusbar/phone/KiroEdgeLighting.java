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

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;

/**
 * KiroOS Edge lighting (S6 edge / S6 edge+): when a notification arrives the two curved
 * sides of the panel glow twice in the chosen color. Settings > Display > Edge screen:
 * Settings.System kiroos_edge_lighting (default on) and kiroos_edge_lighting_color.
 */
public class KiroEdgeLighting {

    private static final String EDGE_LIGHTING = "kiroos_edge_lighting";
    private static final String EDGE_LIGHTING_COLOR = "kiroos_edge_lighting_color";
    private static final int DEFAULT_COLOR = 0xFF2A78D6;

    private static final long PULSE_MS = 700;
    private static final int PULSES = 2;
    /** Several notifications in a burst light the edges once. */
    private static final long MIN_INTERVAL_MS = 2500;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final boolean mSupported;
    private EdgeView mView;
    private long mLastShown;

    public KiroEdgeLighting(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        final String device = SystemProperties.get("ro.product.device", "");
        mSupported = SystemProperties.getBoolean("ro.kiroos.edge",
                device.startsWith("zerolte") || device.startsWith("zenlte"));
    }

    /** Called for every notification SystemUI adds to the shade. */
    public void onNotificationAdded(StatusBarNotification sbn) {
        if (!mSupported || sbn == null || sbn.isOngoing() || !sbn.isClearable()) {
            return;
        }
        if (Settings.System.getInt(mContext.getContentResolver(), EDGE_LIGHTING, 1) == 0) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (mView != null || now - mLastShown < MIN_INTERVAL_MS) {
            return;
        }
        mLastShown = now;
        show(Settings.System.getInt(mContext.getContentResolver(), EDGE_LIGHTING_COLOR,
                DEFAULT_COLOR));
    }

    private void show(int color) {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("KiroEdgeLighting");
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;

        mView = new EdgeView(mContext, color);
        mWindowManager.addView(mView, lp);

        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(PULSE_MS * PULSES);
        anim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.6f, 1f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                // two soft pulses: sin^2 over [0, PULSES * PI]
                final double phase = (float) a.getAnimatedValue() * Math.PI * PULSES;
                final float s = (float) Math.sin(phase);
                mView.setGlow(s * s);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                dismiss();
            }
        });
        anim.start();
    }

    private void dismiss() {
        if (mView != null) {
            if (mView.isAttachedToWindow()) {
                mWindowManager.removeViewImmediate(mView);
            }
            mView = null;
        }
    }

    /** Draws a glow fading inwards from the left and the right edges of the panel. */
    private static final class EdgeView extends View {
        private final Paint mLeft = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRight = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mColor;
        private final float mWidth;
        private float mGlow;

        EdgeView(Context context, int color) {
            super(context);
            mColor = color;
            // the curved part of the S6 edge panels is about 2.5 mm wide
            mWidth = 22 * context.getResources().getDisplayMetrics().density;
        }

        void setGlow(float glow) {
            mGlow = glow;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            final int transparent = mColor & 0x00FFFFFF;
            mLeft.setShader(new LinearGradient(0, 0, mWidth, 0, mColor, transparent,
                    Shader.TileMode.CLAMP));
            mRight.setShader(new LinearGradient(w, 0, w - mWidth, 0, mColor, transparent,
                    Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int alpha = Math.round(255 * mGlow);
            mLeft.setAlpha(alpha);
            mRight.setAlpha(alpha);
            canvas.drawRect(0, 0, mWidth, getHeight(), mLeft);
            canvas.drawRect(getWidth() - mWidth, 0, getWidth(), getHeight(), mRight);
        }
    }
}
