package com.liskovsoft.smartyoutubetv2.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Draws non-interactive SponsorBlock ranges beneath the native seek thumb. */
public final class SponsorMarkerView extends View {
    static final class Marker {
        final long startMs;
        final long endMs;
        final int color;

        Marker(long startMs, long endMs, int color) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.color = color;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Marker> markers = Collections.emptyList();
    private long durationMs;

    public SponsorMarkerView(Context context) {
        this(context, null);
    }

    public SponsorMarkerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setMarkers(List<Marker> markers, long durationMs) {
        this.markers = markers == null ? Collections.emptyList() : new ArrayList<>(markers);
        this.durationMs = durationMs;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (durationMs <= 0 || markers.isEmpty()) return;
        float inset = dp(16);
        float width = Math.max(1f, getWidth() - inset * 2f);
        float centerY = getHeight() / 2f;
        float halfHeight = dp(4);
        for (Marker marker : markers) {
            float start = inset + width * clamp(marker.startMs / (float) durationMs);
            float end = inset + width * clamp(marker.endMs / (float) durationMs);
            paint.setColor(marker.color);
            canvas.drawRoundRect(start, centerY - halfHeight, Math.max(start + dp(2), end),
                    centerY + halfHeight, dp(2), dp(2), paint);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
