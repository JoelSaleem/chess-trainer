#include <jni.h>

#include "sfbridge.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_joelsaleem_chesstrainer_engine_StockfishNative_engineInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(sf_engine_info());
}
