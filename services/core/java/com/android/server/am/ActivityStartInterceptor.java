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
 * limitations under the License.
 */

package com.android.server.am;

import static android.app.ActivityManager.INTENT_SENDER_ACTIVITY;
import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.content.Context.KEYGUARD_SERVICE;
import static android.content.Intent.EXTRA_INTENT;
import static android.content.Intent.EXTRA_PACKAGE_NAME;
import static android.content.Intent.EXTRA_TASK_ID;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static android.content.pm.ApplicationInfo.FLAG_SUSPENDED;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.security.SecureRandom;

import com.android.internal.app.UnlaunchableAppActivity;
import com.android.server.LocalServices;

/**
 * A class that contains activity intercepting logic for {@link ActivityStarter#startActivityLocked}
 * It's initialized
 */
class ActivityStartInterceptor {

    private final ActivityManagerService mService;
    private UserManager mUserManager;
    private final ActivityStackSupervisor mSupervisor;

    /*
     * Per-intent states loaded from ActivityStarter than shouldn't be changed by any
     * interception routines.
     */
    private int mRealCallingPid;
    private int mRealCallingUid;
    private int mUserId;
    private int mStartFlags;
    private String mCallingPackage;

    /*
     * Per-intent states that were load from ActivityStarter and are subject to modifications
     * by the interception routines. After calling {@link #intercept} the caller should assign
     * these values back to {@link ActivityStarter#startActivityLocked}'s local variables.
     */
    Intent mIntent;
    int mCallingPid;
    int mCallingUid;
    ResolveInfo mRInfo;
    ActivityInfo mAInfo;
    String mResolvedType;
    TaskRecord mInTask;
    ActivityOptions mActivityOptions;

    ActivityStartInterceptor(ActivityManagerService service, ActivityStackSupervisor supervisor) {
        mService = service;
        mSupervisor = supervisor;
    }

    void setStates(int userId, int realCallingPid, int realCallingUid, int startFlags,
            String callingPackage) {
        mRealCallingPid = realCallingPid;
        mRealCallingUid = realCallingUid;
        mUserId = userId;
        mStartFlags = startFlags;
        mCallingPackage = callingPackage;
    }

    void intercept(Intent intent, ResolveInfo rInfo, ActivityInfo aInfo, String resolvedType,
            TaskRecord inTask, int callingPid, int callingUid, ActivityOptions activityOptions) {
        mUserManager = UserManager.get(mService.mContext);
        mIntent = intent;
        mCallingPid = callingPid;
        mCallingUid = callingUid;
        mRInfo = rInfo;
        mAInfo = aInfo;
        mResolvedType = resolvedType;
        mInTask = inTask;
        mActivityOptions = activityOptions;
        if (interceptSuspendPackageIfNeed()) {
            // Skip the rest of interceptions as the package is suspended by device admin so
            // no user action can undo this.
            return;
        }
        if (interceptQuietProfileIfNeeded()) {
            // If work profile is turned off, skip the work challenge since the profile can only
            // be unlocked when profile's user is running.
            return;
        }
        if (interceptWorkProfileChallengeIfNeeded()) {
            return;
        }
        interceptKiroAppLockIfNeeded();
    }

    // ---- KiroOS App lock
    //
    // Kiro App Lock (com.kiroos.applock) keeps the locked packages in Settings.Secure. Starting
    // one of their activities shows its UnlockActivity instead, with the original start as an
    // IntentSender and a one-time token; after the fingerprint (or the PIN) it sends the start
    // again with the token filled in, which unlocks the package until the screen turns off.
    // Tasks opened from Recents come here too (ActivityStackSupervisor
    // .startActivityFromRecentsInner); for them the token comes back in the options.

    /** Settings.Secure: the locked packages, comma separated */
    static final String KIRO_APPLOCK_PACKAGES = "kiroos_applock_packages";
    static final String KIRO_APPLOCK_TOKEN = "com.kiroos.applock.TOKEN";
    private static final ComponentName KIRO_UNLOCK = new ComponentName("com.kiroos.applock",
            "com.kiroos.applock.UnlockActivity");

    private static volatile String[] sKiroLocked = new String[0];
    private static boolean sKiroWatching;
    // guarded by the ActivityManagerService lock
    private static final ArraySet<String> sKiroUnlocked = new ArraySet<>();
    private static final ArrayMap<String, Long> sKiroTokens = new ArrayMap<>();
    private static final SecureRandom sKiroRandom = new SecureRandom();

