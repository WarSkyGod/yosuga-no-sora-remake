/* SPDX-License-Identifier: MIT */
/*
 * OpenHarmony NAPI entry implementation.
 *
 * Owns the UIAbility context, the sandbox files directory, the XComponent
 * surface state and the Kirikiri engine thread. The SDL2 OpenHarmony backend
 * reaches the files directory and the native window through the
 * sdl_ohos_bridge.h API implemented here.
 */

#include "krkrsdl2_ohos_entry.h"
#include "ohos_data_extract.h"
#include "sdl_ohos_bridge.h"

#include <ace/xcomponent/native_interface_xcomponent.h>
#include <hilog/log.h>
#include <native_window/external_window.h>
#include <rawfile/raw_file_manager.h>

#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>

/* Kirikiri SDL2 platform hooks (see src/core/sdl2/SDLApplication.h).
 * These are plain C++ symbols; keep C++ linkage so the names match the
 * engine's mangled definitions. */
extern void krkrsdl2_pre_init_platform(void);
extern void krkrsdl2_convert_set_args(int argc, char **argv);
extern bool krkrsdl2_init_platform(void);
extern void krkrsdl2_run_main_loop(void);
extern void krkrsdl2_cleanup(void);

#define LOG_DOMAIN 0x0000
#define LOG_TAG "YosugaOHOS"

static void Log(LogLevel level, const char *fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	char buffer[1024];
	vsnprintf(buffer, sizeof(buffer), fmt, ap);
	va_end(ap);
	OH_LOG_Print(LOG_APP, level, LOG_DOMAIN, LOG_TAG, "%{public}s", buffer);
}

namespace
{

NativeResourceManager *g_resource_manager = nullptr;
std::string g_files_dir;
std::string g_data_dir;
std::string g_save_dir;

pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t g_cond = PTHREAD_COND_INITIALIZER;
OH_NativeXComponent *g_component = nullptr;
OHNativeWindow *g_native_window = nullptr;
uint64_t g_surface_width = 0;
uint64_t g_surface_height = 0;
bool g_window_ready = false;

bool g_engine_started = false;
std::atomic<bool> g_engine_running{false};

void OnSurfaceCreated(OH_NativeXComponent *component, void *window)
{
	(void)component;
	(void)window;
	Log(LOG_INFO, "XComponent surface created");
	OHOS_Entry_LogNative("engine: OnSurfaceCreated called");
}

void OnSurfaceChanged(OH_NativeXComponent *component, void *window)
{
	uint64_t width = 0;
	uint64_t height = 0;
	int32_t sz = OH_NativeXComponent_GetXComponentSize(component, window, &width, &height);

	pthread_mutex_lock(&g_lock);
	g_surface_width = width;
	g_surface_height = height;
	// OpenHarmony 5.0: the OnSurfaceChanged window parameter is the
	// OHNativeWindow itself (OH_NativeXComponent_GetNativeWindow removed).
	g_native_window = static_cast<OHNativeWindow *>(window);
	g_window_ready = g_native_window != nullptr;
	pthread_cond_broadcast(&g_cond);
	pthread_mutex_unlock(&g_lock);

	char sdiag[192];
	snprintf(sdiag, sizeof(sdiag),
		"engine: OnSurfaceChanged w=%llu h=%llu window=%p size_ret=%d",
		static_cast<unsigned long long>(width),
		static_cast<unsigned long long>(height),
		static_cast<void *>(window), static_cast<int>(sz));
	OHOS_Entry_LogNative(sdiag);

	if (g_window_ready)
	{
		SDL_OHOS_OnSurfaceChanged(static_cast<int>(width), static_cast<int>(height));
	}

	Log(LOG_INFO, "XComponent surface changed: %llux%llu window=%p",
		static_cast<unsigned long long>(width),
		static_cast<unsigned long long>(height),
		static_cast<void *>(g_native_window));
}

void OnSurfaceDestroyed(OH_NativeXComponent *component, void *window)
{
	(void)component;
	(void)window;
	pthread_mutex_lock(&g_lock);
	g_native_window = nullptr;
	g_window_ready = false;
	pthread_mutex_unlock(&g_lock);
	Log(LOG_INFO, "XComponent surface destroyed");
}

void OnTouchEvent(OH_NativeXComponent *component, void *window)
{
	OH_NativeXComponent_TouchEvent touch_event;
	OH_NativeXComponent_GetTouchEvent(component, window, &touch_event);

	int touch_type = -1;
	switch (touch_event.type)
	{
	case OH_NATIVEXCOMPONENT_DOWN:
		touch_type = SDL_OHOS_TOUCH_DOWN;
		break;
	case OH_NATIVEXCOMPONENT_UP:
		touch_type = SDL_OHOS_TOUCH_UP;
		break;
	case OH_NATIVEXCOMPONENT_MOVE:
		touch_type = SDL_OHOS_TOUCH_MOVE;
		break;
	case OH_NATIVEXCOMPONENT_CANCEL:
		touch_type = SDL_OHOS_TOUCH_UP;
		break;
	default:
		return;
	}
	SDL_OHOS_OnTouchEvent(touch_type, touch_event.x, touch_event.y);
}

} // namespace

