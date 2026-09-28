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

package com.android.systemui.qs;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * KiroOS control centre: Wi-Fi and Bluetooth as two wide tiles above the brightness bar, with
 * the network / device in use under the name. Tapping the tile switches it, the arrow opens the
 * KiroOS list of networks / devices (the tile's detail view), a long press its settings.
 */
public class KiroConnectivityRow extends LinearLayout {

    static final String WIFI = "wifi";
    static final String BT = "bt";

    private final WideTile mWifi;
    private final WideTile mBt;

    public KiroConnectivityRow(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        final int pad = getResources().getDimensionPixelSize(R.dimen.kiroos_qs_side_padding);
        setPadding(pad, 0, pad, getResources().getDimensionPixelSize(R.dimen.kiroos_qs_row_gap));
        setClipChildren(false);
        setClipToPadding(false);
        mWifi = add(context, false);
        mBt = add(context, true);
    }

    private WideTile add(Context context, boolean end) {
        final WideTile t = (WideTile) LayoutInflater.from(context).inflate(
                R.layout.kiroos_qs_wide_tile, this, false);
        final LayoutParams lp = new LayoutParams(0,
                getResources().getDimensionPixelSize(R.dimen.kiroos_qs_wide_height), 1f);
        if (end) {
            lp.setMarginStart(getResources().getDimensionPixelSize(R.dimen.kiroos_qs_row_gap));
        }
        addView(t, lp);
        return t;
    }

    static boolean accepts(String spec) {
        return WIFI.equals(spec) || BT.equals(spec);
    }

    WideTile tileFor(String spec) {
        return WIFI.equals(spec) ? mWifi : BT.equals(spec) ? mBt : null;
    }

    /** Binds a wide tile to the tile record (Wi-Fi or Bluetooth). */
    void bind(final QSPanel.TileRecord r, final QSPanel panel) {
        final WideTile t = tileFor(r.tile.getTileSpec());
        if (t == null) {
            return;
        }
        t.mRecord = r;
        t.setVisibility(VISIBLE);
        t.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                panel.onTileClick(r.tile);
            }
        });
        t.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                r.tile.longClick();
                return true;
            }
        });
        t.mChevron.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                r.tile.secondaryClick();
            }
        });
    }

    void unbindAll() {
        mWifi.mRecord = null;
        mBt.mRecord = null;
    }

    void update(QSPanel.TileRecord r, QSTile.State state) {
        final WideTile t = tileFor(r.tile.getTileSpec());
        if (t != null && t.mRecord == r) {
            t.setState(state);
        }
    }

    boolean owns(QSPanel.TileRecord r) {
        return r != null && (mWifi.mRecord == r || mBt.mRecord == r);
    }

    /** One wide tile: round icon, name, network / device, and an arrow to the list. */
    public static class WideTile extends LinearLayout {
        private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.1f, 1f);

        QSPanel.TileRecord mRecord;
        private ImageView mIcon;
        private TextView mTitle;
        private TextView mSubtitle;
        ImageView mChevron;
        private final GradientDrawable mShape = new GradientDrawable();
        private final GradientDrawable mIconShape = new GradientDrawable();
        private final int mOnColor;
        private final int mOffColor;
        private final int mOnText;
        private final int mOffText;
        private final int mOffSubtext;
        private final int mOffIcon;
        private final int mOffIconBg;
        private int mColor;
        private boolean mActive;
        private boolean mHasState;
        private ValueAnimator mColorAnim;

        public WideTile(Context context, android.util.AttributeSet attrs) {
            super(context, attrs);
            mOnColor = context.getColor(R.color.qs_tile_bg_active);
            mOffColor = context.getColor(R.color.qs_tile_bg_inactive);
            mOnText = context.getColor(R.color.qs_tile_tint_active);
            mOffText = context.getColor(R.color.qs_text);
            mOffSubtext = context.getColor(R.color.qs_subhead);
            mOffIcon = context.getColor(R.color.qs_tile_tint_inactive);
            mOffIconBg = context.getColor(R.color.kiroos_qs_wide_icon_bg);
            mColor = mOffColor;
            mShape.setCornerRadius(getResources().getDimension(R.dimen.kiroos_qs_wide_radius));
            mShape.setColor(mColor);
            mIconShape.setShape(GradientDrawable.OVAL);
            setBackground(new RippleDrawable(ColorStateList.valueOf(
                    context.getColor(R.color.qs_tile_bg_pressed)), mShape, null));
        }

        @Override
        protected void onFinishInflate() {
            super.onFinishInflate();
            mIcon = (ImageView) findViewById(R.id.kiroos_wide_icon);
            mTitle = (TextView) findViewById(R.id.kiroos_wide_title);
            mSubtitle = (TextView) findViewById(R.id.kiroos_wide_subtitle);
            mChevron = (ImageView) findViewById(R.id.kiroos_wide_chevron);
            mIcon.setBackground(mIconShape);
            applyColors(false);
        }

        void setState(QSTile.State state) {
            final boolean active = state instanceof QSTile.BooleanState
                    && ((QSTile.BooleanState) state).value;
            final Drawable d = state.icon != null ? state.icon.getInvisibleDrawable(mContext) : null;
            mIcon.setImageDrawable(d);
            final String name = getResources().getString(
                    WIFI.equals(mRecord.tile.getTileSpec()) ? R.string.quick_settings_wifi_label
                            : R.string.quick_settings_bluetooth_label);
            mTitle.setText(name);
            // the network / device in use; "On" / "Off" otherwise
            CharSequence sub = state.label;
            if (TextUtils.isEmpty(sub) || TextUtils.equals(sub, name)) {
                sub = getResources().getString(active ? R.string.switch_bar_on
                        : R.string.switch_bar_off);
            }
            mSubtitle.setText(sub);
            setContentDescription(state.contentDescription);
            final boolean changed = mActive != active;
            mActive = active;
            applyColors(changed && mHasState && isShown());
            if (changed && mHasState && isShown() && active) {
                // switching on: the icon pops in
                mIcon.setScaleX(0.8f);
                mIcon.setScaleY(0.8f);
                mIcon.animate().scaleX(1f).scaleY(1f).setDuration(340)
                        .setInterpolator(new OvershootInterpolator(2.2f)).start();
            }
            mHasState = true;
        }

        private void applyColors(boolean animate) {
            final int target = mActive ? mOnColor : mOffColor;
            if (mColorAnim != null) {
                mColorAnim.cancel();
            }
            if (animate && mColor != target) {
                mColorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), mColor, target);
                mColorAnim.setDuration(240);
                mColorAnim.setInterpolator(EASE);
                mColorAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator a) {
                        mColor = (int) a.getAnimatedValue();
                        mShape.setColor(mColor);
                    }
                });
                mColorAnim.start();
            } else {
                mColor = target;
                mShape.setColor(mColor);
            }
            mTitle.setTextColor(mActive ? mOnText : mOffText);
            mSubtitle.setTextColor(mActive ? (mOnText & 0x00FFFFFF) | 0xC0000000 : mOffSubtext);
            mIconShape.setColor(mActive ? 0x33FFFFFF : mOffIconBg);
            mIcon.setColorFilter(mActive ? mOnText : mOffIcon, PorterDuff.Mode.SRC_IN);
            mChevron.setColorFilter(mActive ? mOnText : mOffSubtext, PorterDuff.Mode.SRC_IN);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            // a soft press: the tile dips while held
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    animate().scaleX(0.96f).scaleY(0.96f).setDuration(90)
                            .setInterpolator(EASE).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(new OvershootInterpolator(1.6f)).start();
                    break;
                default:
                    break;
            }
            return super.onTouchEvent(e);
        }
    }
}
