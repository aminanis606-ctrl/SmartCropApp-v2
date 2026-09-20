package com.example.smartcropapp.smartreframe;

import android.content.Context;
import android.net.Uri;

import com.example.smartcropapp.core.ExportQuality;
import com.example.smartcropapp.nalaros.Artifact;
import com.example.smartcropapp.nalaros.ExecutionEngine;
import com.example.smartcropapp.nalaros.Task;

import java.io.File;
import java.util.Arrays;

public class SmartReframeOrchestrator {

    private final Context context;
    private final Uri sourceVideoUri;
    private final String videoId;
    private Artifact analysisArtifact;
    private ExportQuality exportQuality = ExportQuality.P720;

    public SmartReframeOrchestrator(
            Context context,
            Uri sourceVideoUri,
            String videoId) {
        this(context, sourceVideoUri, videoId, ExportQuality.P720);
    }

    public SmartReframeOrchestrator(
            Context context,
            Uri sourceVideoUri,
            String videoId,
            ExportQuality exportQuality) {

        this.context = context;
        this.sourceVideoUri = sourceVideoUri;
        this.videoId = videoId;
        this.exportQuality = exportQuality != null ? exportQuality : ExportQuality.P720;
    }

    public Context getContext() {
        return context;
    }

    public Uri getSourceVideoUri() {
        return sourceVideoUri;
    }

    public String getVideoId() {
        return videoId;
    }

    public ExportQuality getExportQuality() {
        return exportQuality;
    }

    public void setExportQuality(ExportQuality exportQuality) {
        this.exportQuality = exportQuality != null ? exportQuality : ExportQuality.P720;
    }

    public Artifact runPass1(File analysisFile) throws Exception {
        SmartReframeTask configuration =
                new SmartReframeTask(
                        context,
                        sourceVideoUri,
                        videoId,
                        exportQuality);

        Task task =
                new Task(
                        "smartreframe.pass1",
                        sourceVideoUri,
                        configuration,
                        java.util.Collections.singletonList(
                                new Pass1Stage(analysisFile)));

        analysisArtifact =
                new ExecutionEngine().execute(task);

        if (analysisArtifact == null ||
                !analysisArtifact.isValid()) {
            throw new IllegalStateException(
                    "Artifact analysis dari Pass1 tidak valid.");
        }

        return analysisArtifact;
    }

    public Artifact runPass2AndPass3(
            File trajectoryFile,
            File outputVideoFile) throws Exception {

        SmartReframeTask configuration =
                new SmartReframeTask(
                        context,
                        sourceVideoUri,
                        videoId,
                        exportQuality);

        if (analysisArtifact == null ||
                !analysisArtifact.isValid()) {
            throw new IllegalStateException(
                    "Artifact analysis dari Pass1 tidak tersedia.");
        }

        configuration.setAnalysisArtifact(analysisArtifact);

        Task task =
                new Task(
                        "smartreframe.pass2-pass3",
                        sourceVideoUri,
                        configuration,
                        Arrays.asList(
                                new Pass2Stage(trajectoryFile),
                                new Pass3Stage(outputVideoFile, exportQuality)));

        return new ExecutionEngine().execute(task);
    }

    public Artifact runPass2AndPass3(
            File trajectoryFile,
            File outputVideoFile,
            ExportQuality quality) throws Exception {
        if (quality != null) {
            this.exportQuality = quality;
        }
        return runPass2AndPass3(trajectoryFile, outputVideoFile);
    }
}
