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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.android.systemui.SystemUI;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Privacy dots: a green dot at the top right corner while an app uses the camera, an orange
 * one while an app records from the microphone (both: green and orange side by side).
 * Camera use comes from CameraManager availability, the microphone from the recording
 * callbacks; the always-listening hotword source is not counted.
 */
public class KiroPrivacyDots extends SystemUI {

    private static final int GREEN = 0xFF34C759;
    private static final int ORANGE = 0xFFFF9F0A;

    private final Handler mHandler = new Handler();
    private final Set<String> mCamerasInUse = new HashSet<>();
    private boolean mMic;
    private WindowManager mWm;
    private DotsView mView;

    @Override
    public void start() {
        mWm = mContext.getSystemService(WindowManager.class);
        mContext.getSystemService(CameraManager.class).registerAvailabilityCallback(
                new CameraManager.AvailabilityCallback() {
                    @Override
                    public void onCameraAvailable(String id) {
                        mCamerasInUse.remove(id);
                        update();
                    }

                    @Override
                    public void onCameraUnavailable(String id) {
                        mCamerasInUse.add(id);
                        update();
                    }
                }, mHandler);
        mContext.getSystemService(AudioManager.class).registerAudioRecordingCallback(
                new AudioManager.AudioRecordingCallback() {
                    @Override
                    public void onRecordingConfigChanged(List<AudioRecordingConfiguration> list) {
                        boolean mic = false;
                        if (list != null) {
                            for (AudioRecordingConfiguration c : list) {
                                if (c.getClientAudioSource() != MediaRecorder.AudioSource.HOTWORD) {
                                    mic = true;
                                }
                            }
                        }
                        mMic = mic;
                        update();
                    }
                }, mHandler);
    }

    private void update() {
        final boolean cam = !mCamerasInUse.isEmpty();
        if (!cam && !mMic) {
            if (mView != null) {
                mWm.removeView(mView);
                mView = null;
            }
            return;
        }
        if (mView == null) {
            final float d = mContext.getResources().getDisplayMetrics().density;
            final int sb = mContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.status_bar_height);
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    (int) (22 * d), sb,
                    WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.setTitle("KiroPrivacyDots");
            lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
            mView = new DotsView(mContext);
            mWm.addView(mView, lp);
        }
        mView.set(cam, mMic);
    }

    private static final class DotsView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float mR;
        private boolean mCam;
        private boolean mMic;

        DotsView(Context c) {
            super(c);
            mR = 3.2f * c.getResources().getDisplayMetrics().density;
        }

        void set(boolean cam, boolean mic) {
            mCam = cam;
            mMic = mic;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // in the top right corner, above the end of the status bar icons
            float x = getWidth() - mR * 1.8f;
            final float y = mR * 1.6f;
            if (mCam) {
                mPaint.setColor(GREEN);
                canvas.drawCircle(x, y, mR, mPaint);
                x -= mR * 2.8f;
            }
            if (mMic) {
                mPaint.setColor(ORANGE);
                canvas.drawCircle(x, y, mR, mPaint);
            }
        }
    }
}
