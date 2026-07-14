#include <jni.h>
#include <string.h>
#include <android/log.h>

#include "Platinum.h"
#include "PltMediaRenderer.h"

#define LOG_TAG "git-platinum"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define MEDIA_RENDER_CTL_MSG_BASE 0x100
#define MEDIA_RENDER_CTL_MSG_SET_AV_URL    (MEDIA_RENDER_CTL_MSG_BASE + 0)
#define MEDIA_RENDER_CTL_MSG_STOP          (MEDIA_RENDER_CTL_MSG_BASE + 1)
#define MEDIA_RENDER_CTL_MSG_PLAY          (MEDIA_RENDER_CTL_MSG_BASE + 2)
#define MEDIA_RENDER_CTL_MSG_PAUSE         (MEDIA_RENDER_CTL_MSG_BASE + 3)
#define MEDIA_RENDER_CTL_MSG_SEEK          (MEDIA_RENDER_CTL_MSG_BASE + 4)
#define MEDIA_RENDER_CTL_MSG_SETVOLUME     (MEDIA_RENDER_CTL_MSG_BASE + 5)
#define MEDIA_RENDER_CTL_MSG_SETMUTE       (MEDIA_RENDER_CTL_MSG_BASE + 6)
#define MEDIA_RENDER_CTL_MSG_SETPLAYMODE   (MEDIA_RENDER_CTL_MSG_BASE + 7)
#define MEDIA_RENDER_CTL_MSG_PRE           (MEDIA_RENDER_CTL_MSG_BASE + 8)
#define MEDIA_RENDER_CTL_MSG_NEXT          (MEDIA_RENDER_CTL_MSG_BASE + 9)

#define TOCONTRPOINT_SET_MEDIA_DURATION   (MEDIA_RENDER_CTL_MSG_BASE + 0)
#define TOCONTRPOINT_SET_MEDIA_POSITION   (MEDIA_RENDER_CTL_MSG_BASE + 1)
#define TOCONTRPOINT_SET_MEDIA_PLAYINGSTATE (MEDIA_RENDER_CTL_MSG_BASE + 2)

class GitMediaRendererDelegate;

static JavaVM* g_jvm = NULL;
static jclass g_reflection_cls = NULL;
static jmethodID g_onActionReflection_mid = NULL;
static bool g_log_enabled = false;

static PLT_UPnP* g_upnp = NULL;
static PLT_DeviceHostReference g_device;
static GitMediaRendererDelegate* g_delegate = NULL;
static bool g_started = false;

static void jni_callback_action(int cmd, const char* value, const char* data);

class GitMediaRendererDelegate : public PLT_MediaRendererDelegate {
public:
    GitMediaRendererDelegate() {}
    virtual ~GitMediaRendererDelegate() {}

    virtual NPT_Result OnGetCurrentConnectionInfo(PLT_ActionReference& action) {
        if (g_log_enabled) LOGD("OnGetCurrentConnectionInfo");
        action->SetArgumentValue("ConnectionID", "0");
        action->SetArgumentValue("RcsID", "0");
        action->SetArgumentValue("AVTransportID", "0");
        action->SetArgumentValue("ProtocolInfo", "");
        action->SetArgumentValue("PeerConnectionManager", "");
        action->SetArgumentValue("PeerConnectionID", "-1");
        action->SetArgumentValue("Direction", "Input");
        action->SetArgumentValue("Status", "OK");
        return NPT_SUCCESS;
    }

    virtual NPT_Result OnNext(PLT_ActionReference& action) {
        if (g_log_enabled) LOGD("OnNext");
        jni_callback_action(MEDIA_RENDER_CTL_MSG_NEXT, "", "");
        return NPT_SUCCESS;
    }

    virtual NPT_Result OnPause(PLT_ActionReference& action) {
        if (g_log_enabled) LOGD("OnPause");
        PLT_Service* service = NULL;
        if (NPT_SUCCEEDED(g_device->FindServiceByType("urn:schemas-upnp-org:service:AVTransport:1", service)) && service) {
            service->SetStateVariable("TransportState", "PAUSED_PLAYBACK");
        }
        jni_callback_action(MEDIA_RENDER_CTL_MSG_PAUSE, "", "");
        return NPT_SUCCESS;
    }

