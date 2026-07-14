#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <algorithm>
#include <iomanip>
#include <android/log.h>

#define LOG_TAG "VerifyJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_pdy_tvpro_util_VerifyUtil_nativeVerifySignature(JNIEnv *env, jobject thiz, jobject context) {
    try {
        jclass contextClass = env->GetObjectClass(context);
        jmethodID getPackageManager = env->GetMethodID(contextClass, "getPackageManager", 
            "()Landroid/content/pm/PackageManager;");
        jobject packageManager = env->CallObjectMethod(context, getPackageManager);
        
        jmethodID getPackageName = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
        jstring packageName = (jstring)env->CallObjectMethod(context, getPackageName);
        const char *pkgName = env->GetStringUTFChars(packageName, nullptr);
        
        jclass pmClass = env->GetObjectClass(packageManager);
        jmethodID getPackageInfo = env->GetMethodID(pmClass, "getPackageInfo", 
            "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
        jobject packageInfo = env->CallObjectMethod(packageManager, getPackageInfo, packageName, 0x00000040);
        
        jclass piClass = env->GetObjectClass(packageInfo);
        jfieldID signaturesField = env->GetFieldID(piClass, "signatures", 
            "[Landroid/content/pm/Signature;");
        jobjectArray signatures = (jobjectArray)env->GetObjectField(packageInfo, signaturesField);
        
        jsize sigCount = env->GetArrayLength(signatures);
        if (sigCount == 0) {
            LOGE("No signatures found");
            env->ReleaseStringUTFChars(packageName, pkgName);
            return JNI_FALSE;
        }
        
        jobject signature = env->GetObjectArrayElement(signatures, 0);
        jclass sigClass = env->GetObjectClass(signature);
        jmethodID toByteArray = env->GetMethodID(sigClass, "toByteArray", "()[B");
        jbyteArray sigBytes = (jbyteArray)env->CallObjectMethod(signature, toByteArray);
        
        jsize sigLen = env->GetArrayLength(sigBytes);
        jbyte *bytes = env->GetByteArrayElements(sigBytes, nullptr);
        
        std::stringstream ss;
        for (jsize i = 0; i < sigLen; i++) {
            ss << std::hex << std::uppercase << std::setw(2) << std::setfill('0') 
               << (int)(bytes[i] & 0xFF);
        }
        
        std::string sigHex = ss.str();
        std::transform(sigHex.begin(), sigHex.end(), sigHex.begin(), ::toupper);
        
        env->ReleaseByteArrayElements(sigBytes, bytes, JNI_ABORT);
        env->ReleaseStringUTFChars(packageName, pkgName);
        
        return JNI_TRUE;
    } catch (...) {
        LOGE("Exception in nativeVerifySignature");
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_pdy_tvpro_util_VerifyUtil_nativeVerifyPackageName(JNIEnv *env, jobject thiz, jobject context) {
    try {
        jclass contextClass = env->GetObjectClass(context);
        jmethodID getPackageName = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
        jstring packageName = (jstring)env->CallObjectMethod(context, getPackageName);
        const char *pkgName = env->GetStringUTFChars(packageName, nullptr);
        
        const char *expected = "com.pdy.tvpro";
        bool match = strcmp(pkgName, expected) == 0;
        
        env->ReleaseStringUTFChars(packageName, pkgName);
        
        if (!match) {
            LOGE("Package name mismatch: %s != %s", pkgName, expected);
        }
        
        return match ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        LOGE("Exception in nativeVerifyPackageName");
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_pdy_tvpro_util_VerifyUtil_nativeVerifyAll(JNIEnv *env, jobject thiz, jobject context) {
    bool pkgOk = Java_com_pdy_tvpro_util_VerifyUtil_nativeVerifyPackageName(env, thiz, context);
    bool sigOk = Java_com_pdy_tvpro_util_VerifyUtil_nativeVerifySignature(env, thiz, context);
    return (pkgOk && sigOk) ? JNI_TRUE : JNI_FALSE;
}

}