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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.BluetoothController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * KiroOS control centre: the Bluetooth sheet. "My devices" are cards with the kind of device,
 * its state and one tap to connect (the cross disconnects); "Nearby devices" come from a short
 * scan while the sheet is open, one tap pairs them; with Bluetooth off, one clear screen and
 * a button to turn it on. Everything follows the Dark / Light theme.
 */
public class KiroBluetoothPanel extends LinearLayout {

    private static final long SCAN_MS = 12000;
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.1f, 1f);

    private final BluetoothController mController;
    private final Runnable mOpenSettings;
    private final Handler mHandler = new Handler();
    private final ArrayMap<CachedBluetoothDevice, Row> mRows = new ArrayMap<>();

    private final LinearLayout mOff;
    private final LinearLayout mLists;
    private final LinearLayout mMine;
    private final LinearLayout mNearby;
    private final TextView mMineEmpty;
    private final TextView mNearbyEmpty;
    private final ProgressBar mScanSpin;
    private final TextView mScanAgain;

    private final int mText;
    private final int mSubtext;
    private final int mAccent;
    private final int mAccentText;
    private final int mOnAccent;
    private final int mCard;
    private final int mCardActive;
    private final int mIconBg;
    private final int mPressed;

    private boolean mAttached;
    private boolean mScanning;
    private boolean mScannedThisTime;
    private Boolean mShowingOn;

    public KiroBluetoothPanel(Context context, BluetoothController controller,
            Runnable openSettings) {
        super(context);
        mController = controller;
        mOpenSettings = openSettings;
        mText = context.getColor(R.color.qs_text);
        mSubtext = context.getColor(R.color.qs_subhead);
        mAccent = context.getColor(R.color.qs_tile_bg_active);
        mAccentText = context.getColor(R.color.kiroos_qs_accent_text);
        mOnAccent = context.getColor(R.color.qs_tile_tint_active);
        mCard = context.getColor(R.color.kiroos_detail_row);
        mCardActive = context.getColor(R.color.kiroos_detail_row_active);
        mIconBg = context.getColor(R.color.kiroos_detail_icon_bg);
        mPressed = context.getColor(R.color.qs_tile_bg_pressed);

        setOrientation(VERTICAL);
        setPadding(dp(16), dp(4), dp(16), dp(12));

        // Bluetooth off: one picture, one sentence, one button
        mOff = new LinearLayout(context);
        mOff.setOrientation(VERTICAL);
        mOff.setGravity(Gravity.CENTER_HORIZONTAL);
        mOff.setPadding(dp(24), dp(36), dp(24), dp(24));
        final ImageView big = new ImageView(context);
        final GradientDrawable bigBg = new GradientDrawable();
        bigBg.setShape(GradientDrawable.OVAL);
        bigBg.setColor(mIconBg);
        big.setBackground(bigBg);
        big.setImageResource(R.drawable.kiroos_bt_generic);
        big.setColorFilter(mSubtext);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        big.setPadding(dp(24), dp(24), dp(24), dp(24));
        mOff.addView(big, new LayoutParams(dp(92), dp(92)));
        final TextView offTitle = text(context, R.string.kiroos_bt_off_title, 20, mText, true);
        offTitle.setGravity(Gravity.CENTER);
        final LayoutParams tlp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(18);
        mOff.addView(offTitle, tlp);
        final TextView offText = text(context, R.string.kiroos_bt_off_text, 14, mSubtext, false);
        offText.setGravity(Gravity.CENTER);
        final LayoutParams xlp = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        xlp.topMargin = dp(6);
        mOff.addView(offText, xlp);
        final TextView turnOn = text(context, R.string.kiroos_bt_turn_on, 15, mOnAccent, true);
        turnOn.setGravity(Gravity.CENTER);
        turnOn.setPadding(dp(28), 0, dp(28), 0);
        turnOn.setBackground(pill(mAccent));
        turnOn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mController.setBluetoothEnabled(true);
                v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).withEndAction(
                        new Runnable() {
                            @Override
                            public void run() {
                                v.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
                            }
                        }).start();
            }
        });
        final LayoutParams blp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(46));
        blp.topMargin = dp(22);
        mOff.addView(turnOn, blp);
        addView(mOff, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Bluetooth on: my devices, then the nearby ones
        mLists = new LinearLayout(context);
        mLists.setOrientation(VERTICAL);
        mLists.addView(section(context, R.string.kiroos_bt_my_devices));
        mMine = new LinearLayout(context);
        mMine.setOrientation(VERTICAL);
        mLists.addView(mMine);
        mMineEmpty = text(context, R.string.kiroos_bt_no_paired, 14, mSubtext, false);
        mMineEmpty.setPadding(dp(4), dp(2), dp(4), dp(8));
        mLists.addView(mMineEmpty);

        final LinearLayout nearbyHead = new LinearLayout(context);
        nearbyHead.setOrientation(HORIZONTAL);
        nearbyHead.setGravity(Gravity.CENTER_VERTICAL);
        final TextView nearbyTitle = section(context, R.string.kiroos_bt_nearby);
        nearbyHead.addView(nearbyTitle, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        mScanSpin = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
        mScanSpin.setIndeterminateTintList(ColorStateList.valueOf(mAccentText));
        final LayoutParams slp = new LayoutParams(dp(18), dp(18));
        slp.topMargin = dp(8);
        slp.setMarginEnd(dp(6));
        nearbyHead.addView(mScanSpin, slp);
        mScanAgain = text(context, R.string.kiroos_bt_search_again, 14, mAccentText, true);
        mScanAgain.setPadding(dp(10), dp(14), dp(4), dp(6));
        mScanAgain.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                setScanning(true);
                update();
            }
        });
        nearbyHead.addView(mScanAgain);
        mLists.addView(nearbyHead);
        mNearby = new LinearLayout(context);
        mNearby.setOrientation(VERTICAL);
        mLists.addView(mNearby);
        mNearbyEmpty = text(context, R.string.kiroos_bt_searching, 14, mSubtext, false);
        mNearbyEmpty.setPadding(dp(4), dp(2), dp(4), dp(8));
        mLists.addView(mNearbyEmpty);
        addView(mLists, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(Context context, int res, float sp, int color, boolean medium) {
        final TextView t = new TextView(context);
        t.setText(res);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (medium) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return t;
    }

    private TextView section(Context context, int res) {
        final TextView t = text(context, res, 14, mSubtext, true);
        t.setLetterSpacing(0.02f);
        t.setPadding(dp(4), dp(14), dp(4), dp(8));
        return t;
    }

    private GradientDrawable pill(int color) {
        final GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(23));
        d.setColor(color);
        return d;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttached = true;
        mScannedThisTime = false;
        mShowingOn = null;
        update();
        // the sheet opens: its cards come in one after the other
        final View[] order = collectShown();
        for (int i = 0; i < order.length; i++) {
            final View v = order[i];
            v.setAlpha(0f);
            v.setTranslationY(dp(14));
            v.animate().alpha(1f).translationY(0f).setStartDelay(60 + i * 32L)
                    .setDuration(300).setInterpolator(EASE).start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mAttached = false;
        setScanning(false);
        super.onDetachedFromWindow();
    }

    private View[] collectShown() {
        final ArrayList<View> out = new ArrayList<>();
        if (mOff.getVisibility() == VISIBLE) {
            for (int i = 0; i < mOff.getChildCount(); i++) {
                out.add(mOff.getChildAt(i));
            }
        } else {
            for (int i = 0; i < mLists.getChildCount(); i++) {
                final View v = mLists.getChildAt(i);
                if (v == mMine || v == mNearby) {
                    for (int j = 0; j < ((ViewGroup) v).getChildCount(); j++) {
                        out.add(((ViewGroup) v).getChildAt(j));
                    }
                } else if (v.getVisibility() == VISIBLE) {
                    out.add(v);
                }
            }
        }
        return out.toArray(new View[out.size()]);
    }

    private final Runnable mStopScan = new Runnable() {
        @Override
        public void run() {
            setScanning(false);
            update();
        }
    };

    private void setScanning(boolean scanning) {
        mHandler.removeCallbacks(mStopScan);
        if (scanning) {
            mHandler.postDelayed(mStopScan, SCAN_MS);
        }
        if (mScanning != scanning) {
            mScanning = scanning;
            mController.setScanning(scanning);
        }
    }

    /** Called whenever Bluetooth or its devices change. */
    public void update() {
        final boolean on = mController.isBluetoothEnabled();
        final boolean ready = mController.getBluetoothState() == BluetoothAdapter.STATE_ON;
        if (mShowingOn == null || mShowingOn != on) {
            final boolean animate = mShowingOn != null && isShown();
            mShowingOn = on;
            swap(on ? mLists : mOff, on ? mOff : mLists, animate);
        }
        if (!ready) {
            setScanning(false);
            if (!on) {
                mScannedThisTime = false;
            }
        } else if (mAttached && !mScannedThisTime) {
            mScannedThisTime = true;
            setScanning(true);
        }

        final ArrayList<CachedBluetoothDevice> mine = new ArrayList<>();
        final ArrayList<CachedBluetoothDevice> nearby = new ArrayList<>();
        final Collection<CachedBluetoothDevice> all = on ? mController.getDevices() : null;
        if (all != null) {
            for (CachedBluetoothDevice d : all) {
                if (d.getBondState() != BluetoothDevice.BOND_NONE) {
                    mine.add(d);
                } else if (d.isVisible() && hasName(d)) {
                    nearby.add(d);
                }
            }
        }
        Collections.sort(mine, MINE_ORDER);
        Collections.sort(nearby, NAME_ORDER);
        sync(mMine, mine);
        sync(mNearby, nearby);
        for (int i = mRows.size() - 1; i >= 0; i--) {
            if (mRows.valueAt(i).getParent() == null) {
                mRows.removeAt(i);
            }
        }
        mMineEmpty.setVisibility(mine.isEmpty() ? VISIBLE : GONE);
        mNearbyEmpty.setText(mScanning ? R.string.kiroos_bt_searching
                : R.string.kiroos_bt_none_nearby);
        mNearbyEmpty.setVisibility(nearby.isEmpty() ? VISIBLE : GONE);
        mScanSpin.setVisibility(mScanning ? VISIBLE : GONE);
        mScanAgain.setVisibility(!mScanning && ready ? VISIBLE : GONE);
    }

    private void swap(final View in, final View out, boolean animate) {
        if (!animate) {
            in.setVisibility(VISIBLE);
            in.setAlpha(1f);
            out.setVisibility(GONE);
            return;
        }
        out.animate().cancel();
        out.setVisibility(GONE);
        in.setVisibility(VISIBLE);
        in.setAlpha(0f);
        in.setScaleX(0.97f);
        in.setScaleY(0.97f);
        in.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(280)
                .setInterpolator(EASE).start();
    }

    private static boolean hasName(CachedBluetoothDevice d) {
        final String name = d.getName();
        return !TextUtils.isEmpty(name) && !name.equals(d.getDevice().getAddress());
    }

    private static int rank(CachedBluetoothDevice d) {
        final int s = d.getMaxConnectionState();
        return s == BluetoothProfile.STATE_CONNECTED ? 0
                : s == BluetoothProfile.STATE_CONNECTING ? 1 : 2;
    }

    private static final Comparator<CachedBluetoothDevice> NAME_ORDER =
            new Comparator<CachedBluetoothDevice>() {
                @Override
                public int compare(CachedBluetoothDevice a, CachedBluetoothDevice b) {
                    return String.valueOf(a.getName()).compareToIgnoreCase(
                            String.valueOf(b.getName()));
                }
            };

    private static final Comparator<CachedBluetoothDevice> MINE_ORDER =
            new Comparator<CachedBluetoothDevice>() {
                @Override
                public int compare(CachedBluetoothDevice a, CachedBluetoothDevice b) {
                    final int r = rank(a) - rank(b);
                    return r != 0 ? r : NAME_ORDER.compare(a, b);
                }
            };

    private void sync(LinearLayout list, List<CachedBluetoothDevice> devices) {
        for (int i = 0; i < devices.size(); i++) {
            final CachedBluetoothDevice d = devices.get(i);
            Row r = mRows.get(d);
            final boolean fresh = r == null;
            if (fresh) {
                r = new Row(getContext());
                mRows.put(d, r);
            }
            if (r.getParent() != list || list.indexOfChild(r) != i) {
                if (r.getParent() != null) {
                    ((ViewGroup) r.getParent()).removeView(r);
                }
                final LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(8);
                list.addView(r, Math.min(i, list.getChildCount()), lp);
            }
            r.bind(d, !fresh && isShown());
            if (fresh && isShown()) {
                r.setAlpha(0f);
                r.setTranslationY(dp(12));
                r.animate().alpha(1f).translationY(0f).setDuration(280)
                        .setInterpolator(EASE).start();
            }
        }
        for (int i = list.getChildCount() - 1; i >= devices.size(); i--) {
            list.removeViewAt(i);
        }
    }

    private void onRowClick(Row r) {
        final CachedBluetoothDevice d = r.mDevice;
        if (d == null || d.isBusy()) {
            return;
        }
        if (d.getBondState() == BluetoothDevice.BOND_NONE) {
            // pairing is unreliable while scanning
            setScanning(false);
            d.startPairing();
        } else if (d.getMaxConnectionState() == BluetoothProfile.STATE_DISCONNECTED) {
            mController.connect(d);
        } else {
            return;
        }
        r.press();
        update();
    }

    private static int iconFor(CachedBluetoothDevice d) {
        final BluetoothClass c = d.getBtClass();
        if (c == null) {
            return R.drawable.kiroos_bt_generic;
        }
        final int dc = c.getDeviceClass();
        switch (c.getMajorDeviceClass()) {
            case BluetoothClass.Device.Major.COMPUTER:
                return R.drawable.kiroos_bt_laptop;
            case BluetoothClass.Device.Major.PHONE:
                return R.drawable.kiroos_bt_phone;
            case BluetoothClass.Device.Major.WEARABLE:
                return R.drawable.kiroos_bt_watch;
            case BluetoothClass.Device.Major.PERIPHERAL:
                // bit 6: keyboard, bit 7: pointing device, else a game controller
                return (dc & 0x40) != 0 ? R.drawable.kiroos_bt_keyboard
                        : (dc & 0x80) != 0 ? R.drawable.kiroos_bt_mouse
                        : R.drawable.kiroos_bt_gamepad;
            case BluetoothClass.Device.Major.AUDIO_VIDEO:
                switch (dc) {
                    case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                    case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                        return R.drawable.kiroos_bt_car;
                    case BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER:
                    case BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO:
                    case BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO:
                        return R.drawable.kiroos_bt_speaker;
                    default:
                        return R.drawable.kiroos_bt_headphones;
                }
            default:
                return R.drawable.kiroos_bt_generic;
        }
    }

    /** One device: its kind in a circle, name, state, and a spinner or a cross on the right. */
    private final class Row extends LinearLayout {
        CachedBluetoothDevice mDevice;
        private final ImageView mIcon;
        private final TextView mName;
        private final TextView mStatus;
        private final ProgressBar mSpin;
        private final ImageView mDisconnect;
        private final GradientDrawable mBg = new GradientDrawable();
        private final GradientDrawable mIconShape = new GradientDrawable();
        private int mBgColor;
        private int mIconColor;
        private ValueAnimator mAnim;

        Row(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(68));
            setPadding(dp(12), dp(10), dp(8), dp(10));
            mBgColor = mCard;
            mBg.setCornerRadius(dp(20));
            mBg.setColor(mBgColor);
            setBackground(new RippleDrawable(ColorStateList.valueOf(mPressed), mBg, null));

            mIcon = new ImageView(context);
            mIconShape.setShape(GradientDrawable.OVAL);
            mIconColor = mIconBg;
            mIconShape.setColor(mIconColor);
            mIcon.setBackground(mIconShape);
            mIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mIcon.setPadding(dp(10), dp(10), dp(10), dp(10));
            addView(mIcon, new LayoutParams(dp(44), dp(44)));

            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            mName = new TextView(context);
            mName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            mName.setTextColor(mText);
            mName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mName.setSingleLine(true);
            mName.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(mName);
            mStatus = new TextView(context);
            mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            mStatus.setSingleLine(true);
            mStatus.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(mStatus);
            final LayoutParams tl = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            tl.setMarginStart(dp(14));
            tl.setMarginEnd(dp(8));
            addView(texts, tl);

            mSpin = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
            mSpin.setIndeterminateTintList(ColorStateList.valueOf(mAccentText));
            final LayoutParams pl = new LayoutParams(dp(22), dp(22));
            pl.setMarginEnd(dp(10));
            addView(mSpin, pl);

            mDisconnect = new ImageView(context);
            mDisconnect.setImageResource(R.drawable.kiroos_bt_close);
            mDisconnect.setColorFilter(mSubtext);
            mDisconnect.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mDisconnect.setPadding(dp(10), dp(10), dp(10), dp(10));
            mDisconnect.setBackground(new RippleDrawable(ColorStateList.valueOf(mPressed),
                    null, new GradientDrawable() {{ setShape(OVAL); setColor(0xFFFFFFFF); }}));
            mDisconnect.setContentDescription(getResources().getString(
                    R.string.kiroos_bt_disconnect));
            mDisconnect.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mDevice != null) {
                        mController.disconnect(mDevice);
                        update();
                    }
                }
            });
            addView(mDisconnect, new LayoutParams(dp(40), dp(40)));

            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    onRowClick(Row.this);
                }
            });
            setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (mOpenSettings != null) {
                        mOpenSettings.run();
                    }
                    return true;
                }
            });
        }

        void bind(CachedBluetoothDevice d, boolean animate) {
            mDevice = d;
            final int bond = d.getBondState();
            final int conn = d.getMaxConnectionState();
            boolean active = false;
            boolean busy = false;
            int status;
            if (bond == BluetoothDevice.BOND_BONDING) {
                status = R.string.kiroos_bt_pairing;
                busy = true;
            } else if (bond == BluetoothDevice.BOND_NONE) {
                status = R.string.kiroos_bt_tap_to_pair;
            } else if (conn == BluetoothProfile.STATE_CONNECTED) {
                status = R.string.quick_settings_connected;
                active = true;
            } else if (conn == BluetoothProfile.STATE_CONNECTING) {
                status = R.string.quick_settings_connecting;
                busy = true;
            } else if (conn == BluetoothProfile.STATE_DISCONNECTING) {
                status = R.string.kiroos_bt_disconnecting;
                busy = true;
            } else {
                status = R.string.kiroos_bt_not_connected;
            }
            mName.setText(d.getName());
            mStatus.setText(status);
            mStatus.setTextColor(active || busy ? mAccentText : mSubtext);
            mIcon.setImageResource(iconFor(d));
            mIcon.setColorFilter(active ? mOnAccent : mText);
            mSpin.setVisibility(busy ? VISIBLE : GONE);
            mDisconnect.setVisibility(active ? VISIBLE : GONE);
            setContentDescription(d.getName() + ", " + getResources().getString(status));
            colors(active ? mCardActive : mCard, active ? mAccent : mIconBg, animate);
        }

        private void colors(final int bg, final int icon, boolean animate) {
            if (mAnim != null) {
                mAnim.cancel();
            }
            if (!animate || (bg == mBgColor && icon == mIconColor)) {
                mBgColor = bg;
                mIconColor = icon;
                mBg.setColor(bg);
                mIconShape.setColor(icon);
                return;
            }
            final int bg0 = mBgColor;
            final int icon0 = mIconColor;
            final ArgbEvaluator argb = new ArgbEvaluator();
            mAnim = ValueAnimator.ofFloat(0f, 1f);
            mAnim.setDuration(260);
            mAnim.setInterpolator(EASE);
            mAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    final float f = (float) a.getAnimatedValue();
                    mBgColor = (int) argb.evaluate(f, bg0, bg);
                    mIconColor = (int) argb.evaluate(f, icon0, icon);
                    mBg.setColor(mBgColor);
                    mIconShape.setColor(mIconColor);
                }
            });
            mAnim.start();
            if (icon == mAccent) {
                // connected: the icon pops
                mIcon.setScaleX(0.8f);
                mIcon.setScaleY(0.8f);
                mIcon.animate().scaleX(1f).scaleY(1f).setDuration(340)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                        .start();
            }
        }

        void press() {
            animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).withEndAction(new Runnable() {
                @Override
                public void run() {
                    animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(EASE)
                            .start();
                }
            }).start();
        }
    }
}
