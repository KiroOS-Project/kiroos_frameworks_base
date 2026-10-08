/*
 * Copyright (C) 2016 The Android Open Source Project
 * Copyright (C) 2026 KiroOS
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

package com.android.systemui.qs.customize;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.util.DiffUtil;
import android.support.v7.widget.GridLayoutManager.SpanSizeLookup;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto;
import com.android.systemui.R;
import com.android.systemui.qs.QSIconView;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.customize.TileQueryHelper.TileInfo;
import com.android.systemui.qs.customize.TileQueryHelper.TileStateListener;
import com.android.systemui.statusbar.phone.QSTileHost;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * KiroOS control center editor. The top part is the control center as it looks: Wi-Fi and
 * Bluetooth as the wide row (always at the top), then the grid of round tiles in pages of 9.
 * Below, the other controls in a list. A control is removed with its red − badge, added with
 * the + of its row, and grid tiles are moved by touch and hold. Every change is saved at once
 * (QSTileHost.changeTiles), so the control center behind the editor is always current.
 */
public class TileAdapter extends RecyclerView.Adapter<TileAdapter.Holder>
        implements TileStateListener {

    public static final long MOVE_DURATION = 200;
    /** span units of the grid: a tile takes 2 (3 a row), a wide tile 3 (6 alone), a row 6 */
    public static final int SPANS = 6;
    /** a control center page: 3 columns, 3 rows (PagedTileLayout in portrait) */
    private static final int COLUMNS = 3;
    private static final int PAGE = COLUMNS * 3;

    private static final int TYPE_SECTION = 0;
    private static final int TYPE_WIDE = 1;
    private static final int TYPE_TILE = 2;
    private static final int TYPE_MORE = 3;
    private static final int TYPE_MESSAGE = 4;

    private static final long ID_INCLUDED = -1;
    private static final long ID_MORE = -2;
    private static final long ID_EMPTY = -3;
    private static final long ID_ALL_ADDED = -4;

    /** the wide row, in KiroConnectivityRow's order */
    private static final String[] WIDE = { "wifi", "bt" };

    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.1f, 1f);

    private final Context mContext;
    private final Handler mHandler = new Handler();
    private final ItemTouchHelper mItemTouchHelper;
    private final RecyclerView.ItemDecoration mPages;
    private QSTileHost mHost;

    /** the control center, in order: every spec, also the ones this editor doesn't know */
    private final ArrayList<String> mSpecs = new ArrayList<>();
    private List<String> mSaved;
    /** every control that can be shown, by spec, in the order the query found them */
    private final LinkedHashMap<String, TileInfo> mInfos = new LinkedHashMap<>();

    private final ArrayList<Item> mItems = new ArrayList<>();
    private int mWideCount;
    private int mGridCount;
    private boolean mDragging;
    private boolean mRebuildAfterDrag;
    /** while the editor opens, the controls found by the query appear without animations */
    private boolean mOpening;

    /** one row of the editor's list */
    private static final class Item {
        final int type;
        final long id;
        final String spec;
        /** grid tiles: the place in the grid; rows: the card's rounded ends */
        int index;
        boolean first;
        boolean last;

        Item(int type, String spec) {
            this.type = type;
            this.spec = spec;
            this.id = ((long) type << 40) ^ (spec.hashCode() & 0xffffffffL);
        }

        Item(int type, long id) {
            this.type = type;
            this.spec = null;
            this.id = id;
        }

        boolean sameContent(Item o) {
            return type == o.type && index == o.index && first == o.first && last == o.last;
        }
    }

    public TileAdapter(Context context) {
        mContext = context;
        mItemTouchHelper = new ItemTouchHelper(mCallbacks);
        mPages = new PageMarks(context);
        setHasStableIds(true);
    }

    public void setHost(QSTileHost host) {
        mHost = host;
    }

    public ItemTouchHelper getItemTouchHelper() {
        return mItemTouchHelper;
    }

    public RecyclerView.ItemDecoration getItemDecoration() {
        return mPages;
    }

    public SpanSizeLookup getSizeLookup() {
        return mSizeLookup;
    }

    /** the editor opens: the control center's tiles; the other controls arrive by the query */
    public void setTileSpecs(List<String> currentSpecs) {
        mSpecs.clear();
        mSpecs.addAll(currentSpecs);
        mSaved = new ArrayList<>(currentSpecs);
        mInfos.clear();
        mDragging = false;
        mRebuildAfterDrag = false;
        mOpening = true;
        rebuild(false);
    }

    /** the editor is open (its reveal ended): changes animate from now on */
    public void onShown() {
        mOpening = false;
    }

    /** the default controls in their original order (QSCustomizer's Reset) */
    public void resetTo(List<String> specs) {
        mSpecs.clear();
        mSpecs.addAll(specs);
        save();
        rebuild(true);
    }

    public void saveSpecs(QSTileHost host) {
        mHost = host;
        save();
    }

    private void save() {
        if (mHost == null || mSaved == null || mSaved.equals(mSpecs)) return;
        final List<String> now = new ArrayList<>(mSpecs);
        mHost.changeTiles(mSaved, now);
        mSaved = now;
    }

    @Override
    public void onTilesChanged(List<TileInfo> tiles) {
        boolean added = false;
        for (TileInfo t : tiles) {
            if (mInfos.containsKey(t.spec)) continue;
            // the editor shows every control alike, not on (blue) or off as it is now
            if (t.state instanceof QSTile.BooleanState) {
                ((QSTile.BooleanState) t.state).value = false;
            }
            mInfos.put(t.spec, t);
            added = true;
        }
        if (!added) return;
        if (mDragging) {
            mRebuildAfterDrag = true;
        } else {
            rebuild(!mOpening);
        }
    }

    private static boolean isWide(String spec) {
        return WIDE[0].equals(spec) || WIDE[1].equals(spec);
    }

    /** the controls not in the control center: Android's first, then the apps' by name */
    private List<TileInfo> more() {
        final ArrayList<TileInfo> system = new ArrayList<>();
        final ArrayList<TileInfo> apps = new ArrayList<>();
        for (TileInfo t : mInfos.values()) {
            if (mSpecs.contains(t.spec)) continue;
            (t.isSystem ? system : apps).add(t);
        }
        final Collator c = Collator.getInstance();
        Collections.sort(apps, new Comparator<TileInfo>() {
            @Override
            public int compare(TileInfo a, TileInfo b) {
                return c.compare(String.valueOf(a.state.label), String.valueOf(b.state.label));
            }
        });
        system.addAll(apps);
        return system;
    }

    private void rebuild(boolean animate) {
        final ArrayList<Item> items = new ArrayList<>();
        items.add(new Item(TYPE_SECTION, ID_INCLUDED));
        mWideCount = 0;
        for (String w : WIDE) {
            if (mSpecs.contains(w) && mInfos.containsKey(w)) {
                items.add(new Item(TYPE_WIDE, w));
                mWideCount++;
            }
        }
        mGridCount = 0;
        for (String s : mSpecs) {
            if (isWide(s) || !mInfos.containsKey(s)) continue;
            final Item it = new Item(TYPE_TILE, s);
            it.index = mGridCount++;
            items.add(it);
        }
        if (mWideCount + mGridCount == 0 && !mInfos.isEmpty()) {
            items.add(new Item(TYPE_MESSAGE, ID_EMPTY));
        }
        items.add(new Item(TYPE_SECTION, ID_MORE));
        final List<TileInfo> more = more();
        for (int i = 0; i < more.size(); i++) {
            final Item it = new Item(TYPE_MORE, more.get(i).spec);
            it.first = i == 0;
            it.last = i == more.size() - 1;
            items.add(it);
        }
        if (more.isEmpty() && !mInfos.isEmpty()) {
            items.add(new Item(TYPE_MESSAGE, ID_ALL_ADDED));
        }
        if (!animate) {
            mItems.clear();
            mItems.addAll(items);
            notifyDataSetChanged();
            return;
        }
        final ArrayList<Item> old = new ArrayList<>(mItems);
        final DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return items.size();
            }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                return old.get(o).id == items.get(n).id;
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                return old.get(o).sameContent(items.get(n));
            }
        });
        mItems.clear();
        mItems.addAll(items);
        diff.dispatchUpdatesTo(this);
    }

    // ---- changes

    private void add(String spec, View from) {
        if (mSpecs.contains(spec)) return;
        mSpecs.add(spec);
        MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_QS_EDIT_ADD_SPEC, spec);
        save();
        rebuild(true);
        from.announceForAccessibility(mContext.getString(R.string.kiroos_edit_added,
                label(spec)));
    }

    private void remove(String spec, View from) {
        if (!mSpecs.remove(spec)) return;
        MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_QS_EDIT_REMOVE_SPEC,
                spec);
        save();
        rebuild(true);
        from.announceForAccessibility(mContext.getString(R.string.kiroos_edit_removed,
                label(spec)));
    }

    /** the grid in a new order: its places in mSpecs keep the wide and unknown specs where they are */
    private void applyGridOrder(List<String> grid) {
        int g = 0;
        for (int i = 0; i < mSpecs.size() && g < grid.size(); i++) {
            final String s = mSpecs.get(i);
            if (isWide(s) || !mInfos.containsKey(s)) continue;
            mSpecs.set(i, grid.get(g++));
        }
    }

    private List<String> gridSpecs() {
        final ArrayList<String> grid = new ArrayList<>();
        for (Item it : mItems) {
            if (it.type == TYPE_TILE) grid.add(it.spec);
        }
        return grid;
    }

    /** accessibility: one place earlier or later */
    private void moveBy(String spec, int delta, View from) {
        final List<String> grid = gridSpecs();
        final int i = grid.indexOf(spec);
        final int j = i + delta;
        if (i < 0 || j < 0 || j >= grid.size()) return;
        grid.add(j, grid.remove(i));
        applyGridOrder(grid);
        MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_QS_EDIT_MOVE_SPEC, spec);
        save();
        rebuild(true);
        from.announceForAccessibility(mContext.getString(R.string.kiroos_edit_position,
                label(spec), j + 1));
    }

    private CharSequence label(String spec) {
        final TileInfo t = mInfos.get(spec);
        return t != null && t.state.label != null ? t.state.label : spec;
    }

    // ---- views

    @Override
    public long getItemId(int position) {
        return mItems.get(position).id;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        return mItems.get(position).type;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        final Context context = parent.getContext();
        final LayoutInflater inflater = LayoutInflater.from(context);
        switch (viewType) {
            case TYPE_SECTION:
                return new Holder(inflater.inflate(R.layout.kiroos_edit_section, parent, false));
            case TYPE_MESSAGE:
                return new Holder(inflater.inflate(R.layout.kiroos_edit_message, parent, false));
            case TYPE_MORE:
                return new Holder(inflater.inflate(R.layout.kiroos_edit_more_row, parent, false));
            case TYPE_WIDE: {
                final EditTileFrame frame = new EditTileFrame(context, false);
                final int side = dp(5);
                frame.setPadding(side, dp(12), side, dp(5));
                final View wide = inflater.inflate(R.layout.kiroos_qs_wide_tile, frame, false);
                wide.setVisibility(View.VISIBLE);
                wide.findViewById(R.id.kiroos_wide_chevron).setVisibility(View.GONE);
                frame.addView(wide);
                frame.setShape(wide);
                return new Holder(frame);
            }
            default: {
                final EditTileFrame frame = new EditTileFrame(context, true);
                frame.setPadding(0, dp(8), 0, dp(4));
                final CustomizeTileView tile = new CustomizeTileView(context,
                        new QSIconView(context));
                tile.setBackground(null);
                tile.getIcon().disableAnimation();
                tile.setShowAppLabel(false);
                frame.addView(tile, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                frame.setShape(tile.getIcon().getIconView());
                frame.mIconView = tile.getIcon();
                return new Holder(frame);
            }
        }
    }

    @Override
    public void onBindViewHolder(final Holder holder, int position) {
        final Item item = mItems.get(position);
        switch (item.type) {
            case TYPE_SECTION: {
                final boolean included = item.id == ID_INCLUDED;
                holder.mTitle.setText(included ? R.string.kiroos_edit_included
                        : R.string.kiroos_edit_more);
                holder.mHint.setText(included ? R.string.kiroos_edit_included_hint
                        : R.string.kiroos_edit_more_hint);
                return;
            }
            case TYPE_MESSAGE:
                holder.mTitle.setText(item.id == ID_EMPTY ? R.string.kiroos_edit_empty
                        : R.string.kiroos_edit_all_added);
                return;
            case TYPE_MORE:
                bindRow(holder, item);
                return;
            case TYPE_WIDE:
                bindWide(holder, item);
                return;
            default:
                bindTile(holder, item);
        }
    }

    private void bindTile(final Holder holder, final Item item) {
        final TileInfo info = mInfos.get(item.spec);
        final EditTileFrame frame = (EditTileFrame) holder.itemView;
        final CustomizeTileView tile = (CustomizeTileView) frame.getChildAt(0);
        info.state.contentDescription = mContext.getString(R.string.kiroos_edit_position,
                info.state.label, item.index + 1);
        tile.onStateChanged(info.state);
        tile.setShowAppLabel(false);
        tile.setClickable(false);
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        frame.setContentDescription(info.state.contentDescription);
        frame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        bindBadge(frame, item.spec, info);
        frame.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host,
                    AccessibilityNodeInfo node) {
                super.onInitializeAccessibilityNodeInfo(host, node);
                node.addAction(new AccessibilityAction(R.id.kiroos_edit_action_remove,
                        mContext.getString(R.string.kiroos_edit_remove, info.state.label)));
                if (item.index > 0) {
                    node.addAction(new AccessibilityAction(R.id.kiroos_edit_action_earlier,
                            mContext.getString(R.string.kiroos_edit_move_earlier)));
                }
                if (item.index < mGridCount - 1) {
                    node.addAction(new AccessibilityAction(R.id.kiroos_edit_action_later,
                            mContext.getString(R.string.kiroos_edit_move_later)));
                }
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == R.id.kiroos_edit_action_remove) {
                    remove(item.spec, host);
                } else if (action == R.id.kiroos_edit_action_earlier) {
                    moveBy(item.spec, -1, host);
                } else if (action == R.id.kiroos_edit_action_later) {
                    moveBy(item.spec, 1, host);
                } else {
                    return super.performAccessibilityAction(host, action, args);
                }
                return true;
            }
        });
    }

    private void bindWide(Holder holder, Item item) {
        final TileInfo info = mInfos.get(item.spec);
        final EditTileFrame frame = (EditTileFrame) holder.itemView;
        final View wide = frame.getChildAt(0);
        final ImageView icon = (ImageView) wide.findViewById(R.id.kiroos_wide_icon);
        icon.setImageDrawable(info.state.icon != null
                ? info.state.icon.getInvisibleDrawable(mContext) : null);
        ((TextView) wide.findViewById(R.id.kiroos_wide_title)).setText(info.state.label);
        ((TextView) wide.findViewById(R.id.kiroos_wide_subtitle)).setText(
                R.string.kiroos_edit_top_row);
        wide.setContentDescription(info.state.label + ", "
                + mContext.getString(R.string.kiroos_edit_top_row));
        bindBadge(frame, item.spec, info);
    }

    private void bindBadge(final EditTileFrame frame, final String spec, TileInfo info) {
        frame.mBadge.setContentDescription(mContext.getString(R.string.kiroos_edit_remove,
                info.state.label));
        frame.mBadge.setAlpha(1f);
        frame.mBadge.setScaleX(1f);
        frame.mBadge.setScaleY(1f);
        frame.mBadge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                remove(spec, v);
            }
        });
    }

    private void bindRow(Holder holder, Item item) {
        final TileInfo info = mInfos.get(item.spec);
        final float r = dp(16);
        final GradientDrawable card = new GradientDrawable();
        card.setColor(mContext.getColor(R.color.kiroos_detail_row));
        card.setCornerRadii(new float[] {
                item.first ? r : 0, item.first ? r : 0, item.first ? r : 0, item.first ? r : 0,
                item.last ? r : 0, item.last ? r : 0, item.last ? r : 0, item.last ? r : 0 });
        holder.mRow.setBackground(new RippleDrawable(ColorStateList.valueOf(
                mContext.getColor(R.color.qs_tile_bg_pressed)), card, card));
        final GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(mContext.getColor(R.color.kiroos_detail_icon_bg));
        holder.mIcon.setBackground(disc);
        final Drawable d = info.state.icon != null
                ? info.state.icon.getInvisibleDrawable(mContext) : null;
        holder.mIcon.setImageDrawable(d);
        holder.mIcon.setColorFilter(mContext.getColor(R.color.qs_tile_tint_inactive),
                PorterDuff.Mode.SRC_IN);
        holder.mTitle.setText(info.state.label);
        final boolean app = !info.isSystem && info.appLabel != null
                && !info.appLabel.toString().contentEquals(String.valueOf(info.state.label));
        holder.mHint.setVisibility(app ? View.VISIBLE : View.GONE);
        holder.mHint.setText(app ? info.appLabel : null);
        holder.mDivider.setVisibility(item.last ? View.GONE : View.VISIBLE);
        final String spec = item.spec;
        holder.mRow.setContentDescription(mContext.getString(R.string.kiroos_edit_add,
                info.state.label));
        holder.mRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                add(spec, v);
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * mContext.getResources().getDisplayMetrics().density);
    }

    public class Holder extends ViewHolder {
        TextView mTitle;
        TextView mHint;
        View mRow;
        ImageView mIcon;
        View mDivider;

        public Holder(View itemView) {
            super(itemView);
            mTitle = (TextView) itemView.findViewById(R.id.kiroos_edit_title);
            mHint = (TextView) itemView.findViewById(R.id.kiroos_edit_hint);
            mRow = itemView.findViewById(R.id.kiroos_edit_row);
            mIcon = (ImageView) itemView.findViewById(R.id.kiroos_edit_icon);
            mDivider = itemView.findViewById(R.id.kiroos_edit_divider);
        }

        void lift() {
            itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            itemView.animate().scaleX(1.12f).scaleY(1.12f).setDuration(160)
                    .setInterpolator(EASE).start();
            if (itemView instanceof EditTileFrame) {
                ((EditTileFrame) itemView).mBadge.animate().alpha(0f).setDuration(100).start();
            }
        }

        void drop() {
            itemView.animate().scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(new OvershootInterpolator(1.6f)).start();
            if (itemView instanceof EditTileFrame) {
                ((EditTileFrame) itemView).mBadge.animate().alpha(1f).setDuration(160).start();
            }
        }
    }

    /**
     * A tile with its red − badge: on the disc's upper right (grid tiles), or on the corner of
     * the wide tile. The badge's touch area is larger than the dot.
     */
    static final class EditTileFrame extends FrameLayout {
        final ImageView mBadge;
        final boolean mRound;
        QSIconView mIconView;
        private View mShape;
        private final Rect mRect = new Rect();

        EditTileFrame(Context context, boolean round) {
            super(context);
            mRound = round;
            setClipChildren(false);
            setClipToPadding(false);
            mBadge = new ImageView(context);
            mBadge.setImageResource(R.drawable.kiroos_edit_remove);
            mBadge.setScaleType(ImageView.ScaleType.CENTER);
            final int touch = Math.round(40 * context.getResources().getDisplayMetrics().density);
            addView(mBadge, new FrameLayout.LayoutParams(touch, touch));
        }

        void setShape(View shape) {
            mShape = shape;
            // the badge over the tile
            mBadge.bringToFront();
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (mShape == null || mShape.getWidth() == 0) return;
            mRect.set(0, 0, mShape.getWidth(), mShape.getHeight());
            offsetDescendantRectToMyCoords(mShape, mRect);
            final float cx;
            final float cy;
            if (mRound && mIconView != null) {
                // QSIconView draws its disc centered on the icon
                final float radius = mIconView.getBackgroundRadius();
                cx = mRect.exactCenterX() + radius * 0.71f;
                cy = mRect.exactCenterY() - radius * 0.71f;
            } else {
                final float corner = getResources().getDimension(R.dimen.kiroos_qs_wide_radius);
                cx = mRect.right - corner * 0.29f;
                cy = mRect.top + corner * 0.29f;
            }
            final int w = mBadge.getMeasuredWidth();
            final int h = mBadge.getMeasuredHeight();
            final int left = Math.round(cx - w / 2f);
            final int top = Math.round(cy - h / 2f);
            mBadge.layout(left, top, left + w, top + h);
        }
    }

    /** "Page 2", "Page 3"...: a label and a line over the first row of every page after the first */
    private final class PageMarks extends RecyclerView.ItemDecoration {
        private final int mGap;
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLine = new Paint();
        private final float mSide;

        PageMarks(Context context) {
            final float density = context.getResources().getDisplayMetrics().density;
            mGap = Math.round(30 * density);
            mSide = 16 * density;
            mText.setColor(context.getColor(R.color.qs_subhead));
            mText.setTextSize(12 * context.getResources().getDisplayMetrics().scaledDensity);
            mText.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
            mLine.setColor(context.getColor(R.color.qs_tile_divider));
            mLine.setStrokeWidth(Math.max(1f, density));
        }

        private Item tileAt(RecyclerView parent, View child) {
            final int pos = parent.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION || pos >= mItems.size()) return null;
            final Item it = mItems.get(pos);
            return it.type == TYPE_TILE ? it : null;
        }

        @Override
        public void getItemOffsets(Rect out, View view, RecyclerView parent,
                RecyclerView.State state) {
            final Item it = tileAt(parent, view);
            if (it != null && it.index >= PAGE && it.index % PAGE < COLUMNS) {
                out.top = mGap;
            }
        }

        @Override
        public void onDraw(Canvas c, RecyclerView parent, RecyclerView.State state) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                final View child = parent.getChildAt(i);
                final Item it = tileAt(parent, child);
                if (it == null || it.index < PAGE || it.index % PAGE != 0) continue;
                final float y = child.getTop() - mGap / 2f;
                final String label = mContext.getString(R.string.kiroos_edit_page,
                        it.index / PAGE + 1);
                final float textW = mText.measureText(label);
                final float baseline = y - (mText.ascent() + mText.descent()) / 2f;
                final float start = parent.getPaddingLeft() + mSide;
                final float end = parent.getWidth() - parent.getPaddingRight() - mSide;
                c.drawText(label, start, baseline, mText);
                c.drawLine(start + textW + mSide / 2f, y, end, y, mLine);
            }
        }
    }

    private final SpanSizeLookup mSizeLookup = new SpanSizeLookup() {
        @Override
        public int getSpanSize(int position) {
            if (position < 0 || position >= mItems.size()) return SPANS;
            switch (mItems.get(position).type) {
                case TYPE_TILE:
                    return SPANS / COLUMNS;
                case TYPE_WIDE:
                    return mWideCount == 1 ? SPANS : SPANS / 2;
                default:
                    return SPANS;
            }
        }
    };

    private final ItemTouchHelper.Callback mCallbacks = new ItemTouchHelper.Callback() {

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return false;
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, ViewHolder viewHolder) {
            if (viewHolder.getItemViewType() != TYPE_TILE) {
                return makeMovementFlags(0, 0);
            }
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN
                    | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0);
        }

        @Override
        public boolean canDropOver(RecyclerView recyclerView, ViewHolder current,
                ViewHolder target) {
            return target.getItemViewType() == TYPE_TILE;
        }

        @Override
        public boolean onMove(final RecyclerView recyclerView, ViewHolder viewHolder,
                ViewHolder target) {
            final int from = viewHolder.getAdapterPosition();
            final int to = target.getAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false;
            mItems.add(to, mItems.remove(from));
            // the places follow the finger, so the page marks do too
            int index = 0;
            for (Item it : mItems) {
                if (it.type == TYPE_TILE) it.index = index++;
            }
            notifyItemMoved(from, to);
            recyclerView.post(new Runnable() {
                @Override
                public void run() {
                    if (!recyclerView.isComputingLayout()) {
                        recyclerView.invalidateItemDecorations();
                    }
                }
            });
            return true;
        }

        @Override
        public void onSelectedChanged(ViewHolder viewHolder, int actionState) {
            super.onSelectedChanged(viewHolder, actionState);
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                mDragging = true;
                ((Holder) viewHolder).lift();
            }
        }

        @Override
        public void clearView(RecyclerView recyclerView, ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            ((Holder) viewHolder).drop();
            if (!mDragging) return;
            mDragging = false;
            // the drop: the new order is saved, the places and page marks are updated
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    applyGridOrder(gridSpecs());
                    save();
                    mRebuildAfterDrag = false;
                    rebuild(true);
                }
            });
        }

        @Override
        public void onSwiped(ViewHolder viewHolder, int direction) {
        }
    };
}
