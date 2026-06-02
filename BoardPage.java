package com.whiteboard.cleanrecord;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.Stack;

public class BoardPage {
    public final ArrayList<StrokePath> strokeList = new ArrayList<>();
    public final Stack<StrokePath> redoStack = new Stack<>();
    public final ArrayList<PlacedImage> placedImages = new ArrayList<>();

    // TEMPLATE PARAMETERS: 0 = Plain White, 1 = Ruled Notebook Lines, 2 = Math Grid Squares
    public int backgroundGridType = 0;

    // LOCKED BACKGROUND MEDIA OBJECT
    public Bitmap backgroundImageBitmap = null;
    public String backgroundImagePath = null;

    public void clear() {
        strokeList.clear();
        redoStack.clear();
        // Recycles only custom page layers while keeping template rules intact
        for (PlacedImage img : placedImages) {
            if (img.bitmap != null && !img.bitmap.isRecycled()) {
                img.bitmap.recycle();
            }
        }
        placedImages.clear();
    }
}
