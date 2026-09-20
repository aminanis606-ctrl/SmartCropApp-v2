package com.example.smartcropapp.core;

import java.io.Serializable;

/**
 * Export Quality options for SmartReframe video rendering.
 */
public enum ExportQuality implements Serializable {
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
     * - 720p produces target 720x1280 (without upscaling source higher than 720p).
     * - 1080p produces target 1080x1920 without falling back based on source resolution.
     */
    public static ResolutionConfig resolveResolution(ExportQuality quality, int srcWidth, int srcHeight) {
        if (quality == null) {
            quality = P720;
        }

        int maxSrc = Math.max(srcWidth, srcHeight);
        int minSrc = Math.min(srcWidth, srcHeight);

        int targetWidth;
        int targetHeight;

        switch (quality) {
            case P1080:
                // Target: 1080p (1080x1920). JANGAN fallback berdasarkan resolusi source.
                // Mengizinkan upscale dari source 720p atau lainnya.
                targetWidth = 1080;
                targetHeight = 1920;
                break;

            case P720:
            default:
                // Target: 720p (720x1280). Jangan upscale source di atas target 720p.
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
        }

        // Calculate bitrate proportional to pixel count (base 2.5 Mbps for 720x1280)
        long basePixels = 720L * 1280L;
        long targetPixels = (long) targetWidth * (long) targetHeight;
        int targetBitrate = (int) (2_500_000L * targetPixels / basePixels);

        return new ResolutionConfig(targetWidth, targetHeight, targetBitrate);
    }
}
