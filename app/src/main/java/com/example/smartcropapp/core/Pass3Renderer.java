package com.example.smartcropapp.core;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.GLES20;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import com.example.smartcropapp.render.CropShaderProgram;
import com.example.smartcropapp.render.GlRenderContext;
import com.example.smartcropapp.sr.RealEsrgan;
import com.example.smartcropapp.sr.SrFrameBuffer;
import com.example.smartcropapp.sr.TextureShaderProgram;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;

public class Pass3Renderer {
    private static final String TAG = "Pass3Renderer";
    private static final String OUTPUT_MIME = "video/avc";
    private static final int DEFAULT_OUTPUT_WIDTH = 720;
    private static final int DEFAULT_OUTPUT_HEIGHT = 1280;
    private static final int DEFAULT_OUTPUT_BITRATE = 2_500_000;
    private static final int OUTPUT_FPS = 30;
    private static final long TIMEOUT_US = 10000;

    public static File render(Context context, Uri sourceVideoUri, File trajectoryFile, File outputVideoFile) {
        return render(context, sourceVideoUri, trajectoryFile, outputVideoFile, ExportQuality.P720);
    }

    public static File render(Context context, Uri sourceVideoUri, File trajectoryFile, File outputVideoFile, ExportQuality exportQuality) {
        if (context == null || sourceVideoUri == null || trajectoryFile == null || outputVideoFile == null) {
            throw new NullPointerException("Parameter render tidak boleh null");
        }
        if (exportQuality == null) {
            exportQuality = ExportQuality.P720;
        }
        
        Log.d(TAG, "Starting Pass 3 with quality: " + exportQuality.getDisplayName() + "...");
        
        TrajectoryReader trajectory = null;
        try {
            trajectory = TrajectoryReader.load(trajectoryFile);
        } catch (Exception e) {
            Log.w(TAG, "Gagal memuat trajectory, menggunakan default center crop", e);
        }

        try {
            renderVideoTrack(context, sourceVideoUri, outputVideoFile, trajectory, exportQuality);
            Log.d(TAG, "Pass 3 Finished Successfully");
            return outputVideoFile;
        } catch (Exception e) {
            throw new RuntimeException("Pass 3 gagal: " + e.getMessage(), e);
        }
    }

