/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.settings;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;

public class ToggleSlider extends RelativeLayout {
    public interface Listener {
        public void onInit(ToggleSlider v);
        public void onChanged(ToggleSlider v, boolean tracking, boolean checked, int value,
                boolean stopTracking);
    }

    private Listener mListener;
    private boolean mTracking;

    private CompoundButton mToggle;
    private ToggleSeekBar mSlider;
    private TextView mLabel;
    /** KiroOS: the sun inside the pill turns and grows a little with the brightness */
    private View mSun;

    private ToggleSlider mMirror;
    private BrightnessMirrorController mMirrorController;

    public ToggleSlider(Context context) {
        this(context, null);
    }

    public ToggleSlider(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ToggleSlider(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        View.inflate(context, R.layout.status_bar_toggle_slider, this);

        final Resources res = context.getResources();
        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.ToggleSlider, defStyle, 0);

        mToggle = (CompoundButton) findViewById(R.id.toggle);
        mToggle.setOnCheckedChangeListener(mCheckListener);

        mSlider = (ToggleSeekBar) findViewById(R.id.slider);
        mSlider.setOnSeekBarChangeListener(mSeekListener);
        // KiroOS: a fill with round ends that starts as a circle around the sun
        mSlider.setProgressDrawable(
                com.android.systemui.kiroos.KiroBrightnessDrawable.create(context));
        mSun = findViewById(R.id.kiroos_brightness_sun);

        mLabel = (TextView) findViewById(R.id.label);
        mLabel.setText(a.getString(R.styleable.ToggleSlider_text));

        mSlider.setAccessibilityLabel(getContentDescription().toString());

        a.recycle();
    }

    public void setMirror(ToggleSlider toggleSlider) {
        mMirror = toggleSlider;
        if (mMirror != null) {
            mMirror.setChecked(mToggle.isChecked());
            mMirror.setMax(mSlider.getMax());
            mMirror.setValue(mSlider.getProgress());
        }
    }

    public void setMirrorController(BrightnessMirrorController c) {
        mMirrorController = c;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mListener != null) {
            mListener.onInit(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        kiroStopTracking();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            kiroStopTracking();
        }
    }

    /**
     * KiroOS: the slider went away (panel closed or hidden) while a finger held it, so it never
     * got the release: end the drag here, or the brightness mirror stays on the screen.
     */
    private void kiroStopTracking() {
        if (!mTracking) {
            return;
        }
        mTracking = false;
        if (mListener != null) {
            mListener.onChanged(this, false, mToggle.isChecked(), mSlider.getProgress(), true);
        }
        if (mMirrorController != null) {
            mMirrorController.hideMirror();
        }
    }

    public void setOnChangedListener(Listener l) {
        mListener = l;
    }

    public void setChecked(boolean checked) {
        mToggle.setChecked(checked);
    }

    public boolean isChecked() {
        return mToggle.isChecked();
    }

    public void setMax(int max) {
        mSlider.setMax(max);
        if (mMirror != null) {
            mMirror.setMax(max);
        }
    }

    public void setValue(int value) {
        mSlider.setProgress(value);
        updateSun(value);
        if (mMirror != null) {
            mMirror.setValue(value);
        }
    }

    private void updateSun(int value) {
        if (mSun == null) {
            return;
        }
        final int max = mSlider.getMax();
        final float f = max > 0 ? Math.max(0f, Math.min(1f, value / (float) max)) : 0f;
        mSun.setRotation(f * 90f);
        mSun.setScaleX(0.9f + 0.2f * f);
        mSun.setScaleY(0.9f + 0.2f * f);
    }

    // --- KiroOS Beta 4.1: hold the slider without moving it for the display sheet -----------

    private Runnable mOnLongPress;
    private float mDownX, mDownY;
    private int mDownValue;
    private boolean mLongPressed;
    private final Runnable mLongPressCheck = new Runnable() {
        @Override
        public void run() {
            if (mOnLongPress == null || !isAttachedToWindow()) {
                return;
            }
            mLongPressed = true;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            // the touch was not a change of brightness: put it back where it was, end the drag
            final MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            ToggleSlider.super.dispatchTouchEvent(cancel);
            cancel.recycle();
            setValue(mDownValue);
            if (mListener != null) {
                mListener.onChanged(ToggleSlider.this, false, mToggle.isChecked(), mDownValue, true);
            }
            if (mMirrorController != null) {
                mMirrorController.hideMirror();
            }
            mOnLongPress.run();
        }
    };

    /** what holding the slider opens (the control centre's slider only) */
    public void setOnKiroLongPress(Runnable r) {
        mOnLongPress = r;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mOnLongPress != null) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = ev.getX();
                    mDownY = ev.getY();
                    mDownValue = mSlider.getProgress();
                    mLongPressed = false;
                    removeCallbacks(mLongPressCheck);
                    postDelayed(mLongPressCheck,
                            android.view.ViewConfiguration.getLongPressTimeout());
                    break;
                case MotionEvent.ACTION_MOVE:
                    final int slop = android.view.ViewConfiguration.get(getContext())
                            .getScaledTouchSlop();
                    if (Math.abs(ev.getX() - mDownX) > slop || Math.abs(ev.getY() - mDownY) > slop) {
                        removeCallbacks(mLongPressCheck);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(mLongPressCheck);
                    break;
            }
            if (mLongPressed) {
                // the rest of this gesture belongs to the sheet, not to the slider
                if (ev.getActionMasked() == MotionEvent.ACTION_UP
                        || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    mLongPressed = false;
                }
                return true;
            }
        }
        if (mMirror != null) {
            MotionEvent copy = ev.copy();
            mMirror.dispatchTouchEvent(copy);
            copy.recycle();
        }
        return super.dispatchTouchEvent(ev);
    }

    private final OnCheckedChangeListener mCheckListener = new OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton toggle, boolean checked) {
            mSlider.setEnabled(!checked);

            if (mListener != null) {
                mListener.onChanged(
                        ToggleSlider.this, mTracking, checked, mSlider.getProgress(), false);
            }

            if (mMirror != null) {
                mMirror.mToggle.setChecked(checked);
            }
        }
    };

    private final OnSeekBarChangeListener mSeekListener = new OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            updateSun(progress);
            if (mListener != null) {
                mListener.onChanged(
                        ToggleSlider.this, mTracking, mToggle.isChecked(), progress, false);
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            mTracking = true;

            if (mListener != null) {
                mListener.onChanged(ToggleSlider.this, mTracking, mToggle.isChecked(),
                        mSlider.getProgress(), false);
            }

            mToggle.setChecked(false);

            if (mMirrorController != null) {
                mMirrorController.showMirror();
                mMirrorController.setLocation((View) getParent());
            }
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            mTracking = false;

            if (mListener != null) {
                mListener.onChanged(ToggleSlider.this, mTracking, mToggle.isChecked(),
                        mSlider.getProgress(), true);
            }

            if (mMirrorController != null) {
                mMirrorController.hideMirror();
            }
        }
    };
}

