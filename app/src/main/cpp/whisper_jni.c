
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "whisper.h"

#define TAG "whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Exportamos dois conjuntos de símbolos JNI para compatibilidade:
// 1) métodos estáticos na classe LocalSttBackend
// 2) métodos no Companion (histórico)
#define JNI_FN_CLASS(name) Java_com_example_peciwearables_integration_stt_LocalSttBackend_##name
#define JNI_FN_COMPANION(name) Java_com_example_peciwearables_integration_stt_LocalSttBackend_00024Companion_##name

static jlong do_init_context(JNIEnv *env, jstring model_path_jstr) {
    const char *model_path = (*env)->GetStringUTFChars(env, model_path_jstr, NULL);
    if (!model_path) {
        LOGE("initContext: model path is null");
        return 0;
    }
    LOGI("initContext: loading %s", model_path);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    struct whisper_context *ctx = whisper_init_from_file_with_params(model_path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path_jstr, model_path);

    if (!ctx) {
        LOGE("initContext: whisper_init_from_file_with_params returned NULL");
        return 0;
    }
    LOGI("initContext: whisper ctx=%p", ctx);
    return (jlong) ctx;
}

static void do_free_context(jlong ctx_ptr) {
    if (ctx_ptr == 0) return;
    struct whisper_context *ctx = (struct whisper_context *) ctx_ptr;
    whisper_free(ctx);
}

static jstring do_full_transcribe(JNIEnv *env, jlong ctx_ptr, jfloatArray pcm_jarr) {
    if (ctx_ptr == 0) {
        return (*env)->NewStringUTF(env, "");
    }
    struct whisper_context *ctx = (struct whisper_context *) ctx_ptr;

    jsize n = (*env)->GetArrayLength(env, pcm_jarr);
    jfloat *pcm = (*env)->GetFloatArrayElements(env, pcm_jarr, NULL);

    // Beam search beats greedy for PT transcription quality (tiny model
    // especially is very sensitive to this). Cost vs greedy is small on a
    // phone CPU — a few hundred ms extra for a 5s clip.
    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    p.print_realtime    = false;
    p.print_progress    = false;
    p.print_timestamps  = false;
    p.print_special     = false;
    p.translate         = false;
    p.language          = "pt";
    p.detect_language   = false;
    p.no_context        = true;
    p.single_segment    = false;
    p.suppress_blank    = true;
    p.beam_search.beam_size = 5;
    // Penalise aggressive speculative decoding; keeps the tiny model from
    // hallucinating filler phrases when the audio is short or noisy.
    p.temperature       = 0.0f;
    p.temperature_inc   = 0.2f;
    p.entropy_thold     = 2.4f;
    p.logprob_thold     = -1.0f;
    p.no_speech_thold   = 0.6f;

    // Conta de threads razoável para o CPU do telemóvel — 4 é um bom
    // compromisso entre latência e bateria.
    p.n_threads = 4;

    LOGI("fullTranscribe: %d samples", (int) n);
    int rc = whisper_full(ctx, p, pcm, (int) n);
    (*env)->ReleaseFloatArrayElements(env, pcm_jarr, pcm, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full failed with rc=%d", rc);
        return (*env)->NewStringUTF(env, "");
    }

    // Concatena todos os segmentos num único buffer dinâmico.
    int n_seg = whisper_full_n_segments(ctx);
    size_t cap = 256;
    size_t len = 0;
    char *buf = (char *) malloc(cap);
    if (!buf) return (*env)->NewStringUTF(env, "");
    buf[0] = '\0';

    for (int i = 0; i < n_seg; ++i) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (!seg) continue;
        size_t sl = strlen(seg);
        if (len + sl + 2 > cap) {
            while (len + sl + 2 > cap) cap *= 2;
            char *nb = (char *) realloc(buf, cap);
            if (!nb) { free(buf); return (*env)->NewStringUTF(env, ""); }
            buf = nb;
        }
        memcpy(buf + len, seg, sl);
        len += sl;
    }
    buf[len] = '\0';

    jstring result = (*env)->NewStringUTF(env, buf);
    free(buf);
    return result;
}

static jstring do_get_language(JNIEnv *env, jlong ctx_ptr) {
    if (ctx_ptr == 0) return (*env)->NewStringUTF(env, "");
    struct whisper_context *ctx = (struct whisper_context *) ctx_ptr;

    // whisper.cpp não expõe directamente a língua escolhida no último run;
    // devolvemos sempre "pt" porque é a que forçamos em fullTranscribe.
    (void) ctx;
    return (*env)->NewStringUTF(env, "pt");
}

JNIEXPORT jlong JNICALL
JNI_FN_CLASS(initContext)(JNIEnv *env, jclass clazz, jstring model_path_jstr) {
    (void) clazz;
    return do_init_context(env, model_path_jstr);
}

JNIEXPORT void JNICALL
JNI_FN_CLASS(freeContext)(JNIEnv *env, jclass clazz, jlong ctx_ptr) {
    (void) env;
    (void) clazz;
    do_free_context(ctx_ptr);
}

JNIEXPORT jstring JNICALL
JNI_FN_CLASS(fullTranscribe)(JNIEnv *env, jclass clazz, jlong ctx_ptr, jfloatArray pcm_jarr) {
    (void) clazz;
    return do_full_transcribe(env, ctx_ptr, pcm_jarr);
}

JNIEXPORT jstring JNICALL
JNI_FN_CLASS(getLanguage)(JNIEnv *env, jclass clazz, jlong ctx_ptr) {
    (void) clazz;
    return do_get_language(env, ctx_ptr);
}

JNIEXPORT jlong JNICALL
JNI_FN_COMPANION(initContext)(JNIEnv *env, jobject thiz, jstring model_path_jstr) {
    (void) thiz;
    return do_init_context(env, model_path_jstr);
}

JNIEXPORT void JNICALL
JNI_FN_COMPANION(freeContext)(JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    (void) env;
    (void) thiz;
    do_free_context(ctx_ptr);
}

JNIEXPORT jstring JNICALL
JNI_FN_COMPANION(fullTranscribe)(JNIEnv *env, jobject thiz, jlong ctx_ptr, jfloatArray pcm_jarr) {
    (void) thiz;
    return do_full_transcribe(env, ctx_ptr, pcm_jarr);
}

JNIEXPORT jstring JNICALL
JNI_FN_COMPANION(getLanguage)(JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    (void) thiz;
    return do_get_language(env, ctx_ptr);
}