/* ------------------------------------------------------------------------- */
/* sdl_ohos_bridge.h implementation                                          */
/* ------------------------------------------------------------------------- */

void SDL_OHOS_SetFilesDir(const char *files_dir)
{
	if (files_dir != nullptr)
	{
		g_files_dir = files_dir;
	}
}

const char *SDL_OHOS_GetFilesDir(void)
{
	return g_files_dir.empty() ? nullptr : g_files_dir.c_str();
}

void SDL_OHOS_SetDataDir(const char *data_dir)
{
	if (data_dir != nullptr)
	{
		g_data_dir = data_dir;
	}
}

const char *SDL_OHOS_GetDataDir(void)
{
	return g_data_dir.empty() ? nullptr : g_data_dir.c_str();
}

void SDL_OHOS_SetSaveDir(const char *save_dir)
{
	if (save_dir != nullptr)
	{
		g_save_dir = save_dir;
	}
}

const char *SDL_OHOS_GetSaveDir(void)
{
	return g_save_dir.empty() ? nullptr : g_save_dir.c_str();
}

int SDL_OHOS_WaitForNativeWindow(int timeout_ms)
{
	struct timespec ts;
	clock_gettime(CLOCK_REALTIME, &ts);
	ts.tv_sec += static_cast<time_t>(timeout_ms / 1000);
	ts.tv_nsec += static_cast<long>((timeout_ms % 1000)) * 1000000L;
	if (ts.tv_nsec >= 1000000000L)
	{
		ts.tv_sec += 1;
		ts.tv_nsec -= 1000000000L;
	}

	pthread_mutex_lock(&g_lock);
	while (!g_window_ready)
	{
		int wait_result = pthread_cond_timedwait(&g_cond, &g_lock, &ts);
		if (wait_result == ETIMEDOUT)
		{
			break;
		}
	}
	int ready = g_window_ready ? 1 : 0;
	pthread_mutex_unlock(&g_lock);
	return ready;
}

void *SDL_OHOS_GetNativeWindow(void)
{
	pthread_mutex_lock(&g_lock);
	void *window = static_cast<void *>(g_native_window);
	pthread_mutex_unlock(&g_lock);
	return window;
}

int SDL_OHOS_GetSurfaceSize(int *width, int *height)
{
	pthread_mutex_lock(&g_lock);
	int valid = g_window_ready ? 1 : 0;
	if (width)
	{
		*width = static_cast<int>(g_surface_width);
	}
	if (height)
	{
		*height = static_cast<int>(g_surface_height);
	}
	pthread_mutex_unlock(&g_lock);
	return valid;
}

/* ------------------------------------------------------------------------- */
/* krkrsdl2_ohos_entry.h implementation                                      */
/* ------------------------------------------------------------------------- */

void OHOS_Entry_SetResourceManager(napi_env env, napi_value ability_context)
{
	if (g_resource_manager != nullptr)
	{
		OH_ResourceManager_ReleaseNativeResourceManager(g_resource_manager);
		g_resource_manager = nullptr;
	}
	g_resource_manager = OH_ResourceManager_InitNativeResourceManager(env, ability_context);
	if (g_resource_manager == nullptr)
	{
		Log(LOG_ERROR, "OH_ResourceManager_InitNativeResourceManager failed");
	}
}

