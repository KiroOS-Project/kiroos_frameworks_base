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

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.SystemUI;

import java.util.List;

/**
 * Kiro Thermal Guardian and Battery Guardian.
 *
 * Thermal Guardian, from the battery temperature (what the hand feels):
 *   42 C  stage 1: slightly lower CPU/GPU maximum clocks (hardly noticeable)
 *   47 C  stage 2: lower clocks, background apps closed, screen dimmed, a notification
 *   50 C  stage 3: a black cooling screen until the phone is back under 40 C
 * The clocks are applied by init/kiroos_perf.sh (sys.kiroos.guard.thermal); every stage has
 * some hysteresis so it does not flicker around a threshold.
 *
 * Battery Guardian (the kernel's batt_kiro_charge, the cable and USB stay connected):
 *   charge limit (Settings > Battery): charging stops at 80% and restarts at 77%
 *   bypass (Game Space): on the charger the phone runs from it, the battery is not charged
 */
public class KiroGuardian extends SystemUI {

    private static final String TAG = "KiroGuardian";
    private static final String PROP_THERMAL = "sys.kiroos.guard.thermal";
    private static final String PROP_CHARGE = "sys.kiroos.guard.charge";
    /** 1 screen on, 0 off: kiroos_perf.sh lifts the clock floors while the screen is off */
    private static final String PROP_SCREEN = "sys.kiroos.guard.screen";
    static final String KEY_CHARGE_LIMIT = "kiroos_charge_limit";
    static final String KEY_BYPASS = "kiroos_bypass_charging";
    private static final int NOTIFICATION_ID = 0x4b47; // "KG"

    private final Handler mHandler = new Handler();
    private int mStage;
    private int mTemp;          // tenths of a degree
    private int mLevel = -1;
    private int mChargeMode = -1;
    private int mSavedBrightness = -1;
    private int mSavedMode = -1;
    private View mCooling;
    private TextView mCoolingTemp;

