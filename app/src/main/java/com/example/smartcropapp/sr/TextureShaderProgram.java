package com.example.smartcropapp.sr;

import android.opengl.GLES20;

public final class TextureShaderProgram {
    private final int[] textureId = new int[1];
    private final int program;
    private final int pos;
    private final int tex;
    private final int sampler;

    public TextureShaderProgram() {
        String vs = "attribute vec4 aPosition;attribute vec2 aTexCoord;varying vec2 vTexCoord;void main(){gl_Position=aPosition;vTexCoord=aTexCoord;}";
        String fs = "precision mediump float;uniform sampler2D uTexture;varying vec2 vTexCoord;void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}";
        program = link(compile(GLES20.GL_VERTEX_SHADER, vs), compile(GLES20.GL_FRAGMENT_SHADER, fs));
        pos = GLES20.glGetAttribLocation(program, "aPosition");
        tex = GLES20.glGetAttribLocation(program, "aTexCoord");
        sampler = GLES20.glGetUniformLocation(program, "uTexture");
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException(GLES20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static int link(int vertex, int fragment) {
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException(GLES20.glGetProgramInfoLog(program));
        return program;
    }

    public int upload(java.nio.ByteBuffer buffer, int width, int height) {
        if (textureId[0] == 0) {
            GLES20.glGenTextures(1, textureId, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0]);
        }
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
        return textureId[0];
    }

    public int textureId() {
        return textureId[0];
    }

    public void draw(int texture) {
        float[] vertices = {-1,-1,1,-1,-1,1,1,1};
        float[] coords = {0,0,1,0,0,1,1,1};

        java.nio.FloatBuffer vb = java.nio.ByteBuffer.allocateDirect(32)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        java.nio.FloatBuffer tb = java.nio.ByteBuffer.allocateDirect(32)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        vb.put(vertices).position(0);
        tb.put(coords).position(0);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(sampler, 0);

        GLES20.glEnableVertexAttribArray(pos);
        GLES20.glEnableVertexAttribArray(tex);
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vb);
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, tb);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pos);
        GLES20.glDisableVertexAttribArray(tex);
    }
}