    virtual NPT_Result OnPlay(PLT_ActionReference& action) {
        if (g_log_enabled) LOGD("OnPlay");
        PLT_Service* service = NULL;
        if (NPT_SUCCEEDED(g_device->FindServiceByType("urn:schemas-upnp-org:service:AVTransport:1", service)) && service) {
            service->SetStateVariable("TransportState", "PLAYING");
            service->SetStateVariable("TransportStatus", "OK");
        }
        jni_callback_action(MEDIA_RENDER_CTL_MSG_PLAY, "", "");
        return NPT_SUCCESS;
    }

    virtual NPT_Result OnPrevious(PLT_ActionReference& action) {
        if (g_log_enabled) LOGD("OnPrevious");
        jni_callback_action(MEDIA_RENDER_CTL_MSG_PRE, "", "");
        return NPT_SUCCESS;
    }

    virtual NPT_Result OnSeek(PLT_ActionReference& action) {
        NPT_String target;
        action->GetArgumentValue("Target", target);
        if (g_log_enabled) LOGD("OnSeek: %s", target.GetChars());
        jni_callback_action(MEDIA_RENDER_CTL_MSG_SEEK, target.GetChars(), "");
        return NPT_SUCCESS;
    }

    virtual NPT_Result OnStop(PLT_ActionReference& action) {
        if (g_log_enabled) LOGD("OnStop");
        PLT_Service* service = NULL;
        if (NPT_SUCCEEDED(g_device->FindServiceByType("urn:schemas-upnp-org:service:AVTransport:1", service)) && service) {
            service->SetStateVariable("TransportState", "STOPPED");
        }
        jni_callback_action(MEDIA_RENDER_CTL_MSG_STOP, "", "");
        return NPT_SUCCESS;
    }

    virtual NPT_Result OnSetAVTransportURI(PLT_ActionReference& action) {
        NPT_String uri, metadata;
        action->GetArgumentValue("CurrentURI", uri);
        action->GetArgumentValue("CurrentURIMetaData", metadata);
        if (g_log_enabled) LOGD("OnSetAVTransportURI: %s", uri.GetChars());

        // set state variables so GENA events are triggered
        PLT_Service* service = NULL;
        if (NPT_SUCCEEDED(g_device->FindServiceByType("urn:schemas-upnp-org:service:AVTransport:1", service)) && service) {
            service->SetStateVariable("AVTransportURI", uri);
            service->SetStateVariable("AVTransportURIMetaData", metadata);
            service->SetStateVariable("CurrentTrackURI", uri);
            service->SetStateVariable("TransportState", "TRANSITIONING");
            service->SetStateVariable("TransportStatus", "OK");
        }

        jni_callback_action(MEDIA_RENDER_CTL_MSG_SET_AV_URL, uri.GetChars(), metadata.GetChars());
        return NPT_SUCCESS;
    }

    virtual NPT_Result OnSetPlayMode(PLT_ActionReference& action) {
        NPT_String mode;
        action->GetArgumentValue("NewPlayMode", mode);
        if (g_log_enabled) LOGD("OnSetPlayMode: %s", mode.GetChars());
        jni_callback_action(MEDIA_RENDER_CTL_MSG_SETPLAYMODE, mode.GetChars(), "");
        return NPT_SUCCESS;
    }

    virtual NPT_Result OnSetVolume(PLT_ActionReference& action) {
        NPT_String volume;
        action->GetArgumentValue("DesiredVolume", volume);
        if (g_log_enabled) LOGD("OnSetVolume: %s", volume.GetChars());
        jni_callback_action(MEDIA_RENDER_CTL_MSG_SETVOLUME, volume.GetChars(), "");
        return NPT_SUCCESS;
    }

    virtual NPT_Result OnSetVolumeDB(PLT_ActionReference& action) {
        return NPT_SUCCESS;
    }

    virtual NPT_Result OnGetVolumeDBRange(PLT_ActionReference& action) {
        action->SetArgumentValue("MinValue", "0");
        action->SetArgumentValue("MaxValue", "100");
        return NPT_SUCCESS;
    }