    private static void renderVideoTrack(Context context, Uri sourceVideoUri, File outputFile, TrajectoryReader trajectory, ExportQuality exportQuality) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, sourceVideoUri, null);

        int videoTrackIndex = -1;
        int audioTrackIndex = -1;
        MediaFormat inputFormat = null;
        MediaFormat audioFormat = null;

        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                videoTrackIndex = i;
                inputFormat = format;
            } else if (mime != null && mime.startsWith("audio/")) {
                audioTrackIndex = i;
                audioFormat = format;
            }
        }
        if (videoTrackIndex == -1) throw new RuntimeException("Tidak ada video track");

        int srcWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        int srcHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);

        ExportQuality.ResolutionConfig config = ExportQuality.resolveResolution(exportQuality, srcWidth, srcHeight);
        int outputWidth = config.width;
        int outputHeight = config.height;
        int outputBitrate = config.bitrate;

        Log.d(TAG, "Quality: " + exportQuality + ", Source: " + srcWidth + "x" + srcHeight
                + " -> Output: " + outputWidth + "x" + outputHeight + " @" + outputBitrate + "bps");

        // 1. Setup Encoder
        MediaFormat outputFormat = MediaFormat.createVideoFormat(OUTPUT_MIME, outputWidth, outputHeight);
        outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, outputBitrate);
        outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FPS);
        outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

        MediaCodec encoder = MediaCodec.createEncoderByType(OUTPUT_MIME);
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface encoderSurface = encoder.createInputSurface();

        // 2. Setup GL Context
        GlRenderContext glContext = new GlRenderContext();
        glContext.setupEncoderSurface(encoderSurface);

        // 3. Decoder via GlRenderContext
        Surface decoderSurface = glContext.getDecoderInputSurface();
        if (decoderSurface == null) throw new RuntimeException("GlRenderContext gagal menyediakan decoder surface");

        MediaCodec decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        decoder.configure(inputFormat, decoderSurface, null, 0);

        MediaMuxer muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        decoder.start();
        encoder.start();

        CropShaderProgram shader = new CropShaderProgram();
        RealEsrgan realEsrgan = new RealEsrgan(context);
        // Direct x4 SR: generate exactly the encoder resolution.
        // Avoid the old x4 -> 4K -> 1080p overcompute/downsample path.
        int srW = Math.max(1, outputWidth / 4);
        int srH = Math.max(1, outputHeight / 4);
        SrFrameBuffer srFbo = new SrFrameBuffer(srW, srH);
        TextureShaderProgram srShader = new TextureShaderProgram();
        int[] srTexture = new int[1];
        GLES20.glGenTextures(1, srTexture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srTexture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        final int[] muxerVideoTrackRef = {-1};
        final int[] muxerAudioTrackRef = {-1};
        final boolean[] muxerStartedRef = {false};

        boolean inputDone = false;
        boolean encoderDone = false;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        final int panelH = outputHeight / 2;
        final int srPanelH = srH / 2;

        // --- AUDIO COPY THREAD ---
        Thread audioThread = null;
        if (audioTrackIndex != -1 && audioFormat != null) {
            final Uri finalSourceUri = sourceVideoUri;
            final Context finalContext = context;
            final int finalAudioTrackIndex = audioTrackIndex;

            audioThread = new Thread(() -> {
                try {
                    MediaExtractor audioExtractor = new MediaExtractor();
                    audioExtractor.setDataSource(finalContext, finalSourceUri, null);
                    audioExtractor.selectTrack(finalAudioTrackIndex);

                    MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
                    ByteBuffer audioBuf = ByteBuffer.allocate(1024 * 1024);

                    while (!muxerStartedRef[0]) {
                        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                    }

                    while (true) {
                        audioBuf.clear();
                        int sampleSize = audioExtractor.readSampleData(audioBuf, 0);
                        if (sampleSize < 0) break;

                        long time = audioExtractor.getSampleTime();
                        int flags = audioExtractor.getSampleFlags();

                        audioInfo.set(0, sampleSize, time, flags);
                        audioBuf.position(0);
                        audioBuf.limit(sampleSize);

                        muxer.writeSampleData(muxerAudioTrackRef[0], audioBuf, audioInfo);

                        if (!audioExtractor.advance()) break;
                    }
                    audioExtractor.release();
                } catch (Exception e) {
                    Log.e(TAG, "Audio copy error", e);
                }
            });
            audioThread.start();
        }
        // -------------------------

        extractor.selectTrack(videoTrackIndex);

        long renderLoopCount = 0;
        while (!encoderDone) {
            renderLoopCount++;
            if ((renderLoopCount % 500) == 0) {
                Log.w(TAG, "RENDER_LOOP count=" + renderLoopCount
                        + " inputDone=" + inputDone
                        + " encoderDone=" + encoderDone);
            }

            if (!inputDone) {
                int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(inIndex);
                    int sampleSize = extractor.readSampleData(buf, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            boolean isEos = false;
            int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIndex >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) isEos = true;

                decoder.releaseOutputBuffer(outIndex, true);

                SurfaceTexture decoderST = glContext.getDecoderSurfaceTexture();
                if (decoderST != null) {
                    decoderST.updateTexImage();
                    float[] stMatrix = new float[16];
                    decoderST.getTransformMatrix(stMatrix);

                    // === SMART REFRAME LOGIC ===
                    
                    // 1. Clear entire screen ONCE
                    srFbo.bind();
            GLES20.glViewport(0, 0, srW, srH);
                    GLES20.glClearColor(0f, 0f, 0f, 1.0f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                    // Default values for safe fallback
                    String layout = "single";
                    float topX = 0.5f, topY = 0.5f;
                    float botX = 0.5f, botY = 0.5f;
                    float singleX = 0.5f, singleY = 0.5f;

                    // Try to get trajectory data
                    if (trajectory != null) {
                        try {
                            TrajectoryReader.ShotResult shot = trajectory.getShotAt(info.presentationTimeUs);

                            if (shot != null) {
                                layout = shot.layout;
                                if ("split".equals(layout)) {
                                    topX = shot.top.x;
                                    topY = shot.top.y;
                                    botX = shot.bottom.x;
                                    botY = shot.bottom.y;
                                } else {
                                    singleX = shot.single.x;
                                    singleY = shot.single.y;
                                }

                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Error reading trajectory, using default", e);
                        }
                    }

                    // Calculate aspect ratio corrected crop size
                    float cropWidthNorm = 0.40f; // For split view
                    float cropHeightNorm = clamp(
                            cropWidthNorm * (panelH / (float) outputWidth) * (srcWidth / (float) srcHeight),
                            0.1f, 1f);
                    
                    float fullCropHeightNorm = 1.0f; // For single view
                    float fullCropWidthNorm = clamp(
                            fullCropHeightNorm * (outputWidth / (float) outputHeight) * (srcHeight / (float) srcWidth),
                            0.1f, 1f);

                    if ("split".equals(layout)) {
                        // Background video underneath feathered panels.
                        GLES20.glViewport(0, 0, srW, srH);
                        GLES20.glDisable(GLES20.GL_BLEND);
                        shader.draw(
                                glContext.getDecoderTextureId(),
                                stMatrix,
                                0.50f,
                                0.50f,
                                fullCropWidthNorm,
                                fullCropHeightNorm);

                        // Split panels: feather top + bottom only.
                        GLES20.glEnable(GLES20.GL_BLEND);
                        GLES20.glBlendFunc(
                                GLES20.GL_SRC_ALPHA,
                                GLES20.GL_ONE_MINUS_SRC_ALPHA);

                        final float feather = 0.02f;

                        // Top panel
                        GLES20.glViewport(0, srPanelH, srW, srPanelH);
                        shader.draw(
                                glContext.getDecoderTextureId(),
                                stMatrix,
                                topX,
                                topY,
                                cropWidthNorm,
                                cropHeightNorm,
                                0.0f,
                                feather);

                        // Bottom panel
                        GLES20.glViewport(0, 0, srW, srPanelH);
                        shader.draw(
                                glContext.getDecoderTextureId(),
                                stMatrix,
                                botX,
                                botY,
                                cropWidthNorm,
                                cropHeightNorm,
                                feather,
                                0.0f);

                        GLES20.glDisable(GLES20.GL_BLEND);

                    } else {
                        // Draw Single Full Screen (Center Crop)
                        GLES20.glViewport(0, 0, srW, srH);
                        shader.draw(glContext.getDecoderTextureId(), stMatrix, singleX, singleY, fullCropWidthNorm, fullCropHeightNorm);
                    }
                }

                srFbo.unbind();
                java.nio.ByteBuffer srInput = srFbo.readPixels();
                int srOutW = outputWidth;
                int srOutH = outputHeight;
                java.nio.ByteBuffer srOutput = java.nio.ByteBuffer.allocateDirect(srOutW * srOutH * 4);

                try {
                    boolean srOk = realEsrgan.process(srInput, srW, srH, srOutput);
                    if (!srOk) {
                        int glError = GLES20.glGetError();
                        RuntimeException failure =
                                new RuntimeException("RealESRGAN processing failed");
                        writeRenderDiagnostic(
                                context,
                                "RealESRGAN returned false",
                                srcWidth,
                                srcHeight,
                                outputWidth,
                                outputHeight,
                                srW,
                                srH,
                                srOutW,
                                srOutH,
                                srInput,
                                srOutput,
                                glError,
                                failure);
                        throw failure;
                    }
                } catch (RuntimeException e) {
                    if (!"RealESRGAN processing failed".equals(e.getMessage())) {
                        int glError = GLES20.glGetError();
                        writeRenderDiagnostic(
                                context,
                                "RealESRGAN processing exception",
                                srcWidth,
                                srcHeight,
                                outputWidth,
                                outputHeight,
                                srW,
                                srH,
                                srOutW,
                                srOutH,
                                srInput,
                                srOutput,
                                glError,
                                e);
                    }
                    throw e;
                }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srTexture[0]);
        srOutput.rewind();
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                srOutW, srOutH, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, srOutput);
        GLES20.glViewport(0, 0, outputWidth, outputHeight);
        srShader.draw(srTexture[0]);
        glContext.setPresentationTime(info.presentationTimeUs * 1000);
                glContext.swapBuffers();

                if (isEos) encoder.signalEndOfInputStream();
            }

            int encIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (encIndex >= 0) {
                if (!muxerStartedRef[0]) {
                    muxerVideoTrackRef[0] = muxer.addTrack(encoder.getOutputFormat());
                    if (audioTrackIndex != -1) {
                        muxerAudioTrackRef[0] = muxer.addTrack(audioFormat);
                    }
                    muxer.start();
                    muxerStartedRef[0] = true;
                    Log.d(TAG, "Muxer Started");
                }

                if (info.size > 0 && muxerStartedRef[0]) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(encIndex);
                    encodedData.position(info.offset);
                    encodedData.limit(info.offset + info.size);
                    muxer.writeSampleData(muxerVideoTrackRef[0], encodedData, info);
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderDone = true;
                encoder.releaseOutputBuffer(encIndex, false);
            }
        }

        if (audioThread != null) audioThread.join();

        decoder.stop(); decoder.release();
        encoder.stop(); encoder.release();
        encoderSurface.release();
        muxer.stop(); muxer.release();
        extractor.release();
        srFbo.release();
        realEsrgan.release();
        GLES20.glDeleteTextures(1, srTexture, 0);
        glContext.release();
    }

    private static void writeRenderDiagnostic(
            Context context,
            String reason,
            int srcWidth,
            int srcHeight,
            int outputWidth,
            int outputHeight,
            int srW,
            int srH,
            int srOutW,
            int srOutH,
            ByteBuffer srInput,
            ByteBuffer srOutput,
            int glError,
            Exception exception) {

        StringWriter stackWriter = new StringWriter();
        if (exception != null) {
            exception.printStackTrace(new PrintWriter(stackWriter));
        }

        StringBuilder report = new StringBuilder();
        report.append("SmartReframe Pass3 Render Diagnostic\n");
        report.append("=====================================\n");
        report.append("Reason: ").append(reason).append('\n');
        report.append("Source: ").append(srcWidth).append('x').append(srcHeight).append('\n');
        report.append("Output: ").append(outputWidth).append('x').append(outputHeight).append('\n');
        report.append("SR Input: ").append(srW).append('x').append(srH).append('\n');
        report.append("SR Output: ").append(srOutW).append('x').append(srOutH).append('\n');
        report.append("SR Input capacity: ")
                .append(srInput != null ? srInput.capacity() : -1)
                .append('\n');
        report.append("SR Input position: ")
                .append(srInput != null ? srInput.position() : -1)
                .append('\n');
        report.append("SR Input limit: ")
                .append(srInput != null ? srInput.limit() : -1)
                .append('\n');
        report.append("SR Output capacity: ")
                .append(srOutput != null ? srOutput.capacity() : -1)
                .append('\n');
        report.append("SR Output position: ")
                .append(srOutput != null ? srOutput.position() : -1)
                .append('\n');
        report.append("SR Output limit: ")
                .append(srOutput != null ? srOutput.limit() : -1)
                .append('\n');
        report.append("GLES error: 0x")
                .append(Integer.toHexString(glError))
                .append('\n');

        if (exception != null) {
            report.append("\nException / stack trace:\n");
            report.append(stackWriter);
        }

        String fileName = "pass3_failure_" + System.currentTimeMillis() + ".txt";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "Download/SmartReframe/diagnostics");

                android.content.ContentResolver resolver =
                        context.getContentResolver();

                Uri uri = resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values);

                if (uri == null) {
                    throw new IllegalStateException("MediaStore insert returned null");
                }

                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) {
                        throw new IllegalStateException(
                                "MediaStore openOutputStream returned null");
                    }
                    out.write(report.toString().getBytes("UTF-8"));
                    out.flush();
                }
            } else {
                File diagnosticsDir = new File(
                        "/storage/emulated/0/Download/SmartReframe/diagnostics");

                if (!diagnosticsDir.exists() && !diagnosticsDir.mkdirs()) {
                    throw new IllegalStateException(
                            "Gagal membuat diagnostics directory: "
                                    + diagnosticsDir.getAbsolutePath());
                }

                File diagnosticFile = new File(diagnosticsDir, fileName);

                try (FileOutputStream out = new FileOutputStream(diagnosticFile)) {
                    out.write(report.toString().getBytes("UTF-8"));
                    out.flush();
                }
            }

            Log.e(TAG, "Render diagnostic written: " + fileName);
        } catch (Exception diagnosticError) {
            Log.e(TAG, "Gagal menulis render diagnostic", diagnosticError);
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
