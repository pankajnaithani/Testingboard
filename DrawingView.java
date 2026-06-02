package com.whiteboard.cleanrecord;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.io.File;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public class DrawingView extends View {

    private final ArrayList<BoardPage> pages = new ArrayList<>();
    private int currentPageIndex = 0;

    private Path drawPath;
    private Paint drawPaint;
    private Paint cursorPaint;
    private Paint borderPaint;
    private Paint handlePaint;
    
    private Paint toolbarBgPaint;
    private Paint toolbarTextPaint;
    
    private Paint gridLinePaint;
    private Paint marginLinePaint;
    
    private float mX, mY;
    private static final float TOUCH_TOLERANCE = 2;

    private int currentPenColor = Color.BLACK;
    private float activePenWidth = 8f;          
    private float activeHighlighterWidth = 24f;  
    private float currentCursorRadius = 12f;     
    
    private boolean isStrokeEraserMode = false;
    private boolean isHighlighterActive = false; 

    private float hoverX = 0f;
    private float hoverY = 0f; 
    private boolean showHoverCursor = false;
    private boolean isPenTouching = false;

    private Matrix viewMatrix = new Matrix();
    private Matrix inverseMatrix = new Matrix();
    private ScaleGestureDetector scaleGestureDetector;
    private android.os.Handler longPressHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable longPressRunnable;
    private float initialTouchX = 0f;
    private float initialTouchY = 0f;
    private static final float MOVE_CANCEL_THRESHOLD = 45f; 
    private float lastTouchX, lastTouchY;
    private boolean isPanningAndZooming = false;

    private boolean isMediaEditMode = false;
    private int activeImageIndex = -1; 
    private boolean isScalingFromHandle = false;
    private final float handleRadiusPx = 24f;

    private boolean isCropModeActive = false;
    private int selectedCornerHandle = -1; 

    private final RectF btnRotateBounds = new RectF();
    private final RectF btnFrontBounds = new RectF();
    private final RectF btnDeleteBounds = new RectF();
    private final RectF btnCropBounds = new RectF();

    private boolean isActivelyDraggingLayer = false;

    // FLOATING TOUCH INTERCEPT GUARD: Blocks menu clicks until stylus explicitly lifts off after selection
    private boolean isWaitingForFirstTouchRelease = false;

    private long recordingStartTime = 0L;
    private boolean isRecordingActive = false;

    private Bitmap recordingBuffer = null;
    private Canvas bufferCanvas = null;
    private final Object bufferLock = new Object();
    private Matrix videoScaleMatrix = new Matrix();

    public DrawingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initAdvancedEngine();
    }

    private void initAdvancedEngine() {
        drawPath = new Path();
        drawPaint = new Paint();
        drawPaint.setAntiAlias(true);
        drawPaint.setStyle(Paint.Style.STROKE);
        drawPaint.setStrokeJoin(Paint.Join.ROUND);
        drawPaint.setStrokeCap(Paint.Cap.ROUND);

        cursorPaint = new Paint();
        cursorPaint.setAntiAlias(true);
        cursorPaint.setColor(Color.RED);
        cursorPaint.setStyle(Paint.Style.FILL);

        borderPaint = new Paint();
        borderPaint.setAntiAlias(true);
        borderPaint.setColor(Color.parseColor("#3F51B5")); 
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(4f);

        handlePaint = new Paint();
        handlePaint.setAntiAlias(true);
        handlePaint.setColor(Color.parseColor("#3F51B5")); 
        handlePaint.setStyle(Paint.Style.FILL);

        toolbarBgPaint = new Paint();
        toolbarBgPaint.setAntiAlias(true);
        toolbarBgPaint.setColor(Color.parseColor("#F5F5F5"));
        toolbarBgPaint.setStyle(Paint.Style.FILL);
        toolbarBgPaint.setShadowLayer(8f, 0f, 4f, Color.parseColor("#40000000"));

        toolbarTextPaint = new Paint();
        toolbarTextPaint.setAntiAlias(true);
        toolbarTextPaint.setColor(Color.parseColor("#222222"));
        toolbarTextPaint.setTextSize(convertSpToPx(14)); 
        toolbarTextPaint.setTextAlign(Paint.Align.CENTER);

        gridLinePaint = new Paint();
        gridLinePaint.setAntiAlias(true);
        gridLinePaint.setStyle(Paint.Style.STROKE);
        gridLinePaint.setColor(Color.parseColor("#D0E1FD")); 
        gridLinePaint.setStrokeWidth(2f);

        marginLinePaint = new Paint();
        marginLinePaint.setAntiAlias(true);
        marginLinePaint.setStyle(Paint.Style.STROKE);
        marginLinePaint.setColor(Color.parseColor("#F06292")); 
        marginLinePaint.setStrokeWidth(3f);

        pages.add(new BoardPage());
        scaleGestureDetector = new ScaleGestureDetector(getContext(), new ScaleListener());
        setLayerType(LAYER_TYPE_SOFTWARE, null); 
        
        updateDrawingPaintStyle();
    }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_NULL);
        }
        return super.onResolvePointerIcon(event, pointerIndex);
    }

    public void updateDrawingPaintStyle() {
        drawPaint.setColor(currentPenColor);
        if (isHighlighterActive) {
            drawPaint.setStrokeWidth(activeHighlighterWidth);
            int pureColor = currentPenColor & 0x00FFFFFF;
            drawPaint.setColor(pureColor | 0x66000000); 
        } else {
            drawPaint.setStrokeWidth(activePenWidth);
        }
    }

    private float dpToPx(float dp) {
        return dp * getContext().getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        synchronized (bufferLock) {
            int targetVideoWidth = 1920;
            int targetVideoHeight = 1080;
            
            recordingBuffer = Bitmap.createBitmap(targetVideoWidth, targetVideoHeight, Bitmap.Config.ARGB_8888);
            bufferCanvas = new Canvas(recordingBuffer);

            videoScaleMatrix.reset();
            float scaleX = (float) targetVideoWidth / w;
            float scaleY = (float) targetVideoHeight / h;
            float uniformScale = Math.min(scaleX, scaleY);
            
            float dx = (targetVideoWidth - (w * uniformScale)) / 2f;
            float dy = (targetVideoHeight - (h * uniformScale)) / 2f;
            
            videoScaleMatrix.postScale(uniformScale, uniformScale);
            videoScaleMatrix.postTranslate(dx, dy);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawCoreContent(canvas);
        if (showHoverCursor) {
            canvas.drawCircle(hoverX, hoverY, currentCursorRadius, cursorPaint);
        }
        updateRecordingBuffer();
    }

    private void drawCoreContent(Canvas canvas) {
        canvas.drawColor(Color.WHITE);
        canvas.save();
        canvas.concat(viewMatrix);

        BoardPage curPage = getCurrentPage();

        if (curPage.backgroundImageBitmap != null && !curPage.backgroundImageBitmap.isRecycled()) {
            canvas.drawBitmap(curPage.backgroundImageBitmap, 0, 0, null);
        }

        drawBackgroundTemplates(canvas, curPage);
        
        if (curPage.placedImages != null) {
            for (int i = 0; i < curPage.placedImages.size(); i++) {
                PlacedImage img = curPage.placedImages.get(i);
                if (img.bitmap != null && !img.bitmap.isRecycled()) {
                    canvas.save();
                    canvas.concat(img.transformMatrix);
                    canvas.clipRect(img.localCropBounds);
                    canvas.drawBitmap(img.bitmap, 0, 0, null);
                    
                    if (isMediaEditMode && i == activeImageIndex) {
                        if (isCropModeActive) {
                            borderPaint.setColor(Color.parseColor("#E53935")); 
                            handlePaint.setColor(Color.parseColor("#E53935"));  
                        } else {
                            borderPaint.setColor(Color.parseColor("#3F51B5")); 
                            handlePaint.setColor(Color.parseColor("#3F51B5"));  
                        }
                        
                        canvas.drawRect(img.localCropBounds, borderPaint);
                        canvas.drawCircle(img.localCropBounds.left, img.localCropBounds.top, handleRadiusPx, handlePaint);
                        canvas.drawCircle(img.localCropBounds.right, img.localCropBounds.top, handleRadiusPx, handlePaint);
                        canvas.drawCircle(img.localCropBounds.right, img.localCropBounds.bottom, handleRadiusPx, handlePaint);
                        canvas.drawCircle(img.localCropBounds.left, img.localCropBounds.bottom, handleRadiusPx, handlePaint);
                    }
                    canvas.restore();
                }
            }
        }

        for (StrokePath stroke : curPage.strokeList) {
            canvas.drawPath(stroke.path, stroke.paint);
        }
        if (!drawPath.isEmpty()) {
            canvas.drawPath(drawPath, drawPaint);
        }
        canvas.restore();

        if (isMediaEditMode && activeImageIndex >= 0 && activeImageIndex < curPage.placedImages.size()) {
            drawNativeOverlayToolbar(canvas, curPage.placedImages.get(activeImageIndex));
        }
    }

    private void drawNativeOverlayToolbar(Canvas canvas, PlacedImage img) {
        Matrix combinedMatrix = new Matrix(img.transformMatrix);
        combinedMatrix.postConcat(viewMatrix);
        
        float[] imageCoords = new float[]{img.localCropBounds.centerX(), img.localCropBounds.top};
        combinedMatrix.mapPoints(imageCoords);

        float barCenterX = imageCoords[0];
        float barCenterY = imageCoords[1] - dpToPx(55); 

        float halfWidth = dpToPx(210);
        float halfHeight = dpToPx(24);

        RectF toolbarRect = new RectF(barCenterX - halfWidth, barCenterY - halfHeight, barCenterX + halfWidth, barCenterY + halfHeight);
        canvas.drawRoundRect(toolbarRect, dpToPx(16), dpToPx(16), toolbarBgPaint);

        float sectionW = (halfWidth * 2f) / 4f;
        float startX = barCenterX - halfWidth;

        btnRotateBounds.set(startX, barCenterY - halfHeight, startX + sectionW, barCenterY + halfHeight);
        btnCropBounds.set(startX + sectionW, barCenterY - halfHeight, startX + sectionW * 2f, barCenterY + halfHeight);
        btnFrontBounds.set(startX + sectionW * 2f, barCenterY - halfHeight, startX + sectionW * 3f, barCenterY + halfHeight);
        btnDeleteBounds.set(startX + sectionW * 3f, barCenterY - halfHeight, startX + halfWidth * 2f, barCenterY + halfHeight);

        float textOffset = (toolbarTextPaint.descent() + toolbarTextPaint.ascent()) / 2f;
        canvas.drawText("↻ Rotate", btnRotateBounds.centerX(), btnRotateBounds.centerY() - textOffset, toolbarTextPaint);
        
        String cropText = isCropModeActive ? "✓ Apply" : "✂ Crop";
        canvas.drawText(cropText, btnCropBounds.centerX(), btnCropBounds.centerY() - textOffset, toolbarTextPaint);
        
        canvas.drawText("🔝 Front", btnFrontBounds.centerX(), btnFrontBounds.centerY() - textOffset, toolbarTextPaint);
        canvas.drawText("🗑 Delete", btnDeleteBounds.centerX(), btnDeleteBounds.centerY() - textOffset, toolbarTextPaint);
    }

    private void drawBackgroundTemplates(Canvas canvas, BoardPage page) {
        if (page.backgroundGridType == 0) return; 

        float virtualWidth = Math.max(3000f, getWidth() * 4f);
        float virtualHeight = Math.max(3000f, getHeight() * 4f);

        if (page.backgroundGridType == 1) {
            float rowSpacing = dpToPx(28); 
            float verticalMarginLeft = dpToPx(60);

            for (float y = rowSpacing * 2; y < virtualHeight; y += rowSpacing) {
                canvas.drawLine(-virtualWidth, y, virtualWidth, y, gridLinePaint);
            }
            canvas.drawLine(verticalMarginLeft, -virtualHeight, verticalMarginLeft, virtualHeight, marginLinePaint);

        } else if (page.backgroundGridType == 2) {
            float boxSize = dpToPx(32);
            for (float y = -virtualHeight; y < virtualHeight; y += boxSize) {
                canvas.drawLine(-virtualWidth, y, virtualWidth, y, gridLinePaint);
            }
            for (float x = -virtualWidth; x < virtualWidth; x += boxSize) {
                canvas.drawLine(x, -virtualHeight, x, virtualHeight, gridLinePaint);
            }
        }
    }

    public void cycleCurrentPageBackgroundTemplate() {
        BoardPage page = getCurrentPage();
        page.backgroundGridType = (page.backgroundGridType + 1) % 3; 
        invalidate();
    }

    public void convertActiveLayerToPermanentBackground() {
        BoardPage curPage = getCurrentPage();
        if (activeImageIndex >= 0 && activeImageIndex < curPage.placedImages.size()) {
            PlacedImage activeLayer = curPage.placedImages.get(activeImageIndex);
            if (activeLayer.bitmap != null && !activeLayer.bitmap.isRecycled()) {
                Bitmap backgroundBake = Bitmap.createBitmap(
                    activeLayer.bitmap,
                    Math.max(0, (int) activeLayer.localCropBounds.left),
                    Math.max(0, (int) activeLayer.localCropBounds.top),
                    (int) activeLayer.localCropBounds.width(),
                    (int) activeLayer.localCropBounds.height()
                );
                
                curPage.backgroundImageBitmap = backgroundBake;
                curPage.backgroundImagePath = activeLayer.imagePath;

                curPage.placedImages.remove(activeImageIndex);
                isMediaEditMode = false;
                activeImageIndex = -1;
                invalidate();
            }
        }
    }

    public void applyBackgroundToAllExistingSlides() {
        BoardPage sourcePage = getCurrentPage();
        if (sourcePage.backgroundImageBitmap == null) return;

        for (BoardPage page : pages) {
            page.backgroundImageBitmap = sourcePage.backgroundImageBitmap;
            page.backgroundImagePath = sourcePage.backgroundImagePath;
            page.backgroundGridType = sourcePage.backgroundGridType;
        }
        invalidate();
    }

    private void updateRecordingBuffer() {
        synchronized (bufferLock) {
            if (bufferCanvas != null) {
                bufferCanvas.drawColor(Color.WHITE);
                bufferCanvas.save();
                bufferCanvas.concat(videoScaleMatrix);
                drawCoreContent(bufferCanvas);
                bufferCanvas.restore();
                
                if (showHoverCursor) {
                    bufferCanvas.save();
                    bufferCanvas.concat(videoScaleMatrix);
                    bufferCanvas.drawCircle(hoverX, hoverY, currentCursorRadius, cursorPaint);
                    bufferCanvas.restore();
                }
            }
        }
    }

    public void getRecordingFrame(Bitmap targetBitmap) {
        synchronized (bufferLock) {
            if (recordingBuffer != null && targetBitmap != null) {
                Canvas c = new Canvas(targetBitmap);
                c.drawBitmap(recordingBuffer, 0, 0, null);
            }
        }
    }

    public void setMediaEditMode(boolean enable) {
        this.isMediaEditMode = enable;
        if (!enable) {
            if (isCropModeActive) {
                applyPermanentCropSlice(); 
            }
            isCropModeActive = false;
            activeImageIndex = -1;
        }
        invalidate();
    }

    public void deleteCurrentImage() {
        BoardPage curPage = getCurrentPage();
        if (curPage.placedImages != null && !curPage.placedImages.isEmpty()) {
            int targetIndex = (activeImageIndex >= 0) ? activeImageIndex : curPage.placedImages.size() - 1;
            if (targetIndex >= 0 && targetIndex < curPage.placedImages.size()) {
                PlacedImage removed = curPage.placedImages.remove(targetIndex);
                if (removed.bitmap != null && !removed.bitmap.isRecycled()) {
                    removed.bitmap.recycle();
                }
            }
        }
        isMediaEditMode = false;
        isCropModeActive = false;
        activeImageIndex = -1;
        invalidate();
    }

    public void importMedia(Bitmap bitmap, String path) {
        if (bitmap == null) return;
        BoardPage curPage = getCurrentPage();
        
        PlacedImage newLayer = new PlacedImage(bitmap, path);
        float posX = (getWidth() - bitmap.getWidth()) / 2f;
        float posY = (getHeight() - bitmap.getHeight()) / 2f;
        newLayer.transformMatrix.postTranslate(posX, posY);
        
        curPage.placedImages.add(newLayer);
        activeImageIndex = curPage.placedImages.size() - 1;
        isMediaEditMode = true; 
        isCropModeActive = false;
        invalidate();
    }

    @Deprecated
    public void importMedia(Bitmap bitmap) {
        importMedia(bitmap, "");
    }

    private int findSelectedCornerHandle(float screenX, float screenY) {
        BoardPage curPage = getCurrentPage();
        if (activeImageIndex < 0 || activeImageIndex >= curPage.placedImages.size()) return -1;

        PlacedImage img = curPage.placedImages.get(activeImageIndex);
        Matrix combined = new Matrix(img.transformMatrix);
        combined.postConcat(viewMatrix);
        Matrix invCombined = new Matrix();
        combined.invert(invCombined);

        float[] localTouch = new float[]{screenX, screenY};
        invCombined.mapPoints(localTouch);

        float left = img.localCropBounds.left;
        float right = img.localCropBounds.right;
        float top = img.localCropBounds.top;
        float bottom = img.localCropBounds.bottom;

        float touchRange = handleRadiusPx * 3f;

        if (Math.abs(localTouch[0] - left) < touchRange && Math.abs(localTouch[1] - top) < touchRange) return 0; 
        if (Math.abs(localTouch[0] - right) < touchRange && Math.abs(localTouch[1] - top) < touchRange) return 1; 
        if (Math.abs(localTouch[0] - right) < touchRange && Math.abs(localTouch[1] - bottom) < touchRange) return 2; 
        if (Math.abs(localTouch[0] - left) < touchRange && Math.abs(localTouch[1] - bottom) < touchRange) return 3; 

        return -1;
    }

    private boolean isTouchInsideActiveImage(float screenX, float screenY) {
        BoardPage curPage = getCurrentPage();
        if (activeImageIndex < 0 || activeImageIndex >= curPage.placedImages.size()) return false;
        return isTouchInsideTargetImage(curPage.placedImages.get(activeImageIndex), screenX, screenY);
    }

    private boolean isTouchInsideTargetImage(PlacedImage img, float screenX, float screenY) {
        if (img == null) return false;
        Matrix combined = new Matrix(img.transformMatrix);
        combined.postConcat(viewMatrix);
        Matrix invCombined = new Matrix();
        combined.invert(invCombined);

        float[] localT = new float[]{screenX, screenY};
        invCombined.mapPoints(localT);

        return (localT[0] >= img.localCropBounds.left && localT[0] <= img.localCropBounds.right && 
                localT[1] >= img.localCropBounds.top && localT[1] <= img.localCropBounds.bottom);
    }

    private void applyPermanentCropSlice() {
        BoardPage curPage = getCurrentPage();
        if (activeImageIndex < 0 || activeImageIndex >= curPage.placedImages.size()) return;
        
        PlacedImage img = curPage.placedImages.get(activeImageIndex);
        if (img.bitmap == null || img.bitmap.isRecycled()) return;

        try {
            RectF bounds = img.localCropBounds;
            int startX = Math.max(0, (int) bounds.left);
            int startY = Math.max(0, (int) bounds.top);
            int cropW = Math.min(img.bitmap.getWidth() - startX, (int) bounds.width());
            int cropH = Math.min(img.bitmap.getHeight() - startY, (int) bounds.height());

            if (cropW > 15 && cropH > 15 && (startX > 0 || startY > 0 || cropW < img.bitmap.getWidth() || cropH < img.bitmap.getHeight())) {
                Bitmap slicedBitmap = Bitmap.createBitmap(img.bitmap, startX, startY, cropW, cropH);
                img.transformMatrix.preTranslate(startX, startY);
                img.bitmap.recycle();
                img.bitmap = slicedBitmap;
                img.localCropBounds.set(0, 0, cropW, cropH);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean checkToolbarClick(float sx, float sy) {
        // FIXED OVERRIDE: Block all toolbar overlay interaction states if menu lock is currently waiting for release execution
        if (!isMediaEditMode || activeImageIndex < 0 || isActivelyDraggingLayer || isWaitingForFirstTouchRelease) return false;
        BoardPage curPage = getCurrentPage();
        PlacedImage img = curPage.placedImages.get(activeImageIndex);

        if (btnRotateBounds.contains(sx, sy)) {
            img.transformMatrix.postRotate(90, img.localCropBounds.centerX(), img.localCropBounds.centerY());
            invalidate();
            return true;
        } else if (btnCropBounds.contains(sx, sy)) {
            if (isCropModeActive) {
                applyPermanentCropSlice(); 
                isCropModeActive = false;
            } else {
                isCropModeActive = true; 
            }
            invalidate();
            return true;
        } else if (btnFrontBounds.contains(sx, sy)) {
            curPage.placedImages.remove(img);
            curPage.placedImages.add(img);
            activeImageIndex = curPage.placedImages.size() - 1;
            invalidate();
            return true;
        } else if (btnDeleteBounds.contains(sx, sy)) {
            deleteCurrentImage();
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int pointerCount = event.getPointerCount();
        BoardPage curPage = getCurrentPage();
        float screenX = event.getX();
        float screenY = event.getY();

        float bezelPaddingPx = dpToPx(30);
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            float pX = event.getX(index);
            if (pX < bezelPaddingPx || pX > (getWidth() - bezelPaddingPx)) {
                return true; 
            }
        }

        if (event.getAction() == MotionEvent.ACTION_DOWN && !isStrokeEraserMode) {
            initialTouchX = screenX;
            initialTouchY = screenY;
            isActivelyDraggingLayer = false; 

            if (longPressRunnable != null) longPressHandler.removeCallbacks(longPressRunnable);

            longPressRunnable = () -> {
                boolean imageFound = false;
                for (int i = curPage.placedImages.size() - 1; i >= 0; i--) {
                    if (isTouchInsideTargetImage(curPage.placedImages.get(i), screenX, screenY)) {
                        activeImageIndex = i;
                        isMediaEditMode = true;
                        imageFound = true;
                        isActivelyDraggingLayer = true; 
                        
                        // INITIALIZE HAND SHAKE GUARD: Turn guard on since the view just popped open from a hold gesture
                        isWaitingForFirstTouchRelease = true;
                        
                        invalidate();
                        break;
                    }
                }
                
                if (!imageFound && isMediaEditMode && findSelectedCornerHandle(screenX, screenY) == -1 
                        && !btnRotateBounds.contains(screenX, screenY) && !btnCropBounds.contains(screenX, screenY) 
                        && !btnFrontBounds.contains(screenX, screenY) && !btnDeleteBounds.contains(screenX, screenY)) {
                    
                    if (isCropModeActive) {
                        applyPermanentCropSlice();
                    }
                    isMediaEditMode = false;
                    isCropModeActive = false;
                    activeImageIndex = -1;
                    invalidate();
                }
            };
            longPressHandler.postDelayed(longPressRunnable, 600); 
        }

        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            float deltaX = Math.abs(screenX - initialTouchX);
            float deltaY = Math.abs(screenY - initialTouchY);
            if (deltaX > MOVE_CANCEL_THRESHOLD || deltaY > MOVE_CANCEL_THRESHOLD) {
                if (longPressRunnable != null) longPressHandler.removeCallbacks(longPressRunnable);
                if (isMediaEditMode && activeImageIndex >= 0) {
                    isActivelyDraggingLayer = true; 
                }
            }
        }

        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (longPressRunnable != null) longPressHandler.removeCallbacks(longPressRunnable);
            isActivelyDraggingLayer = false;
            // Lift off verification clears the guard thread so future intentional taps process normally!
            isWaitingForFirstTouchRelease = false;
            invalidate();
        }

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (checkToolbarClick(screenX, screenY)) {
                return true;
            }
        }

        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            hoverX = screenX;
            hoverY = screenY;
            showHoverCursor = (event.getAction() != MotionEvent.ACTION_UP);
        }

        if (isMediaEditMode && event.getAction() == MotionEvent.ACTION_DOWN) {
            selectedCornerHandle = findSelectedCornerHandle(screenX, screenY);
            isScalingFromHandle = (selectedCornerHandle != -1);
            if (isScalingFromHandle) {
                lastTouchX = screenX;
                lastTouchY = screenY;
                return true;
            }
        }

        if (isMediaEditMode && isScalingFromHandle && event.getAction() == MotionEvent.ACTION_MOVE) {
            if (activeImageIndex >= 0 && activeImageIndex < curPage.placedImages.size()) {
                PlacedImage img = curPage.placedImages.get(activeImageIndex);
                Matrix invCombined = new Matrix();
                Matrix combined = new Matrix(img.transformMatrix);
                combined.postConcat(viewMatrix);
                combined.invert(invCombined);

                float[] pts = new float[]{screenX, screenY};
                invCombined.mapPoints(pts);
                float localX = pts[0];
                float localY = pts[1];

                if (isCropModeActive) {
                    switch (selectedCornerHandle) {
                        case 0: 
                            if (localX < img.localCropBounds.right - 20) img.localCropBounds.left = Math.max(0, localX);
                            if (localY < img.localCropBounds.bottom - 20) img.localCropBounds.top = Math.max(0, localY);
                            break;
                        case 1: 
                            if (localX > img.localCropBounds.left + 20) img.localCropBounds.right = Math.min(img.bitmap.getWidth(), localX);
                            if (localY < img.localCropBounds.bottom - 20) img.localCropBounds.top = Math.max(0, localY);
                            break;
                        case 2: 
                            if (localX > img.localCropBounds.left + 20) img.localCropBounds.right = Math.min(img.bitmap.getWidth(), localX);
                            if (localY > img.localCropBounds.top + 20) img.localCropBounds.bottom = Math.min(img.bitmap.getHeight(), localY);
                            break;
                        case 3: 
                            if (localX < img.localCropBounds.right - 20) img.localCropBounds.left = Math.max(0, localX);
                            if (localY > img.localCropBounds.top + 20) img.localCropBounds.bottom = Math.min(img.bitmap.getHeight(), localY);
                            break;
                    }
                } else {
                    float scaleFactor = (screenX > lastTouchX) ? 1.02f : 0.98f;
                    img.transformMatrix.postScale(scaleFactor, scaleFactor, screenX, screenY);
                }
            }
            lastTouchX = screenX;
            lastTouchY = screenY;
            invalidate();
            return true;
        }

        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            isScalingFromHandle = false;
            selectedCornerHandle = -1;
        }

        if (isMediaEditMode && !isScalingFromHandle && activeImageIndex >= 0 && activeImageIndex < curPage.placedImages.size() && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            if (pointerCount >= 2) {
                float x0 = event.getX(0); float y0 = event.getY(0);
                float x1 = event.getX(1); float y1 = event.getY(1);
                
                if (isTouchInsideActiveImage(x0, y0) && isTouchInsideActiveImage(x1, y1)) {
                    scaleGestureDetector.onTouchEvent(event);
                }
            } else if (pointerCount == 1 && event.getAction() == MotionEvent.ACTION_MOVE) {
                if (isTouchInsideActiveImage(screenX, screenY)) {
                    float dx = screenX - lastTouchX;
                    float dy = screenY - lastTouchY;
                    curPage.placedImages.get(activeImageIndex).transformMatrix.postTranslate(dx, dy);
                }
            }
            lastTouchX = screenX;
            lastTouchY = screenY;
            invalidate();
            return true;
        }

        if (pointerCount >= 2 && !isMediaEditMode) {
            isPanningAndZooming = true;
            showHoverCursor = false;
            scaleGestureDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float x = event.getX();
                float x_y = event.getY();
                viewMatrix.postTranslate(x - lastTouchX, x_y - lastTouchY);
                lastTouchX = x;
                lastTouchY = x_y;
            }
            lastTouchX = screenX;
            lastTouchY = screenY;
            invalidate();
            return true;
        }

        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            if (event.getAction() == MotionEvent.ACTION_UP) { isPanningAndZooming = false; }
            return true;
        }

        if (isPanningAndZooming) {
            if (event.getAction() == MotionEvent.ACTION_UP) { isPanningAndZooming = false; }
            return true;
        }

        viewMatrix.invert(inverseMatrix);
        float[] mappedPoints = new float[]{screenX, screenY};
        inverseMatrix.mapPoints(mappedPoints);
        float x = mappedPoints[0];
        float y = mappedPoints[1];

        if (!isStrokeEraserMode) {
            float pressure = event.getPressure();
            float targetBaseWidth = isHighlighterActive ? activeHighlighterWidth : activePenWidth;
            drawPaint.setStrokeWidth(targetBaseWidth * (0.4f + pressure * 1.6f));
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isPenTouching = true;
                if (isStrokeEraserMode) {
                    performVectorStrokeEraser(x, y);
                } else {
                    curPage.redoStack.clear();
                    drawPath.moveTo(x, y);
                    mX = x;
                    mY = y;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (!isStrokeEraserMode) {
                    float dx = Math.abs(x - mX);
                    float dy = Math.abs(y - mY);
                    if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                        drawPath.quadTo(mX, mY, (x + mX) / 2, (y + mY) / 2);
                        mX = x;
                        mY = y;
                    }
                } else {
                    performVectorStrokeEraser(x, y);
                }
                break;

            case MotionEvent.ACTION_UP:
                isPenTouching = false;
                showHoverCursor = false;
                if (!isStrokeEraserMode && !drawPath.isEmpty()) {
                    drawPath.lineTo(x, y);
                    long relativeTime = isRecordingActive ? (System.currentTimeMillis() - recordingStartTime) : 0L;
                    curPage.strokeList.add(new StrokePath(new Path(drawPath), new Paint(drawPaint), relativeTime));
                    drawPath.reset();
                }
                break;
        }

        invalidate();
        return true;
    }

    private void performVectorStrokeEraser(float x, float y) {
        BoardPage curPage = getCurrentPage();
        float toleranceRadius = 24f;
        RectF touchBox = new RectF(x - toleranceRadius, y - toleranceRadius, x + toleranceRadius, y + toleranceRadius);
        RectF pathBounds = new RectF();
        ArrayList<StrokePath> strokesToRemove = new ArrayList<>();

        for (StrokePath stroke : curPage.strokeList) {
            stroke.path.computeBounds(pathBounds, true);
            if (RectF.intersects(touchBox, pathBounds)) {
                strokesToRemove.add(stroke);
            }
        }
        if (!strokesToRemove.isEmpty()) {
            curPage.strokeList.removeAll(strokesToRemove);
            invalidate();
        }
    }

    public boolean onHoverEvent(MotionEvent event) {
        if (isPenTouching || event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            return super.onHoverEvent(event);
        }
        
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_ENTER:
                hoverX = event.getX();
                hoverY = event.getY();
                showHoverCursor = true;
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                showHoverCursor = false;
                break;
        }
        invalidate();
        return true;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            BoardPage curPage = getCurrentPage();
            
            if (isMediaEditMode && activeImageIndex >= 0 && activeImageIndex < curPage.placedImages.size()) {
                curPage.placedImages.get(activeImageIndex).transformMatrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
            } else {
                viewMatrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
            }
            invalidate();
            return true;
        }
    }

    public BoardPage getCurrentPage() { return pages.get(currentPageIndex); }
    public int getCurrentPageIndex() { return currentPageIndex; }
    public int getTotalPages() { return pages.size(); }
    public ArrayList<BoardPage> getAllPages() { return pages; }

    public void jumpToPage(int index) {
        applyPermanentCropSlice();
        currentPageIndex = index;
        isMediaEditMode = false;
        isCropModeActive = false;
        activeImageIndex = -1;
        invalidate();
    }

    public void nextPage() {
        applyPermanentCropSlice();
        if (currentPageIndex == pages.size() - 1) { 
            BoardPage next = new BoardPage();
            next.backgroundGridType = getCurrentPage().backgroundGridType;
            next.backgroundImageBitmap = getCurrentPage().backgroundImageBitmap;
            next.backgroundImagePath = getCurrentPage().backgroundImagePath;
            pages.add(next); 
        }
        currentPageIndex++;
        isMediaEditMode = false;
        isCropModeActive = false;
        activeImageIndex = -1;
        invalidate();
    }

    public void prevPage() {
        applyPermanentCropSlice();
        if (currentPageIndex > 0) { 
            currentPageIndex--; 
            isMediaEditMode = false;
            isCropModeActive = false;
            activeImageIndex = -1;
            invalidate(); 
        }
    }

    public void undo() {
        BoardPage page = getCurrentPage();
        if (!page.strokeList.isEmpty()) {
            page.redoStack.push(page.strokeList.remove(page.strokeList.size() - 1));
            invalidate();
        }
    }

    public void redo() {
        BoardPage page = getCurrentPage();
        if (!page.redoStack.isEmpty()) {
            page.strokeList.add(page.redoStack.pop());
            invalidate();
        }
    }

    public void resetZoomAndPan() { viewMatrix.reset(); invalidate(); }
    public void startTimeSyncAnchor() { recordingStartTime = System.currentTimeMillis(); isRecordingActive = true; }
    public void stopTimeSyncAnchor() { isRecordingActive = false; }

    private float convertSpToPx(int sp) {
        return sp * getContext().getResources().getDisplayMetrics().scaledDensity;
    }

    public void setPenWidth(float width) { this.activePenWidth = width; updateDrawingPaintStyle(); }
    public float getPenWidth() { return activePenWidth; }
    
    public void setHighlighterWidth(float width) { this.activeHighlighterWidth = width; updateDrawingPaintStyle(); }
    public float getHighlighterWidth() { return activeHighlighterWidth; }

    public void setIsHighlighterActive(boolean active) { this.isHighlighterActive = active; isStrokeEraserMode = false; updateDrawingPaintStyle(); }

    public void setPenColor(int color) {
        isStrokeEraserMode = false;
        this.currentPenColor = color;
        updateDrawingPaintStyle();
    }

    public void setCursorRadius(float radius) { if (radius >= 4f && radius <= 60f) { this.currentCursorRadius = radius; invalidate(); } }
    public float getCurrentCursorRadius() { return currentCursorRadius; }
    public void setStrokeEraserMode() { applyPermanentCropSlice(); isStrokeEraserMode = true; isMediaEditMode = false; activeImageIndex = -1; }
    public void onClickTrashCanLayerIndex() { deleteCurrentImage(); }
    public void clearCurrentPage() { getCurrentPage().clear(); isMediaEditMode = false; isCropModeActive = false; activeImageIndex = -1; invalidate(); }

    public Bitmap generatePageThumbnail(int pageIndex, int thumbWidth, int thumbHeight) {
        Bitmap thumbBitmap = Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(thumbBitmap);
        canvas.drawColor(Color.WHITE);

        if (pageIndex < 0 || pageIndex >= pages.size()) return thumbBitmap;
        BoardPage pageData = pages.get(pageIndex);

        canvas.save();
        float scaleX = (float) thumbWidth / getWidth();
        float scaleY = (float) thumbHeight / getHeight();
        canvas.scale(scaleX, scaleY);

        if (pageData.backgroundImageBitmap != null && !pageData.backgroundImageBitmap.isRecycled()) {
            canvas.drawBitmap(pageData.backgroundImageBitmap, 0, 0, null);
        }
        drawBackgroundTemplates(canvas, pageData);

        if (pageData.placedImages != null) {
            for (PlacedImage img : pageData.placedImages) {
                if (img.bitmap != null && !img.bitmap.isRecycled()) {
                    canvas.save();
                    canvas.concat(img.transformMatrix);
                    canvas.clipRect(img.localCropBounds);
                    canvas.drawBitmap(img.bitmap, 0, 0, null);
                    canvas.restore();
                }
            }
        }
        if (pageData.strokeList != null) {
            for (StrokePath stroke : pageData.strokeList) {
                canvas.drawPath(stroke.path, stroke.paint);
            }
        }
        canvas.restore();
        return thumbBitmap;
    }

    public String getProjectAsJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("active_page_index", currentPageIndex);
            float[] mainMatrixVals = new float[9];
            viewMatrix.getValues(mainMatrixVals);
            JSONArray mainMatrixArray = new JSONArray();
            for (float val : mainMatrixVals) mainMatrixArray.put((double) val);
            root.put("viewport_matrix", mainMatrixArray);

            JSONArray pagesArray = new JSONArray();
            for (BoardPage page : pages) {
                JSONObject pageObj = new JSONObject();
                pageObj.put("bg_grid_type", page.backgroundGridType);
                pageObj.put("bg_image_path", page.backgroundImagePath != null ? page.backgroundImagePath : "");

                JSONArray imagesJsonArray = new JSONArray();
                if (page.placedImages != null) {
                    for (PlacedImage img : page.placedImages) {
                        JSONObject imgObj = new JSONObject();
                        imgObj.put("path", img.imagePath != null ? img.imagePath : "");
                        float[] mediaMatrixVals = new float[9];
                        img.transformMatrix.getValues(mediaMatrixVals);
                        JSONArray mediaMatrixArray = new JSONArray();
                        for (float mVal : mediaMatrixVals) mediaMatrixArray.put((double) mVal);
                        imgObj.put("matrix", mediaMatrixArray);
                        
                        JSONArray cropArray = new JSONArray();
                        cropArray.put((double) img.localCropBounds.left);
                        cropArray.put((double) img.localCropBounds.top);
                        cropArray.put((double) img.localCropBounds.right);
                        cropArray.put((double) img.localCropBounds.bottom);
                        imgObj.put("crop_bounds", cropArray);
                        imagesJsonArray.put(imgObj);
                    }
                }
                pageObj.put("placed_images_list", imagesJsonArray);

                JSONArray strokesArray = new JSONArray();
                for (StrokePath stroke : page.strokeList) {
                    JSONObject strokeObj = new JSONObject();
                    strokeObj.put("color", stroke.paint.getColor());
                    strokeObj.put("width", (double) stroke.paint.getStrokeWidth());
                    strokeObj.put("time", 0L);

                    JSONArray pointsArray = new JSONArray();
                    PathMeasure pm = new PathMeasure(stroke.path, false);
                    float length = pm.getLength();
                    float distance = 0f;
                    float[] pos = new float[2];
                    while (distance < length) {
                        pm.getPosTan(distance, pos, null);
                        JSONObject pt = new JSONObject();
                        pt.put("x", (double) pos[0]);
                        pt.put("y", (double) pos[1]);
                        pointsArray.put(pt);
                        distance += 5f; 
                    }
                    strokeObj.put("points", pointsArray);
                    strokesArray.put(strokeObj);
                }
                pageObj.put("strokes", strokesArray);
                pagesArray.put(pageObj);
            }
            root.put("pages", pagesArray);
            return root.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public void loadProjectFromJson(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) return;
        try {
            pages.clear();
            JSONObject root = new JSONObject(jsonStr);
            int activePageIdx = root.optInt("active_page_index", 0);
            JSONArray mainMatrixArray = root.optJSONArray("viewport_matrix");
            if (mainMatrixArray != null && mainMatrixArray.length() == 9) {
                float[] mainVals = new float[9];
                for (int m = 0; m < 9; m++) mainVals[m] = (float) mainMatrixArray.getDouble(m);
                viewMatrix.setValues(mainVals);
            }

            JSONArray pagesArray = root.getJSONArray("pages");
            for (int i = 0; i < pagesArray.length(); i++) {
                BoardPage page = new BoardPage();
                JSONObject pageObj = pagesArray.getJSONObject(i);
                page.backgroundGridType = pageObj.optInt("bg_grid_type", 0);
                String bgPathStr = pageObj.optString("bg_image_path", "");
                if (!bgPathStr.isEmpty()) {
                    File file = new File(bgPathStr);
                    if (file.exists()) {
                        page.backgroundImageBitmap = BitmapFactory.decodeFile(bgPathStr);
                        page.backgroundImagePath = bgPathStr;
                    }
                }

                JSONArray imagesJsonArray = pageObj.optJSONArray("placed_images_list");
                if (imagesJsonArray != null) {
                    for (int imgIdx = 0; imgIdx < imagesJsonArray.length(); imgIdx++) {
                        JSONObject imgObj = imagesJsonArray.getJSONObject(imgIdx);
                        String bgPath = imgObj.optString("path", "");
                        if (!bgPath.isEmpty()) {
                            File file = new File(bgPath);
                            if (file.exists()) {
                                Bitmap bmp = BitmapFactory.decodeFile(bgPath);
                                if (bmp != null) {
                                    PlacedImage placedImage = new PlacedImage(bmp, bgPath);
                                    JSONArray matrixArray = imgObj.optJSONArray("matrix");
                                    if (matrixArray != null && matrixArray.length() == 9) {
                                        float[] mVals = new float[9];
                                        for (int m = 0; m < 9; m++) mVals[m] = (float) matrixArray.getDouble(m);
                                        placedImage.transformMatrix.setValues(mVals);
                                    }
                                    JSONArray cropArray = imgObj.optJSONArray("crop_bounds");
                                    if (cropArray != null && cropArray.length() == 4) {
                                        placedImage.localCropBounds.set(
                                            (float) cropArray.getDouble(0),
                                            (float) cropArray.getDouble(1),
                                            (float) cropArray.getDouble(2),
                                            (float) cropArray.getDouble(3)
                                        );
                                    }
                                    page.placedImages.add(placedImage);
                                }
                            }
                        }
                    }
                }
                JSONArray strokesArray = pageObj.getJSONArray("strokes");
                for (int j = 0; j < strokesArray.length(); j++) {
                    JSONObject strokeObj = strokesArray.getJSONObject(j);
                    Paint p = new Paint();
                    p.setAntiAlias(true);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeJoin(Paint.Join.ROUND);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setColor(strokeObj.getInt("color"));
                    p.setStrokeWidth((float) strokeObj.getDouble("width"));

                    JSONArray pointsArray = strokeObj.getJSONArray("points");
                    Path path = new Path();
                    if (pointsArray.length() > 0) {
                        JSONObject first = pointsArray.getJSONObject(0);
                        path.moveTo((float) first.getDouble("x"), (float) first.getDouble("y"));
                        for (int k = 1; k < pointsArray.length(); k++) {
                            JSONObject pt = pointsArray.getJSONObject(k);
                            path.lineTo((float) pt.getDouble("x"), (float) pt.getDouble("y"));
                        }
                    }
                    page.strokeList.add(new StrokePath(path, p, 0L));
                }
                pages.add(page);
            }
            currentPageIndex = (activePageIdx >= 0 && activePageIdx < pages.size()) ? activePageIdx : 0;
            invalidate();
        } catch (Exception e) {}
    }
}
