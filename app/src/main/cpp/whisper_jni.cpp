#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "WhisperCppJNI"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

struct Ctx {
    whisper_context* ctx = nullptr;
    std::atomic<bool> abort_requested{false};
};

static Ctx* ptr(jlong h) {
    return reinterpret_cast<Ctx*>(h);
}

static bool abort_callback(void* user_data) {
    auto* c = static_cast<Ctx*>(user_data);
    return c && c->abort_requested.load();
}

static std::string jstr_to_utf8(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* c = env->GetStringUTFChars(js, nullptr);
    if (!c) return {};
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_whispertflite_engine_WhisperEngineCpp_nativeInit(JNIEnv* env, jobject /*thiz*/, jstring jModelPath) {
    const std::string path = jstr_to_utf8(env, jModelPath);
    if (path.empty()) {
        ALOGW("nativeInit: empty model path");
        return 0;
    }

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    whisper_context* wctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (!wctx) {
        ALOGW("nativeInit: whisper_init_from_file_with_params failed");
        return 0;
    }

    auto* c = new Ctx();
    c->ctx = wctx;
    return reinterpret_cast<jlong>(c);
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispertflite_engine_WhisperEngineCpp_nativeAbort(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    (void)env;
    Ctx* c = ptr(handle);
    if (c) {
        c->abort_requested.store(true);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispertflite_engine_WhisperEngineCpp_nativeFree(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    (void)env;
    Ctx* c = ptr(handle);
    if (!c) return;
    c->abort_requested.store(true);
    if (c->ctx) {
        whisper_free(c->ctx);
        c->ctx = nullptr;
    }
    delete c;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_whispertflite_engine_WhisperEngineCpp_nativeTranscribe(
        JNIEnv* env,
        jobject /*thiz*/,
        jlong handle,
        jbyteArray jPcm16,
        jboolean jTranslate,
        jstring jLanguage,
        jint jNThreads) {
    jclass stringCls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(3, stringCls, env->NewStringUTF(""));
    if (!out || !stringCls) {
        return nullptr;
    }

    Ctx* c = ptr(handle);
    if (!c || !c->ctx) {
        env->SetObjectArrayElement(out, 0, env->NewStringUTF(""));
        env->SetObjectArrayElement(out, 1, env->NewStringUTF(""));
        env->SetObjectArrayElement(out, 2, env->NewStringUTF("TRANSCRIBE"));
        return out;
    }

    jsize nBytes = env->GetArrayLength(jPcm16);
    if (nBytes < 2) {
        env->SetObjectArrayElement(out, 0, env->NewStringUTF(""));
        env->SetObjectArrayElement(out, 1, env->NewStringUTF(""));
        env->SetObjectArrayElement(out, 2, env->NewStringUTF("TRANSCRIBE"));
        return out;
    }

    std::vector<int16_t> pcm16(static_cast<size_t>(nBytes / 2));
    env->GetByteArrayRegion(jPcm16, 0, nBytes, reinterpret_cast<jbyte*>(pcm16.data()));

    std::vector<float> pcmf32(pcm16.size());
    for (size_t i = 0; i < pcm16.size(); ++i) {
        pcmf32[i] = static_cast<float>(pcm16[i]) / 32768.0f;
    }

    c->abort_requested.store(false);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads = jNThreads > 0 ? jNThreads : 4;
    wparams.translate = jTranslate;
    wparams.no_context = true;
    wparams.single_segment = false;
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.suppress_blank = true;

    std::string lang = jstr_to_utf8(env, jLanguage);
    std::string lang_keep;
    if (lang.empty() || lang == "auto") {
        wparams.language = "auto";
        wparams.detect_language = true;
    } else {
        lang_keep = std::move(lang);
        wparams.language = lang_keep.c_str();
        wparams.detect_language = false;
    }

    wparams.abort_callback = abort_callback;
    wparams.abort_callback_user_data = c;

    const auto t0 = std::chrono::steady_clock::now();
    const int rc = whisper_full(c->ctx, wparams, pcmf32.data(), static_cast<int>(pcmf32.size()));
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - t0)
                            .count();
    ALOGD("whisper_full ms=%lld threads=%d samples=%d detect_lang=%d",
          static_cast<long long>(ms),
          wparams.n_threads,
          static_cast<int>(pcmf32.size()),
          wparams.detect_language ? 1 : 0);

    if (rc != 0) {
        ALOGW("nativeTranscribe: whisper_full returned %d", rc);
        env->SetObjectArrayElement(out, 0, env->NewStringUTF(""));
        env->SetObjectArrayElement(out, 1, env->NewStringUTF(""));
        env->SetObjectArrayElement(out, 2, env->NewStringUTF(jTranslate ? "TRANSLATE" : "TRANSCRIBE"));
        return out;
    }

    const int n_segments = whisper_full_n_segments(c->ctx);
    std::string text;
    for (int i = 0; i < n_segments; ++i) {
        const char* seg = whisper_full_get_segment_text(c->ctx, i);
        if (seg) {
            text += seg;
        }
    }

    while (!text.empty() && (text.front() == ' ' || text.front() == '\n')) {
        text.erase(text.begin());
    }

    const int lid = whisper_full_lang_id(c->ctx);
    const char* lstr = whisper_lang_str(lid);
    std::string langOut = lstr ? lstr : "";

    env->SetObjectArrayElement(out, 0, env->NewStringUTF(text.c_str()));
    env->SetObjectArrayElement(out, 1, env->NewStringUTF(langOut.c_str()));
    env->SetObjectArrayElement(out, 2, env->NewStringUTF(jTranslate ? "TRANSLATE" : "TRANSCRIBE"));
    return out;
}
