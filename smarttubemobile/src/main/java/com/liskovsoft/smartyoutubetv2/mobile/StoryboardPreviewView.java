package com.liskovsoft.smartyoutubetv2.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

/** Draws one cell from a SmartTube storyboard sprite without allocating cropped bitmaps. */
public final class StoryboardPreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final Rect destination = new Rect();
    private Bitmap storyboard;
    private int frame;
    private int rows = 1;
    private int columns = 1;

    public StoryboardPreviewView(Context context) {
        super(context);
    }

    public StoryboardPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StoryboardPreviewView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    void setStoryboardFrame(Bitmap bitmap, int frameIndex, int rowCount, int columnCount) {
        storyboard = bitmap;
        rows = Math.max(1, rowCount);
        columns = Math.max(1, columnCount);
        frame = Math.max(0, Math.min(frameIndex, rows * columns - 1));
        invalidate();
    }

    void clearStoryboard() {
        storyboard = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (storyboard == null || storyboard.isRecycled()) return;
        int cellWidth = storyboard.getWidth() / columns;
        int cellHeight = storyboard.getHeight() / rows;
        int column = frame % columns;
        int row = frame / columns;
        source.set(column * cellWidth, row * cellHeight,
                (column + 1) * cellWidth, (row + 1) * cellHeight);
        destination.set(getPaddingLeft(), getPaddingTop(),
                getWidth() - getPaddingRight(), getHeight() - getPaddingBottom());
        canvas.drawBitmap(storyboard, source, destination, paint);
    }
}