    /**
     * Whether pkg is locked for userId. The token handed out for it unlocks it: in the intent's
     * extras (a start sent again by Kiro App Lock) or in the Recents options. The extras are only
     * looked at for a locked package that has a token out, so other starts keep their extras
     * untouched. Called with the AMS lock held.
     */
    static boolean kiroNeedsUnlock(ActivityManagerService service, String pkg, int userId,
            Intent intent, Bundle options) {
        kiroWatch(service);
        if (pkg == null || userId != UserHandle.USER_SYSTEM
                || KIRO_UNLOCK.getPackageName().equals(pkg)) {
            return false;
        }
        boolean locked = false;
        for (String p : sKiroLocked) {
            if (p.equals(pkg)) {
                locked = true;
                break;
            }
        }
        if (!locked || sKiroUnlocked.contains(pkg)) return false;
        final Long expected = sKiroTokens.get(pkg);
        if (expected != null) {
            long token = 0;
            try {
                if (intent != null) {
                    // a copy: the intent's own extras are not unparceled here
                    final Bundle extras = intent.getExtras();
                    if (extras != null) token = extras.getLong(KIRO_APPLOCK_TOKEN, 0);
                } else if (options != null) {
                    token = options.getLong(KIRO_APPLOCK_TOKEN, 0);
                }
            } catch (RuntimeException e) {
                // extras that cannot be read here carry no token
            }
            if (token == expected) {
                sKiroTokens.remove(pkg);
                sKiroUnlocked.add(pkg);
                if (intent != null) intent.removeExtra(KIRO_APPLOCK_TOKEN);
                return false;
            }
        }
        return true;
    }

    /** a new token for pkg, replacing the one of an earlier lock screen */
    private static long kiroNewToken(String pkg) {
        long t;
        do {
            t = sKiroRandom.nextLong();
        } while (t == 0);
        sKiroTokens.put(pkg, t);
        return t;
    }

