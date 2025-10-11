#include <jni.h>
#include <string>
#include <cstring>
#include "zlm/api/include/mk_mediakit.h"

static mk_media g_media = nullptr;
static bool g_env_inited = false;
static bool g_video_inited = false;
static bool g_init_completed = false;

extern "C" JNIEXPORT void JNICALL
Java_com_example_mycam_server_ZlmRtspPublisher_nativeStart(JNIEnv* env, jobject thiz, jstring url) {
    if (!g_env_inited) {
        // threads=2, log level=2(info), console log only, no files, no ini/ssl
        mk_env_init1(2, 2, LOG_CONSOLE, NULL, 0, 1, NULL, 1, NULL, NULL);
        g_env_inited = true;
    }
    // Start RTSP (non-SSL) on 8554
    mk_rtsp_server_start(8554, 0);
    // Create media __defaultVhost__/live/live
    if (!g_media) {
        g_media = mk_media_create("__defaultVhost__", "live", "live", 0, 0, 0);
    }
    g_video_inited = false;
    g_init_completed = false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mycam_server_ZlmRtspPublisher_nativeStop(JNIEnv* env, jobject thiz) {
    if (g_media) {
        mk_media_release(g_media);
        g_media = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mycam_server_ZlmRtspPublisher_nativeOnVideoConfig(JNIEnv* env, jobject thiz, jbyteArray sps, jbyteArray pps) {
    if (!g_media) return;
    if (!g_video_inited) {
        mk_media_init_video(g_media, 0 /*H264*/, 1280, 720, 30.0f, 2000000);
        g_video_inited = true;
    }
    // Push SPS/PPS as AnnexB to help SDP/decoder
    if (sps) {
        jsize slen = env->GetArrayLength(sps);
        if (slen > 0) {
            jbyte* sdata = env->GetByteArrayElements(sps, NULL);
            std::string annexb;
            annexb.resize(4 + slen);
            annexb[0]=0; annexb[1]=0; annexb[2]=0; annexb[3]=1;
            memcpy(&annexb[4], sdata, slen);
            mk_media_input_h264(g_media, annexb.data(), (int)annexb.size(), 0, 0);
            env->ReleaseByteArrayElements(sps, sdata, JNI_ABORT);
        }
    }
    if (pps) {
        jsize plen = env->GetArrayLength(pps);
        if (plen > 0) {
            jbyte* pdata = env->GetByteArrayElements(pps, NULL);
            std::string annexb;
            annexb.resize(4 + plen);
            annexb[0]=0; annexb[1]=0; annexb[2]=0; annexb[3]=1;
            memcpy(&annexb[4], pdata, plen);
            mk_media_input_h264(g_media, annexb.data(), (int)annexb.size(), 0, 0);
            env->ReleaseByteArrayElements(pps, pdata, JNI_ABORT);
        }
    }
    if (g_video_inited && !g_init_completed) {
        mk_media_init_complete(g_media);
        g_init_completed = true;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mycam_server_ZlmRtspPublisher_nativeOnVideoNal(JNIEnv* env, jobject thiz, jbyteArray nal, jlong ptsUs, jboolean isKey) {
    if (!g_media) return;
    jsize len = env->GetArrayLength(nal);
    if (len <= 0) return;
    jbyte* data = env->GetByteArrayElements(nal, NULL);
    std::string annexb;
    annexb.resize(4 + len);
    annexb[0]=0; annexb[1]=0; annexb[2]=0; annexb[3]=1;
    memcpy(&annexb[4], data, len);
    mk_media_input_h264(g_media, annexb.data(), (int)annexb.size(), (uint64_t)(ptsUs/1000), (uint64_t)(ptsUs/1000));
    env->ReleaseByteArrayElements(nal, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mycam_server_ZlmRtspPublisher_nativeOnAudioAac(JNIEnv* env, jobject thiz, jbyteArray aac, jlong ptsUs) {
    // Audio disabled for now
    (void)aac; (void)ptsUs;
}