void OHOS_Entry_SetFilesDir(const char *files_dir)
{
	SDL_OHOS_SetFilesDir(files_dir);
	Log(LOG_INFO, "filesDir: %{public}s", files_dir != nullptr ? files_dir : "(null)");
}
void OHOS_Entry_SetSurfaceId(const char *surface_id)
{
	if (surface_id == nullptr || surface_id[0] == '\0')
	{
		OHOS_Entry_LogNative("engine: SetSurfaceId called with empty id");
		return;
	}
	uint64_t id = 0;
	try
	{
		id = static_cast<uint64_t>(std::strtoull(surface_id, nullptr, 10));
	}
	catch (...)
	{
		OHOS_Entry_LogNative("engine: SetSurfaceId parse failed");
		return;
	}
	char msg[128];
	snprintf(msg, sizeof(msg), "engine: SetSurfaceId=%s parsed=%llu", surface_id, static_cast<unsigned long long>(id));
	OHOS_Entry_LogNative(msg);

	OHNativeWindow *window = nullptr;
	int32_t ret = OH_NativeWindow_CreateNativeWindowFromSurfaceId(id, &window);
	if (ret == 0 && window != nullptr)
	{
		pthread_mutex_lock(&g_lock);
		g_native_window = window;
		g_window_ready = true;
		pthread_cond_broadcast(&g_cond);
		pthread_mutex_unlock(&g_lock);
		OHOS_Entry_LogNative("engine: native window created from surfaceId");
	}
	else
	{
		char fmsg[128];
		snprintf(fmsg, sizeof(fmsg), "engine: CreateNativeWindowFromSurfaceId failed ret=%d", static_cast<int>(ret));
		OHOS_Entry_LogNative(fmsg);
	}
}


void OHOS_Entry_SetExternalDirs(const char *base_dir, const char *save_dir)
{
	SDL_OHOS_SetDataDir(base_dir);
	SDL_OHOS_SetSaveDir(save_dir);
	Log(LOG_INFO, "external baseDir: %{public}s  saveDir: %{public}s",
		base_dir != nullptr ? base_dir : "(null)",
		save_dir != nullptr ? save_dir : "(null)");
}

void *OHOS_Entry_GetResourceManager(void)
{
	return static_cast<void *>(g_resource_manager);
}

void OHOS_Entry_LogNative(const char *message)
{
	const char *text = message != nullptr ? message : "(null)";
	Log(LOG_INFO, "%s", text);
	// Write to the sandbox files dir (readable via hdc as shell) and, when
	// granted, to the public Download app folder.
	const char *targets[3] = {g_files_dir.c_str(), g_data_dir.c_str(), "/data/local/tmp"};
	for (int i = 0; i < 3; ++i)
	{
		if (targets[i] == nullptr || targets[i][0] == '\0')
		{
			continue;
		}
		std::string dir_name = std::string(targets[i]);
		std::string log_path = dir_name + "/engine.log";
		if (i == 2)
		{
			log_path = "/data/local/tmp/yosuga-engine.log";
		}
		FILE *file = fopen(log_path.c_str(), "a");
		if (file != nullptr)
		{
			fputs(text, file);
			fputc('\n', file);
			fclose(file);
		}
	}
}

void OHOS_Entry_AttachXComponent(void *component)
{
	OH_NativeXComponent *native = static_cast<OH_NativeXComponent *>(component);
	if (native == nullptr)
	{
		Log(LOG_ERROR, "AttachXComponent: null component");
		return;
	}

	pthread_mutex_lock(&g_lock);
	g_component = native;
	pthread_mutex_unlock(&g_lock);

	// Touch events are delivered through the lifecycle callback struct
	// (DispatchTouchEvent); this SDK header does not provide the separate
	// OH_NativeXComponent_TouchEvent_Callback registration API.
	OH_NativeXComponent_Callback callback;
	memset(&callback, 0, sizeof(callback));
	callback.OnSurfaceCreated = OnSurfaceCreated;
	callback.OnSurfaceChanged = OnSurfaceChanged;
	callback.OnSurfaceDestroyed = OnSurfaceDestroyed;
	callback.DispatchTouchEvent = OnTouchEvent;
	int32_t reg = OH_NativeXComponent_RegisterCallback(native, &callback);
	char regmsg[96];
	snprintf(regmsg, sizeof(regmsg), "engine: XComponent RegisterCallback result=%d", static_cast<int>(reg));
	OHOS_Entry_LogNative(regmsg);


	// NOTE: do NOT call OH_NativeXComponent_GetXComponentSize with a null
	// window here: on this system that triggers SIGBUS on the UI thread.
	// The surface callbacks deliver the window; we wait for them instead.
	Log(LOG_INFO, "XComponent attached");
}