    virtual NPT_Result OnSetMute(PLT_ActionReference& action) {
        NPT_String mute;
        action->GetArgumentValue("DesiredMute", mute);
        if (g_log_enabled) LOGD("OnSetMute: %s", mute.GetChars());
        const char* val = (mute == "1" || mute.Compare("true", true) == 0) ? "1" : "0";
        jni_callback_action(MEDIA_RENDER_CTL_MSG_SETMUTE, val, "");
        return NPT_SUCCESS;
    }
};

static void jni_callback_action(int cmd, const char* value, const char* data) {
    if (g_jvm == NULL || g_reflection_cls == NULL || g_onActionReflection_mid == NULL) {
        LOGE("jni_callback_action: not initialized");
        return;
    }

    JNIEnv* env = NULL;
    bool attached = false;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, NULL) != JNI_OK) {
            LOGE("jni_callback_action: AttachCurrentThread failed");
            return;
        }
        attached = true;
    }
    if (env == NULL) {
        LOGE("jni_callback_action: env is NULL");
        return;
    }

    jstring jvalue = env->NewStringUTF(value ? value : "");
    jstring jdata = env->NewStringUTF(data ? data : "");

    env->CallStaticVoidMethod(
        g_reflection_cls,
        g_onActionReflection_mid,
        (jint)cmd,
        jvalue,
        jdata
    );

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    env->DeleteLocalRef(jvalue);
    env->DeleteLocalRef(jdata);

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

