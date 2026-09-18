/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.internal.hardware;

import com.android.internal.R;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

public class AmbientDisplayConfiguration {

    /** KiroOS: always on display (Settings.Secure, 1 = on). */
    public static final String ALWAYS_ON_ENABLED = "kiroos_aod_enabled";
    /** KiroOS: minutes the always on display stays visible after the screen turns off, 0 = always. */
    public static final String ALWAYS_ON_TIMEOUT = "kiroos_aod_timeout";
    public static final int ALWAYS_ON_DEFAULT_TIMEOUT = 10;

    private final Context mContext;

    public AmbientDisplayConfiguration(Context context) {
        mContext = context;
    }
    
    public boolean enabled(int user) {
        return pulseOnNotificationEnabled(user)
                || pulseOnPickupEnabled(user)
                || pulseOnDoubleTapEnabled(user)
                || alwaysOnEnabled(user);
    }

    public boolean alwaysOnEnabled(int user) {
        return boolSetting(ALWAYS_ON_ENABLED, user) && ambientDisplayAvailable();
    }

    /** Milliseconds the always on display stays on, 0 = until the screen is turned on. */
    public long alwaysOnTimeoutMillis(int user) {
        int minutes = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                ALWAYS_ON_TIMEOUT, ALWAYS_ON_DEFAULT_TIMEOUT, user);
        return minutes <= 0 ? 0 : minutes * 60000L;
    }
    
    public boolean available() {
        return pulseOnNotificationAvailable() || pulseOnPickupAvailable()
                || pulseOnDoubleTapAvailable();
    }
    
    public boolean pulseOnNotificationEnabled(int user) {
        return boolSetting(Settings.Secure.DOZE_ENABLED, user) && pulseOnNotificationAvailable();
    }

    public boolean pulseOnNotificationAvailable() {
        return ambientDisplayAvailable();
    }

    public boolean pulseOnPickupEnabled(int user) {
        return boolSetting(Settings.Secure.DOZE_PULSE_ON_PICK_UP, user)
                && pulseOnPickupAvailable();
    }
    
    public boolean pulseOnPickupAvailable() {
        return mContext.getResources().getBoolean(R.bool.config_dozePulsePickup)
                && ambientDisplayAvailable();
    }
    
    public boolean pulseOnDoubleTapEnabled(int user) {
        return boolSetting(Settings.Secure.DOZE_PULSE_ON_DOUBLE_TAP, user)
                && pulseOnDoubleTapAvailable();
    }

    public boolean pulseOnDoubleTapAvailable() {
        return !TextUtils.isEmpty(doubleTapSensorType()) && ambientDisplayAvailable();
    }

    public String doubleTapSensorType() {
        return mContext.getResources().getString(R.string.config_dozeDoubleTapSensorType);
    }

    public String ambientDisplayComponent() {
        return mContext.getResources().getString(R.string.config_dozeComponent);
    }

    private boolean ambientDisplayAvailable() {
        return !TextUtils.isEmpty(ambientDisplayComponent());
    }

    private boolean boolSetting(String name, int user) {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(), name, 1, user) != 0;
    }

}