static void EngineMain()
{
	g_engine_running = true;
	OHOS_Entry_LogNative("engine: thread started");

	if (!SDL_OHOS_WaitForNativeWindow(60000))
	{
		Log(LOG_ERROR, "timed out waiting for the XComponent native window");
		OHOS_Entry_LogNative("engine: FAILED waiting for XComponent native window (60s timeout)");
		g_engine_running = false;
		return;
	}
	OHOS_Entry_LogNative("engine: XComponent native window ready");

	// Determine the engine base directory. The ArkTS shell may have set an
	// external (public Download) base with game data at <base>/data and
	// savedata at <save>/. Fall back to the sandbox files directory.
	std::string base_dir = !g_data_dir.empty() ? g_data_dir : g_files_dir;
	bool data_ok = false;
	if (!base_dir.empty())
	{
		// Mirrors the Windows krkrz layout: the engine reads from the app
		// root, so data.xp3 sits at <base>/data.xp3 and loose files live in
		// <base>/data/.
		struct stat st;
		std::string startup = base_dir + "/data/startup.tjs";
		std::string system_dir = base_dir + "/data/system";
		std::string xp3 = base_dir + "/data.xp3";
		bool has_startup = stat(startup.c_str(), &st) == 0 && S_ISREG(st.st_mode);
		bool has_system = stat(system_dir.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
		bool has_xp3 = stat(xp3.c_str(), &st) == 0 && S_ISREG(st.st_mode);
		data_ok = (has_startup && has_system) || has_xp3;
		char diag[384];
		snprintf(diag, sizeof(diag),
			"engine: base=%s startup=%s system=%s xp3=%s data_ok=%d",
			base_dir.c_str(), has_startup ? "yes" : "no",
			has_system ? "yes" : "no", has_xp3 ? "yes" : "no",
			data_ok ? 1 : 0);
		OHOS_Entry_LogNative(diag);
	}
	else
	{
		OHOS_Entry_LogNative("engine: base_dir is EMPTY");
	}
	if (!data_ok)
	{
		// Legacy bundled/mini builds: extract the packaged rawfile data into
		// the sandbox files directory.
		if (g_resource_manager != nullptr && OHOS_ExtractGameData())
		{
			base_dir = g_files_dir;
			data_ok = !base_dir.empty();
		}
	}
	if (!data_ok)
	{
		Log(LOG_ERROR, "game data is missing; use the in-game download or import first");
		g_engine_running = false;
		return;
	}

	if (!base_dir.empty() && chdir(base_dir.c_str()) != 0)
	{
		Log(LOG_WARN, "chdir(%{public}s) failed", base_dir.c_str());
	}

	if (!base_dir.empty())
	{
		// Let the engine core find the data without a cross-library symbol:
		// the environment survives the libentry -> libkrkrsdl2 boundary.
		setenv("KRKR_OHOS_DATA_DIR", base_dir.c_str(), 1);
		OHOS_Entry_LogNative(("engine: KRKR_OHOS_DATA_DIR=" + base_dir).c_str());
	}

	char app_name[] = "krkrsdl2";
	char *argv[] = {app_name, nullptr};

	OHOS_Entry_LogNative("engine: data ok, starting krkrsdl2 platform");
	try
	{
		OHOS_Entry_LogNative("engine: pre_init_platform enter");
		krkrsdl2_pre_init_platform();
		OHOS_Entry_LogNative("engine: pre_init_platform done");
		krkrsdl2_convert_set_args(1, argv);
		OHOS_Entry_LogNative("engine: init_platform enter");
		if (krkrsdl2_init_platform())
		{
			// The application asked to terminate during startup.
			OHOS_Entry_LogNative("engine: init_platform requested termination");
			g_engine_running = false;
			return;
		}
		OHOS_Entry_LogNative("engine: init_platform done");
		OHOS_Entry_LogNative("engine: entering main loop");
		krkrsdl2_run_main_loop();
		krkrsdl2_cleanup();
	}
	catch (...)
	{
		Log(LOG_ERROR, "uncaught exception escaped the Kirikiri engine");
		OHOS_Entry_LogNative("engine: UNCAUGHT EXCEPTION escaped Kirikiri engine");
	}

	g_engine_running = false;
	Log(LOG_INFO, "engine thread finished");
	OHOS_Entry_LogNative("engine: thread finished");
}

void OHOS_Entry_StartEngine(void)
{
	if (g_engine_started || g_engine_running.load())
	{
		return;
	}
	g_engine_started = true;
	std::thread(EngineMain).detach();
}
