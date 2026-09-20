package com.example.smartcropapp.sr;

import android.opengl.GLES20;
import java.nio.ByteBuffer;

public final class SrFrameBuffer {
    public final int width, height;
    private final int fbo, texture;

    public SrFrameBuffer(int width, int height) {
        this.width = width;
        this.height = height;

        int[] v = new int[1];
        GLES20.glGenTextures(1, v, 0);
        texture = v[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        GLES20.glGenFramebuffers(1, v, 0);
        fbo = v[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texture, 0);

        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("SR framebuffer incomplete");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    public void bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, width, height);
    }

    public void unbind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    public ByteBuffer readPixels() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
        GLES20.glFinish();
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, buffer);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        buffer.rewind();
        return buffer;
    }

    public void release() {
        int[] v = {fbo};
        GLES20.glDeleteFramebuffers(1, v, 0);
        v[0] = texture;
        GLES20.glDeleteTextures(1, v, 0);
    }
}
