package com.whiteboard.cleanrecord;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;

public class PlacedImage {
    public Bitmap bitmap;
    public String imagePath;
    public Matrix transformMatrix;
    
    // MANUAL CROP SPACE: Tracks the sub-rect area currently displayed on screen
    public RectF localCropBounds;

    public PlacedImage(Bitmap bitmap, String imagePath) {
        this.bitmap = bitmap;
        this.imagePath = imagePath;
        this.transformMatrix = new Matrix();
        
        // Initialize default boundaries matching the full bitmap size 
        if (bitmap != null) {
            this.localCropBounds = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        } else {
            this.localCropBounds = new RectF();
        }
    }
}
