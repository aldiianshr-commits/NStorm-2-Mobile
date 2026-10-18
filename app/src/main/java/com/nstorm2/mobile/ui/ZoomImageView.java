package com.nstorm2.mobile.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Bitmap view with pinch-zoom, drag and a transparency checkerboard. */
public class ZoomImageView extends View {
    private Bitmap bitmap;
    private final Matrix matrix = new Matrix();
    private final Paint bitmapPaint = new Paint();
    private final Paint checker = new Paint();
    private float scale = 1f, tx, ty;
    private boolean fitted;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;

    public ZoomImageView(Context c, AttributeSet a) {
        super(c, a);
        Bitmap tile = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                tile.setPixel(x, y, ((x < 8) ^ (y < 8)) ? Color.rgb(54, 60, 82) : Color.rgb(38, 43, 62));
        checker.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        bitmapPaint.setFilterBitmap(false);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float f = d.getScaleFactor();
                float ns = Math.max(0.05f, Math.min(64f, scale * f));
                f = ns / scale;
                tx = d.getFocusX() - (d.getFocusX() - tx) * f;
                ty = d.getFocusY() - (d.getFocusY() - ty) * f;
                scale = ns;
                invalidate();
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                tx -= dx;
                ty -= dy;
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                fit();
                invalidate();
                return true;
            }
        });
    }

    public void setBitmap(Bitmap b) {
        bitmap = b;
        fitted = false;
        invalidate();
    }

    private void fit() {
        if (bitmap == null || getWidth() == 0) return;
        scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        tx = (getWidth() - bitmap.getWidth() * scale) / 2f;
        ty = (getHeight() - bitmap.getHeight() * scale) / 2f;
        fitted = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null) return;
        if (!fitted) fit();
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate(tx, ty);
        RectF r = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        matrix.mapRect(r);
        canvas.drawRect(r, checker);
        canvas.drawBitmap(bitmap, matrix, bitmapPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }
}
