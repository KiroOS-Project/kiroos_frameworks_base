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

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;

/**
 * KiroOS Beta 4.1 volume slider: a tall pill whose fill rises from the bottom with the level and
 * carries the stream's icon at its foot. It follows the finger relatively (drag anywhere, no jump
 * to where it was touched), ticks the haptic once per step, and glides between levels when the
 * volume keys change it. A tap on the icon mutes / unmutes where the stream allows it.
 */
public class KiroVolumePill extends View {

    public interface Listener {
        /** the user moved it to a new level (fromTouch) or tapped the icon (toggleMute) */
        void onLevel(KiroVolumePill pill, int level);

        void onToggleMute(KiroVolumePill pill);

        /** a finger is on it (true) or left it (false) */
        void onTracking(KiroVolumePill pill, boolean tracking);
    }

    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.1f, 1f);

    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Path mClip = new Path();
    private final int mSlop;
    private Drawable mIcon;
    private int mIconOnFill;
    private int mIconOnTrack;
    private Listener mListener;

    private int mMin;
    private int mMax = 1;
    private int mLevel;
    /** what is drawn, 0..1, animated toward the level */
    private float mShown;
    private ValueAnimator mGlide;

    private boolean mTracking;
    private float mDownY;
    private float mDownShown;
    private boolean mMoved;
    private boolean mOnIcon;

    public KiroVolumePill(Context context) {
        super(context);
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setHapticFeedbackEnabled(true);
    }

    public void setColors(int track, int fill, int iconOnFill, int iconOnTrack) {
        mTrack.setColor(track);
        mFill.setColor(fill);
        mIconOnFill = iconOnFill;
        mIconOnTrack = iconOnTrack;
        invalidate();
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public void setIcon(Drawable icon) {
        if (icon == mIcon) return;
        mIcon = icon != null ? icon.mutate() : null;
        invalidate();
    }

    public boolean isTracking() {
        return mTracking;
    }

    /** the level from the system (volume keys, another app): glides there unless dragged */
    public void setRange(int min, int max, int level, boolean animate) {
        mMin = min;
        mMax = Math.max(min + 1, max);
        if (mTracking) return;
        mLevel = Math.max(mMin, Math.min(mMax, level));
        final float target = (mLevel - mMin) / (float) (mMax - mMin);
        if (mGlide != null) mGlide.cancel();
        if (!animate || !isAttachedToWindow()) {
            mShown = target;
            invalidate();
            return;
        }
        // from wherever the fill is now (a held key retargets it every step without restarting)
        mGlide = ValueAnimator.ofFloat(mShown, target);
        mGlide.setDuration(160);
        mGlide.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        mGlide.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                mShown = (float) a.getAnimatedValue();
                invalidate();
            }
        });
        mGlide.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        final float r = w / 2f;
        mRect.set(0, 0, w, h);
        mClip.reset();
        mClip.addRoundRect(mRect, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(mClip);
        canvas.drawRect(mRect, mTrack);
        // Beta 4.1: the fill follows the level all the way down (it kept a whole circle at the
        // foot before, which vanished at once on the last step: the "drop to 0")
        final float fillTop = h - h * Math.max(0f, Math.min(1f, mShown));
        if (fillTop < h) {
            canvas.drawRect(0, fillTop, w, h, mFill);
        }
        canvas.restore();
        if (mIcon != null) {
            final int s = Math.round(w * 0.46f);
            final int left = Math.round((w - s) / 2f);
            final int top = Math.round(h - w / 2f - s / 2f);
            mIcon.setBounds(left, top, left + s, top + s);
            // white once the fill reaches the icon's middle
            mIcon.setTint(fillTop <= top + s / 2f ? mIconOnFill : mIconOnTrack);
            mIcon.draw(canvas);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mGlide != null) mGlide.cancel();
                mTracking = true;
                mMoved = false;
                mDownY = e.getY();
                mDownShown = mShown;
                mOnIcon = e.getY() > getHeight() - getWidth();
                getParent().requestDisallowInterceptTouchEvent(true);
                animate().scaleX(1.06f).scaleY(1.02f).setDuration(120).start();
                if (mListener != null) mListener.onTracking(this, true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                final float dy = e.getY() - mDownY;
                if (!mMoved && Math.abs(dy) < mSlop) return true;
                mMoved = true;
                // a full drag over the pill's height goes from empty to full
                mShown = Math.max(0f, Math.min(1f, mDownShown - dy / Math.max(1, getHeight())));
                final int level = mMin + Math.round(mShown * (mMax - mMin));
                if (level != mLevel) {
                    mLevel = level;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    if (mListener != null) mListener.onLevel(this, level);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mTracking = false;
                animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                if (!mMoved && e.getActionMasked() == MotionEvent.ACTION_UP && mOnIcon
                        && mListener != null) {
                    mListener.onToggleMute(this);
                }
                // settle on the step the finger left it at
                setRange(mMin, mMax, mLevel, true);
                if (mListener != null) mListener.onTracking(this, false);
                return true;
            }
        }
        return super.onTouchEvent(e);
    }
}
