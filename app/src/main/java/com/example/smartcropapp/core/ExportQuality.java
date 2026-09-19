package com.example.smartcropapp.core;

import java.io.Serializable;

/**
 * Export Quality options for SmartReframe video rendering.
 */
public enum ExportQuality implements Serializable {
    AUTO("Auto"),
    P720("720p"),
    P1080("1080p");

    private final String displayName;

    ExportQuality(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static class ResolutionConfig {
        public final int width;
        public final int height;
        public final int bitrate;

        public ResolutionConfig(int width, int height, int bitrate) {
            this.width = width;
            this.height = height;
            this.bitrate = bitrate;
        }

        @Override
        public String toString() {
            return width + "x" + height + " @" + bitrate + "bps";
        }
    }

    /**
     * Resolves output resolution and bitrate based on the selected quality and source dimensions.
     * - Maintains the aspect ratio of SmartReframe (9:16 portrait).
     * - 720p produces max 1280x720 (720x1280 in 9:16).
     * - 1080p produces max 1920x1080 (1080x1920 in 9:16).
     * - Auto determines resolution automatically based on source video.
     * - If source resolution is lower than target, avoids unnecessary upscaling.
     */
    public static ResolutionConfig resolveResolution(ExportQuality quality, int srcWidth, int srcHeight) {
        if (quality == null) {
            quality = AUTO;
        }

        int maxSrc = Math.max(srcWidth, srcHeight);
        int minSrc = Math.min(srcWidth, srcHeight);

        int targetWidth;
        int targetHeight;

        switch (quality) {
            case P1080:
                // Target: 1080p (1080x1920, bounded by 1920x1080 maintaining 9:16)
                // If source resolution is lower than 1080p, cap to avoid upscaling
                if (minSrc >= 1080 || maxSrc >= 1920) {
                    targetWidth = 1080;
                    targetHeight = 1920;
                } else if (minSrc >= 720 || maxSrc >= 1280) {
                    targetWidth = 720;
                    targetHeight = 1280;
                } else if (minSrc >= 540 || maxSrc >= 960) {
                    targetWidth = 540;
                    targetHeight = 960;
                } else {
                    targetWidth = 360;
                    targetHeight = 640;
                }
                break;

            case P720:
                // Target: 720p (720x1280, bounded by 1280x720 maintaining 9:16)
                // If source resolution is lower than 720p, cap to avoid upscaling
                if (minSrc >= 720 || maxSrc >= 1280) {
                    targetWidth = 720;
                    targetHeight = 1280;
                } else if (minSrc >= 540 || maxSrc >= 960) {
                    targetWidth = 540;
                    targetHeight = 960;
                } else {
                    targetWidth = 360;
                    targetHeight = 640;
                }
                break;

            case AUTO:
            default:
                // Auto determines resolution automatically based on source video
                if (minSrc >= 1080 || maxSrc >= 1920) {
                    targetWidth = 1080;
                    targetHeight = 1920;
                } else if (minSrc >= 720 || maxSrc >= 1280) {
                    targetWidth = 720;
                    targetHeight = 1280;
                } else if (minSrc >= 540 || maxSrc >= 960) {
                    targetWidth = 540;
                    targetHeight = 960;
                } else {
                    targetWidth = 360;
                    targetHeight = 640;
                }
                break;
        }

        // Calculate bitrate proportional to pixel count (base 2.5 Mbps for 720x1280)
        long basePixels = 720L * 1280L;
        long targetPixels = (long) targetWidth * (long) targetHeight;
        int targetBitrate = (int) (2_500_000L * targetPixels / basePixels);

        return new ResolutionConfig(targetWidth, targetHeight, targetBitrate);
    }
}
