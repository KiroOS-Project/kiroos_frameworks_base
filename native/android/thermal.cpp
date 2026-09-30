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

/*
 * KiroOS: the Android 11 thermal API (<android/thermal.h>) for apps built for newer Android.
 * Geekbench links it and its native library failed to load without it. The status reported is
 * the stage of KiroOS Thermal Guardian (SystemUI KiroGuardian, sys.kiroos.guard.thermal).
 */

#include <errno.h>

#include <cutils/properties.h>

extern "C" {

struct AThermalManager {
    int unused;
};

// ATHERMAL_STATUS_* of <android/thermal.h>
enum {
    ATHERMAL_STATUS_ERROR = -1,
    ATHERMAL_STATUS_NONE = 0,
    ATHERMAL_STATUS_LIGHT = 1,
    ATHERMAL_STATUS_MODERATE = 2,
    ATHERMAL_STATUS_SEVERE = 3,
    ATHERMAL_STATUS_CRITICAL = 4,
};

typedef void (*AThermal_StatusCallback)(void* data, int status);

AThermalManager* AThermal_acquireManager() {
    return new AThermalManager();
}

void AThermal_releaseManager(AThermalManager* manager) {
    delete manager;
}

int AThermal_getCurrentThermalStatus(AThermalManager* manager) {
    if (manager == nullptr) {
        return ATHERMAL_STATUS_ERROR;
    }
    // Thermal Guardian: stage 1 at 42 C (lower clocks), 2 at 47 C (background apps closed),
    // 3 at 50 C (cooling screen)
    switch (property_get_int32("sys.kiroos.guard.thermal", 0)) {
        case 1:
            return ATHERMAL_STATUS_MODERATE;
        case 2:
            return ATHERMAL_STATUS_SEVERE;
        case 3:
            return ATHERMAL_STATUS_CRITICAL;
        default:
            return ATHERMAL_STATUS_NONE;
    }
}

// No status callbacks: apps poll AThermal_getCurrentThermalStatus instead.
int AThermal_registerThermalStatusListener(AThermalManager* manager,
        AThermal_StatusCallback /* callback */, void* /* data */) {
    return manager == nullptr ? EINVAL : EPERM;
}

int AThermal_unregisterThermalStatusListener(AThermalManager* manager,
        AThermal_StatusCallback /* callback */, void* /* data */) {
    return manager == nullptr ? EINVAL : EPERM;
}

}  // extern "C"
