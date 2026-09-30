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

import android.graphics.Path;
import android.util.Log;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.View.OnLayoutChangeListener;
import android.widget.TextView;

import com.android.systemui.qs.PagedTileLayout.PageListener;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSTile.Host.Callback;
import com.android.systemui.qs.TouchAnimator.Builder;
import com.android.systemui.qs.TouchAnimator.Listener;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.Collection;

public class QSAnimator implements Callback, PageListener, Listener, OnLayoutChangeListener,
        OnAttachStateChangeListener, Tunable {

    private static final String TAG = "QSAnimator";

    private static final String ALLOW_FANCY_ANIMATION = "sysui_qs_fancy_anim";
    private static final String MOVE_FULL_ROWS = "sysui_qs_move_whole_rows";

    public static final float EXPANDED_TILE_DELAY = .86f;

    private final ArrayList<View> mAllViews = new ArrayList<>();
    private final ArrayList<View> mTopFiveQs = new ArrayList<>();
    private final QuickQSPanel mQuickQsPanel;
    private final QSPanel mQsPanel;
    private final QSContainer mQsContainer;

    private PagedTileLayout mPagedLayout;

    private boolean mOnFirstPage = true;
    private TouchAnimator mFirstPageAnimator;
    private TouchAnimator mFirstPageDelayedAnimator;
    private TouchAnimator mTranslationXAnimator;
    private TouchAnimator mTranslationYAnimator;
    private TouchAnimator mNonfirstPageAnimator;
    private TouchAnimator mBrightnessAnimator;

    private boolean mOnKeyguard;

    // KiroOS: the grid comes in as a wave, row by row (see updateAnimators)
    private static final float KIRO_ROW_SCALE = 0.7f;
    private static final android.view.animation.Interpolator KIRO_WAVE =
            new android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f);
    /** eases out past the end and settles back: the small bounce of every row */
    private static final android.view.animation.Interpolator KIRO_BACK =
            new android.view.animation.PathInterpolator(0.34f, 1.45f, 0.64f, 1f);
    private final android.util.SparseArray<TouchAnimator.Builder> mKiroRowBuilders =
            new android.util.SparseArray<>();
    private final android.util.SparseArray<TouchAnimator.Builder> mKiroRowAlphas =
            new android.util.SparseArray<>();
    private final ArrayList<TouchAnimator> mKiroRowAnimators = new ArrayList<>();
    private final ArrayList<View> mKiroLayerViews = new ArrayList<>();
    private boolean mKiroLayersOn;
    private float mKiroRowRise;
    private int mKiroFirstRow;

    private boolean mAllowFancy;
    private boolean mFullRows;
    private int mNumQuickTiles;
    private float mLastPosition;
    private QSTileHost mHost;

    public QSAnimator(QSContainer container, QuickQSPanel quickPanel, QSPanel panel) {
        mQsContainer = container;
        mQuickQsPanel = quickPanel;
        mQsPanel = panel;
        mQsPanel.addOnAttachStateChangeListener(this);
        container.addOnLayoutChangeListener(this);
        QSTileLayout tileLayout = mQsPanel.getTileLayout();
        if (tileLayout instanceof PagedTileLayout) {
            mPagedLayout = ((PagedTileLayout) tileLayout);
            mPagedLayout.setPageListener(this);
        } else {
            Log.w(TAG, "QS Not using page layout");
        }
    }

    public void onRtlChanged() {
        updateAnimators();
    }

    public void setOnKeyguard(boolean onKeyguard) {
        mOnKeyguard = onKeyguard;
        mQuickQsPanel.setVisibility(mOnKeyguard ? View.INVISIBLE : View.VISIBLE);
        if (mOnKeyguard) {
            clearAnimationState();
        }
    }

    public void setHost(QSTileHost qsh) {
        mHost = qsh;
        qsh.addCallback(this);
        updateAnimators();
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        TunerService.get(mQsContainer.getContext()).addTunable(this, ALLOW_FANCY_ANIMATION,
                MOVE_FULL_ROWS, QuickQSPanel.NUM_QUICK_TILES);
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        if (mHost != null) {
            mHost.removeCallback(this);
        }
        TunerService.get(mQsContainer.getContext()).removeTunable(this);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (ALLOW_FANCY_ANIMATION.equals(key)) {
            mAllowFancy = newValue == null || Integer.parseInt(newValue) != 0;
            if (!mAllowFancy) {
                clearAnimationState();
            }
        } else if (MOVE_FULL_ROWS.equals(key)) {
            mFullRows = newValue == null || Integer.parseInt(newValue) != 0;
        } else if (QuickQSPanel.NUM_QUICK_TILES.equals(key)) {
            mNumQuickTiles = mQuickQsPanel.getNumQuickTiles(mQsContainer.getContext());
            clearAnimationState();
        }
        updateAnimators();
    }

    @Override
    public void onPageChanged(boolean isFirst) {
        if (mOnFirstPage == isFirst) return;
        if (!isFirst) {
            clearAnimationState();
        }
        mOnFirstPage = isFirst;
    }

    private void updateAnimators() {
        TouchAnimator.Builder firstPageBuilder = new Builder();
        TouchAnimator.Builder translationXBuilder = new Builder();
        TouchAnimator.Builder translationYBuilder = new Builder();

        if (mQsPanel.getHost() == null) return;
        Collection<QSTile<?>> tiles = mQsPanel.getHost().getTiles();
        int count = 0;

        clearAnimationState();
        mAllViews.clear();
        mTopFiveQs.clear();
        mKiroLayerViews.clear();
        mKiroRowBuilders.clear();
        mKiroRowAlphas.clear();
        mKiroRowAnimators.clear();
        mKiroFirstRow = Integer.MAX_VALUE;
        mKiroRowRise = 28 * mQsPanel.getResources().getDisplayMetrics().density;

        mAllViews.add((View) mQsPanel.getTileLayout());

        // KiroOS: the quick row doesn't fly into the grid (the stock animation): its tiles fade
        // out as the panel opens, and the whole grid comes in as a wave from the top, row by
        // row, each row dropping in and growing into place with a small bounce. Wi-Fi and
        // Bluetooth are the wide tiles above the brightness bar (no grid tile).
        final TouchAnimator.Builder quickFade = new TouchAnimator.Builder();
        int gridIndex = 0;
        for (QSTile<?> tile : tiles) {
            QSTileBaseView tileView = mQsPanel.getTileView(tile);
            if (count < mNumQuickTiles && mAllowFancy) {
                final QSTileBaseView quick = mQuickQsPanel.getTileView(tile);
                if (quick != null) {
                    quickFade.addFloat(quick, "alpha", 1, 0);
                    mAllViews.add(quick);
                }
            }
            count++;
            if (tileView == null) {
                continue;
            }
            final TextView label = ((QSTileView) tileView).getLabel();
            kiroRowAlpha(gridIndex).addFloat(tileView, "alpha", 0, 1);
            kiroRow(gridIndex).addFloat(tileView, "scaleX", KIRO_ROW_SCALE, 1)
                    .addFloat(tileView, "scaleY", KIRO_ROW_SCALE, 1)
                    .addFloat(tileView, "translationY", -mKiroRowRise, 0);
            mKiroLayerViews.add(tileView);
            mAllViews.add(tileView);
            mAllViews.add(label);
            gridIndex++;
        }
        if (mAllowFancy) {
            // KiroOS: the grid stays in its final place while the panel slides in (like the
            // Wi-Fi / Bluetooth row and the brightness bar below), so only the wave moves it and
            // it never slides across the rows above
            firstPageBuilder.addFloat((View) mQsPanel.getTileLayout(), "translationY",
                    mQsPanel.getHeight(), 0);
            // KiroOS: the wide Wi-Fi / Bluetooth tiles rise with the panel and open out wide
            // ahead of the tile wave
            final View conn = mQsPanel.getConnectivityRow();
            if (conn != null) {
                firstPageBuilder.addFloat(conn, "translationY", mQsPanel.getHeight(), 0);
                mKiroRowAnimators.add(new TouchAnimator.Builder()
                        .addFloat(conn, "alpha", 0, 1)
                        .setStartDelay(0.02f)
                        .setEndDelay(0.65f)
                        .setInterpolator(KIRO_WAVE)
                        .build());
                // no drop: it starts right under the header, which would hide it
                mKiroRowAnimators.add(new TouchAnimator.Builder()
                        .addFloat(conn, "scaleX", 0.82f, 1)
                        .addFloat(conn, "scaleY", 0.82f, 1)
                        .setStartDelay(0.02f)
                        .setEndDelay(0.46f)
                        .setInterpolator(KIRO_BACK)
                        .build());
                mKiroLayerViews.add(conn);
                mAllViews.add(conn);
            }
            // Make brightness appear static position and alpha in through second half.
            View brightness = mQsPanel.getBrightnessView();
            if (brightness != null) {
                firstPageBuilder.addFloat(brightness, "translationY", mQsPanel.getHeight(), 0);
                mBrightnessAnimator = new TouchAnimator.Builder()
                        .addFloat(brightness, "alpha", 0, 1)
                        .addFloat(brightness, "scaleX", 0.7f, 1)
                        .setStartDelay(.08f)
                        .setEndDelay(.4f)
                        .setInterpolator(KIRO_BACK)
                        .build();
                mKiroLayerViews.add(brightness);
                mAllViews.add(brightness);
            } else {
                mBrightnessAnimator = null;
            }
            mKiroRowAnimators.add(quickFade.setEndDelay(0.7f).setInterpolator(KIRO_WAVE).build());
            mFirstPageAnimator = firstPageBuilder
                    .setListener(this)
                    .build();
            // KiroOS: the tiles come in with the wave above, so only the footer fades at the end
            // (the whole grid used to stay hidden until 86% and then appear at once)
            mFirstPageDelayedAnimator = new TouchAnimator.Builder()
                    .setStartDelay(EXPANDED_TILE_DELAY)
                    .addFloat(mQsPanel.getFooter().getView(), "alpha", 0, 1).build();
            for (int i = 0; i < mKiroRowBuilders.size(); i++) {
                final int row = mKiroRowBuilders.keyAt(i);
                // the first grid row starts as soon as the panel moves, then one every 13%
                final float start = Math.min(0.55f, 0.12f + 0.13f * (row - mKiroFirstRow));
                mKiroRowAnimators.add(mKiroRowBuilders.valueAt(i)
                        .setStartDelay(start)
                        .setEndDelay(Math.max(0f, 1f - (start + 0.45f)))
                        .setInterpolator(KIRO_BACK)
                        .build());
                final TouchAnimator.Builder alpha = mKiroRowAlphas.get(row);
                if (alpha != null) {
                    mKiroRowAnimators.add(alpha
                            .setStartDelay(start)
                            .setEndDelay(Math.max(0f, 1f - (start + 0.28f)))
                            .setInterpolator(KIRO_WAVE)
                            .build());
                }
            }
            mAllViews.add(mQsPanel.getFooter().getView());
            float px = 0;
            float py = 1;
            if (tiles.size() <= 3) {
                px = 1;
            } else if (tiles.size() <= 6) {
                px = .4f;
            }
            PathInterpolatorBuilder interpolatorBuilder = new PathInterpolatorBuilder(0, 0, px, py);
            translationXBuilder.setInterpolator(interpolatorBuilder.getXInterpolator());
            translationYBuilder.setInterpolator(interpolatorBuilder.getYInterpolator());
            mTranslationXAnimator = translationXBuilder.build();
            mTranslationYAnimator = translationYBuilder.build();
        }
        mNonfirstPageAnimator = new TouchAnimator.Builder()
                .addFloat(mQuickQsPanel, "alpha", 1, 0)
                .setListener(mNonFirstPageListener)
                .setEndDelay(.5f)
                .build();
    }

    /** KiroOS: the wave's motion (scale, drop) for the grid row of tile #index */
    private TouchAnimator.Builder kiroRow(int index) {
        return kiroRowBuilder(mKiroRowBuilders, index);
    }

    /** KiroOS: the wave's fade for the grid row of tile #index (quicker than the motion) */
    private TouchAnimator.Builder kiroRowAlpha(int index) {
        return kiroRowBuilder(mKiroRowAlphas, index);
    }

    private TouchAnimator.Builder kiroRowBuilder(
            android.util.SparseArray<TouchAnimator.Builder> rows, int index) {
        final int columns = mPagedLayout != null ? Math.max(1, mPagedLayout.getColumnCount()) : 3;
        final int row = index / columns;
        if (row < mKiroFirstRow) {
            mKiroFirstRow = row;
        }
        TouchAnimator.Builder b = rows.get(row);
        if (b == null) {
            b = new TouchAnimator.Builder();
            rows.put(row, b);
        }
        return b;
    }

    private boolean isIconInAnimatedRow(int count) {
        if (mPagedLayout == null) {
            return false;
        }
        final int columnCount = mPagedLayout.getColumnCount();
        return count < ((mNumQuickTiles + columnCount - 1) / columnCount) * columnCount;
    }

    private void getRelativePosition(int[] loc1, View view, View parent) {
        loc1[0] = 0 + view.getWidth() / 2;
        loc1[1] = 0;
        getRelativePositionInt(loc1, view, parent);
    }

    private void getRelativePositionInt(int[] loc1, View view, View parent) {
        if(view == parent || view == null) return;
        // Ignore tile pages as they can have some offset we don't want to take into account in
        // RTL.
        if (!(view instanceof PagedTileLayout.TilePage)) {
            loc1[0] += view.getLeft();
            loc1[1] += view.getTop();
        }
        getRelativePositionInt(loc1, (View) view.getParent(), parent);
    }

    public void setPosition(float position) {
        if (mFirstPageAnimator == null) return;
        if (mOnKeyguard) {
            return;
        }
        mLastPosition = position;
        if (mOnFirstPage && mAllowFancy) {
            mQuickQsPanel.setAlpha(1);
            mFirstPageAnimator.setPosition(position);
            mFirstPageDelayedAnimator.setPosition(position);
            mTranslationXAnimator.setPosition(position);
            mTranslationYAnimator.setPosition(position);
            if (mBrightnessAnimator != null) {
                mBrightnessAnimator.setPosition(position);
            }
            for (int i = 0; i < mKiroRowAnimators.size(); i++) {
                mKiroRowAnimators.get(i).setPosition(position);
            }
        } else {
            mNonfirstPageAnimator.setPosition(position);
        }
    }

    @Override
    public void onAnimationAtStart() {
        mQuickQsPanel.setVisibility(View.VISIBLE);
        kiroLayers(false);
    }

    @Override
    public void onAnimationAtEnd() {
        mQuickQsPanel.setVisibility(View.INVISIBLE);
        final int N = mTopFiveQs.size();
        for (int i = 0; i < N; i++) {
            mTopFiveQs.get(i).setVisibility(View.VISIBLE);
        }
        kiroLayers(false);
    }

    @Override
    public void onAnimationStarted() {
        mQuickQsPanel.setVisibility(mOnKeyguard ? View.INVISIBLE : View.VISIBLE);
        if (mOnFirstPage) {
            final int N = mTopFiveQs.size();
            for (int i = 0; i < N; i++) {
                mTopFiveQs.get(i).setVisibility(View.INVISIBLE);
            }
            kiroLayers(mAllowFancy);
        }
    }

    /**
     * KiroOS: while the panel opens, every moving tile / row is a hardware layer, drawn once and
     * then only moved and faded by the GPU (at 1440 x 2560 a see-through tile was redrawn off
     * screen in every frame); plain views again once the panel is fully open or closed.
     */
    private void kiroLayers(boolean on) {
        if (mKiroLayersOn == on) {
            return;
        }
        mKiroLayersOn = on;
        final int type = on ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        for (int i = 0; i < mKiroLayerViews.size(); i++) {
            mKiroLayerViews.get(i).setLayerType(type, null);
        }
    }

    private void clearAnimationState() {
        kiroLayers(false);
        final int N = mAllViews.size();
        mQuickQsPanel.setAlpha(0);
        for (int i = 0; i < N; i++) {
            View v = mAllViews.get(i);
            v.setAlpha(1);
            v.setTranslationX(0);
            v.setTranslationY(0);
            v.setScaleX(1);
            v.setScaleY(1);
        }
        final int N2 = mTopFiveQs.size();
        for (int i = 0; i < N2; i++) {
            mTopFiveQs.get(i).setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        mQsPanel.post(mUpdateAnimators);
    }

    @Override
    public void onTilesChanged() {
        // Give the QS panels a moment to generate their new tiles, then create all new animators
        // hooked up to the new views.
        mQsPanel.post(mUpdateAnimators);
    }

    private final TouchAnimator.Listener mNonFirstPageListener =
            new TouchAnimator.ListenerAdapter() {
                @Override
                public void onAnimationAtEnd() {
                    mQuickQsPanel.setVisibility(View.INVISIBLE);
                }

                @Override
                public void onAnimationStarted() {
                    mQuickQsPanel.setVisibility(View.VISIBLE);
                }
            };

    private Runnable mUpdateAnimators = new Runnable() {
        @Override
        public void run() {
            updateAnimators();
            setPosition(mLastPosition);
        }
    };
}
