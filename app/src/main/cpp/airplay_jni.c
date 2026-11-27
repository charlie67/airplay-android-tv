/*
 * JNI bridge between the AirPlay protocol core (AirPlayServerLib) and the
 * Android app. Plays the role of airplay2dll/FgAirplayServer.cpp on Windows:
 * it starts the RAOP (mirroring/audio) and legacy AirPlay servers, and
 * forwards decrypted H.264 access units and decoded PCM audio to Kotlin.
 *
 * Service discovery (Bonjour/mDNS) is done on the Kotlin side with
 * NsdManager, so dnssd.c is not used here.
 */
#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "raop.h"
#include "airplay.h"
#include "stream.h"

#define TAG "AirPlayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Same ports as the Windows build. airplay_start() also binds its legacy
 * mirror server on 7100, so neither may use that port. */
#define DEFAULT_RAOP_PORT 5001
#define DEFAULT_AIRPLAY_PORT 7001
#define MAX_CLIENTS 10

static JavaVM *g_vm = NULL;
static pthread_key_t g_detach_key;
static pthread_once_t g_detach_once = PTHREAD_ONCE_INIT;

typedef struct {
	jobject listener;
	jmethodID on_connected;
	jmethodID on_disconnected;
	jmethodID on_video;
	jmethodID on_audio;
	jmethodID on_volume;
	jmethodID on_audio_flush;
	jmethodID on_sender_paused;
	jmethodID on_geometry;

	airplay_t *airplay;
	raop_t *raop;
	airplay_callbacks_t airplay_cb;
	raop_callbacks_t raop_cb;
	unsigned short raop_port;
	unsigned short airplay_port;
	int log_level;
} server_t;

/* ---- thread attachment -------------------------------------------------- */

static void
detach_current_thread(void *unused)
{
	(void)unused;
	if (g_vm) {
		(*g_vm)->DetachCurrentThread(g_vm);
	}
}

static void
make_detach_key(void)
{
	pthread_key_create(&g_detach_key, detach_current_thread);
}

/* Returns a JNIEnv for the calling thread, attaching protocol threads to the
 * VM on first use. They are detached automatically when the thread exits. */
static JNIEnv *
get_env(void)
{
	JNIEnv *env = NULL;
	if (!g_vm) {
		return NULL;
	}
	jint status = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
	if (status == JNI_OK) {
		return env;
	}
	if (status != JNI_EDETACHED) {
		return NULL;
	}
	JavaVMAttachArgs args = { JNI_VERSION_1_6, "AirPlayNative", NULL };
	if ((*g_vm)->AttachCurrentThread(g_vm, &env, &args) != JNI_OK) {
		return NULL;
	}
	pthread_once(&g_detach_once, make_detach_key);
	pthread_setspecific(g_detach_key, (void *)1);
	return env;
}

