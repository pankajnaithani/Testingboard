package com.whiteboard.cleanrecord;

import android.graphics.Paint;
import android.graphics.Path;

public class StrokePath {
    public Path path;
    public Paint paint;
    public long relativeTimestamp;

    public StrokePath(Path path, Paint paint, long relativeTimestamp) {
        this.path = new Path(path);
        this.paint = new Paint(paint);
        this.relativeTimestamp = relativeTimestamp;
    }
}