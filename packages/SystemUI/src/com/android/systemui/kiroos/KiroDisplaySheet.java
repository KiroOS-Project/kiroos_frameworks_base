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

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import cyanogenmod.hardware.LiveDisplayManager;

/**
 * KiroOS Beta 4.1: holding the brightness slider of the control centre opens this sheet: the
 * Light or Dark theme in one tap (every open app redraws, nothing restarts) and Eye protection
 * (LiveDisplay's warm night colours, on until turned off; the mode in use before comes back).
 */
public class KiroDisplaySheet {

    /** the LiveDisplay mode before Eye protection was turned on */
    private static final String PREV_MODE = "kiroos_eye_prev_mode";

    private final Context mContext;
    private final SystemUIDialog mDialog;
    private final UiModeManager mUi;
    private final LiveDisplayManager mLive;
    private TextView mLight;
    private TextView mDark;

    public KiroDisplaySheet(Context context) {
        mContext = context;
        mUi = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        LiveDisplayManager live = null;
        try {
            live = LiveDisplayManager.getInstance(context);
        } catch (RuntimeException e) {
            // no LiveDisplay on this build: no Eye protection row
        }
        mLive = live;
        mDialog = new SystemUIDialog(context);
        mDialog.setTitle(R.string.kiroos_display_sheet_title);
        mDialog.setView(build());
        mDialog.setPositiveButton(R.string.kiroos_display_sheet_settings,
                new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                mContext.startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            }
        });
        mDialog.setNegativeButton(R.string.quick_settings_done, null);
    }

    public void show() {
        mDialog.show();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                mContext.getResources().getDisplayMetrics()));
    }

    private View build() {
        final LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(8), dp(20), dp(4));

        // the theme: two cards, the one in use outlined
        final LinearLayout themes = new LinearLayout(mContext);
        themes.setOrientation(LinearLayout.HORIZONTAL);
        mLight = card(R.string.kiroos_display_sheet_light, "☀");
        mDark = card(R.string.kiroos_display_sheet_dark, "☾");
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(96), 1f);
        lp.setMarginEnd(dp(6));
        themes.addView(mLight, lp);
        final LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(96), 1f);
        rp.setMarginStart(dp(6));
        themes.addView(mDark, rp);
        mLight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTheme(false);
            }
        });
        mDark.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTheme(true);
            }
        });
        root.addView(themes);
        refreshTheme(isDark());

        // Eye protection, where LiveDisplay has a night mode
        if (mLive != null && mLive.getConfig() != null
                && mLive.getConfig().hasFeature(LiveDisplayManager.MODE_NIGHT)) {
            final LinearLayout row = new LinearLayout(mContext);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(18), 0, dp(6));
            final LinearLayout texts = new LinearLayout(mContext);
            texts.setOrientation(LinearLayout.VERTICAL);
            final TextView title = new TextView(mContext);
            title.setText(R.string.kiroos_display_sheet_eye);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTextColor(textColor(1f));
            final TextView sub = new TextView(mContext);
            sub.setText(R.string.kiroos_display_sheet_eye_summary);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            sub.setTextColor(textColor(0.62f));
            texts.addView(title);
            texts.addView(sub);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final Switch eye = new Switch(mContext);
            eye.setChecked(mLive.getMode() == LiveDisplayManager.MODE_NIGHT);
            eye.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    setEyeProtection(on);
                }
            });
            row.addView(eye);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    eye.toggle();
                }
            });
            root.addView(row);
        }
        return root;
    }

    private TextView card(int label, String symbol) {
        final TextView t = new TextView(mContext);
        t.setGravity(Gravity.CENTER);
        t.setText(symbol + "\n" + mContext.getString(label));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setLineSpacing(dp(4), 1f);
        t.setTextColor(textColor(1f));
        return t;
    }

    private int textColor(float alpha) {
        final int base = isDark() ? 0xFFFFFF : 0x1C1C1E;
        return (Math.round(alpha * 255) << 24) | base;
    }

    private boolean isDark() {
        return (mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void refreshTheme(boolean dark) {
        style(mLight, !dark);
        style(mDark, dark);
    }

    private void style(TextView card, boolean selected) {
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        final int accent = mContext.getColor(R.color.kiroos_bright_fill);
        bg.setColor(selected ? (accent & 0x00FFFFFF) | 0x33000000
                : (isDark() ? 0x14FFFFFF : 0x0F000000));
        bg.setStroke(dp(selected ? 2 : 1), selected ? accent
                : (isDark() ? 0x22FFFFFF : 0x1A000000));
        card.setBackground(bg);
        card.setSelected(selected);
    }

    /** every open app redraws in the new theme (KiroOS follows the night mode) */
    private void setTheme(boolean dark) {
        refreshTheme(dark);
        mUi.setNightMode(dark ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_NO);
    }

    private void setEyeProtection(boolean on) {
        if (mLive == null) {
            return;
        }
        if (on) {
            final int mode = mLive.getMode();
            if (mode != LiveDisplayManager.MODE_NIGHT) {
                Settings.System.putInt(mContext.getContentResolver(), PREV_MODE, mode);
            }
            mLive.setMode(LiveDisplayManager.MODE_NIGHT);
        } else {
            int prev = Settings.System.getInt(mContext.getContentResolver(), PREV_MODE,
                    LiveDisplayManager.MODE_OFF);
            if (prev == LiveDisplayManager.MODE_NIGHT) {
                prev = LiveDisplayManager.MODE_OFF;
            }
            mLive.setMode(prev);
        }
    }
}
