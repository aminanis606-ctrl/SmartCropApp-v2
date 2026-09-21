package com.example.smartcropapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.media.MediaMetadataRetriever;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.smartcropapp.core.Pass1Extractor;
import com.example.smartcropapp.core.Pass2Optimizer;
import com.example.smartcropapp.core.Pass3Renderer;
import com.example.smartcropapp.core.ExportQuality;
import com.example.smartcropapp.smartreframe.SmartReframeOrchestrator;

import android.widget.RadioButton;
import android.widget.RadioGroup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "IkhlasApp";
    
    private TextView statusText;
    private TextView tvSelectionStatus;
    private Button btnPickVideos;
    private Button btnStart;
    private RadioGroup rgExportQuality;
    private RadioButton rbQuality720p;
    private RadioButton rbQuality1080p;
    private Uri selectedVideoUri;
    private List<Uri> selectedVideoUris = new ArrayList<>();
    private ActivityResultLauncher<String[]> pickMultipleMedia;
    private boolean isProcessing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initializeStorageStructure();
        setupUI();
        setupMediaPicker();
        statusText.setText("Siap. Pilih video untuk mulai.");
    }

    private void setupUI() {
        statusText = findViewById(R.id.statusText);
        tvSelectionStatus = findViewById(R.id.tvSelectionStatus);
        btnPickVideos = findViewById(R.id.btnPickVideos);
        btnStart = findViewById(R.id.btnStart);
        rgExportQuality = findViewById(R.id.rgExportQuality);
        rbQuality720p = findViewById(R.id.rbQuality720p);
        rbQuality1080p = findViewById(R.id.rbQuality1080p);

        updateSelectionStatusUI();

        btnPickVideos.setOnClickListener(v -> {
            if (isProcessing) return;
            
            if (!checkStoragePermission()) {
                requestStoragePermission();
                return;
            }
            
            pickMultipleMedia.launch(new String[]{"video/*"});
        });

        btnStart.setText("MULAI PROSES");
        btnStart.setOnClickListener(v -> {
            if (isProcessing) return;

            if (selectedVideoUris == null || selectedVideoUris.isEmpty()) {
                Toast.makeText(MainActivity.this, "Pilih video terlebih dahulu!", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!checkStoragePermission()) {
                requestStoragePermission();
                return;
            }

            startBatchPipeline(selectedVideoUris);
        });
    }

    private void updateSelectionStatusUI() {
        if (tvSelectionStatus == null) return;
        if (selectedVideoUris == null || selectedVideoUris.isEmpty()) {
            tvSelectionStatus.setText("Belum ada video dipilih");
        } else if (selectedVideoUris.size() == 1) {
            tvSelectionStatus.setText("1 video dipilih");
        } else {
            tvSelectionStatus.setText(selectedVideoUris.size() + " video dipilih");
        }
    }

    public ExportQuality getSelectedExportQuality() {
        if (rgExportQuality == null) {
            return ExportQuality.P720;
        }
        int checkedId = rgExportQuality.getCheckedRadioButtonId();
        if (checkedId == R.id.rbQuality1080p) {
            return ExportQuality.P1080;
        } else {
            return ExportQuality.P720;
        }
    }

    public void setExportQuality(ExportQuality quality) {
        if (quality == null || rgExportQuality == null) return;
        switch (quality) {
            case P1080:
                if (rbQuality1080p != null) rbQuality1080p.setChecked(true);
                break;
            case P720:
            default:
                if (rbQuality720p != null) rbQuality720p.setChecked(true);
                break;
        }
    }

    private void setExportQualityEnabled(boolean enabled) {
        if (rgExportQuality != null) {
            for (int i = 0; i < rgExportQuality.getChildCount(); i++) {
                rgExportQuality.getChildAt(i).setEnabled(enabled);
            }
        }
    }

    private void setupMediaPicker() {
        pickMultipleMedia = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris != null && !uris.isEmpty()) {
                        selectedVideoUris = new ArrayList<>(uris);
                        selectedVideoUri = uris.get(0);
                    }
                    updateSelectionStatusUI();
                });
    }

    private String getVideoId(Uri uri) {
        if (uri == null) return "unknown";

        try (Cursor c = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {

            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("(\\d+)").matcher(name);

                if (m.find()) return m.group(1);
            }
        } catch (Exception e) {
            Log.w(TAG, "Gagal mengambil video ID", e);
        }

        return "unknown";
    }

    private void startAutoPipeline() {
        if (selectedVideoUris != null && !selectedVideoUris.isEmpty()) {
            startBatchPipeline(selectedVideoUris);
        } else if (selectedVideoUri != null) {
            startBatchPipeline(Collections.singletonList(selectedVideoUri));
        } else {
            Toast.makeText(this, "Pilih video terlebih dahulu!", Toast.LENGTH_SHORT).show();
        }
    }

    public void startBatchPipeline(List<Uri> videoUris) {
        if (videoUris == null || videoUris.isEmpty()) {
            Toast.makeText(this, "Pilih video terlebih dahulu!", Toast.LENGTH_SHORT).show();
            return;
        }

        final List<Uri> queue = new ArrayList<>(videoUris);
        final int totalVideos = queue.size();
        final ExportQuality exportQuality = getSelectedExportQuality();

        isProcessing = true;
        setExportQualityEnabled(false);
        if (btnPickVideos != null) btnPickVideos.setEnabled(false);
        if (btnStart != null) btnStart.setEnabled(false);

        if (totalVideos == 1) {
            statusText.setText("Memulai Pipeline Otomatis (" + exportQuality.getDisplayName() + ")...");
        } else {
            statusText.setText("Memulai Batch Processing (" + totalVideos + " video, " + exportQuality.getDisplayName() + ")...");
        }

        new Thread(() -> {
            int successCount = 0;
            int failureCount = 0;
            List<String> failedVideos = new ArrayList<>();

            File outputDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "IkhlasApp");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            for (int i = 0; i < totalVideos; i++) {
                final int currentIndex = i + 1;
                final Uri currentUri = queue.get(i);
                final String videoId = getVideoId(currentUri);
                final String progressPrefix = totalVideos > 1
                        ? ("Memproses video " + currentIndex + "/" + totalVideos + ": ")
                        : "";

                File internalAnalysis = new File(getFilesDir(), "analysis_" + currentIndex + "_" + videoId + ".json");
                File internalTrajectory = new File(getFilesDir(), "trajectory_" + currentIndex + "_" + videoId + ".json");

                // Ensure no previous state is carried over
                if (internalAnalysis.exists()) internalAnalysis.delete();
                if (internalTrajectory.exists()) internalTrajectory.delete();

                try {
                    SmartReframeOrchestrator orchestrator = new SmartReframeOrchestrator(
                            this,
                            currentUri,
                            videoId,
                            exportQuality);

                    // PHASE 1 / PASS 1
                    runOnUiThread(() ->
                            statusText.setText(progressPrefix + "Pass 1: Analisis Video..."));

                    orchestrator.runPass1(internalAnalysis);

                    // OUTPUT PLANNING
                    File outputVideo = createUniqueOutputFile(
                            currentUri,
                            internalAnalysis,
                            outputDir);

                    // PHASE 2 / PASS 2 + PASS 3
                    runOnUiThread(() ->
                            statusText.setText(progressPrefix + "Pass 2: Optimasi Gerakan..."));

                    runOnUiThread(() ->
                            statusText.setText(progressPrefix + "Pass 3: Rendering Video (" + exportQuality.getDisplayName() + ")..."));

                    orchestrator.runPass2AndPass3(
                            internalTrajectory,
                            outputVideo,
                            exportQuality);

                    successCount++;
                    Log.i(TAG, "Video " + currentIndex + "/" + totalVideos + " (" + videoId + ") selesai: " + outputVideo.getAbsolutePath());

                } catch (Exception e) {
                    failureCount++;
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    Log.e(TAG, "Gagal memproses video " + currentIndex + "/" + totalVideos + " (" + videoId + ")", e);
                    failedVideos.add("Video " + currentIndex + " (" + videoId + "): " + errorMsg);
                } finally {
                    // Clean up temporary files per video to guarantee safe isolation
                    if (internalAnalysis.exists()) internalAnalysis.delete();
                    if (internalTrajectory.exists()) internalTrajectory.delete();
                }
            }

            final int finalSuccess = successCount;
            final int finalFailure = failureCount;

            runOnUiThread(() -> {
                isProcessing = false;
                setExportQualityEnabled(true);
                if (btnPickVideos != null) btnPickVideos.setEnabled(true);
                if (btnStart != null) btnStart.setEnabled(true);

                if (totalVideos == 1) {
                    if (finalSuccess == 1) {
                        statusText.setText("SELESAI! Video tersimpan di Movies/IkhlasApp/\n(Berhasil: 1, Gagal: 0)");
                        Toast.makeText(MainActivity.this, "Proses berhasil!", Toast.LENGTH_LONG).show();
                    } else {
                        statusText.setText("Gagal memproses video.\n(Berhasil: 0, Gagal: 1)");
                        Toast.makeText(MainActivity.this, "Proses gagal!", Toast.LENGTH_LONG).show();
                    }
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Batch Selesai!\n");
                    sb.append("Berhasil: ").append(finalSuccess).append(" | Gagal: ").append(finalFailure);
                    if (finalSuccess > 0) {
                        sb.append("\nVideo tersimpan di Movies/IkhlasApp/");
                    }
                    if (finalFailure > 0) {
                        sb.append("\n(").append(finalFailure).append(" video gagal)");
                    }
                    statusText.setText(sb.toString());

                    Toast.makeText(
                            MainActivity.this,
                            "Batch selesai: " + finalSuccess + " berhasil, " + finalFailure + " gagal",
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private File createUniqueOutputFile(
            Uri sourceUri,
            File analysisFile,
            File outputDir) throws Exception {

        MediaMetadataRetriever mmr =
                new MediaMetadataRetriever();

        try {
            mmr.setDataSource(this, sourceUri);

            String durationStr =
                    mmr.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION);

            long durationMs =
                    durationStr == null
                            ? 0L
                            : Long.parseLong(durationStr);

            long totalSeconds =
                    Math.max(0L, durationMs / 1000L);

            long minutes = totalSeconds / 60L;
            long seconds = totalSeconds % 60L;

            String duration =
                    String.format(
                            Locale.US,
                            "%02d:%02d",
                            minutes,
                            seconds);

            org.json.JSONObject root =
                    new org.json.JSONObject(
                            readTextFile(analysisFile));

            org.json.JSONArray shots =
                    root.optJSONArray("shots");

            int shotCount =
                    shots == null ? 0 : shots.length();

            int splitCount = 0;

            if (shots != null) {
                for (int i = 0; i < shots.length(); i++) {
                    if ("split".equals(
                            shots.getJSONObject(i)
                                    .optString("layout"))) {
                        splitCount++;
                    }
                }
            }

            int singleCount =
                    Math.max(0, shotCount - splitCount);

            String sourceName =
                    "video";

            String uriName =
                    sourceUri.getLastPathSegment();

            if (uriName != null && !uriName.isEmpty()) {
                int slash = uriName.lastIndexOf('/');
                if (slash >= 0) {
                    uriName = uriName.substring(slash + 1);
                }

                int dot = uriName.lastIndexOf('.');
                if (dot > 0) {
                    uriName = uriName.substring(0, dot);
                }

                if (!uriName.isEmpty()) {
                    sourceName = uriName;
                }
            }

            sourceName =
                    sourceName.replaceAll(
                            "[^A-Za-z0-9_-]",
                            "_");

            String base =
                    String.format(
                            Locale.US,
                            "%s_SHOT%d_SPLIT%d_SINGLE%d_%s",
                            getVideoId(sourceUri),
                            shotCount,
                            splitCount,
                            singleCount,
                            duration);

            File result =
                    new File(
                            outputDir,
                            base + ".mp4");

            int index = 1;

            while (result.exists()) {
                result =
                        new File(
                                outputDir,
                                String.format(
                                        Locale.US,
                                        "%s_%03d.mp4",
                                        base,
                                        index++));
            }

            return result;

        } finally {
            mmr.release();
        }
    }

    private String readTextFile(File file) throws Exception {
        try (InputStream in =
                     new java.io.FileInputStream(file)) {

            java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream();

            byte[] buffer = new byte[4096];
            int n;

            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }

            return out.toString("UTF-8");
        }
    }

    private void checkPermissions() {
        if (!checkStoragePermission()) {
            requestStoragePermission();
        } else {
            statusText.setText("Siap. Klik MULAI PROSES.");
        }
    }

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
        }
    }

    private void initializeStorageStructure() {
        try {
            File baseDir = new File(
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS),
                    "IkhlasApp");

            File configDir = new File(baseDir, "config");
            if (!configDir.exists()) configDir.mkdirs();

            File manualSplitFile =
                    new File(configDir, "manual_split.txt");

            AssetManager assetManager = getAssets();
            InputStream in =
                    assetManager.open("manual_split.txt");

            OutputStream out =
                    new FileOutputStream(manualSplitFile, false);

            byte[] buffer = new byte[1024];
            int read;

            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

            in.close();
            out.close();

        } catch (Exception e) {
            Log.e(TAG, "Init storage error", e);
        }
    }
}
