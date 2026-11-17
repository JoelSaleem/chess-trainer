#include <jni.h>

#include "sfbridge.h"

// JNI bridge over the flat C Stockfish interface in sfbridge.h.
//
// The SFEngine* handle is carried across the boundary as a jlong. Search output
// arrives on Stockfish's own search thread, so JNI_OnLoad caches the JavaVM, the
// callbacks attach that thread, and a global ref keeps the Kotlin listener alive
// until the bestmove (always the search's final callback) is delivered.

namespace {
JavaVM* g_vm = nullptr;
jmethodID g_onInfo = nullptr;
jmethodID g_onBestMove = nullptr;

// One search's state, passed through sf_go's void* context.
struct CallbackCtx {
    jobject listener;  // global ref, released in onBestMoveCb
};

// A JNIEnv for the current thread, attaching it when it is a bare native thread
// (which Stockfish's search thread is). Left attached across info callbacks and
// detached once in onBestMoveCb.
JNIEnv* currentEnv() {
    if (g_vm == nullptr) return nullptr;
    JNIEnv* env = nullptr;
    jint r = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (r == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    } else if (r != JNI_OK) {
        return nullptr;
    }
    return env;
}

SFEngine* asEngine(jlong handle) { return reinterpret_cast<SFEngine*>(handle); }
}  // namespace

// extern "C" so the function-pointer types match sfbridge.h's C typedefs exactly.
extern "C" {

static void onInfoCb(void* context, const SFInfo* info) {
    auto* ctx = static_cast<CallbackCtx*>(context);
    if (ctx == nullptr || info == nullptr) return;
    JNIEnv* env = currentEnv();
    if (env == nullptr) return;
    jstring pv = env->NewStringUTF(info->pv != nullptr ? info->pv : "");
    env->CallVoidMethod(ctx->listener, g_onInfo,
                        static_cast<jint>(info->depth), static_cast<jint>(info->selDepth),
                        static_cast<jint>(info->multiPV), static_cast<jint>(info->scoreCp),
                        static_cast<jint>(info->scoreMate), static_cast<jboolean>(info->isMate),
                        static_cast<jlong>(info->nodes), static_cast<jlong>(info->timeMs), pv);
    if (pv != nullptr) env->DeleteLocalRef(pv);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

static void onBestMoveCb(void* context, const char* bestMove, const char* ponder) {
    auto* ctx = static_cast<CallbackCtx*>(context);
    if (ctx == nullptr) return;
    JNIEnv* env = currentEnv();
    if (env != nullptr) {
        jstring best = bestMove != nullptr ? env->NewStringUTF(bestMove) : nullptr;
        jstring pon = ponder != nullptr ? env->NewStringUTF(ponder) : nullptr;
        env->CallVoidMethod(ctx->listener, g_onBestMove, best, pon);
        if (best != nullptr) env->DeleteLocalRef(best);
        if (pon != nullptr) env->DeleteLocalRef(pon);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteGlobalRef(ctx->listener);
    }
    delete ctx;
    // The search has finished on this native thread; release its JNI attachment.
    if (g_vm != nullptr) g_vm->DetachCurrentThread();
}

}  // extern "C"

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_joelsaleem_chesstrainer_engine_StockfishNative_engineInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(sf_engine_info());
}

extern "C" JNIEXPORT void JNICALL
Java_com_joelsaleem_chesstrainer_engine_StockfishNative_globalInit(JNIEnv*, jobject) {
    sf_global_init();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_joelsaleem_chesstrainer_engine_StockfishNative_create(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(sf_create());
}

extern "C" JNIEXPORT void JNICALL
Java_com_joelsaleem_chesstrainer_engine_StockfishNative_destroy(JNIEnv*, jobject, jlong handle) {
    sf_destroy(asEngine(handle));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_joelsaleem_chesstrainer_engine_StockfishNative_loadNetworks(
        JNIEnv* env, jobject, jlong handle, jstring bigPath, jstring smallPath) {
    const char* big = env->GetStringUTFChars(bigPath, nullptr);
    const char* small = env->GetStringUTFChars(smallPath, nullptr);
    bool ok = (big != nullptr && small != nullptr) &&
              sf_load_networks(asEngine(handle), big, small);
    if (big != nullptr) env->ReleaseStringUTFChars(bigPath, big);
    if (small != nullptr) env->ReleaseStringUTFChars(smallPath, small);
    return static_cast<jboolean>(ok);
}

extern "C" JNIEXPORT void JNICALL
Java_com_joelsaleem_chesstrainer_engine_StockfishNative_setOption(
        JNIEnv* env, jobject, jlong handle, jstring name, jstring value) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    if (n != nullptr && v != nullptr) sf_set_option(asEngine(handle), n, v);
    if (n != nullptr) env->ReleaseStringUTFChars(name, n);
    if (v != nullptr) env->ReleaseStringUTFChars(value, v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_joelsaleem_chesstrainer_engine_StockfishNative_newGame(JNIEnv*, jobject, jlong handle) {
    sf_new_game(asEngine(handle));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_joelsaleem_chesstrainer_engine_StockfishNative_setPosition(
        JNIEnv* env, jobject, jlong handle, jstring fen) {
    const char* f = env->GetStringUTFChars(fen, nullptr);
    bool ok = f != nullptr && sf_set_position(asEngine(handle), f);
    if (f != nullptr) env->ReleaseStringUTFChars(fen, f);
    return static_cast<jboolean>(ok);
}

extern "C" JNIEXPORT void JNICALL
Java_com_joelsaleem_chesstrainer_engine_StockfishNative_go(
        JNIEnv* env, jobject, jlong handle, jint depth, jint movetimeMs, jobject listener) {
    if (g_onInfo == nullptr || g_onBestMove == nullptr) {
        jclass cls = env->FindClass(
            "com/joelsaleem/chesstrainer/engine/StockfishNative$SearchListener");
        if (cls == nullptr) return;  // exception pending
        g_onInfo = env->GetMethodID(cls, "onInfo", "(IIIIIZJJLjava/lang/String;)V");
        g_onBestMove = env->GetMethodID(cls, "onBestMove",
                                        "(Ljava/lang/String;Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (g_onInfo == nullptr || g_onBestMove == nullptr) return;
    }
    auto* ctx = new CallbackCtx{env->NewGlobalRef(listener)};
    sf_go(asEngine(handle), depth, movetimeMs, ctx, onInfoCb, onBestMoveCb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_joelsaleem_chesstrainer_engine_StockfishNative_stop(JNIEnv*, jobject, jlong handle) {
    sf_stop(asEngine(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_joelsaleem_chesstrainer_engine_StockfishNative_waitForSearch(
        JNIEnv*, jobject, jlong handle) {
    sf_wait_for_search(asEngine(handle));
}
