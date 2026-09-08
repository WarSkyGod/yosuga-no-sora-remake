/* SPDX-License-Identifier: MIT */
/*
 * Android JNI bridge for the external data flow:
 *  - the bootstrap activity reports the extracted data directory
 *    (nativeSetDataDir), which StorageImpl uses to resolve ./data/* to real
 *    files instead of APK assets;
 *  - nativeExtractXp3Start runs the xp3 extractor on a worker thread and
 *    reports through <outDir>.status / <outDir>.progress (the Java side
 *    polls them, mirroring the OHOS flow);
 *  - nativeOnAudioFocusChange pauses/resumes the FAudio/SDL playback
 *    device when another app (alarm, call, ...) takes the audio focus.
 */

#include <jni.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>

#include <SDL_audio.h>

#include "AndroidDataBridge.h"
#include "xp3_extract.h"

static std::string gDataDir;

// Extraction worker state. gExtractRunning is atomic: the Java side polls
// it from another thread and the start/done race previously left the flag
// latched after a completed run, permanently blocking re-extraction.
static std::atomic<bool> gExtractRunning{false};
static std::thread gExtractThread;

const char *AndroidDataDir_Get()
{
	return gDataDir.empty() ? nullptr : gDataDir.c_str();
}

extern "C" JNIEXPORT void JNICALL
Java_com_shuimo0413_yosuganosora_hdremake_KirikiriSDL2Activity_nativeSetDataDir(
	JNIEnv *env, jobject thiz, jstring dir)
{
	if (dir == nullptr) return;
	const char *utf = env->GetStringUTFChars(dir, nullptr);
	if (utf)
	{
		gDataDir = utf;
		env->ReleaseStringUTFChars(dir, utf);
	}
}

