package com.whiteboard.cleanrecord;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST = 501;
    private static final int PICK_MEDIA_REQUEST = 502;

    private DrawingView drawingView;
    private CanvasRecorder recorder;
    private ProjectDatabaseHelper dbHelper;
    private long currentProjectId = -1L;

    private Button btnRecord, btnPause, btnStop, btnMoreActions;
    private TextView txtTimer, txtPageIndicator;
    private ProgressBar micVolumeMonitor;

    private LinearLayout layoutSlideTray, slideThumbnailsContainer;

    // 5 Tool Bindings
    private CardView btnToolPen1, btnToolPen2, btnToolPen3, btnToolHighlighter, btnToolEraser;
    private SeekBar sliderStrokeWidth;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean runMicMonitor = false;
    private int elapsedSeconds = 0;
    private boolean isTimerRunning = false;
    private boolean isImageEditToggle = false;
    
    private int selectedActiveColor = Color.BLACK;
    private boolean isHighlighterSelected = false;

    // Persistent Settings
    private SharedPreferences prefs;
    private float pen1Width, pen2Width, pen3Width;
    private int activeToolIndex = 0; // 0=Pen1, 1=Pen2, 2=Pen3, 3=Highlighter, 4=Eraser

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Load Persistent Pen Data
        prefs = getSharedPreferences("WhiteboardPrefs", MODE_PRIVATE);
        pen1Width = prefs.getFloat("pen1_width", 4f);
        pen2Width = prefs.getFloat("pen2_width", 10f);
        pen3Width = prefs.getFloat("pen3_width", 24f);

        dbHelper = new ProjectDatabaseHelper(this);
        currentProjectId = getIntent().getLongExtra("PROJECT_ID", -1L);

        drawingView = findViewById(R.id.drawingView);
        btnRecord = findViewById(R.id.btnRecord);
        btnPause = findViewById(R.id.btnPause);
        btnStop = findViewById(R.id.btnStop);
        txtTimer = findViewById(R.id.txtTimer);
        txtPageIndicator = findViewById(R.id.txtPageIndicator);
        micVolumeMonitor = findViewById(R.id.micVolumeMonitor);
        btnMoreActions = findViewById(R.id.btnMoreActions);

        layoutSlideTray = findViewById(R.id.layoutSlideTray);
        slideThumbnailsContainer = findViewById(R.id.slideThumbnailsContainer);

        btnToolPen1 = findViewById(R.id.btnToolPen1);
        btnToolPen2 = findViewById(R.id.btnToolPen2);
        btnToolPen3 = findViewById(R.id.btnToolPen3);
        btnToolHighlighter = findViewById(R.id.btnToolHighlighter);
        btnToolEraser = findViewById(R.id.btnToolEraser);
        sliderStrokeWidth = findViewById(R.id.sliderStrokeWidth);

        btnRecord.setOnClickListener(v -> startRecordingSequence());
        btnPause.setOnClickListener(v -> togglePauseResume());
        btnStop.setOnClickListener(v -> stopRecordingProcess());

        findViewById(R.id.btnUndo).setOnClickListener(v -> { drawingView.undo(); refreshSlideTrayPanel(); });
        findViewById(R.id.btnRedo).setOnClickListener(v -> { drawingView.redo(); refreshSlideTrayPanel(); });
        findViewById(R.id.btnNextPage).setOnClickListener(v -> { drawingView.nextPage(); refreshSlideTrayPanel(); });
        findViewById(R.id.btnPrevPage).setOnClickListener(v -> { drawingView.prevPage(); refreshSlideTrayPanel(); });

        findViewById(R.id.btnShowSlides).setOnClickListener(v -> {
            if (layoutSlideTray.getVisibility() == View.VISIBLE) {
                layoutSlideTray.setVisibility(View.GONE);
            } else {
                layoutSlideTray.setVisibility(View.VISIBLE);
                refreshSlideTrayPanel();
            }
        });

        findViewById(R.id.btnAddNewSlideFromTray).setOnClickListener(v -> {
            drawingView.nextPage();
            refreshSlideTrayPanel();
        });

        btnMoreActions.setOnClickListener(v -> showActionsMenu());

        // Multi-Pen Click Listeners
        btnToolPen1.setOnClickListener(v -> selectPenSlot(0, pen1Width));
        btnToolPen2.setOnClickListener(v -> selectPenSlot(1, pen2Width));
        btnToolPen3.setOnClickListener(v -> selectPenSlot(2, pen3Width));

        btnToolHighlighter.setOnClickListener(v -> {
            isHighlighterSelected = true;
            setActiveToolUI(3);
            drawingView.setIsHighlighterActive(true);
            drawingView.setPenColor(selectedActiveColor);
            sliderStrokeWidth.setProgress((int) drawingView.getHighlighterWidth());
        });

        btnToolEraser.setOnClickListener(v -> {
            setActiveToolUI(4);
            drawingView.setStrokeEraserMode();
            sliderStrokeWidth.setProgress((int) drawingView.getCurrentCursorRadius());
        });

        sliderStrokeWidth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float calculatedWidth = Math.max(2f, (float) progress);
                
                switch (activeToolIndex) {
                    case 0:
                        pen1Width = calculatedWidth;
                        prefs.edit().putFloat("pen1_width", pen1Width).apply();
                        drawingView.setPenWidth(pen1Width);
                        break;
                    case 1:
                        pen2Width = calculatedWidth;
                        prefs.edit().putFloat("pen2_width", pen2Width).apply();
                        drawingView.setPenWidth(pen2Width);
                        break;
                    case 2:
                        pen3Width = calculatedWidth;
                        prefs.edit().putFloat("pen3_width", pen3Width).apply();
                        drawingView.setPenWidth(pen3Width);
                        break;
                    case 3:
                        drawingView.setHighlighterWidth(calculatedWidth);
                        break;
                    case 4:
                        drawingView.setCursorRadius(calculatedWidth);
                        break;
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        findViewById(R.id.paletteBlack).setOnClickListener(v -> updateActiveColorValue(Color.BLACK));
        findViewById(R.id.paletteBlue).setOnClickListener(v -> updateActiveColorValue(Color.parseColor("#1565C0")));
        findViewById(R.id.paletteRed).setOnClickListener(v -> updateActiveColorValue(Color.parseColor("#E53935")));
        findViewById(R.id.paletteGreen).setOnClickListener(v -> updateActiveColorValue(Color.parseColor("#2E7D32")));
        findViewById(R.id.paletteYellow).setOnClickListener(v -> updateActiveColorValue(Color.parseColor("#FBC02D")));
        findViewById(R.id.palettePurple).setOnClickListener(v -> updateActiveColorValue(Color.parseColor("#8E24AA")));

        setupPaletteLongTouchMenu(findViewById(R.id.paletteBlack), Color.BLACK);
        setupPaletteLongTouchMenu(findViewById(R.id.paletteBlue), Color.parseColor("#1565C0"));
        setupPaletteLongTouchMenu(findViewById(R.id.paletteRed), Color.parseColor("#E53935"));
        setupPaletteLongTouchMenu(findViewById(R.id.paletteGreen), Color.parseColor("#2E7D32"));
        setupPaletteLongTouchMenu(findViewById(R.id.paletteYellow), Color.parseColor("#FBC02D"));
        setupPaletteLongTouchMenu(findViewById(R.id.palettePurple), Color.parseColor("#8E24AA"));

        if (currentProjectId != -1L) {
            List<ProjectModel> projects = dbHelper.getAllProjects();
            for (ProjectModel p : projects) {
                if (p.getId() == currentProjectId) {
                    drawingView.loadProjectFromJson(p.getJsonContent());
                    break;
                }
            }
        }
        
        // Initialize Default Pen state to Pen 1
        selectPenSlot(0, pen1Width);
        updatePageText();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
    }

    private void selectPenSlot(int index, float width) {
        isHighlighterSelected = false;
        setActiveToolUI(index);
        drawingView.setIsHighlighterActive(false);
        drawingView.setPenColor(selectedActiveColor);
        drawingView.setPenWidth(width);
        sliderStrokeWidth.setProgress((int) width);
    }

    private void showActionsMenu() {
        PopupMenu popup = new PopupMenu(MainActivity.this, btnMoreActions);
        popup.getMenu().add(1, 1, 1, "+ Import Image or PDF");
        popup.getMenu().add(1, 2, 2, isImageEditToggle ? "Lock File Layer" : "Edit File Layer");
        popup.getMenu().add(1, 3, 3, "Delete Active File");
        popup.getMenu().add(1, 4, 4, "Set File as Background");
        popup.getMenu().add(1, 5, 5, "Cycle Grid Type");
        popup.getMenu().add(1, 6, 6, "Clear Entire Slide");
        popup.getMenu().add(1, 7, 7, "Export Lesson to PDF");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
                    startActivityForResult(intent, PICK_MEDIA_REQUEST);
                    break;
                case 2:
                    isImageEditToggle = !isImageEditToggle;
                    drawingView.setMediaEditMode(isImageEditToggle);
                    Toast.makeText(this, isImageEditToggle ? "Edit Mode Enabled" : "Edit Mode Locked", Toast.LENGTH_SHORT).show();
                    break;
                case 3:
                    drawingView.deleteCurrentImage();
                    isImageEditToggle = false;
                    refreshSlideTrayPanel();
                    break;
                case 4:
                    drawingView.convertActiveLayerToPermanentBackground();
                    refreshSlideTrayPanel();
                    Toast.makeText(MainActivity.this, "Image locked as Slide background.", Toast.LENGTH_SHORT).show();
                    break;
                case 5:
                    drawingView.cycleCurrentPageBackgroundTemplate();
                    refreshSlideTrayPanel();
                    break;
                case 6:
                    drawingView.clearCurrentPage();
                    refreshSlideTrayPanel();
                    break;
                case 7:
                    PdfExporter.exportLessonToVectorPdf(this, drawingView.getAllPages(), drawingView.getWidth(), drawingView.getHeight());
                    break;
            }
            return true;
        });
        popup.show();
    }

    private void setActiveToolUI(int activeIndex) {
        int activeColor = Color.parseColor("#BBDEFB"); 
        int inactiveColor = Color.parseColor("#FFFFFF");
        
        btnToolPen1.setCardBackgroundColor(activeIndex == 0 ? activeColor : inactiveColor);
        btnToolPen2.setCardBackgroundColor(activeIndex == 1 ? activeColor : inactiveColor);
        btnToolPen3.setCardBackgroundColor(activeIndex == 2 ? activeColor : inactiveColor);
        btnToolHighlighter.setCardBackgroundColor(activeIndex == 3 ? activeColor : inactiveColor);
        btnToolEraser.setCardBackgroundColor(activeIndex == 4 ? activeColor : inactiveColor);
        
        this.activeToolIndex = activeIndex;
    }

    private void updateActiveColorValue(int color) {
        selectedActiveColor = color;
        drawingView.setPenColor(color);
    }

    private void setupPaletteLongTouchMenu(View paletteView, final int targetColor) {
        paletteView.setOnLongClickListener(v -> {
            updateActiveColorValue(targetColor);

            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("Adjust Pointer Highlight Size");

            LinearLayout container = new LinearLayout(MainActivity.this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(45, 30, 45, 30);

            final TextView labelCursor = new TextView(MainActivity.this);
            labelCursor.setText("Current Radius: " + (int) drawingView.getCurrentCursorRadius());
            labelCursor.setPadding(0, 10, 0, 10);
            container.addView(labelCursor);

            SeekBar seekBarCursor = new SeekBar(MainActivity.this);
            seekBarCursor.setMax(60);
            seekBarCursor.setProgress((int) drawingView.getCurrentCursorRadius());
            seekBarCursor.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean f) {
                    float val = Math.max(4f, (float) p);
                    drawingView.setCursorRadius(val);
                    labelCursor.setText("Current Radius: " + (int) val);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
            container.addView(seekBarCursor);

            builder.setView(container);
            builder.setPositiveButton("Done", (dialog, which) -> dialog.dismiss());
            
            builder.create().show();
            return true; 
        });
    }

    private void refreshSlideTrayPanel() {
        slideThumbnailsContainer.removeAllViews();
        int totalPages = drawingView.getTotalPages();
        int activeIndex = drawingView.getCurrentPageIndex();

        int cardWidthPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 320, getResources().getDisplayMetrics());
        int cardHeightPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 190, getResources().getDisplayMetrics());

        for (int i = 0; i < totalPages; i++) {
            final int pageIdx = i;
            
            LinearLayout thumbWrapper = new LinearLayout(this);
            thumbWrapper.setOrientation(LinearLayout.VERTICAL);
            thumbWrapper.setPadding(24, 4, 24, 4);
            thumbWrapper.setGravity(android.view.Gravity.CENTER);

            ImageView ivThumb = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardWidthPx, cardHeightPx);
            ivThumb.setLayoutParams(lp);
            ivThumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
            
            Bitmap thumbBmp = drawingView.generatePageThumbnail(pageIdx, cardWidthPx, cardHeightPx);
            ivThumb.setImageBitmap(thumbBmp);
            
            if (pageIdx == activeIndex) {
                ivThumb.setBackgroundColor(Color.parseColor("#3F51B5"));
                ivThumb.setPadding(8, 8, 8, 8);
            } else {
                ivThumb.setBackgroundColor(Color.parseColor("#A8A8A8"));
                ivThumb.setPadding(3, 3, 3, 3);
            }

            TextView tvLabel = new TextView(this);
            tvLabel.setText(String.format(Locale.getDefault(), "Slide %d of %d", (pageIdx + 1), totalPages));
            tvLabel.setTextSize(12f); 
            tvLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            tvLabel.setTextColor(Color.WHITE);
            tvLabel.setPadding(0, 8, 0, 0);

            thumbWrapper.addView(ivThumb);
            thumbWrapper.addView(tvLabel);

            thumbWrapper.setOnClickListener(v -> {
                drawingView.jumpToPage(pageIdx);
                refreshSlideTrayPanel();
            });

            slideThumbnailsContainer.addView(thumbWrapper);
        }
        updatePageText();
    }

    private void updatePageText() {
        txtPageIndicator.setText("P. " + (drawingView.getCurrentPageIndex() + 1) + "/" + drawingView.getTotalPages());
    }

    private void saveCurrentProjectStateToDatabase() {
        String dataJson = drawingView.getProjectAsJson();
        if (currentProjectId == -1L) {
            String generatedTitle = "Lesson " + new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(new java.util.Date());
            currentProjectId = dbHelper.insertProject(generatedTitle, dataJson);
        } else {
            dbHelper.updateProject(currentProjectId, "Whiteboard Lesson", dataJson);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrentProjectStateToDatabase();
    }

    private void startRecordingSequence() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
        } else {
            startRecordingProcessAsync();
        }
    }

    private void startRecordingProcessAsync() {
        btnRecord.setText("Connecting...");
        btnRecord.setEnabled(false);

        final int w = drawingView.getWidth();
        final int h = drawingView.getHeight();

        if (recorder == null) {
            recorder = new CanvasRecorder(this, drawingView);
        }

        new Thread(() -> {
            try {
                recorder.start(w, h);
                mainHandler.post(() -> {
                    if (recorder.isRecording()) {
                        btnRecord.setVisibility(View.GONE);
                        btnPause.setVisibility(View.VISIBLE);
                        btnPause.setText("Pause");
                        btnStop.setVisibility(View.VISIBLE);
                        
                        elapsedSeconds = 0;
                        isTimerRunning = true;
                        mainHandler.post(timerRunnable);

                        runMicMonitor = true;
                        mainHandler.post(micVolumeMonitorRunnable);
                    } else {
                        resetRecordButtonUI();
                        Toast.makeText(MainActivity.this, "Encoder allocation error.", Toast.LENGTH_LONG).show();
                    }
                    btnRecord.setEnabled(true);
                });
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> {
                    resetRecordButtonUI();
                    btnRecord.setEnabled(true);
                    Toast.makeText(MainActivity.this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void resetRecordButtonUI() {
        btnRecord.setVisibility(View.VISIBLE);
        btnRecord.setText("REC");
        btnPause.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
    }

    private void togglePauseResume() {
        if (recorder == null || !recorder.isRecording()) return;
        if (recorder.isPaused()) {
            recorder.resume();
            btnPause.setText("Pause");
            btnPause.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF9800));
            isTimerRunning = true;
            mainHandler.post(timerRunnable);
        } else {
            recorder.pause();
            btnPause.setText("Resume");
            btnPause.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF33AA33));
            isTimerRunning = false;
        }
    }

    private void stopRecordingProcess() {
        if (recorder == null) return;
        runMicMonitor = false;
        isTimerRunning = false;
        btnStop.setEnabled(false);
        
        new Thread(() -> {
            recorder.stop();
            mainHandler.post(() -> {
                micVolumeMonitor.setProgress(0);
                txtTimer.setText("00:00");
                btnStop.setVisibility(View.GONE);
                btnPause.setVisibility(View.GONE);
                btnRecord.setVisibility(View.VISIBLE);
                btnRecord.setText("REC");
                btnStop.setEnabled(true);
                
                saveCurrentProjectStateToDatabase();
                Toast.makeText(MainActivity.this, "Video Saved successfully", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isTimerRunning) return;
            elapsedSeconds++;
            txtTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60));
            mainHandler.postDelayed(this, 1000);
        }
    };

    private final Runnable micVolumeMonitorRunnable = new Runnable() {
        @Override
        public void run() {
            if (!runMicMonitor) return;
            int amplitude = recorder != null ? recorder.getMaxAmplitude() : 0;
            micVolumeMonitor.setProgress((int) (Math.min(amplitude / 32767.0f, 1.0f) * 100));
            mainHandler.postDelayed(this, 100);
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecordingProcessAsync();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String mimeType = getContentResolver().getType(uri);
        if (mimeType != null && mimeType.contains("pdf")) { processPdfImport(uri); } 
        else { processImageImport(uri); }
    }

    private String saveMediaToLocalCache(Uri uri, String extension) {
        try {
            File cacheDir = new File(getFilesDir(), "whiteboard_media");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File destFile = new File(cacheDir, "img_" + System.currentTimeMillis() + extension);
            
            try (InputStream is = getContentResolver().openInputStream(uri);
                 FileOutputStream os = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
            return destFile.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void processImageImport(Uri uri) {
        new AlertDialog.Builder(this)
            .setTitle("Import Image")
            .setPositiveButton("Current Slide", (d, w) -> loadMediaStream(uri, false))
            .setNegativeButton("New Slide", (d, w) -> loadMediaStream(uri, true))
            .show();
    }

    private void loadMediaStream(Uri uri, boolean newPage) {
        String permanentPath = saveMediaToLocalCache(uri, ".png");
        if (permanentPath == null) return;

        Bitmap bmp = BitmapFactory.decodeFile(permanentPath);
        if (bmp != null) {
            if (newPage) drawingView.nextPage();
            drawingView.importMedia(bmp, permanentPath);
            refreshSlideTrayPanel();
        }
    }

    private void processPdfImport(Uri uri) {
        new AlertDialog.Builder(this)
            .setTitle("Import PDF")
            .setPositiveButton("Split All Pages", (d, w) -> renderPdfPages(uri, true))
            .setNegativeButton("Page 1 Only", (d, w) -> renderPdfPages(uri, false))
            .show();
    }

    private void renderPdfPages(Uri uri, boolean allPages) {
        try {
            File file = new File(getCacheDir(), "doc.pdf");
            try (InputStream is = getContentResolver().openInputStream(uri); FileOutputStream os = new FileOutputStream(file)) {
                byte[] b = new byte[1024]; int r;
                while ((r = is.read(b)) != -1) os.write(b, 0, r);
            }
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer render = new PdfRenderer(pfd);
            int count = allPages ? render.getPageCount() : 1;
            
            File mediaCacheDir = new File(getFilesDir(), "whiteboard_media");
            if (!mediaCacheDir.exists()) mediaCacheDir.mkdirs();

            for (int i = 0; i < count; i++) {
                if (i > 0) drawingView.nextPage();
                PdfRenderer.Page p = render.openPage(i);
                
                Bitmap bmp = Bitmap.createBitmap(p.getWidth() * 2, p.getHeight() * 2, Bitmap.Config.ARGB_8888);
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                
                File pageCacheFile = new File(mediaCacheDir, "pdf_page_" + System.currentTimeMillis() + "_" + i + ".png");
                try (FileOutputStream fos = new FileOutputStream(pageCacheFile)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                
                drawingView.importMedia(bmp, pageCacheFile.getAbsolutePath());
                p.close();
            }
            render.close(); pfd.close(); 
            refreshSlideTrayPanel();
        } catch (Exception e) { e.printStackTrace(); }
    }

    @Override
    public void onBackPressed() {
        saveCurrentProjectStateToDatabase();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        runMicMonitor = false;
        isTimerRunning = false;
    }
}
