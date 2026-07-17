#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

#include "rnnoise.h"

static float sanitizeF(float x) { return std::isfinite(x) ? x : 0.f; }

namespace {

void upsample16kTo48k(const float *in160, float *out480) {
    for (int i = 0; i < 480; i++) {
        float t = ((static_cast<float>(i) + 0.5f) * (160.0f / 480.0f)) - 0.5f;
        int i0 = static_cast<int>(std::floor(t));
        if (i0 < 0) i0 = 0;
        if (i0 > 158) i0 = 158;
        float frac = t - static_cast<float>(i0);
        out480[i] = in160[i0] * (1.f - frac) + in160[i0 + 1] * frac;
    }
}

void downsample48kTo16k(const float *in480, float *out160) {
    for (int j = 0; j < 160; j++) {
        float t = ((static_cast<float>(j) + 0.5f) * (480.0f / 160.0f)) - 0.5f;
        int i0 = static_cast<int>(std::floor(t));
        if (i0 < 0) i0 = 0;
        if (i0 > 478) i0 = 478;
        float frac = t - static_cast<float>(i0);
        out160[j] = in480[i0] * (1.f - frac) + in480[i0 + 1] * frac;
    }
}

void denoiseFrame160(DenoiseState *st, int16_t *frame160) {
    float f160[160];
    float f480[480];
    for (int j = 0; j < 160; j++) {
        f160[j] = static_cast<float>(frame160[j]);
    }
    upsample16kTo48k(f160, f480);
    for (int i = 0; i < 480; i++) {
        f480[i] = sanitizeF(f480[i]);
    }
    rnnoise_process_frame(st, f480, f480);
    downsample48kTo16k(f480, f160);
    for (int j = 0; j < 160; j++) {
        float v = std::round(f160[j]);
        if (v > 32767.f) v = 32767.f;
        if (v < -32768.f) v = -32768.f;
        frame160[j] = static_cast<int16_t>(v);
    }
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_whispertflite_asr_RnnoiseJni_rnnoiseCreate(JNIEnv *env, jclass /* clazz */) {
    DenoiseState *st = rnnoise_create(nullptr);
    return reinterpret_cast<jlong>(st);
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispertflite_asr_RnnoiseJni_rnnoiseDestroy(JNIEnv *env, jclass /* clazz */, jlong handle) {
    if (handle == 0) return;
    auto *st = reinterpret_cast<DenoiseState *>(handle);
    rnnoise_destroy(st);
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispertflite_asr_RnnoiseJni_rnnoiseProcessFramesInPlace(
    JNIEnv *env,
    jclass /* clazz */,
    jlong handle,
    jshortArray merged,
    jint completeSamples) {
    if (handle == 0) return;
    auto *st = reinterpret_cast<DenoiseState *>(handle);
    jsize len = env->GetArrayLength(merged);
    if (completeSamples > len || completeSamples < 0) return;
    jshort *p = env->GetShortArrayElements(merged, nullptr);
    if (!p) return;
    for (jint off = 0; off + 160 <= completeSamples; off += 160) {
        denoiseFrame160(st, reinterpret_cast<int16_t *>(p + off));
    }
    env->ReleaseShortArrayElements(merged, p, 0);
}
