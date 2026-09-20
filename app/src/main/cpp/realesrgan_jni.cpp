#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include "ncnn/net.h"

static ncnn::Net net;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_smartcropapp_sr_RealEsrgan_nativeInit(
        JNIEnv* env, jclass, jstring paramPath, jstring modelPath) {
    const char* param = env->GetStringUTFChars(paramPath, nullptr);
    const char* model = env->GetStringUTFChars(modelPath, nullptr);

    net.opt.use_vulkan_compute = true;
    net.opt.use_fp16_packed = true;
    net.opt.use_fp16_storage = true;
    net.opt.use_fp16_arithmetic = true;

    int ret = net.load_param(param);
    if (ret == 0) ret = net.load_model(model);

    env->ReleaseStringUTFChars(paramPath, param);
    env->ReleaseStringUTFChars(modelPath, model);
    return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_smartcropapp_sr_RealEsrgan_nativeProcess(
        JNIEnv* env, jclass, jobject inputBuffer, jint width, jint height,
        jobject outputBuffer) {
    auto* input = static_cast<unsigned char*>(env->GetDirectBufferAddress(inputBuffer));
    auto* output = static_cast<unsigned char*>(env->GetDirectBufferAddress(outputBuffer));
    if (!input || !output) return JNI_FALSE;

    ncnn::Mat in(width, height, 3);
    for (int y = 0; y < height; y++)
        for (int x = 0; x < width; x++) {
            const unsigned char* p = input + (y * width + x) * 4;
            float* r = in.channel(0).row(y);
            float* g = in.channel(1).row(y);
            float* b = in.channel(2).row(y);
            r[x] = p[0] / 255.f;
            g[x] = p[1] / 255.f;
            b[x] = p[2] / 255.f;
        }

    ncnn::Extractor ex = net.create_extractor();
    ex.input("data", in);

    ncnn::Mat out;
    if (ex.extract("output", out) != 0) return JNI_FALSE;

    int ow = out.w;
    int oh = out.h;
    for (int y = 0; y < oh; y++) {
        for (int x = 0; x < ow; x++) {
            unsigned char* p = output + (y * ow + x) * 4;
            p[0] = (unsigned char)std::clamp(out.channel(0).row(y)[x] * 255.f, 0.f, 255.f);
            p[1] = (unsigned char)std::clamp(out.channel(1).row(y)[x] * 255.f, 0.f, 255.f);
            p[2] = (unsigned char)std::clamp(out.channel(2).row(y)[x] * 255.f, 0.f, 255.f);
            p[3] = 255;
        }
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_smartcropapp_sr_RealEsrgan_nativeRelease(
        JNIEnv*, jclass) {
    net.clear();
}
