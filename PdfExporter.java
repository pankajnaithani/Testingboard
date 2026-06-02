package com.whiteboard.cleanrecord;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.os.Environment;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

public class PdfExporter {

    public static void exportLessonToVectorPdf(Context context, List<BoardPage> pagesList, int width, int height) {
        if (pagesList == null || pagesList.isEmpty()) {
            Toast.makeText(context, "No pages to export", Toast.LENGTH_SHORT).show();
            return;
        }

        int pdfWidth = (width <= 0) ? 1920 : width;
        int pdfHeight = (height <= 0) ? 1080 : height;

        PdfDocument document = new PdfDocument();
        Paint strokePaint = new Paint();
        strokePaint.setAntiAlias(true);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);

        Paint mediaPaint = new Paint();
        mediaPaint.setAntiAlias(true);
        mediaPaint.setFilterBitmap(true);

        for (int i = 0; i < pagesList.size(); i++) {
            BoardPage pageData = pagesList.get(i);
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pdfWidth, pdfHeight, i + 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            // Background Fill Page White
            canvas.drawColor(Color.WHITE);

            // MULTI-IMAGE PDF EXPORTER LAYER MATRIX ENGINE
            if (pageData.placedImages != null) {
                for (PlacedImage img : pageData.placedImages) {
                    if (img.bitmap != null && !img.bitmap.isRecycled()) {
                        canvas.save();
                        // Draw the image exactly where it is placed on the slide workspace
                        canvas.concat(img.transformMatrix);
                        canvas.drawBitmap(img.bitmap, 0, 0, mediaPaint);
                        canvas.restore();
                    }
                }
            }

            // Draw vector overlay paths over background layers
            if (pageData.strokeList != null) {
                for (StrokePath stroke : pageData.strokeList) {
                    strokePaint.setColor(stroke.paint.getColor());
                    strokePaint.setStrokeWidth(stroke.paint.getStrokeWidth());
                    canvas.drawPath(stroke.path, strokePaint);
                }
            }

            document.finishPage(page);
        }

        try {
            File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            File cleanBoardDocsDir = new File(documentsDir, "cleanBoard");
            if (!cleanBoardDocsDir.exists()) {
                cleanBoardDocsDir.mkdirs();
            }

            File pdfFile = new File(cleanBoardDocsDir, "Lesson_" + System.currentTimeMillis() + ".pdf");
            try (FileOutputStream fos = new FileOutputStream(pdfFile)) {
                document.writeTo(fos);
            }
            Toast.makeText(context, "PDF Saved: Documents/cleanBoard", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(context, "Failed to save PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            document.close();
        }
    }
}