static bool init_jni_reflection(JNIEnv* env) {
    if (g_reflection_cls != NULL) return true;

    jclass cls = env->FindClass("com/pngcui/skyworth/dlna/jni/PlatinumReflection");
    if (cls == NULL) {
        LOGE("init_jni_reflection: failed to find PlatinumReflection class");
        return false;
    }

    g_reflection_cls = (jclass)env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);

    g_onActionReflection_mid = env->GetStaticMethodID(
        g_reflection_cls,
        "onActionReflection",
        "(ILjava/lang/String;Ljava/lang/String;)V"
    );
    if (g_onActionReflection_mid == NULL) {
        LOGE("init_jni_reflection: failed to find onActionReflection method");
        env->DeleteGlobalRef(g_reflection_cls);
        g_reflection_cls = NULL;
        return false;
    }

    LOGI("init_jni_reflection: success");
    return true;
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    LOGI("JNI_OnLoad");
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_pngcui_skyworth_dlna_jni_PlatinumJniProxy_startDlnaMediaRender(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray friendname,
        jbyteArray uuid) {

    if (g_started) {
        LOGW("startDlnaMediaRender: already started");
        return 0;
    }

    if (!init_jni_reflection(env)) {
        LOGE("startDlnaMediaRender: init_jni_reflection failed");
        return -1;
    }

    const char* friend_name = NULL;
    const char* uuid_str = NULL;
    jbyte* fn_bytes = NULL;
    jbyte* uuid_bytes = NULL;

    if (friendname) {
        fn_bytes = env->GetByteArrayElements(friendname, NULL);
        friend_name = (const char*)fn_bytes;
    }
    if (uuid) {
        uuid_bytes = env->GetByteArrayElements(uuid, NULL);
        uuid_str = (const char*)uuid_bytes;
    }

    if (g_log_enabled) {
        LOGI("startDlnaMediaRender: name=%s, uuid=%s",
             friend_name ? friend_name : "(null)",
             uuid_str ? uuid_str : "(null)");
    }

    NPT_Result res = NPT_FAILURE;

    if (g_upnp == NULL) {
        g_upnp = new PLT_UPnP();
        g_delegate = new GitMediaRendererDelegate();

        PLT_MediaRenderer* renderer = new PLT_MediaRenderer(
            friend_name ? friend_name : "GitPlatinumRenderer",
            false,
            uuid_str && strlen(uuid_str) > 0 ? uuid_str : NULL
        );
        renderer->SetDelegate(g_delegate);

        g_device = PLT_DeviceHostReference(renderer);

        res = g_upnp->AddDevice(g_device);
        if (NPT_FAILED(res)) {
            LOGE("AddDevice failed: %d", res);
            goto cleanup;
        }

        res = g_upnp->Start();
        if (NPT_FAILED(res)) {
            LOGE("Start failed: %d", res);
            goto cleanup;
        }

        g_started = true;
        LOGI("DLNA Media Renderer started successfully");
    }

cleanup:
    if (fn_bytes) env->ReleaseByteArrayElements(friendname, fn_bytes, JNI_ABORT);
    if (uuid_bytes) env->ReleaseByteArrayElements(uuid, uuid_bytes, JNI_ABORT);

    return g_started ? 0 : -1;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pngcui_skyworth_dlna_jni_PlatinumJniProxy_stopDlnaMediaRender(
        JNIEnv* env,
        jobject /* this */) {

    if (!g_started || g_upnp == NULL) {
        LOGW("stopDlnaMediaRender: not started");
        return;
    }

    LOGI("stopDlnaMediaRender");

    g_upnp->RemoveDevice(g_device);
    g_upnp->Stop();

    delete g_upnp;
    g_upnp = NULL;
    delete g_delegate;
    g_delegate = NULL;
    g_device = NULL;
    g_started = false;

    LOGI("DLNA Media Renderer stopped");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_pngcui_skyworth_dlna_jni_PlatinumJniProxy_responseGenaEvent(
        JNIEnv* env,
        jobject /* this */,
        jint cmd,
        jbyteArray value,
        jbyteArray data) {

    if (!g_started || g_device.IsNull()) {
        LOGE("responseGenaEvent: not started");
        return JNI_FALSE;
    }

    const char* val_str = NULL;
    const char* data_str = NULL;
    jbyte* val_bytes = NULL;
    jbyte* data_bytes = NULL;

    if (value) {
        val_bytes = env->GetByteArrayElements(value, NULL);
        val_str = (const char*)val_bytes;
    }
    if (data) {
        data_bytes = env->GetByteArrayElements(data, NULL);
        data_str = (const char*)data_bytes;
    }

    if (g_log_enabled) {
        LOGD("responseGenaEvent: cmd=%d, value=%s", cmd, val_str ? val_str : "");
    }

    NPT_Result res = NPT_FAILURE;
    PLT_Service* service = NULL;

    PLT_MediaRenderer* renderer = static_cast<PLT_MediaRenderer*>(g_device.AsPointer());

    switch (cmd) {
        case TOCONTRPOINT_SET_MEDIA_DURATION: {
            NPT_String val = val_str ? val_str : "";
            res = renderer->FindServiceByType("urn:schemas-upnp-org:service:AVTransport:1", service);
            if (NPT_SUCCEEDED(res) && service) {
                service->SetStateVariable("CurrentTrackDuration", val);
            }
            break;
        }
        case TOCONTRPOINT_SET_MEDIA_POSITION: {
            NPT_String val = val_str ? val_str : "";
            res = renderer->FindServiceByType("urn:schemas-upnp-org:service:AVTransport:1", service);
            if (NPT_SUCCEEDED(res) && service) {
                service->SetStateVariable("RelativeTimePosition", val);
                service->SetStateVariable("AbsoluteTimePosition", val);
            }
            break;
        }
        case TOCONTRPOINT_SET_MEDIA_PLAYINGSTATE: {
            NPT_String val = val_str ? val_str : "STOPPED";
            res = renderer->FindServiceByType("urn:schemas-upnp-org:service:AVTransport:1", service);
            if (NPT_SUCCEEDED(res) && service) {
                service->SetStateVariable("TransportState", val);
            }
            break;
        }
        default:
            LOGW("responseGenaEvent: unknown cmd=%d", cmd);
            res = NPT_ERROR_INVALID_PARAMETERS;
            break;
    }

    if (val_bytes) env->ReleaseByteArrayElements(value, val_bytes, JNI_ABORT);
    if (data_bytes) env->ReleaseByteArrayElements(data, data_bytes, JNI_ABORT);

    return NPT_SUCCEEDED(res) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_pngcui_skyworth_dlna_jni_PlatinumJniProxy_enableLogPrint(
        JNIEnv* env,
        jobject /* this */,
        jboolean flag) {

    g_log_enabled = (flag == JNI_TRUE);
    LOGI("enableLogPrint: %d", g_log_enabled);
    return JNI_TRUE;
}
