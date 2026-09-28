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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

import com.android.systemui.R;

/**
 * The brightness pill of the control centre: a track and a fill with fully round ends. The
 * fill is never narrower than it is tall, so at the lowest brightness it is a circle around
 * the sun and it grows from there to the whole track; a scaled shape squeezed its round end
 * into a flat oval at the start of the bar.
 */
public class KiroBrightnessDrawable extends Drawable {

    /** track + fill, with an empty secondary progress so the fill keeps its own level */
    public static Drawable create(Context context) {
        final LayerDrawable d = new LayerDrawable(new Drawable[] {
                new KiroBrightnessDrawable(context.getColor(R.color.kiroos_bright_track), true),
                new KiroBrightnessDrawable(context.getColor(R.color.kiroos_bright_fill), false),
                new ColorDrawable(0),
        });
        d.setId(0, android.R.id.background);
        d.setId(1, android.R.id.progress);
        d.setId(2, android.R.id.secondaryProgress);
        return d;
    }

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final boolean mTrack;

    private KiroBrightnessDrawable(int color, boolean track) {
        mPaint.setColor(color);
        mTrack = track;
    }

    @Override
    public void draw(Canvas canvas) {
        final Rect b = getBounds();
        final float h = b.height();
        if (h <= 0 || b.width() <= 0) {
            return;
        }
        final float r = h / 2f;
        float w = b.width();
        if (!mTrack) {
            final float min = Math.min(h, w);
            w = min + (w - min) * (getLevel() / 10000f);
        }
        mRect.set(b.left, b.top, b.left + w, b.bottom);
        canvas.drawRoundRect(mRect, r, r, mPaint);
    }

    @Override
    protected boolean onLevelChange(int level) {
        invalidateSelf();
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
