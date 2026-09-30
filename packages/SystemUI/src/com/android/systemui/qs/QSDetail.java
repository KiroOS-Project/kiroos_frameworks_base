/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.GradientDrawable;
import android.view.animation.PathInterpolator;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile.DetailAdapter;
import com.android.systemui.statusbar.phone.BaseStatusBarHeader;
import com.android.systemui.statusbar.phone.QSTileHost;

public class QSDetail extends LinearLayout {

    private static final String TAG = "QSDetail";
    private static final long FADE_DURATION = 300;

    private final SparseArray<View> mDetailViews = new SparseArray<>();

    private ViewGroup mDetailContent;
    private TextView mDetailSettingsButton;
    private TextView mDetailDoneButton;
    private QSDetailClipper mClipper;
    private DetailAdapter mDetailAdapter;
    private QSPanel mQsPanel;

    private View mQsDetailHeader;
    private TextView mQsDetailHeaderTitle;
    private Switch mQsDetailHeaderSwitch;
    private ImageView mQsDetailHeaderProgress;
    // KiroOS: the sheet header's icon in a circle and the state under the title
    private ImageView mKiroIcon;
    private TextView mKiroSubtitle;
    private final GradientDrawable mKiroIconShape = new GradientDrawable();
    private static final PathInterpolator KIRO_SHEET_IN = new PathInterpolator(0.2f, 0.9f, 0.1f, 1f);
    private android.animation.ObjectAnimator mKiroFade;

    private QSTileHost mHost;

    private boolean mScanState;
    private boolean mClosingDetail;
    private boolean mFullyExpanded;
    private BaseStatusBarHeader mHeader;
    private boolean mTriggeredExpand;
    private int mOpenX;
    private int mOpenY;
    private boolean mAnimatingOpen;
    private boolean mSwitchState;

