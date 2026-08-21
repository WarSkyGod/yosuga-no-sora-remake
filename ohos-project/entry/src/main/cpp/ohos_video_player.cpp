/* SPDX-License-Identifier: MIT */
/*
 * OpenHarmony hardware video player for the KrKriz engine.
 *
 * Uses the Media Kit OH_AVPlayer (hardware decode via AVCodec) and renders
 * directly into the XComponent's OHNativeWindow.
 */

#include "ohos_video_player.h"

#include <multimedia/player_framework/avplayer.h>
#include <multimedia/player_framework/avplayer_base.h>
#include <native_window/external_window.h>

#include <cstdarg>
#include <cstdio>
#include <hilog/log.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace Yosuga
{

static OHOSVideoPlayer *g_cb_self = nullptr;

static void OHOS_VPlayerInfoCallback(OH_AVPlayer */*player*/, AVPlayerOnInfoType type,
	OH_AVFormat */*infoBody*/, void *userData)
{
	OHOSVideoPlayer *self = static_cast<OHOSVideoPlayer *>(userData);
	if (self == nullptr) return;
	self->HandleInfo((int)type);
}

static void OHOS_VPlayerErrorCallback(OH_AVPlayer */*player*/, int32_t errorCode,
	const char */*errorMsg*/, void *userData)
{
	OHOSVideoPlayer *self = static_cast<OHOSVideoPlayer *>(userData);
	if (self == nullptr) return;
	self->HandleError(errorCode);
}

static OHOSVideoPlayer::EndCallback g_ohos_end_callback = nullptr;

void OHOSVideoPlayer::SetEndCallback(EndCallback cb)
{
	g_ohos_end_callback = cb;
}

OHOSVideoPlayer::OHOSVideoPlayer()
	: m_player(nullptr), m_nativeWindow(nullptr), m_playing(false), m_listener(nullptr)
{
}

OHOSVideoPlayer::~OHOSVideoPlayer()
{
	Close();
	g_cb_self = nullptr;
}

std::string OHOSVideoPlayer::LogPath()
{
	const char *dd = getenv("KRKR_OHOS_DATA_DIR");
	return (dd && dd[0]) ? (std::string(dd) + "/video-player.log") : std::string("/data/local/tmp/yosuga-video.log");
}

void OHOSVideoPlayer::Log(const char *fmt, ...)
{
	FILE *lf = fopen(LogPath().c_str(), "a");
	if (lf)
	{
		va_list ap;
		va_start(ap, fmt);
		vfprintf(lf, fmt, ap);
		va_end(ap);
		fputc('\n', lf);
		fclose(lf);
	}
}

bool OHOSVideoPlayer::Open(const std::string &filePath, OHNativeWindow *nativeWindow, bool loop)
{
	std::lock_guard<std::mutex> lock(m_mutex);

	if (m_player != nullptr)
	{
		OH_AVPlayer_Stop(m_player);
		OH_AVPlayer_Release(m_player);
		m_player = nullptr;
	}
	m_nativeWindow = nativeWindow;
	m_playing = false;

	if (m_nativeWindow == nullptr)
	{
		Log("Open: native window is null");
		return false;
	}

	m_player = OH_AVPlayer_Create();
	if (m_player == nullptr)
	{
		Log("Open: OH_AVPlayer_Create failed");
		return false;
	}

	/* Use an fd source: more reliable than file:// for public-dir paths. */
	int vfd = open(filePath.c_str(), O_RDONLY);
	if (vfd < 0)
	{
		Log("Open: open(%s) failed", filePath.c_str());
		OH_AVPlayer_Release(m_player);
		m_player = nullptr;
		return false;
	}
	struct stat vst;
	if (stat(filePath.c_str(), &vst) != 0 || vst.st_size <= 0)
	{
		Log("Open: stat(%s) failed", filePath.c_str());
		close(vfd);
		OH_AVPlayer_Release(m_player);
		m_player = nullptr;
		return false;
	}
	OH_AVErrCode ret = OH_AVPlayer_SetFDSource(m_player, vfd, 0, vst.st_size);
	close(vfd); /* AVPlayer duplicates/keeps its own reference */
	if (ret != AV_ERR_OK)
	{
		Log("Open: SetFDSource(%s) ret=%d", filePath.c_str(), (int)ret);
		OH_AVPlayer_Release(m_player);
		m_player = nullptr;
		return false;
	}
	Log("Open: SetFDSource ok, size=%lld", (long long)vst.st_size);

	ret = OH_AVPlayer_SetVideoSurface(m_player, m_nativeWindow);
	if (ret != AV_ERR_OK)
	{
		Log("Open: SetVideoSurface ret=%d", (int)ret);
		OH_AVPlayer_Release(m_player);
		m_player = nullptr;
		return false;
	}

	/* Keep the video aspect ratio (letterbox) instead of stretching it to
	 * fill the whole window. */
	if (m_nativeWindow)
	{
		OH_NativeWindow_NativeWindowSetScalingMode(m_nativeWindow, 0, OH_SCALING_MODE_SCALE_TO_WINDOW);
	}

	OH_AVPlayer_SetLooping(m_player, loop);

	OH_AVPlayer_SetOnInfoCallback(m_player, OHOS_VPlayerInfoCallback, this);
	OH_AVPlayer_SetOnErrorCallback(m_player, OHOS_VPlayerErrorCallback, this);

	Log("Open: Prepare+Play (%s)", filePath.c_str());
	ret = OH_AVPlayer_Prepare(m_player);
	if (ret != AV_ERR_OK)
	{
		Log("Open: Prepare ret=%d", (int)ret);
		OH_AVPlayer_Release(m_player);
		m_player = nullptr;
		return false;
	}
	/* Set volume after Prepare so audio output is active. */
	OH_AVPlayer_SetVolume(m_player, 1.0f, 1.0f);
	Log("Open: volume set to 1.0");
	ret = OH_AVPlayer_Play(m_player);
	if (ret != AV_ERR_OK)
	{
		Log("Open: Play ret=%d", (int)ret);
		OH_AVPlayer_Release(m_player);
		m_player = nullptr;
		return false;
	}
	m_playing = true;
	Log("Open: playing");
	return true;
}

void OHOSVideoPlayer::HandleInfo(int type)
{
	Log("HandleInfo: type=%d", type);
	if (type == (int)AV_INFO_TYPE_EOS)
	{
		Log("HandleInfo: EOS -> notify engine");
		if (m_listener) m_listener->OnVideoEnded();
		if (g_ohos_end_callback) g_ohos_end_callback();
	}
	else if (type == (int)AV_INFO_TYPE_STATE_CHANGE)
	{
		/* optional state tracking */
	}
}

void OHOSVideoPlayer::HandleError(int32_t errorCode)
{
	Log("HandleError: %d", (int)errorCode);
	if (m_listener) m_listener->OnVideoError((int)errorCode);
}

void OHOSVideoPlayer::Pause()
{
	std::lock_guard<std::mutex> lock(m_mutex);
	if (m_player != nullptr) OH_AVPlayer_Pause(m_player);
	m_playing = false;
}

void OHOSVideoPlayer::Resume()
{
	std::lock_guard<std::mutex> lock(m_mutex);
	if (m_player != nullptr) OH_AVPlayer_Play(m_player);
	m_playing = true;
}

void OHOSVideoPlayer::Stop()
{
	std::lock_guard<std::mutex> lock(m_mutex);
	if (m_player != nullptr) OH_AVPlayer_Stop(m_player);
	m_playing = false;
}

void OHOSVideoPlayer::Close()
{
	std::lock_guard<std::mutex> lock(m_mutex);
	if (m_player != nullptr)
	{
		OH_AVPlayer_Stop(m_player);
		OH_AVPlayer_ReleaseSync(m_player);
		m_player = nullptr;
	}
	m_playing = false;
	m_nativeWindow = nullptr;
}

} // namespace Yosuga
