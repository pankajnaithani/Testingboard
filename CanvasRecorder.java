package com.whiteboard.cleanrecord;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix; 
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;
import java.io.File;
import java.nio.ByteBuffer;

public class CanvasRecorder {

    private MediaCodec softwareEncoder;
    private Surface inputSurface;
    private int videoTrackIndex = -1;
    private Matrix videoScaleMatrix = new Matrix();

    private MediaCodec audioEncoder;
    private AudioRecord audioRecord;
    private int audioTrackIndex = -1;
    private int audioBufferSize = 0;
    private Thread audioCaptureThread;
    private volatile int currentAmplitude = 0;

    private MediaMuxer mediaMuxer;
    private volatile boolean isMuxerStarted = false;
    private final Object muxerLock = new Object();

    private volatile boolean isRecording = false; 
    private volatile boolean isPaused = false;
    private HandlerThread recordingThread;
    private Handler recordingHandler;
    private final DrawingView drawingView;
    private final Context context;
    private Bitmap frameCopyBitmap = null;
    
    // REAL-TIME SYNCHRONIZATION CLOCKS
    private long recordingStartTimeUs = 0;
    private long totalPausedTimeUs = 0;
    private long pauseStartTimeUs = 0;
    private long lastVideoPtsUs = 0;
    private long lastAudioPtsUs = 0;

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    public CanvasRecorder(Context context, DrawingView drawingView) {
        this.context = context;
        this.drawingView = drawingView;
    }

    public void start(int width, int height) {
        if (isRecording) return;
        try {
            int targetWidth = 1920;
            int targetHeight = 1080;
            isMuxerStarted = false;
            videoTrackIndex = -1;
            audioTrackIndex = -1;

            frameCopyBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 8000000); 
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);    
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            softwareEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            softwareEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = softwareEncoder.createInputSurface();
            softwareEncoder.start();

            MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();

            audioBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, audioBufferSize * 2);
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new RuntimeException("Microphone initialization failed.");
            }

            String fileName = "Whiteboard_" + System.currentTimeMillis() + ".mp4";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CleanBoard");
                Uri videoUri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (videoUri != null) {
                    ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(videoUri, "rw");
                    mediaMuxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                }
            } else {
                File dir = new File(Environment.getExternalStorageDirectory(), "Movies/CleanBoard");
                if (!dir.exists()) dir.mkdirs();
                mediaMuxer = new MediaMuxer(new File(dir, fileName).getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }

            isRecording = true;
            isPaused = false;
            
            // RESET TIME CLOCKS
            totalPausedTimeUs = 0;
            pauseStartTimeUs = 0;
            lastVideoPtsUs = 0;
            lastAudioPtsUs = 0;
            recordingStartTimeUs = System.nanoTime() / 1000;

            drawingView.startTimeSyncAnchor();
            audioRecord.startRecording();

            recordingThread = new HandlerThread("CleanVideoLoopThread");
            recordingThread.start();
            recordingHandler = new Handler(recordingThread.getLooper());
            recordingHandler.post(recordLoopRunnable);

            audioCaptureThread = new Thread(new AudioCaptureRunnable(), "CleanAudioLoopThread");
            audioCaptureThread.start();

        } catch (Exception e) {
            e.printStackTrace();
            isRecording = false;
            stop();
        }
    }

    private final Runnable recordLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording || softwareEncoder == null || frameCopyBitmap == null) return;
            try {
                if (!isPaused) {
                    drainSoftwareEncoder(false);
                    drawingView.getRecordingFrame(frameCopyBitmap);
                    
                    Canvas canvas = inputSurface.lockCanvas(null);
                    if (canvas != null) {
                        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                        p.setAntiAlias(true);
                        p.setDither(true);
                        canvas.drawBitmap(frameCopyBitmap, 0, 0, p);
                        inputSurface.unlockCanvasAndPost(canvas);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            
            if (isRecording && recordingHandler != null) {
                recordingHandler.postDelayed(this, 33);
            }
        }
    };

    private class AudioCaptureRunnable implements Runnable {
        @Override
        public void run() {
            byte[] audioBuffer = new byte[audioBufferSize];
            while (isRecording) {
                if (isPaused) {
                    try { Thread.sleep(30); } catch (Exception e) {}
                    continue;
                }

                int readSize = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                if (readSize > 0 && isRecording) {
                    calculateVolumeAmplitude(audioBuffer, readSize);
                    
                    try {
                        int inputBufferIndex = audioEncoder.dequeueInputBuffer(5000);
                        if (inputBufferIndex >= 0) {
                            ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex);
                            if (inputBuffer != null) {
                                inputBuffer.clear();
                                inputBuffer.put(audioBuffer, 0, readSize);
                                
                                // FORCE PERFECT REAL-TIME AUDIO ALIGNMENT
                                long currentPtsUs = (System.nanoTime() / 1000) - recordingStartTimeUs - totalPausedTimeUs;
                                if (currentPtsUs <= lastAudioPtsUs) {
                                    currentPtsUs = lastAudioPtsUs + 1000;
                                }
                                lastAudioPtsUs = currentPtsUs;
                                
                                audioEncoder.queueInputBuffer(inputBufferIndex, 0, readSize, currentPtsUs, 0);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                drainAudioEncoder(false);
            }
        }
    }

    private void calculateVolumeAmplitude(byte[] buffer, int size) {
        int max = 0;
        for (int i = 0; i < size - 1; i += 2) {
            short val = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xff));
            if (Math.abs(val) > max) max = Math.abs(val);
        }
        currentAmplitude = max;
    }

    private void drainSoftwareEncoder(boolean endOfStream) {
        if (softwareEncoder == null) return;
        if (endOfStream) {
            try { softwareEncoder.signalEndOfInputStream(); } catch (Exception e) {}
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (isRecording || endOfStream) {
            int encoderStatus = softwareEncoder.dequeueOutputBuffer(bufferInfo, 2000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (muxerLock) {
                    if (!isMuxerStarted) {
                        videoTrackIndex = mediaMuxer.addTrack(softwareEncoder.getOutputFormat());
                        if (videoTrackIndex != -1 && audioTrackIndex != -1) {
                            mediaMuxer.start();
                            isMuxerStarted = true;
                        }
                    }
                }
                break;
            } else if (encoderStatus >= 0) {
                ByteBuffer encodedData = softwareEncoder.getOutputBuffer(encoderStatus);
                synchronized (muxerLock) {
                    if (encodedData != null && bufferInfo.size != 0 && isMuxerStarted) {
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        
                        // ONLY FORCE PTS ON ACTIVE VIDEO FRAMES, SKIP CODEC CONFIGURATION BUFFERS
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            long currentPtsUs = (System.nanoTime() / 1000) - recordingStartTimeUs - totalPausedTimeUs;
                            if (currentPtsUs <= lastVideoPtsUs) {
                                currentPtsUs = lastVideoPtsUs + 1000;
                            }
                            lastVideoPtsUs = currentPtsUs;
                            bufferInfo.presentationTimeUs = currentPtsUs;
                        }

                        mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                    }
                }
                softwareEncoder.releaseOutputBuffer(encoderStatus, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    private void drainAudioEncoder(boolean endOfStream) {
        if (audioEncoder == null) return;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        
        while (isRecording || endOfStream) {
            int encoderStatus = audioEncoder.dequeueOutputBuffer(bufferInfo, 2000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (muxerLock) {
                    if (!isMuxerStarted) {
                        audioTrackIndex = mediaMuxer.addTrack(audioEncoder.getOutputFormat());
                        if (videoTrackIndex != -1 && audioTrackIndex != -1) {
                            mediaMuxer.start();
                            isMuxerStarted = true;
                        }
                    }
                }
                break;
            } else if (encoderStatus >= 0) {
                ByteBuffer encodedData = audioEncoder.getOutputBuffer(encoderStatus);
                synchronized (muxerLock) {
                    if (encodedData != null && bufferInfo.size != 0 && isMuxerStarted) {
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        mediaMuxer.writeSampleData(audioTrackIndex, encodedData, bufferInfo);
                    }
                }
                audioEncoder.releaseOutputBuffer(encoderStatus, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    public void pause() {
        if (isRecording && !isPaused) {
            isPaused = true;
            pauseStartTimeUs = System.nanoTime() / 1000;
            drawingView.stopTimeSyncAnchor();
        }
    }

    public void resume() {
        if (isRecording && isPaused) {
            isPaused = false;
            totalPausedTimeUs += (System.nanoTime() / 1000) - pauseStartTimeUs;
            pauseStartTimeUs = 0;
            drawingView.startTimeSyncAnchor();
        }
    }

    public int getMaxAmplitude() { 
        return currentAmplitude; 
    }

    public void stop() {
        if (!isRecording) return;
        
        isRecording = false;
        isPaused = false;
        drawingView.stopTimeSyncAnchor();

        if (recordingHandler != null) {
            recordingHandler.removeCallbacksAndMessages(null);
        }

        try {
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
            }
        } catch (Exception e) { e.printStackTrace(); }

        if (recordingThread != null) {
            recordingThread.quitSafely();
            recordingThread = null;
            recordingHandler = null;
        }

        audioCaptureThread = null;

        try {
            if (softwareEncoder != null) {
                drainSoftwareEncoder(true);
                softwareEncoder.stop();
                softwareEncoder.release();
            }
        } catch (Exception e) { e.printStackTrace(); }

        try {
            if (audioEncoder != null) {
                drainAudioEncoder(true);
                audioEncoder.stop();
                audioEncoder.release();
            }
        } catch (Exception e) { e.printStackTrace(); }

        try {
            if (mediaMuxer != null) {
                synchronized (muxerLock) {
                    if (isMuxerStarted) {
                        mediaMuxer.stop();
                    }
                }
                mediaMuxer.release();
            }
        } catch (Exception e) { e.printStackTrace(); }

        softwareEncoder = null;
        audioEncoder = null;
        audioRecord = null;
        mediaMuxer = null;
        inputSurface = null;
        frameCopyBitmap = null;
        isMuxerStarted = false;
        currentAmplitude = 0;
    }

    public boolean isRecording() { return isRecording; }
    public boolean isPaused() { return isPaused; }
}
