package com.example.smartcropapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.smartcropapp.core.ExportQuality;

import org.junit.Test;

public class ExportQualityTest {

    private static final double EPSILON = 0.001;
    private static final double TARGET_ASPECT_RATIO = 9.0 / 16.0;

    @Test
    public void testAutoQualityWith1080pSource() {
        // Landscape 1080p source (1920x1080)
        ExportQuality.ResolutionConfig resLandscape =
                ExportQuality.resolveResolution(ExportQuality.AUTO, 1920, 1080);
        assertEquals(1080, resLandscape.width);
        assertEquals(1920, resLandscape.height);
        assertEquals(TARGET_ASPECT_RATIO, (double) resLandscape.width / resLandscape.height, EPSILON);

        // Portrait 1080p source (1080x1920)
        ExportQuality.ResolutionConfig resPortrait =
                ExportQuality.resolveResolution(ExportQuality.AUTO, 1080, 1920);
        assertEquals(1080, resPortrait.width);
        assertEquals(1920, resPortrait.height);
        assertEquals(TARGET_ASPECT_RATIO, (double) resPortrait.width / resPortrait.height, EPSILON);
    }

    @Test
    public void testAutoQualityWith720pSource() {
        // Landscape 720p source (1280x720)
        ExportQuality.ResolutionConfig resLandscape =
                ExportQuality.resolveResolution(ExportQuality.AUTO, 1280, 720);
        assertEquals(720, resLandscape.width);
        assertEquals(1280, resLandscape.height);
        assertEquals(TARGET_ASPECT_RATIO, (double) resLandscape.width / resLandscape.height, EPSILON);

        // Portrait 720p source (720x1280)
        ExportQuality.ResolutionConfig resPortrait =
                ExportQuality.resolveResolution(ExportQuality.AUTO, 720, 1280);
        assertEquals(720, resPortrait.width);
        assertEquals(1280, resPortrait.height);
        assertEquals(TARGET_ASPECT_RATIO, (double) resPortrait.width / resPortrait.height, EPSILON);
    }

    @Test
    public void testAutoQualityWithLowResSource() {
        // 640x480 source
        ExportQuality.ResolutionConfig res =
                ExportQuality.resolveResolution(ExportQuality.AUTO, 640, 480);
        assertTrue("Output width should not upscale beyond source dimensions", res.width <= 480);
        assertTrue("Output height should not upscale beyond source dimensions", res.height <= 640);
        assertEquals(TARGET_ASPECT_RATIO, (double) res.width / res.height, EPSILON);
    }

    @Test
    public void test720pQualityWith1080pSource() {
        // 720p on 1080p source should produce 720x1280 (max 1280x720 keeping 9:16)
        ExportQuality.ResolutionConfig res =
                ExportQuality.resolveResolution(ExportQuality.P720, 1920, 1080);
        assertEquals(720, res.width);
        assertEquals(1280, res.height);
        assertEquals(TARGET_ASPECT_RATIO, (double) res.width / res.height, EPSILON);
    }

    @Test
    public void test720pQualityWith720pSource() {
        ExportQuality.ResolutionConfig res =
                ExportQuality.resolveResolution(ExportQuality.P720, 1280, 720);
        assertEquals(720, res.width);
        assertEquals(1280, res.height);
        assertEquals(TARGET_ASPECT_RATIO, (double) res.width / res.height, EPSILON);
    }

    @Test
    public void test720pQualityWithLowResSourceDoesNotUpscale() {
        // Source is 640x360, selecting 720p should NOT upscale to 720x1280
        ExportQuality.ResolutionConfig res =
                ExportQuality.resolveResolution(ExportQuality.P720, 640, 360);
        assertTrue("Width should not upscale to 720 on 360p source", res.width < 720);
        assertTrue("Height should not upscale to 1280 on 360p source", res.height < 1280);
        assertEquals(TARGET_ASPECT_RATIO, (double) res.width / res.height, EPSILON);
    }

    @Test
    public void test1080pQualityWith1080pSource() {
        ExportQuality.ResolutionConfig res =
                ExportQuality.resolveResolution(ExportQuality.P1080, 1920, 1080);
        assertEquals(1080, res.width);
        assertEquals(1920, res.height);
        assertEquals(TARGET_ASPECT_RATIO, (double) res.width / res.height, EPSILON);
    }

    @Test
    public void test1080pQualityWith720pSourceDoesNotUpscale() {
        // Source is 1280x720, selecting 1080p should NOT upscale to 1080x1920
        ExportQuality.ResolutionConfig res =
                ExportQuality.resolveResolution(ExportQuality.P1080, 1280, 720);
        assertEquals(720, res.width);
        assertEquals(1280, res.height);
        assertEquals(TARGET_ASPECT_RATIO, (double) res.width / res.height, EPSILON);
    }

    @Test
    public void test1080pQualityWithLowResSourceDoesNotUpscale() {
        // Source is 640x360, selecting 1080p should NOT upscale to 1080x1920
        ExportQuality.ResolutionConfig res =
                ExportQuality.resolveResolution(ExportQuality.P1080, 640, 360);
        assertTrue("Width should not upscale to 1080 on 360p source", res.width < 720);
        assertTrue("Height should not upscale to 1920 on 360p source", res.height < 1280);
        assertEquals(TARGET_ASPECT_RATIO, (double) res.width / res.height, EPSILON);
    }
}
