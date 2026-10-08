/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.qs.customize;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto;
import com.android.systemui.R;
import com.android.systemui.qs.QSContainer;
import com.android.systemui.qs.QSDetailClipper;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.phone.NotificationsQuickSettingsContainer;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.KeyguardMonitor.Callback;

import java.util.ArrayList;
import java.util.List;

/**
 * KiroOS: the control center editor, full screen over the control center (show() / hide(), from
 * the pencil). A header with Reset, the title and Done; under it TileAdapter's list: the control
 * center as it looks, then the controls that can be added.
 */
public class QSCustomizer extends LinearLayout {

    private final QSDetailClipper mClipper;

    private PhoneStatusBar mPhoneStatusBar;

    private boolean isShown;
    private QSTileHost mHost;
    private RecyclerView mRecyclerView;
    private TileAdapter mTileAdapter;
    private View mDone;
    private boolean mCustomizing;
    private NotificationsQuickSettingsContainer mNotifQsContainer;
    private QSContainer mQsContainer;
    private AlertDialog mResetDialog;

    public QSCustomizer(Context context, AttributeSet attrs) {
        super(new ContextThemeWrapper(context, R.style.edit_theme), attrs);
        mClipper = new QSDetailClipper(this);

        LayoutInflater.from(getContext()).inflate(R.layout.qs_customize_panel_content, this);

        mDone = findViewById(R.id.kiroos_edit_done);
        mDone.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                // the editor closes into the button
                final Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
                offsetDescendantRectToMyCoords(v, r);
                hide(r.centerX(), r.centerY());
            }
        });
        findViewById(R.id.kiroos_edit_reset).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });

        mRecyclerView = (RecyclerView) findViewById(android.R.id.list);
        mTileAdapter = new TileAdapter(getContext());
        mRecyclerView.setAdapter(mTileAdapter);
        mTileAdapter.getItemTouchHelper().attachToRecyclerView(mRecyclerView);
        GridLayoutManager layout = new GridLayoutManager(getContext(), TileAdapter.SPANS);
        layout.setSpanSizeLookup(mTileAdapter.getSizeLookup());
        mRecyclerView.setLayoutManager(layout);
        mRecyclerView.addItemDecoration(mTileAdapter.getItemDecoration());
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setMoveDuration(TileAdapter.MOVE_DURATION);
        // a control's place changes its description, not its look: no cross-fade
        animator.setSupportsChangeAnimations(false);
        mRecyclerView.setItemAnimator(animator);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        View navBackdrop = findViewById(R.id.nav_bar_background);
        if (navBackdrop != null) {
            boolean shouldShow = newConfig.smallestScreenWidthDp >= 600
                    || newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE;
            navBackdrop.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
        }
    }

    public void setHost(QSTileHost host) {
        mHost = host;
        mPhoneStatusBar = host.getPhoneStatusBar();
        mTileAdapter.setHost(host);
    }

    public void setContainer(NotificationsQuickSettingsContainer notificationsQsContainer) {
        mNotifQsContainer = notificationsQsContainer;
    }

    public void setQsContainer(QSContainer qsContainer) {
        mQsContainer = qsContainer;
    }

    public void show(int x, int y) {
        if (!isShown) {
            MetricsLogger.visible(getContext(), MetricsProto.MetricsEvent.QS_EDIT);
            isShown = true;
            setTileSpecs();
            mRecyclerView.scrollToPosition(0);
            setVisibility(View.VISIBLE);
            mClipper.animateCircularClip(x, y, true, mExpandAnimationListener);
            new TileQueryHelper(mContext, mHost).setListener(mTileAdapter);
            mNotifQsContainer.setCustomizerAnimating(true);
            mNotifQsContainer.setCustomizerShowing(true);
            announceForAccessibility(mContext.getString(
                    R.string.accessibility_desc_quick_settings_edit));
            mHost.getKeyguardMonitor().addCallback(mKeyguardCallback);
        }
    }

    public void hide(int x, int y) {
        if (isShown) {
            MetricsLogger.hidden(getContext(), MetricsProto.MetricsEvent.QS_EDIT);
            isShown = false;
            if (mResetDialog != null) {
                mResetDialog.dismiss();
                mResetDialog = null;
            }
            setCustomizing(false);
            save();
            mClipper.animateCircularClip(x, y, false, mCollapseAnimationListener);
            mNotifQsContainer.setCustomizerAnimating(true);
            mNotifQsContainer.setCustomizerShowing(false);
            announceForAccessibility(mContext.getString(
                    R.string.accessibility_desc_quick_settings));
            mHost.getKeyguardMonitor().removeCallback(mKeyguardCallback);
        }
    }

    public boolean isShown() {
        return isShown;
    }

    private void setCustomizing(boolean customizing) {
        mCustomizing = customizing;
        mQsContainer.notifyCustomizeChanged();
    }

    public boolean isCustomizing() {
        return mCustomizing;
    }

    /** Reset asks first: it throws away the user's order */
    private void confirmReset() {
        if (mResetDialog != null) return;
        final AlertDialog dialog = new AlertDialog.Builder(mContext)
                .setTitle(R.string.kiroos_edit_reset_title)
                .setMessage(R.string.kiroos_edit_reset_text)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.kiroos_edit_reset,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                MetricsLogger.action(getContext(),
                                        MetricsProto.MetricsEvent.ACTION_QS_EDIT_RESET);
                                reset();
                            }
                        })
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                mResetDialog = null;
            }
        });
        SystemUIDialog.setShowForAllUsers(dialog, true);
        SystemUIDialog.applyFlags(dialog);
        mResetDialog = dialog;
        dialog.show();
    }

    private void reset() {
        ArrayList<String> tiles = new ArrayList<>();
        String defTiles = mContext.getString(R.string.quick_settings_tiles_default);
        for (String tile : defTiles.split(",")) {
            tile = tile.trim();
            if (!tile.isEmpty()) tiles.add(tile);
        }
        mTileAdapter.resetTo(tiles);
        mRecyclerView.smoothScrollToPosition(0);
    }

    private void setTileSpecs() {
        List<String> specs = new ArrayList<>();
        for (QSTile tile : mHost.getTiles()) {
            specs.add(tile.getTileSpec());
        }
        mTileAdapter.setTileSpecs(specs);
    }

    private void save() {
        mTileAdapter.saveSpecs(mHost);
    }

    private final Callback mKeyguardCallback = new Callback() {
        @Override
        public void onKeyguardChanged() {
            if (mHost.getKeyguardMonitor().isShowing()) {
                hide(0, 0);
            }
        }
    };

    private final AnimatorListener mExpandAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (isShown) {
                setCustomizing(true);
            }
            mTileAdapter.onShown();
            mNotifQsContainer.setCustomizerAnimating(false);
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mTileAdapter.onShown();
            mNotifQsContainer.setCustomizerAnimating(false);
        }
    };

    private final AnimatorListener mCollapseAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (!isShown) {
                setVisibility(View.GONE);
            }
            mNotifQsContainer.setCustomizerAnimating(false);
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            if (!isShown) {
                setVisibility(View.GONE);
            }
            mNotifQsContainer.setCustomizerAnimating(false);
        }
    };
}