    @Override
    public void start() {
        mStage = parse(SystemProperties.get(PROP_THERMAL, "0"));
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                mLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100
                        / Math.max(1, intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
                update();
            }
        }, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, mHandler);
        final IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                SystemProperties.set(PROP_SCREEN,
                        Intent.ACTION_SCREEN_ON.equals(intent.getAction()) ? "1" : "0");
            }
        }, screen, null, mHandler);
        final ContentObserver settings = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                update();
            }
        };
        final ContentResolver cr = mContext.getContentResolver();
        cr.registerContentObserver(Settings.System.getUriFor(KEY_CHARGE_LIMIT), false, settings,
                UserHandle.USER_ALL);
        cr.registerContentObserver(Settings.System.getUriFor(KEY_BYPASS), false, settings,
                UserHandle.USER_ALL);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean setting(String key) {
        return Settings.System.getIntForUser(mContext.getContentResolver(), key, 0,
                UserHandle.USER_CURRENT) != 0;
    }

    private void update() {
        if (mLevel < 0) {
            return;             // no battery broadcast yet
        }
        updateThermal();
        updateCharging();
    }

    // ------------------------------------------------------------------ thermal

    private int stageFor(int stage, int t) {
        // up at 42 / 47 / 50 C; down only a few degrees lower (stage 3 ends under 40 C)
        if (t >= 500) return 3;
        if (stage == 3 && t >= 400) return 3;
        if (t >= 470) return 2;
        if (stage >= 2 && t >= 450) return 2;
        if (t >= 420) return 1;
        if (stage >= 1 && t >= 400) return 1;
        return 0;
    }

    private void updateThermal() {
        final int next = stageFor(mStage, mTemp);
        if (mCoolingTemp != null) {
            mCoolingTemp.setText(mContext.getString(R.string.kiro_cooling_temp, mTemp / 10f));
        }
        if (next == mStage) {
            return;
        }
        Log.i(TAG, "battery " + mTemp / 10f + " C: stage " + mStage + " -> " + next);
        final int prev = mStage;
        mStage = next;
        SystemProperties.set(PROP_THERMAL, String.valueOf(next));
        if (next >= 2 && prev < 2) {
            closeBackgroundApps();
            dim();
            notifyHot();
        } else if (next < 2 && prev >= 2) {
            undim();
            mContext.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        }
        if (next == 3) {
            showCooling();
        } else {
            hideCooling();
        }
    }

    private void closeBackgroundApps() {
        final ActivityManager am = mContext.getSystemService(ActivityManager.class);
        final List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) {
            return;
        }
        int n = 0;
        for (ActivityManager.RunningAppProcessInfo p : procs) {
            // cached and service processes of apps only: never the system, never what is on
            // screen or playing
            if (p.uid < Process.FIRST_APPLICATION_UID
                    || p.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) {
                continue;
            }
            for (String pkg : p.pkgList) {
                am.killBackgroundProcesses(pkg);
                n++;
            }
        }
        Log.i(TAG, "closed " + n + " background apps");
    }

    private void dim() {
        final ContentResolver cr = mContext.getContentResolver();
        if (mSavedBrightness >= 0) {
            return;
        }
        mSavedMode = Settings.System.getIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT);
        mSavedBrightness = Settings.System.getIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS,
                128, UserHandle.USER_CURRENT);
        Settings.System.putIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT);
        Settings.System.putIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS,
                Math.max(20, mSavedBrightness * 6 / 10), UserHandle.USER_CURRENT);
    }

    private void undim() {
        if (mSavedBrightness < 0) {
            return;
        }
        final ContentResolver cr = mContext.getContentResolver();
        Settings.System.putIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS, mSavedBrightness,
                UserHandle.USER_CURRENT);
        Settings.System.putIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, mSavedMode,
                UserHandle.USER_CURRENT);
        mSavedBrightness = -1;
    }

    private void notifyHot() {
        final Notification n = new Notification.Builder(mContext)
                .setSmallIcon(R.drawable.ic_kiro_thermal)
                .setContentTitle(mContext.getString(R.string.kiro_hot_title))
                .setContentText(mContext.getString(R.string.kiro_hot_text))
                .setStyle(new Notification.BigTextStyle()
                        .bigText(mContext.getString(R.string.kiro_hot_text)))
                .setColor(mContext.getColor(R.color.kiro_guard_warning))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();
        mContext.getSystemService(NotificationManager.class).notifyAsUser(null, NOTIFICATION_ID,
                n, UserHandle.ALL);
    }

    private void showCooling() {
        if (mCooling != null) {
            return;
        }
        final float d = mContext.getResources().getDisplayMetrics().density;
        final LinearLayout box = new LinearLayout(mContext);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.BLACK);
        box.setPadding((int) (32 * d), 0, (int) (32 * d), 0);
        final TextView title = new TextView(mContext);
        title.setText(R.string.kiro_cooling_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setGravity(Gravity.CENTER);
        final TextView body = new TextView(mContext);
        body.setText(R.string.kiro_cooling_text);
        body.setTextColor(0xFFB8B8C0);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, (int) (12 * d), 0, (int) (20 * d));
        mCoolingTemp = new TextView(mContext);
        mCoolingTemp.setText(mContext.getString(R.string.kiro_cooling_temp, mTemp / 10f));
        mCoolingTemp.setTextColor(mContext.getColor(R.color.kiro_guard_warning));
        mCoolingTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        mCoolingTemp.setGravity(Gravity.CENTER);
        final Button sos = new Button(mContext);
        sos.setText(R.string.kiro_cooling_emergency);
        sos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // the emergency dialer stays reachable over the cooling screen
                hideCooling();
                mContext.startActivityAsUser(new Intent("com.android.phone.EmergencyDialer.DIAL")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), UserHandle.CURRENT);
            }
        });
        box.addView(title);
        box.addView(body);
        box.addView(mCoolingTemp);
        box.addView(sos);
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.OPAQUE);
        lp.setTitle("KiroCooling");
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        mContext.getSystemService(WindowManager.class).addView(box, lp);
        mCooling = box;
    }

    private void hideCooling() {
        if (mCooling != null) {
            mContext.getSystemService(WindowManager.class).removeView(mCooling);
            mCooling = null;
            mCoolingTemp = null;
        }
    }

    // ------------------------------------------------------------------ charging

    private void updateCharging() {
        // the kernel (batt_kiro_charge) stops and restarts charging itself: here only the mode
        final int mode = setting(KEY_BYPASS) ? 2 : setting(KEY_CHARGE_LIMIT) ? 1 : 0;
        if (mode != mChargeMode) {
            mChargeMode = mode;
            SystemProperties.set(PROP_CHARGE, String.valueOf(mode));
            Log.i(TAG, "charge mode " + mode);
        }
    }
}