    /** the locked list follows Settings; every unlock ends when the screen turns off */
    private static void kiroWatch(final ActivityManagerService service) {
        if (sKiroWatching || !service.mSystemReady) return;
        sKiroWatching = true;
        service.mHandler.post(new Runnable() {
            @Override
            public void run() {
                final ContentResolver cr = service.mContext.getContentResolver();
                final Runnable read = new Runnable() {
                    @Override
                    public void run() {
                        final String v = Settings.Secure.getStringForUser(cr,
                                KIRO_APPLOCK_PACKAGES, UserHandle.USER_SYSTEM);
                        sKiroLocked = TextUtils.isEmpty(v) ? new String[0] : v.split(",");
                    }
                };
                cr.registerContentObserver(Settings.Secure.getUriFor(KIRO_APPLOCK_PACKAGES),
                        false, new ContentObserver(service.mHandler) {
                            @Override
                            public void onChange(boolean selfChange) {
                                read.run();
                            }
                        }, UserHandle.USER_SYSTEM);
                read.run();
                service.mContext.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        synchronized (service) {
                            sKiroUnlocked.clear();
                            sKiroTokens.clear();
                        }
                    }
                }, new IntentFilter(Intent.ACTION_SCREEN_OFF), null, service.mHandler);
            }
        });
    }

    private boolean interceptKiroAppLockIfNeeded() {
        if (mAInfo == null) return false;
        final String pkg = mAInfo.packageName;
        if (!kiroNeedsUnlock(mService, pkg, mUserId, mIntent, null)) {
            return false;
        }
        final Intent unlock = new Intent()
                .setComponent(KIRO_UNLOCK)
                .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | FLAG_ACTIVITY_TASK_ON_HOME);
        final ResolveInfo rInfo = mSupervisor.resolveIntent(unlock, null, mUserId);
        if (rInfo == null) {
            // Kiro App Lock is missing: never keep the user out of their apps
            return false;
        }
        final IIntentSender target = mService.getIntentSenderLocked(
                INTENT_SENDER_ACTIVITY, mCallingPackage, mCallingUid, mUserId, null, null, 0,
                new Intent[] {mIntent}, new String[] {mResolvedType},
                FLAG_CANCEL_CURRENT | FLAG_ONE_SHOT, null);
        unlock.putExtra(EXTRA_PACKAGE_NAME, pkg);
        unlock.putExtra(EXTRA_INTENT, new IntentSender(target));
        unlock.putExtra(KIRO_APPLOCK_TOKEN, kiroNewToken(pkg));
        if (mInTask != null) {
            // from Recents: Kiro App Lock reopens the task itself
            unlock.putExtra(EXTRA_TASK_ID, mInTask.taskId);
            mInTask = null;
        }
        mIntent = unlock;
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;
        mRInfo = rInfo;
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptQuietProfileIfNeeded() {
        // Do not intercept if the user has not turned off the profile
        if (!mUserManager.isQuietModeEnabled(UserHandle.of(mUserId))) {
            return false;
        }
        IIntentSender target = mService.getIntentSenderLocked(
                INTENT_SENDER_ACTIVITY, mCallingPackage, mCallingUid, mUserId, null, null, 0,
                new Intent[] {mIntent}, new String[] {mResolvedType},
                FLAG_CANCEL_CURRENT | FLAG_ONE_SHOT, null);

        mIntent = UnlaunchableAppActivity.createInQuietModeDialogIntent(mUserId,
                new IntentSender(target));
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;

        final UserInfo parent = mUserManager.getProfileParent(mUserId);
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, parent.id);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptSuspendPackageIfNeed() {
        // Do not intercept if the admin did not suspend the package
        if (mAInfo == null || mAInfo.applicationInfo == null ||
                (mAInfo.applicationInfo.flags & FLAG_SUSPENDED) == 0) {
            return false;
        }
        DevicePolicyManagerInternal devicePolicyManager = LocalServices.getService(
                DevicePolicyManagerInternal.class);
        if (devicePolicyManager == null) {
            return false;
        }
        mIntent = devicePolicyManager.createPackageSuspendedDialogIntent(
                mAInfo.packageName, mUserId);
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;

        final UserInfo parent = mUserManager.getProfileParent(mUserId);
        if (parent != null) {
            mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, parent.id);
        } else {
            mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId);
        }
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptWorkProfileChallengeIfNeeded() {
        final Intent interceptingIntent = interceptWithConfirmCredentialsIfNeeded(mIntent,
                mResolvedType, mAInfo, mCallingPackage, mUserId);
        if (interceptingIntent == null) {
            return false;
        }
        mIntent = interceptingIntent;
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;
        // If we are intercepting and there was a task, convert it into an extra for the
        // ConfirmCredentials intent and unassign it, as otherwise the task will move to
        // front even if ConfirmCredentials is cancelled.
        if (mInTask != null) {
            mIntent.putExtra(EXTRA_TASK_ID, mInTask.taskId);
            mInTask = null;
        }
        if (mActivityOptions == null) {
            mActivityOptions = ActivityOptions.makeBasic();
        }

        ActivityRecord homeActivityRecord = mSupervisor.getHomeActivity();
        if (homeActivityRecord != null && homeActivityRecord.task != null) {
            // Showing credential confirmation activity in home task to avoid stopping multi-windowed
            // mode after showing the full-screen credential confirmation activity.
            mActivityOptions.setLaunchTaskId(homeActivityRecord.task.taskId);
        }

        final UserInfo parent = mUserManager.getProfileParent(mUserId);
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, parent.id);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    /**
     * Creates an intent to intercept the current activity start with Confirm Credentials if needed.
     *
     * @return The intercepting intent if needed.
     */
    private Intent interceptWithConfirmCredentialsIfNeeded(Intent intent, String resolvedType,
            ActivityInfo aInfo, String callingPackage, int userId) {
        if (!mService.mUserController.shouldConfirmCredentials(userId)) {
            return null;
        }
        // Allow direct boot aware activity to be displayed before the user is unlocked.
        if (aInfo.directBootAware && mService.mUserController.isUserRunningLocked(userId,
                ActivityManager.FLAG_AND_LOCKED)) {
            return null;
        }
        final IIntentSender target = mService.getIntentSenderLocked(
                INTENT_SENDER_ACTIVITY, callingPackage,
                Binder.getCallingUid(), userId, null, null, 0, new Intent[]{ intent },
                new String[]{ resolvedType },
                FLAG_CANCEL_CURRENT | FLAG_ONE_SHOT | FLAG_IMMUTABLE, null);
        final KeyguardManager km = (KeyguardManager) mService.mContext
                .getSystemService(KEYGUARD_SERVICE);
        final Intent newIntent = km.createConfirmDeviceCredentialIntent(null, null, userId);
        if (newIntent == null) {
            return null;
        }
        newIntent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS |
                FLAG_ACTIVITY_TASK_ON_HOME);
        newIntent.putExtra(EXTRA_PACKAGE_NAME, aInfo.packageName);
        newIntent.putExtra(EXTRA_INTENT, new IntentSender(target));
        return newIntent;
    }

}
