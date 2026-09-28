/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.qs;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import com.android.systemui.R;

import java.util.Objects;

public class QSIconView extends ViewGroup {

    protected final View mIcon;
    protected final int mIconSizePx;
    protected final int mTilePaddingBelowIconPx;
    private boolean mAnimationEnabled = true;

    // KiroOS: round tile background behind the icon: KiroOS blue when on, a dark disc when off
    // (white in the light theme); the colour fades between the two and the icon pops when a
    // tile turns on
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.1f, 1f);
    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mBgRadiusPx;
    private final int mBgColorActive;
    private final int mBgColorInactive;
    private final int mBgColorPressed;
    private int mBgColor;
    private ValueAnimator mBgAnim;
    private boolean mActive;
    private boolean mBgPressed;
    private boolean mHasState;
    protected ColorFilter mIconFilter;
    private final ColorFilter mActiveFilter;
    private final ColorFilter mInactiveFilter;
    /** tiles without an on/off state (battery...) draw white icons: inverted in the light theme */
    private final ColorFilter mNeutralFilter;

    public QSIconView(Context context) {
        super(context);

        final Resources res = context.getResources();
        mIconSizePx = res.getDimensionPixelSize(R.dimen.qs_tile_icon_size);
        mTilePaddingBelowIconPx =  res.getDimensionPixelSize(R.dimen.qs_tile_padding_below_icon);
        mBgRadiusPx = mIconSizePx / 2
                + res.getDimensionPixelSize(R.dimen.qs_tile_icon_bg_inset);
        mBgColorActive = context.getColor(R.color.qs_tile_bg_active);
        mBgColorInactive = context.getColor(R.color.qs_tile_bg_inactive);
        mBgColorPressed = context.getColor(R.color.qs_tile_bg_pressed);
        mBgColor = mBgColorInactive;
        mActiveFilter = new PorterDuffColorFilter(context.getColor(R.color.qs_tile_tint_active),
                PorterDuff.Mode.SRC_IN);
        // the "off" tint, with the dimmed "off" icons brought back to a readable alpha
        final int off = context.getColor(R.color.qs_tile_tint_inactive);
        mInactiveFilter = new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                0, 0, 0, 0, Color.red(off),
                0, 0, 0, 0, Color.green(off),
                0, 0, 0, 0, Color.blue(off),
                0, 0, 0, 2.5f, 0 }));
        final boolean light = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_NO;
        mNeutralFilter = light ? new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0 })) : null;
        setWillNotDraw(false);

        mIcon = createIcon();
        addView(mIcon);
    }

    public int getBackgroundRadius() {
        return mBgRadiusPx;
    }

    public void setBackgroundPressed(boolean pressed) {
        if (mBgPressed != pressed) {
            mBgPressed = pressed;
            invalidate();
            // a soft press: the disc dips while held and springs back
            animate().cancel();
            if (pressed) {
                animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).setInterpolator(EASE).start();
            } else {
                animate().scaleX(1f).scaleY(1f).setDuration(280)
                        .setInterpolator(new OvershootInterpolator(1.8f)).start();
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float cx = mIcon.getLeft() + mIcon.getWidth() / 2f;
        final float cy = mIcon.getTop() + mIconSizePx / 2f;
        mBgPaint.setColor(mBgColor);
        canvas.drawCircle(cx, cy, mBgRadiusPx, mBgPaint);
        if (mBgPressed) {
            mBgPaint.setColor(mBgColorPressed);
            canvas.drawCircle(cx, cy, mBgRadiusPx, mBgPaint);
        }
    }

    /** Applies the on/off icon tint to an extra icon of this tile (signal overlays). */
    protected void applyIconFilter(ImageView iv) {
        if (mIconFilter != null) {
            iv.setColorFilter(mIconFilter);
        } else {
            iv.clearColorFilter();
        }
    }

    public void disableAnimation() {
        mAnimationEnabled = false;
    }

    public View getIconView() {
        return mIcon;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int w = MeasureSpec.getSize(widthMeasureSpec);
        final int iconSpec = exactly(mIconSizePx);
        mIcon.measure(MeasureSpec.makeMeasureSpec(w, getIconMeasureMode()), iconSpec);
        setMeasuredDimension(w, mIcon.getMeasuredHeight() + mTilePaddingBelowIconPx);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int w = getMeasuredWidth();
        final int h = getMeasuredHeight();
        int top = 0;
        final int iconLeft = (w - mIcon.getMeasuredWidth()) / 2;
        layout(mIcon, iconLeft, top);
    }

    public void setIcon(QSTile.State state) {
        setIcon((ImageView) mIcon, state);
    }

    protected void setIcon(ImageView iv, QSTile.State state) {
        if (!Objects.equals(state.icon, iv.getTag(R.id.qs_icon_tag))) {
            Drawable d = state.icon != null
                    ? iv.isShown() && mAnimationEnabled ? state.icon.getDrawable(mContext)
                    : state.icon.getInvisibleDrawable(mContext) : null;
            int padding = state.icon != null ? state.icon.getPadding() : 0;
            if (d != null && state.autoMirrorDrawable) {
                d.setAutoMirrored(true);
            }
            iv.setImageDrawable(d);
            iv.setTag(R.id.qs_icon_tag, state.icon);
            iv.setPadding(0, padding, 0, padding);
            if (d instanceof Animatable && iv.isShown()) {
                Animatable a = (Animatable) d;
                a.start();
                if (!iv.isShown()) {
                    a.stop(); // skip directly to end state
                }
            }
        }
        final boolean active;
        if (state.disabledByPolicy) {
            active = false;
            mIconFilter = new PorterDuffColorFilter(
                    getContext().getColor(R.color.qs_tile_disabled_color), PorterDuff.Mode.SRC_ATOP);
        } else if (state instanceof QSTile.BooleanState) {
            active = ((QSTile.BooleanState) state).value;
            mIconFilter = active ? mActiveFilter : mInactiveFilter;
        } else {
            // Tiles without an on/off state (battery, profiles...) keep their own colours; their
            // white icons are inverted in the light theme so they stay readable
            active = false;
            mIconFilter = mNeutralFilter;
        }
        applyIconFilter(iv);
        if (mActive != active || !mHasState) {
            final boolean animate = mHasState && isShown();
            mActive = active;
            setBgColor(active ? mBgColorActive : mBgColorInactive, animate);
            if (animate && active) {
                // turning on: the icon pops in
                iv.setScaleX(0.78f);
                iv.setScaleY(0.78f);
                iv.animate().scaleX(1f).scaleY(1f).setDuration(340)
                        .setInterpolator(new OvershootInterpolator(2.4f)).start();
            }
        }
        mHasState = true;
    }

    private void setBgColor(int target, boolean animate) {
        if (mBgAnim != null) {
            mBgAnim.cancel();
            mBgAnim = null;
        }
        if (!animate || mBgColor == target) {
            mBgColor = target;
            invalidate();
            return;
        }
        mBgAnim = ValueAnimator.ofObject(new ArgbEvaluator(), mBgColor, target);
        mBgAnim.setDuration(240);
        mBgAnim.setInterpolator(EASE);
        mBgAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                mBgColor = (int) a.getAnimatedValue();
                invalidate();
            }
        });
        mBgAnim.start();
    }

    protected int getIconMeasureMode() {
        return MeasureSpec.EXACTLY;
    }

    protected View createIcon() {
        final ImageView icon = new ImageView(mContext);
        icon.setId(android.R.id.icon);
        icon.setScaleType(ScaleType.FIT_CENTER);
        return icon;
    }

    protected final int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    protected final void layout(View child, int left, int top) {
        child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
    }
}
