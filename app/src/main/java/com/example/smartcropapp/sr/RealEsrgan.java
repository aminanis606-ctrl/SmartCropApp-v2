package com.example.smartcropapp.sr;

import android.content.Context;
import java.io.*;

public final class RealEsrgan {
    static { System.loadLibrary("realesrgan_jni"); }

    private static native boolean nativeInit(String param, String model);
    private static native boolean nativeProcess(java.nio.ByteBuffer in, int w, int h, java.nio.ByteBuffer out);
    private static native void nativeRelease();

    private final String param;
    private final String model;

    public RealEsrgan(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), "realesrgan");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
        param = copy(context, "models/realesrgan-x4plus.param", new File(dir, "model.param"));
        model = copy(context, "models/realesrgan-x4plus.bin", new File(dir, "model.bin"));
        if (!nativeInit(param, model)) throw new IOException("RealESRGAN init failed");
    }

    private static String copy(Context c, String asset, File dst) throws IOException {
        if (!dst.exists()) {
            try (InputStream in = c.getAssets().open(asset);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
        }
        return dst.getAbsolutePath();
    }

    public boolean process(java.nio.ByteBuffer in, int w, int h, java.nio.ByteBuffer out) {
        return nativeProcess(in, w, h, out);
    }

    public void release() { nativeRelease(); }
}
