package org.telegram.messenger.family;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** PDF page in document coordinates: pinch/pan stays stable during high-resolution rerender. */
public final class FamilyPdfView extends View {
    public interface Listener { void zoom(float value, boolean settled); void page(int direction); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector scale;
    private final GestureDetector gestures;
    private Bitmap bitmap;
    private float width = 1, height = 1, zoom = 1, x, y, lastX, lastY;
    private Listener listener;
    private boolean dragging;
    public FamilyPdfView(Context context) {
        super(context); setFocusable(true);
        scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { getParent().requestDisallowInterceptTouchEvent(true); return true; }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                at(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY(), false); return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector detector) { changed(true); }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onDoubleTap(MotionEvent event) { at(zoom > 1.15f ? 1 : 2.5f, event.getX(), event.getY(), true); return true; }
            @Override public boolean onFling(MotionEvent start, MotionEvent end, float vx, float vy) {
                if (start != null && zoom <= 1.02f && Math.abs(end.getX() - start.getX()) > 80 * getResources().getDisplayMetrics().density && Math.abs(vx) > Math.abs(vy) * 1.5f) {
                    if (listener != null) listener.page(vx < 0 ? 1 : -1); return true;
                }
                return false;
            }
        });
    }
    public void setListener(Listener value) { listener = value; }
    public float getZoom() { return zoom; }
    public void setPage(Bitmap value, int pageWidth, int pageHeight, boolean reset) {
        bitmap = value; width = pageWidth; height = pageHeight;
        if (reset) { zoom = 1; center(); } else clamp();
        invalidate(); changed(false);
    }
    public void zoomIn() { at(zoom * 1.4f, getWidth() / 2f, getHeight() / 2f, true); }
    public void zoomOut() { at(zoom / 1.4f, getWidth() / 2f, getHeight() / 2f, true); }
    public void fitPage() { zoom = 1; center(); invalidate(); changed(true); }
    public void fitWidth() { at(Math.max(1, getWidth() / width / base()), getWidth() / 2f, getHeight() / 2f, true); }
    private float base() { return Math.max(.001f, Math.min(getWidth() / width, getHeight() / height)); }
    private void center() { x = (getWidth() - width * base() * zoom) / 2; y = (getHeight() - height * base() * zoom) / 2; }
    private void clamp() {
        float w = width * base() * zoom, h = height * base() * zoom;
        x = w <= getWidth() ? (getWidth() - w) / 2 : Math.max(getWidth() - w, Math.min(0, x));
        y = h <= getHeight() ? (getHeight() - h) / 2 : Math.max(getHeight() - h, Math.min(0, y));
    }
    private void at(float target, float focusX, float focusY, boolean settled) {
        target = Math.max(1, Math.min(6, target)); float ratio = target / zoom;
        x = focusX - (focusX - x) * ratio; y = focusY - (focusY - y) * ratio; zoom = target;
        clamp(); invalidate(); changed(settled);
    }
    private void changed(boolean settled) { if (listener != null) listener.zoom(zoom, settled); }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { center(); }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xff33383f);
        if (bitmap == null || bitmap.isRecycled()) return;
        canvas.save(); canvas.translate(x, y); canvas.scale(base() * zoom, base() * zoom);
        canvas.drawBitmap(bitmap, null, new RectF(0, 0, width, height), paint); canvas.restore();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        gestures.onTouchEvent(event); scale.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) { dragging = true; lastX = event.getX(); lastY = event.getY(); }
        else if (action == MotionEvent.ACTION_POINTER_DOWN) dragging = false;
        else if (action == MotionEvent.ACTION_MOVE && dragging && !scale.isInProgress() && event.getPointerCount() == 1) {
            x += event.getX() - lastX; y += event.getY() - lastY; clamp(); invalidate(); lastX = event.getX(); lastY = event.getY();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { dragging = false; changed(true); performClick(); }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
