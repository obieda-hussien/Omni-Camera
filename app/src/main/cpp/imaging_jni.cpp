#include <jni.h>
#include <android/bitmap.h>
#include <stdexcept>
#include "imaging_kernel.h"

namespace {
class LockedBitmap {
    JNIEnv* env; jobject bitmap;
public:
    omni::Image image{};
    LockedBitmap(JNIEnv* e, jobject b): env(e), bitmap(b) {
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) throw std::invalid_argument("RGBA_8888 bitmap required");
        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) throw std::runtime_error("Could not lock bitmap");
        image = {static_cast<uint8_t*>(pixels), static_cast<int>(info.width), static_cast<int>(info.height), static_cast<int>(info.stride)};
    }
    ~LockedBitmap() { AndroidBitmap_unlockPixels(env, bitmap); }
};
std::vector<float> floats(JNIEnv* env, jfloatArray array) {
    if (!array) return {};
    std::vector<float> result(env->GetArrayLength(array));
    env->GetFloatArrayRegion(array, 0, static_cast<jsize>(result.size()), result.data());
    return result;
}
void error(JNIEnv* env, const char* message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message); }
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_omni_camera_imaging_NativeImaging_nativeDevelop(JNIEnv* env, jobject, jobject bitmap,
    jfloatArray mask, jint mw, jint mh, jfloatArray faceArray, jfloatArray params) {
    try {
        LockedBitmap image(env, bitmap);
        auto m = floats(env, mask), f = floats(env, faceArray), p = floats(env, params);
        if (p.size() != 8 || (!m.empty() && (mw <= 0 || mh <= 0 || static_cast<size_t>(mw) * mh != m.size())) || f.size() % 4 != 0)
            throw std::invalid_argument("Invalid imaging parameters");
        std::vector<omni::Face> faces;
        for (size_t i = 0; i < f.size(); i += 4) faces.push_back({f[i], f[i + 1], f[i + 2], f[i + 3]});
        omni::Options options;
        options.strength = p[0]; options.faceLight = p[1]; options.background = p[2]; options.denoise = p[3];
        options.detail = p[4]; options.warmth = p[5]; options.subjectLuma = p[6]; options.backgroundLuma = p[7];
        return omni::develop(image.image, {m.empty() ? nullptr : m.data(), mw, mh}, faces, options).noise;
    } catch (const std::exception& e) { error(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_omni_camera_imaging_NativeImaging_nativeCreateBurst(JNIEnv* env, jobject, jobject base) {
    try {
        LockedBitmap a(env, base);
        return reinterpret_cast<jlong>(new omni::Burst(a.image));
    } catch (const std::exception& e) { error(env, e.what()); return 0; }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_omni_camera_imaging_NativeImaging_nativeAddBurst(JNIEnv* env, jobject, jlong handle, jobject candidate, jfloat stops) {
    try {
        if (!handle) throw std::invalid_argument("Burst is closed");
        LockedBitmap b(env, candidate);
        return reinterpret_cast<omni::Burst*>(handle)->add(b.image, stops);
    } catch (const std::exception& e) { error(env, e.what()); return JNI_FALSE; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_omni_camera_imaging_NativeImaging_nativeFinishBurst(JNIEnv* env, jobject, jlong handle, jobject destination) {
    try {
        if (!handle) throw std::invalid_argument("Burst is closed");
        LockedBitmap output(env, destination);
        reinterpret_cast<omni::Burst*>(handle)->finish(output.image);
    } catch (const std::exception& e) { error(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_omni_camera_imaging_NativeImaging_nativeReleaseBurst(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<omni::Burst*>(handle);
}