namespace {

struct AndroidExtractCtx {
	std::string xp3Path;
	std::string outDir;
	std::string progressPath;
	std::string statusPath;
};

int AndroidProgressBridge(void *vctx, int done, int total, const char *nameUtf8)
{
	AndroidExtractCtx *ctx = static_cast<AndroidExtractCtx *>(vctx);
	if (done % 512 != 0 && done < total) return 1;
	FILE *p = fopen(ctx->progressPath.c_str(), "w");
	if (p)
	{
		fprintf(p, "%d %d %s\n", done, total, nameUtf8 ? nameUtf8 : "");
		fclose(p);
	}
	return 1;
}

void AndroidExtractWorker(AndroidExtractCtx *ctx)
{
	OHOSXp3ExtractResult res;
	memset(&res, 0, sizeof(res));
	int rc = -1;
	try
	{
		rc = OHOS_ExtractXp3(ctx->xp3Path.c_str(), ctx->outDir.c_str(),
			AndroidProgressBridge, ctx, &res);
	}
	catch (...)
	{
		snprintf(res.error, sizeof(res.error), "worker exception");
	}
	/* Reset the latch BEFORE publishing the status file: once the status
	 * file exists the Java side may immediately start the next extraction,
	 * whose join below waits for this worker to fully finish anyway. */
	gExtractRunning = false;
	FILE *s = fopen(ctx->statusPath.c_str(), "w");
	if (s)
	{
		if (rc == 0)
		{
			fprintf(s, "ok %d %d\n", res.filesDone, res.filesTotal);
		}
		else
		{
			fprintf(s, "error %s\n", res.error);
		}
		fclose(s);
	}
	delete ctx;
}

} /* namespace */

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shuimo0413_yosuganosora_hdremake_BootstrapActivity_nativeExtractXp3Start(
	JNIEnv *env, jobject thiz, jstring xp3Path, jstring outDir)
{
	// Atomic test-and-set: closes the check/start race window.
	if (gExtractRunning.exchange(true)) return JNI_FALSE;
	if (xp3Path == nullptr || outDir == nullptr) return JNI_FALSE;
	const char *xp3 = env->GetStringUTFChars(xp3Path, nullptr);
	const char *out = env->GetStringUTFChars(outDir, nullptr);
	if (!xp3 || !out)
	{
		if (xp3) env->ReleaseStringUTFChars(xp3Path, xp3);
		if (out) env->ReleaseStringUTFChars(outDir, out);
		return JNI_FALSE;
	}
	AndroidExtractCtx *ctx = new AndroidExtractCtx();
	ctx->xp3Path = xp3;
	ctx->outDir = out;
	ctx->progressPath = ctx->outDir + ".progress";
	ctx->statusPath = ctx->outDir + ".status";
	env->ReleaseStringUTFChars(xp3Path, xp3);
	env->ReleaseStringUTFChars(outDir, out);
	FILE *p = fopen(ctx->progressPath.c_str(), "w");
	if (p) fclose(p);
	FILE *s = fopen(ctx->statusPath.c_str(), "w");
	if (s) fclose(s);
	try
	{
		// A finished-but-not-detached previous worker must be reaped
		// before the thread object is reassigned, or std::terminate fires.
		if (gExtractThread.joinable()) gExtractThread.join();
		gExtractThread = std::thread(AndroidExtractWorker, ctx);
	}
	catch (...)
	{
		delete ctx;
		gExtractRunning = false;
		return JNI_FALSE;
	}
	return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shuimo0413_yosuganosora_hdremake_KirikiriSDL2Activity_nativeDetachExtractThread(
	JNIEnv *env, jobject thiz)
{
	if (gExtractThread.joinable()) gExtractThread.detach();
}

// ---- Audio focus ----------------------------------------------------------
// The Java side claims AUDIOFOCUS_GAIN and forwards focus changes here.
// When the focus is lost (alarm, call, navigation, another player) the SDL
// playback devices are paused; when AUDIOFOCUS_GAIN arrives they resume.
// FAudio opens its SDL device through SDL_OpenAudioDevice, so the device id
// is dynamic (>= 2, legacy SDL_PauseAudio targets id 1): scan the small
// device-id space and remember exactly which devices WE paused, so the
// resume never un-pauses a device that was already paused before us.
namespace {
std::atomic<bool> gFocusMuted{false};
std::atomic<bool> gFocusDevices[8] = {};
/* App lifecycle mute (background): mirrors the focus-loss logic but with a
 * separate flag set so a background pause never collides with a real
 * audio-focus loss (alarm/call while the app is already in background). */
std::atomic<bool> gAppMuted{false};
std::atomic<bool> gAppDevices[8] = {};
}

extern "C" JNIEXPORT void JNICALL
Java_com_shuimo0413_yosuganosora_hdremake_KirikiriSDL2Activity_nativeOnAudioFocusChange(
	JNIEnv *env, jclass clazz, jint change)
{
	(void)env;
	(void)clazz;
	// AudioManager.AUDIOFOCUS_GAIN == 1; every other change we care about
	// (AUDIOFOCUS_LOSS, _LOSS_TRANSIENT, _LOSS_TRANSIENT_CAN_DUCK) mutes.
	if (change == 1 /* AUDIOFOCUS_GAIN */)
	{
		if (gFocusMuted.exchange(false))
		{
			for (int slot = 0; slot < 8; ++slot)
			{
				if (gFocusDevices[slot].exchange(false))
				{
					SDL_PauseAudioDevice((SDL_AudioDeviceID)(slot + 2), 0);
				}
			}
		}
	}
	else if (!gFocusMuted.exchange(true))
	{
		// IDs 2..9 cover every realistic SDL_OpenAudioDevice allocation in
		// this app (FAudio owns the single playback device). Unknown ids
		// report SDL_AUDIO_STOPPED and are skipped.
		for (int slot = 0; slot < 8; ++slot)
		{
			SDL_AudioDeviceID id = (SDL_AudioDeviceID)(slot + 2);
			if (SDL_GetAudioDeviceStatus(id) == SDL_AUDIO_PLAYING)
			{
				SDL_PauseAudioDevice(id, 1);
				gFocusDevices[slot] = true;
			}
		}
	}
	}

	// ---- App lifecycle (background) -------------------------------------------
	// iOS suspends the FAudio engine when the app leaves the foreground so no
	// BGM/SE keeps playing in the background (see FAudioDevice.cpp
	// TVPIOSAudioSuspend/Resume).  Android mirrors that here with the same
	// device-pause trick as the focus path but on an independent flag set.
	extern "C" JNIEXPORT void JNICALL
	Java_com_shuimo0413_yosuganosora_hdremake_KirikiriSDL2Activity_nativeOnAppBackground(
		JNIEnv *env, jclass clazz)
	{
		(void)env;
		(void)clazz;
		if (gAppMuted.exchange(true)) return;
		for (int slot = 0; slot < 8; ++slot)
		{
			SDL_AudioDeviceID id = (SDL_AudioDeviceID)(slot + 2);
			if (SDL_GetAudioDeviceStatus(id) == SDL_AUDIO_PLAYING)
			{
				SDL_PauseAudioDevice(id, 1);
				gAppDevices[slot] = true;
			}
		}
	}

	extern "C" JNIEXPORT void JNICALL
	Java_com_shuimo0413_yosuganosora_hdremake_KirikiriSDL2Activity_nativeOnAppForeground(
		JNIEnv *env, jclass clazz)
	{
		(void)env;
		(void)clazz;
		if (!gAppMuted.exchange(false)) return;
		for (int slot = 0; slot < 8; ++slot)
		{
			if (gAppDevices[slot].exchange(false))
			{
				SDL_PauseAudioDevice((SDL_AudioDeviceID)(slot + 2), 0);
			}
		}
	}