    public QSDetail(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        FontSizeUtils.updateFontSize(mDetailDoneButton, R.dimen.qs_detail_button_text_size);
        FontSizeUtils.updateFontSize(mDetailSettingsButton, R.dimen.qs_detail_button_text_size);

        for (int i = 0; i < mDetailViews.size(); i++) {
            mDetailViews.valueAt(i).dispatchConfigurationChanged(newConfig);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mDetailContent = (ViewGroup) findViewById(android.R.id.content);
        mDetailSettingsButton = (TextView) findViewById(android.R.id.button2);
        mDetailDoneButton = (TextView) findViewById(android.R.id.button1);

        mQsDetailHeader = findViewById(R.id.qs_detail_header);
        mQsDetailHeaderTitle = (TextView) mQsDetailHeader.findViewById(android.R.id.title);
        mQsDetailHeaderSwitch = (Switch) mQsDetailHeader.findViewById(android.R.id.toggle);
        mQsDetailHeaderProgress = (ImageView) findViewById(R.id.qs_detail_header_progress);
        mKiroIcon = (ImageView) mQsDetailHeader.findViewById(R.id.kiroos_detail_icon);
        mKiroSubtitle = (TextView) mQsDetailHeader.findViewById(R.id.kiroos_detail_subtitle);
        mKiroIconShape.setShape(GradientDrawable.OVAL);
        mKiroIcon.setBackground(mKiroIconShape);

        updateDetailText();

        mClipper = new QSDetailClipper(this);

        final OnClickListener doneListener = new OnClickListener() {
            @Override
            public void onClick(View v) {
                announceForAccessibility(
                        mContext.getString(R.string.accessibility_desc_quick_settings));
                mQsPanel.closeDetail();
            }
        };
        mDetailDoneButton.setOnClickListener(doneListener);
    }

    public void setQsPanel(QSPanel panel, BaseStatusBarHeader header) {
        mQsPanel = panel;
        mHeader = header;
        mHeader.setCallback(mQsPanelCallback);
        mQsPanel.setCallback(mQsPanelCallback);
    }

    public void setHost(QSTileHost host) {
        mHost = host;
    }
    public boolean isShowingDetail() {
        return mDetailAdapter != null;
    }

    public void setFullyExpanded(boolean fullyExpanded) {
        mFullyExpanded = fullyExpanded;
    }

    public void setExpanded(boolean qsExpanded) {
        if (!qsExpanded) {
            mTriggeredExpand = false;
        }
    }

    private void updateDetailText() {
        mDetailDoneButton.setText(R.string.quick_settings_done);
        mDetailSettingsButton.setText(R.string.quick_settings_more_settings);
    }

    public void updateResources() {
        updateDetailText();
    }

    public boolean isClosingDetail() {
        return mClosingDetail;
    }

    private void handleShowingDetail(final QSTile.DetailAdapter adapter, int x, int y) {
        final boolean showingDetail = adapter != null;
        setClickable(showingDetail);
        if (showingDetail) {
            mQsDetailHeaderTitle.setText(adapter.getTitle());
            final int icon = kiroIconFor(adapter.getMetricsCategory());
            mKiroIcon.setVisibility(icon != 0 ? VISIBLE : GONE);
            if (icon != 0) {
                mKiroIcon.setImageResource(icon);
            }
            final Boolean toggleState = adapter.getToggleState();
            mKiroSubtitle.setVisibility(toggleState != null ? VISIBLE : GONE);
            kiroShowState(toggleState != null && toggleState);
            if (toggleState == null) {
                mQsDetailHeaderSwitch.setVisibility(INVISIBLE);
                mQsDetailHeader.setClickable(false);
            } else {
                mQsDetailHeaderSwitch.setVisibility(VISIBLE);
                handleToggleStateChanged(toggleState, adapter.getToggleEnabled());
                mQsDetailHeader.setClickable(true);
                mQsDetailHeader.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean checked = !mQsDetailHeaderSwitch.isChecked();
                        mQsDetailHeaderSwitch.setChecked(checked);
                        adapter.setToggleState(checked);
                    }
                });
            }
            if (!mFullyExpanded) {
                mTriggeredExpand = true;
                mHost.animateToggleQSExpansion();
            } else {
                mTriggeredExpand = false;
            }
            mOpenX = x;
            mOpenY = y;
        } else {
            // Ensure we collapse into the same point we opened from.
            x = mOpenX;
            y = mOpenY;
            if (mTriggeredExpand) {
                mHost.animateToggleQSExpansion();
                mTriggeredExpand = false;
            }
        }

        boolean visibleDiff = (mDetailAdapter != null) != (adapter != null);
        if (!visibleDiff && mDetailAdapter == adapter) return;  // already in right state
        AnimatorListener listener = null;
        if (adapter != null) {
            int viewCacheIndex = adapter.getMetricsCategory();
            View detailView = adapter.createDetailView(mContext, mDetailViews.get(viewCacheIndex),
                    mDetailContent);
            if (detailView == null) throw new IllegalStateException("Must return detail view");

            final Intent settingsIntent = adapter.getSettingsIntent();
            mDetailSettingsButton.setVisibility(settingsIntent != null ? VISIBLE : GONE);
            mDetailSettingsButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mHost.startActivityDismissingKeyguard(settingsIntent);
                }
            });

            mDetailContent.removeAllViews();
            mDetailContent.addView(detailView);
            mDetailViews.put(viewCacheIndex, detailView);
            MetricsLogger.visible(mContext, adapter.getMetricsCategory());
            announceForAccessibility(mContext.getString(
                    R.string.accessibility_quick_settings_detail,
                    adapter.getTitle()));
            mDetailAdapter = adapter;
            listener = mHideGridContentWhenDone;
            setVisibility(View.VISIBLE);
        } else {
            if (mDetailAdapter != null) {
                MetricsLogger.hidden(mContext, mDetailAdapter.getMetricsCategory());
            }
            mClosingDetail = true;
            mDetailAdapter = null;
            listener = mTeardownDetailWhenDone;
            mHeader.setVisibility(View.VISIBLE);
            mQsPanel.setGridContentVisibility(true);
            mQsPanelCallback.onScanStateChanged(false);
        }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        if (visibleDiff) {
            mAnimatingOpen = adapter != null;
            if (mFullyExpanded || mDetailAdapter != null) {
                // KiroOS: the sheet grows out of the tile it belongs to and settles softly,
                // over the grid, which goes away under it (no circle wiping over the panel)
                kiroAnimateSheet(x, y, mDetailAdapter != null, listener);
            } else {
                animate().alpha(0)
                        .setDuration(FADE_DURATION)
                        .setListener(listener)
                        .start();
            }
        }
    }

    /** KiroOS: the icon of the feature a sheet belongs to */
    private static int kiroIconFor(int metricsCategory) {
        switch (metricsCategory) {
            case MetricsEvent.QS_BLUETOOTH_DETAILS:
                return R.drawable.ic_qs_bluetooth_on;
            case MetricsEvent.QS_WIFI_DETAILS:
                return R.drawable.ic_qs_wifi_full_4;
            case MetricsEvent.QS_DND_DETAILS:
                return R.drawable.ic_qs_dnd_on;
            case MetricsEvent.QS_CAST_DETAILS:
                return R.drawable.ic_qs_cast_on;
            case MetricsEvent.QS_DATAUSAGEDETAIL:
                return R.drawable.ic_qs_signal_4;
            default:
                return 0;
        }
    }

    /** KiroOS: accent circle and "On" while the feature is on, a quiet circle and "Off" if not */
    private void kiroShowState(boolean on) {
        mKiroSubtitle.setText(on ? R.string.kiroos_detail_on : R.string.kiroos_detail_off);
        mKiroIconShape.setColor(mContext.getColor(on ? R.color.qs_tile_bg_active
                : R.color.kiroos_detail_icon_bg));
        mKiroIcon.setColorFilter(mContext.getColor(on ? R.color.qs_tile_tint_active
                : R.color.qs_text));
    }

    /**
     * KiroOS: the sheet is opaque from the first frame and grows out of its tile; only what's on
     * it fades in (a see-through sheet showed the tiles behind it). Closing, it shrinks back and
     * fades only at the very end.
     */
    private void kiroAnimateSheet(int x, int y, final boolean open,
            final AnimatorListener listener) {
        animate().cancel();
        if (mKiroFade != null) {
            mKiroFade.cancel();
        }
        setPivotX(Math.max(0, Math.min(getWidth(), x)));
        setPivotY(Math.max(0, Math.min(getHeight(), y)));
        if (open) {
            mClosingDetail = false;
            setAlpha(1f);
            setScaleX(0.92f);
            setScaleY(0.92f);
            kiroFadeContent(0f, 1f, 70, 220);
            animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(320)
                    .setInterpolator(KIRO_SHEET_IN).setListener(listener).start();
        } else {
            kiroFadeContent(1f, 0f, 0, 110);
            mKiroFade = android.animation.ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0f);
            mKiroFade.setStartDelay(120);
            mKiroFade.setDuration(90);
            mKiroFade.start();
            animate().scaleX(0.94f).scaleY(0.94f).setStartDelay(0).setDuration(210)
                    .setInterpolator(Interpolators.FAST_OUT_LINEAR_IN)
                    .setListener(new AnimatorListenerAdapter() {
                        private boolean mCancelled;

                        @Override
                        public void onAnimationCancel(Animator animation) {
                            // reopened before it was gone: the new sheet must not be torn down
                            mCancelled = true;
                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            animation.removeListener(this);
                            if (!mCancelled) {
                                setScaleX(1f);
                                setScaleY(1f);
                                if (listener != null) {
                                    listener.onAnimationEnd(animation);
                                }
                            }
                        }
                    }).start();
        }
    }

    /** KiroOS: fades what's on the sheet (header, list, buttons), never the scan progress bar */
    private void kiroFadeContent(float from, float to, long delay, long duration) {
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child == mQsDetailHeaderProgress) {
                continue;
            }
            child.animate().cancel();
            child.setAlpha(from);
            child.animate().alpha(to).setStartDelay(delay).setDuration(duration)
                    .setInterpolator(to > from ? Interpolators.ALPHA_IN : Interpolators.ALPHA_OUT)
                    .start();
        }
    }

    private void handleToggleStateChanged(boolean state, boolean toggleEnabled) {
        mSwitchState = state;
        if (mDetailAdapter != null && mDetailAdapter.getToggleState() != null) {
            kiroShowState(state);
        }
        if (mAnimatingOpen) {
            return;
        }
        mQsDetailHeaderSwitch.setChecked(state);
        mQsDetailHeader.setEnabled(toggleEnabled);
        mQsDetailHeaderSwitch.setEnabled(toggleEnabled);
    }

    private void handleScanStateChanged(boolean state) {
        if (mScanState == state) return;
        mScanState = state;
        final Animatable anim = (Animatable) mQsDetailHeaderProgress.getDrawable();
        if (state) {
            mQsDetailHeaderProgress.animate().alpha(1f);
            anim.start();
        } else {
            mQsDetailHeaderProgress.animate().alpha(0f);
            anim.stop();
        }
    }

    private void checkPendingAnimations() {
        handleToggleStateChanged(mSwitchState,
                            mDetailAdapter != null && mDetailAdapter.getToggleEnabled());
    }

    private final QSPanel.Callback mQsPanelCallback = new QSPanel.Callback() {
        @Override
        public void onToggleStateChanged(final boolean state) {
            post(new Runnable() {
                @Override
                public void run() {
                    handleToggleStateChanged(state,
                            mDetailAdapter != null && mDetailAdapter.getToggleEnabled());
                }
            });
        }

        @Override
        public void onShowingDetail(final DetailAdapter detail, final int x, final int y) {
            post(new Runnable() {
                @Override
                public void run() {
                    handleShowingDetail(detail, x, y);
                }
            });
        }

        @Override
        public void onScanStateChanged(final boolean state) {
            post(new Runnable() {
                @Override
                public void run() {
                    handleScanStateChanged(state);
                }
            });
        }
    };

    private final AnimatorListenerAdapter mHideGridContentWhenDone = new AnimatorListenerAdapter() {
        public void onAnimationCancel(Animator animation) {
            // If we have been cancelled, remove the listener so that onAnimationEnd doesn't get
            // called, this will avoid accidentally turning off the grid when we don't want to.
            animation.removeListener(this);
            mAnimatingOpen = false;
            checkPendingAnimations();
        };

        @Override
        public void onAnimationEnd(Animator animation) {
            // Only hide content if still in detail state.
            if (mDetailAdapter != null) {
                mQsPanel.setGridContentVisibility(false);
                mHeader.setVisibility(View.INVISIBLE);
            }
            mAnimatingOpen = false;
            checkPendingAnimations();
        }
    };

    private final AnimatorListenerAdapter mTeardownDetailWhenDone = new AnimatorListenerAdapter() {
        public void onAnimationEnd(Animator animation) {
            mDetailContent.removeAllViews();
            setVisibility(View.INVISIBLE);
            mClosingDetail = false;
        };
    };
}