static void
clear_exception(JNIEnv *env, const char *where)
{
	if ((*env)->ExceptionCheck(env)) {
		LOGE("Java exception in %s", where);
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
}

/* Strings from senders are standard UTF-8 (device names can contain emoji),
 * which NewStringUTF does not accept. Pass bytes and decode in Kotlin. */
static jbyteArray
to_bytes(JNIEnv *env, const char *str)
{
	jsize len = str ? (jsize)strlen(str) : 0;
	jbyteArray array = (*env)->NewByteArray(env, len);
	if (array && len > 0) {
		(*env)->SetByteArrayRegion(env, array, 0, len, (const jbyte *)str);
	}
	return array;
}

/* ---- RAOP callbacks ------------------------------------------------------ */

static void
cb_connected(void *cls, const char *remoteName, const char *remoteDeviceId)
{
	server_t *server = cls;
	JNIEnv *env = get_env();
	if (!env) return;
	jbyteArray name = to_bytes(env, remoteName);
	jbyteArray id = to_bytes(env, remoteDeviceId);
	(*env)->CallVoidMethod(env, server->listener, server->on_connected, name, id);
	clear_exception(env, "onConnected");
	(*env)->DeleteLocalRef(env, name);
	(*env)->DeleteLocalRef(env, id);
}

static void
cb_disconnected(void *cls, const char *remoteName, const char *remoteDeviceId)
{
	server_t *server = cls;
	JNIEnv *env = get_env();
	if (!env) return;
	jbyteArray name = to_bytes(env, remoteName);
	jbyteArray id = to_bytes(env, remoteDeviceId);
	(*env)->CallVoidMethod(env, server->listener, server->on_disconnected, name, id);
	clear_exception(env, "onDisconnected");
	(*env)->DeleteLocalRef(env, name);
	(*env)->DeleteLocalRef(env, id);
}

static void
cb_audio_process(void *cls, pcm_data_struct *data, const char *remoteName,
	const char *remoteDeviceId)
{
	server_t *server = cls;
	if (!data || !data->data || data->data_len <= 0 || data->bits_per_sample != 16
		|| data->channels == 0 || data->channels > 2
		|| data->sample_rate < 8000 || data->sample_rate > 192000) {
		return;
	}
	JNIEnv *env = get_env();
	if (!env) return;
	jbyteArray pcm = (*env)->NewByteArray(env, data->data_len);
	if (!pcm) {
		clear_exception(env, "onAudio alloc");
		return;
	}
	(*env)->SetByteArrayRegion(env, pcm, 0, data->data_len, (const jbyte *)data->data);
	(*env)->CallVoidMethod(env, server->listener, server->on_audio, pcm,
		(jint)data->sample_rate, (jint)data->channels);
	clear_exception(env, "onAudio");
	(*env)->DeleteLocalRef(env, pcm);
}

static void
cb_video_process(void *cls, h264_decode_struct *data, const char *remoteName,
	const char *remoteDeviceId)
{
	server_t *server = cls;
	if (!data || !data->data || data->data_len <= 0) {
		return;
	}
	JNIEnv *env = get_env();
	if (!env) return;
	jbyteArray au = (*env)->NewByteArray(env, data->data_len);
	if (!au) {
		clear_exception(env, "onVideo alloc");
		return;
	}
	(*env)->SetByteArrayRegion(env, au, 0, data->data_len, (const jbyte *)data->data);
	/* frame_type 0 marks the SPS+PPS packet and keyframes; 1 other frames. */
	(*env)->CallVoidMethod(env, server->listener, server->on_video, au,
		(jboolean)(data->frame_type == 0), (jlong)data->pts);
	clear_exception(env, "onVideo");
	(*env)->DeleteLocalRef(env, au);
}

static void
cb_video_report_geometry(void *cls, float sourceWidth, float sourceHeight,
	float outputWidth, float outputHeight, const char *remoteName,
	const char *remoteDeviceId)
{
	server_t *server = cls;
	JNIEnv *env = get_env();
	if (!env) return;
	(*env)->CallVoidMethod(env, server->listener, server->on_geometry,
		sourceWidth, sourceHeight, outputWidth, outputHeight);
	clear_exception(env, "onVideoGeometry");
}

static void
cb_video_set_sender_paused(void *cls, int paused, const char *remoteName,
	const char *remoteDeviceId)
{
	server_t *server = cls;
	JNIEnv *env = get_env();
	if (!env) return;
	(*env)->CallVoidMethod(env, server->listener, server->on_sender_paused,
		(jboolean)(paused != 0));
	clear_exception(env, "onSenderPaused");
}

static void
cb_audio_flush(void *cls, void *session, const char *remoteName,
	const char *remoteDeviceId)
{
	server_t *server = cls;
	JNIEnv *env = get_env();
	if (!env) return;
	(*env)->CallVoidMethod(env, server->listener, server->on_audio_flush);
	clear_exception(env, "onAudioFlush");
}

static void
cb_audio_set_volume(void *cls, void *session, float volume, const char *remoteName,
	const char *remoteDeviceId)
{
	server_t *server = cls;
	JNIEnv *env = get_env();
	if (!env) return;
	/* AirPlay volume in dB: 0.0 is full volume, -144.0 is mute. */
	(*env)->CallVoidMethod(env, server->listener, server->on_volume, volume);
	clear_exception(env, "onVolume");
}

static void
cb_audio_set_metadata(void *cls, void *session, const void *buffer, int buflen,
	const char *remoteName, const char *remoteDeviceId)
{
}

static void
cb_audio_set_coverart(void *cls, void *session, const void *buffer, int buflen,
	const char *remoteName, const char *remoteDeviceId)
{
}

static int
cb_pairing_request(void *cls, const char *remoteName, const char *remoteDeviceId,
	const char *remoteModel, const char *remoteOsName, const char *remoteOsVersion,
	const char *remoteOsBuildVersion, const char *remoteSourceVersion,
	const char *pairingFingerprint)
{
	LOGI("Pairing request from %s (%s %s %s)", remoteName ? remoteName : "?",
		remoteModel ? remoteModel : "?", remoteOsName ? remoteOsName : "?",
		remoteOsVersion ? remoteOsVersion : "?");
	return 1;
}

/* ---- legacy AirPlay (URL playback is not supported, as on Windows) -------- */

static void
ap_video_play(void *cls, char *url, double volume, double start_pos)
{
	LOGW("URL playback requested but not supported");
}

static void
ap_video_get_play_info(void *cls, double *duration, double *position, double *rate)
{
	if (duration) *duration = 0;
	if (position) *position = 0;
	if (rate) *rate = 0;
}

static void
log_callback(void *cls, int level, const char *msg)
{
	int prio;
	switch (level) {
	case RAOP_LOG_EMERG:
	case RAOP_LOG_ALERT:
	case RAOP_LOG_CRIT:
	case RAOP_LOG_ERR: prio = ANDROID_LOG_ERROR; break;
	case RAOP_LOG_WARNING: prio = ANDROID_LOG_WARN; break;
	case RAOP_LOG_NOTICE:
	case RAOP_LOG_INFO: prio = ANDROID_LOG_INFO; break;
	default: prio = ANDROID_LOG_DEBUG; break;
	}
	__android_log_write(prio, TAG, msg ? msg : "");
}

/* ---- lifecycle ----------------------------------------------------------- */

static void
server_destroy(JNIEnv *env, server_t *server)
{
	if (!server) return;
	if (server->raop) {
		raop_destroy(server->raop);
		server->raop = NULL;
	}
	if (server->airplay) {
		airplay_destroy(server->airplay);
		server->airplay = NULL;
	}
	if (server->listener) {
		(*env)->DeleteGlobalRef(env, server->listener);
	}
	free(server);
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved)
{
	g_vm = vm;
	/* A sender closing its socket mid-send must not kill the process. */
	signal(SIGPIPE, SIG_IGN);
	return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_airplayserver_tv_NativeBridge_nativeStart(JNIEnv *env, jclass clazz,
	jobject listener, jbyteArray hwaddr_array, jbyteArray seed_array,
	jint display_width, jint display_height, jboolean verbose)
{
	unsigned char seed[32];
	char hwaddr[6];
	int error = 0;
	int ret;

	if (!listener || !hwaddr_array || !seed_array
		|| (*env)->GetArrayLength(env, hwaddr_array) != 6
		|| (*env)->GetArrayLength(env, seed_array) != 32) {
		LOGE("nativeStart: invalid arguments");
		return 0;
	}
	(*env)->GetByteArrayRegion(env, hwaddr_array, 0, 6, (jbyte *)hwaddr);
	(*env)->GetByteArrayRegion(env, seed_array, 0, 32, (jbyte *)seed);

	server_t *server = calloc(1, sizeof(server_t));
	if (!server) return 0;
	server->log_level = verbose ? RAOP_LOG_DEBUG : RAOP_LOG_INFO;

	server->listener = (*env)->NewGlobalRef(env, listener);
	jclass cls = (*env)->GetObjectClass(env, listener);
	server->on_connected = (*env)->GetMethodID(env, cls, "onConnected", "([B[B)V");
	server->on_disconnected = (*env)->GetMethodID(env, cls, "onDisconnected", "([B[B)V");
	server->on_video = (*env)->GetMethodID(env, cls, "onVideoData", "([BZJ)V");
	server->on_audio = (*env)->GetMethodID(env, cls, "onAudioData", "([BII)V");
	server->on_volume = (*env)->GetMethodID(env, cls, "onVolume", "(F)V");
	server->on_audio_flush = (*env)->GetMethodID(env, cls, "onAudioFlush", "()V");
	server->on_sender_paused = (*env)->GetMethodID(env, cls, "onSenderPaused", "(Z)V");
	server->on_geometry = (*env)->GetMethodID(env, cls, "onVideoGeometry", "(FFFF)V");
	(*env)->DeleteLocalRef(env, cls);
	if (!server->on_connected || !server->on_disconnected || !server->on_video
		|| !server->on_audio || !server->on_volume || !server->on_audio_flush
		|| !server->on_sender_paused || !server->on_geometry) {
		clear_exception(env, "nativeStart method lookup");
		LOGE("nativeStart: listener is missing callback methods");
		server_destroy(env, server);
		return 0;
	}

	server->airplay_cb.cls = server;
	server->airplay_cb.video_play = ap_video_play;
	server->airplay_cb.video_get_play_info = ap_video_get_play_info;

	server->raop_cb.cls = server;
	server->raop_cb.connected = cb_connected;
	server->raop_cb.disconnected = cb_disconnected;
	server->raop_cb.audio_process = cb_audio_process;
	server->raop_cb.video_process = cb_video_process;
	server->raop_cb.video_report_geometry = cb_video_report_geometry;
	server->raop_cb.video_set_sender_paused = cb_video_set_sender_paused;
	server->raop_cb.audio_flush = cb_audio_flush;
	server->raop_cb.audio_set_volume = cb_audio_set_volume;
	server->raop_cb.audio_set_metadata = cb_audio_set_metadata;
	server->raop_cb.audio_set_coverart = cb_audio_set_coverart;
	server->raop_cb.pairing_request = cb_pairing_request;

	/* Legacy AirPlay HTTP server (advertised as _airplay._tcp). */
	server->airplay = airplay_init_with_seed(MAX_CLIENTS, &server->airplay_cb, NULL, seed, &error);
	if (!server->airplay) {
		LOGE("airplay_init failed (%d)", error);
		server_destroy(env, server);
		return 0;
	}
	airplay_set_log_callback(server->airplay, log_callback, server);
	airplay_set_log_level(server->airplay, server->log_level);
	server->airplay_port = DEFAULT_AIRPLAY_PORT;
	ret = airplay_start(server->airplay, &server->airplay_port, hwaddr, sizeof(hwaddr), NULL);
	if (ret != 1) {
		LOGW("AirPlay port %d unavailable, using an ephemeral port", DEFAULT_AIRPLAY_PORT);
		airplay_stop(server->airplay);
		server->airplay_port = 0;
		ret = airplay_start(server->airplay, &server->airplay_port, hwaddr, sizeof(hwaddr), NULL);
	}
	if (ret != 1) {
		LOGE("airplay_start failed (%d)", ret);
		server_destroy(env, server);
		return 0;
	}

	/* RAOP/RTSP server: pairing, FairPlay, mirroring and audio streams. */
	server->raop = raop_init_with_seed(MAX_CLIENTS, &server->raop_cb, seed);
	if (!server->raop) {
		LOGE("raop_init failed");
		server_destroy(env, server);
		return 0;
	}
	raop_set_log_callback(server->raop, log_callback, server);
	raop_set_log_level(server->raop, server->log_level);
	raop_set_password(server->raop, NULL);
	raop_set_display_size(server->raop, (unsigned int)display_width, (unsigned int)display_height);
	server->raop_port = DEFAULT_RAOP_PORT;
	ret = raop_start(server->raop, &server->raop_port);
	if (ret != 1) {
		LOGW("RAOP port %d unavailable, using an ephemeral port", DEFAULT_RAOP_PORT);
		raop_stop(server->raop);
		server->raop_port = 0;
		ret = raop_start(server->raop, &server->raop_port);
	}
	if (ret != 1) {
		LOGE("raop_start failed (%d)", ret);
		server_destroy(env, server);
		return 0;
	}
	raop_set_port(server->raop, server->raop_port);

	LOGI("Receiver started: raop port %u, airplay port %u, display %dx%d",
		server->raop_port, server->airplay_port, display_width, display_height);
	return (jlong)(intptr_t)server;
}

JNIEXPORT jint JNICALL
Java_com_airplayserver_tv_NativeBridge_nativeGetRaopPort(JNIEnv *env, jclass clazz, jlong handle)
{
	server_t *server = (server_t *)(intptr_t)handle;
	return server ? server->raop_port : 0;
}

JNIEXPORT jint JNICALL
Java_com_airplayserver_tv_NativeBridge_nativeGetAirplayPort(JNIEnv *env, jclass clazz, jlong handle)
{
	server_t *server = (server_t *)(intptr_t)handle;
	return server ? server->airplay_port : 0;
}

JNIEXPORT void JNICALL
Java_com_airplayserver_tv_NativeBridge_nativeDisconnect(JNIEnv *env, jclass clazz, jlong handle)
{
	server_t *server = (server_t *)(intptr_t)handle;
	if (server && server->raop) {
		raop_disconnect_session(server->raop);
	}
}

JNIEXPORT void JNICALL
Java_com_airplayserver_tv_NativeBridge_nativeStop(JNIEnv *env, jclass clazz, jlong handle)
{
	server_t *server = (server_t *)(intptr_t)handle;
	if (server) {
		if (server->raop) {
			raop_stop(server->raop);
		}
		if (server->airplay) {
			airplay_stop(server->airplay);
		}
		server_destroy(env, server);
	}
}
