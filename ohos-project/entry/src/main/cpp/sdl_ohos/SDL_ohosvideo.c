/* SPDX-License-Identifier: MIT */
/*
 * OpenHarmony video backend for SDL2.
 *
 * Modeled on the SDL2 RPi/Android EGL drivers. The XComponent surface state
 * is owned by the NAPI entry module and exchanged through sdl_ohos_bridge.h;
 * this backend only consumes the native window and surface size.
 */

#include "../../SDL_internal.h"

#include "../SDL_sysvideo.h"
#include "../../events/SDL_mouse_c.h"
#include "../../events/SDL_keyboard_c.h"

#include "SDL_ohosvideo.h"
#include "SDL_ohosevents.h"
#include "SDL_ohosgl.h"
#include "sdl_ohos_bridge.h"
#include <native_window/external_window.h>
#include <string.h>
#include <sys/mman.h>
#include <dlfcn.h>
#include <hilog/log.h>

#define OHOS_FALLBACK_WIDTH 1920
#define OHOS_FALLBACK_HEIGHT 1080

/* OH_NativeWindow_LockBuffer / OH_NativeWindow_UnlockAndFlushBuffer are API
 * 23+; the CI builds against the API 12 sysroot, so reference them through
 * dlsym at run time instead of the headers (guarded availability). */
typedef int32_t (*OHNW_LockBufferFn)(OHNativeWindow *, Region, OHNativeWindowBuffer **);
typedef int32_t (*OHNW_UnlockFlushFn)(OHNativeWindow *);
static OHNW_LockBufferFn OHOS_NW_LockBuffer = NULL;
static OHNW_UnlockFlushFn OHOS_NW_UnlockAndFlushBuffer = NULL;
static void OHOS_ResolveNativeWindowCpuApi(void)
{
	static int resolved = 0;
	if (resolved) return;
	resolved = 1;
	void *handle = dlopen("libnative_window.so", RTLD_NOW);
	if (handle == NULL) return;
	OHOS_NW_LockBuffer = (OHNW_LockBufferFn)dlsym(handle, "OH_NativeWindow_LockBuffer");
	OHOS_NW_UnlockAndFlushBuffer = (OHNW_UnlockFlushFn)dlsym(handle, "OH_NativeWindow_UnlockAndFlushBuffer");
}

static SDL_VideoDevice *OHOS_CreateDevice(void);
static void OHOS_DestroyDevice(SDL_VideoDevice *device);

static int OHOS_VideoInit(_THIS);
static void OHOS_VideoQuit(_THIS);
static void OHOS_GetDisplayModes(_THIS, SDL_VideoDisplay *display);
static int OHOS_SetDisplayMode(_THIS, SDL_VideoDisplay *display, SDL_DisplayMode *mode);
static int OHOS_GetDisplayBounds(_THIS, SDL_VideoDisplay *display, SDL_Rect *rect);
static int OHOS_CreateSDLWindow(_THIS, SDL_Window *window);
static void OHOS_GetSurfaceSize(int *width, int *height);

/* --- Software framebuffer support --------------------------------------- */
/* Paints an SDL_Surface into an OH_NativeWindow buffer. Kept simple: one
 * surface lives in window->driverdata and is re-uploaded on Update. */

static int OHOS_CreateWindowFramebuffer(_THIS, SDL_Window *window,
	Uint32 *format, void **pixels, int *pitch)
{
	SDL_WindowData *data = (SDL_WindowData *)window->driverdata;
	if (data == NULL)
	{
		return SDL_SetError("Window has no driver data");
	}
	int w = 0, h = 0;
	OHOS_GetSurfaceSize(&w, &h);
	if (w <= 0 || h <= 0)
	{
		w = OHOS_FALLBACK_WIDTH;
		h = OHOS_FALLBACK_HEIGHT;
	}

	if (data->framebuffer != NULL)
	{
		SDL_FreeSurface(data->framebuffer);
		data->framebuffer = NULL;
	}
	data->framebuffer = SDL_CreateRGBSurface(0, w, h, 32,
		0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000);
	if (data->framebuffer == NULL)
	{
		return SDL_OutOfMemory();
	}
	/* The pixel memory is uninitialised and the engine presents it before
	 * the first script-painted frame: it flashed white garbage on startup.
	 * Clear it to black. */
	SDL_memset(data->framebuffer->pixels, 0,
		(size_t)data->framebuffer->h * (size_t)data->framebuffer->pitch);
	*format = data->framebuffer->format->format;
	*pixels = data->framebuffer->pixels;
	*pitch = data->framebuffer->pitch;
	return 0;
}

static int OHOS_UpdateWindowFramebuffer(_THIS, SDL_Window *window,
	const SDL_Rect *rects, int numrects)
{
	SDL_WindowData *data = (SDL_WindowData *)window->driverdata;
	OHNativeWindow *native_window;
	OHNativeWindowBuffer *buffer = NULL;
	BufferHandle *handle = NULL;
	int fence_fd = -1;
	int32_t dummy = 0;

	if (data == NULL || data->framebuffer == NULL)
	{
		return SDL_SetError("No framebuffer");
	}
	/* Belt and braces: while the AVPlayer owns the surface (weak bridge
	 * symbol resolves to libentry's implementation at run time) the engine
	 * must not touch the native window at all. */
	if (SDL_OHOS_IsVideoPlaying && SDL_OHOS_IsVideoPlaying())
	{
		return 0;
	}
	/* Acquire the native window with the surface lifecycle lock HELD for the
	 * whole frame. OnSurfaceDestroyed (UI thread) blocks until this frame's
	 * write completes, so the render thread can never write into a surface
	 * that is being torn down. The old flow (GetNativeWindow -> release lock
	 * -> render) left a use-after-free window on every surface rebuild:
	 * window resizes on real hardware crashed in EVERY drag direction
	 * (heap corruption visible as a wild pc inside libace_compatible, and
	 * pc=0 through a cleared callback on phones). SDL_OHOS_ReleaseNativeWindow()
	 * must be called on EVERY exit path below. */
	native_window = (OHNativeWindow *)SDL_OHOS_AcquireNativeWindow();
	if (native_window == NULL)
	{
		return SDL_SetError("No native window");
	}

	int32_t w = data->framebuffer->w;
	int32_t h = data->framebuffer->h;
	/* The native window (XComponent surface) has the PHYSICAL size reported
	 * by ArkTS; request buffers at that geometry. The logical framebuffer is
	 * then stretched into it. */
	int32_t bw = w, bh = h;
	{
		int pw = 0, ph = 0;
		if (SDL_OHOS_GetPhysicalSize(&pw, &ph) && pw > 0 && ph > 0)
		{
			bw = pw;
			bh = ph;
		}
	}
	if (bw <= 0 || bh <= 0)
	{
		SDL_OHOS_ReleaseNativeWindow();
		return SDL_SetError("OHOS: invalid buffer size");
	}
	/* Window resize race mitigation: during maximize/restore transitions the
	 * consumer can hand out buffers smaller than the geometry we set a moment
	 * ago. Re-asserting SET_BUFFER_GEOMETRY EVERY frame widens that race
	 * window (each call re-negotiates with the compositor mid-transition);
	 * set it only when the requested size actually changes. */
	{
		static int32_t last_bw = -1, last_bh = -1;
		if (bw != last_bw || bh != last_bh)
		{
			if (OH_NativeWindow_NativeWindowHandleOpt(native_window, SET_BUFFER_GEOMETRY, bw, bh) != 0)
			{
				SDL_OHOS_ReleaseNativeWindow();
				return SDL_SetError("OHOS: SET_BUFFER_GEOMETRY failed");
			}
			last_bw = bw;
			last_bh = bh;
			if (SDL_OHOS_DiagLog)
			{
				char diag[96];
				snprintf(diag, sizeof(diag), "upd: geometry set %dx%d", bw, bh);
				SDL_OHOS_DiagLog(diag);
			}
		}
	}

	/* LockBuffer is the only path whose buffers actually present on this
	 * device; the AVPlayer video now renders into its OWN XComponent surface,
	 * so the CPU production mode here can no longer disturb video playback.
	 * RequestBuffer remains as a fallback. */
	Region lock_region = { NULL, 0 };
	int locked = 0;
	buffer = NULL;
	OHOS_ResolveNativeWindowCpuApi();
	if (OHOS_NW_LockBuffer != NULL && OHOS_NW_UnlockAndFlushBuffer != NULL &&
		OHOS_NW_LockBuffer(native_window, lock_region, &buffer) == 0 && buffer != NULL)
	{
		locked = 1;
	}
	else if (OH_NativeWindow_NativeWindowRequestBuffer(native_window, &buffer, &fence_fd) == 0 && buffer != NULL)
	{
		locked = 0;
	}
	else
	{
		SDL_OHOS_ReleaseNativeWindow();
		return SDL_SetError("OHOS: NativeWindowRequestBuffer failed");
	}

	void *mapped = NULL;
	Uint8 *fb_dst = NULL;
	handle = OH_NativeWindow_GetBufferHandleFromNative(buffer);
	if (handle != NULL && handle->virAddr != NULL)
	{
		fb_dst = (Uint8 *)handle->virAddr;
	}
	else if (handle != NULL && handle->fd >= 0)
	{
		mapped = mmap(NULL, (size_t)handle->size, PROT_READ | PROT_WRITE, MAP_SHARED, handle->fd, 0);
		if (mapped != MAP_FAILED)
			fb_dst = (Uint8 *)mapped;
		else
			mapped = NULL;
	}
	if (fb_dst == NULL)
	{
		if (locked)
			OHOS_NW_UnlockAndFlushBuffer(native_window);
		else
			OH_NativeWindow_NativeWindowAbortBuffer(native_window, buffer);
		SDL_OHOS_ReleaseNativeWindow();
		return SDL_SetError("OHOS: buffer has no writable address");
	}

	/* Window resize race: the consumer may hand us a buffer SMALLER than the
	 * geometry we just requested (the ArkTS side updates the physical size
	 * one frame later, and the compositor switches the window size
	 * asynchronously on maximize/restore). Writing the requested (stale,
	 * larger) geometry into the actual (smaller) buffer overflows it and
	 * segfaults - the "press the system restore button and the game dies"
	 * crash. Always trust the buffer's own geometry for the write loop.
	 * NOTE: GET_BUFFER_GEOMETRY takes height FIRST, then width. */
	{
		static int32_t last_w = -1, last_h = -1;
		int32_t gw = 0, gh = 0;
		if (OH_NativeWindow_NativeWindowHandleOpt(native_window, GET_BUFFER_GEOMETRY, &gh, &gw) == 0 &&
			gw > 0 && gh > 0 && (gw < bw || gh < bh))
		{
			bw = (gw < bw) ? gw : bw;
			bh = (gh < bh) ? gh : bh;
			if (SDL_OHOS_DiagLog && (bw != last_w || bh != last_h))
			{
				char diag[96];
				snprintf(diag, sizeof(diag), "upd: clamped write to %dx%d", bw, bh);
				SDL_OHOS_DiagLog(diag);
			}
		}
		last_w = bw;
		last_h = bh;
	}

	/* Capacity clamp from the buffer handle itself. GET_BUFFER_GEOMETRY
	 * reports the REQUESTED geometry - right after our own SET it echoes
	 * that request back, so it can never catch the compositor handing out a
	 * smaller buffer mid-resize. Real hardware (HarmonyOS PC) resizes hit
	 * exactly that window and crashed in EVERY drag direction once the
	 * per-frame SET was throttled (krkr_fault.txt: pc jumped to a wild
	 * address inside libace_compatible - heap corruption from the overflow).
	 * The handle's stride (row bytes) and total size give the true writable
	 * area regardless of any request timing. RGBA_8888:
	 * capacity_rows = size / stride, max_columns = stride / 4. */
	{
		int32_t stride_px = (handle->stride > 0) ? handle->stride / 4 : 0;
		int32_t cap_rows = (handle->stride > 0)
			? (int32_t)((uint64_t)handle->size / (uint64_t)handle->stride)
			: 0;
		if (stride_px > 0 && bw > stride_px)
			bw = stride_px;
		if (cap_rows > 0 && bh > cap_rows)
			bh = cap_rows;
	}

	/* Copy the SDL surface (ARGB8888) into the native buffer, scaling from
	 * the logical framebuffer (1920x1080) to the physical buffer size. The
	 * buffer stride may differ from bw*4 (alignment) - always use it. */
	{
		Uint8 *dst = fb_dst;
		const Uint8 *src = (const Uint8 *)data->framebuffer->pixels;
		int src_pitch = data->framebuffer->pitch;
		int dst_stride = handle->stride;
		if (dst_stride <= 0) dst_stride = bw * 4;
		if (bw == w && bh == h)
		{
			/* same size. The SDL surface stores ARGB as B,G,R,A bytes in memory
			 * (masks 0x00ff0000/0x0000ff00/0x000000ff/0xff000000) while the
			 * XComponent buffer is RGBA_8888 - swap R and B or the picture
			 * shows red/blue swapped. */
			for (int y = 0; y < h; y++)
			{
				const Uint8 *srow = src + (size_t)y * (size_t)src_pitch;
				Uint8 *drow = dst + (size_t)y * (size_t)dst_stride;
				for (int x = 0; x < w; x++)
				{
					drow[x * 4 + 0] = srow[x * 4 + 2];
					drow[x * 4 + 1] = srow[x * 4 + 1];
					drow[x * 4 + 2] = srow[x * 4 + 0];
					drow[x * 4 + 3] = srow[x * 4 + 3];
				}
			}
		}
		else
		{
			/* nearest-neighbor scale */
			for (int dy = 0; dy < bh; dy++)
			{
				int sy = dy * h / bh;
				const Uint8 *srow = src + (size_t)sy * (size_t)src_pitch;
				Uint8 *drow = dst + (size_t)dy * (size_t)dst_stride;
				for (int dx = 0; dx < bw; dx++)
				{
					int sx = dx * w / bw;
					const Uint8 *p = srow + (size_t)sx * 4;
					/* same R/B swap as the fast path above */
					drow[dx * 4 + 0] = p[2];
					drow[dx * 4 + 1] = p[1];
					drow[dx * 4 + 2] = p[0];
					drow[dx * 4 + 3] = p[3];
				}
			}
		}
	}

	if (mapped != NULL)
		munmap(mapped, (size_t)handle->size);
	if (locked)
	{
		if (OHOS_NW_UnlockAndFlushBuffer(native_window) != 0)
		{
			SDL_OHOS_ReleaseNativeWindow();
			return SDL_SetError("OHOS: UnlockAndFlushBuffer failed");
		}
	}
	else
	{
		Region region;
		region.rects = NULL;
		region.rectNumber = 0;
		if (OH_NativeWindow_NativeWindowFlushBuffer(native_window, buffer, fence_fd, region) != 0)
		{
			SDL_OHOS_ReleaseNativeWindow();
			return SDL_SetError("OHOS: FlushBuffer failed");
		}
	}
	(void)rects;
	(void)numrects;
	(void)dummy;
	SDL_OHOS_ReleaseNativeWindow();
	return 0;
}

static void OHOS_DestroyWindowFramebuffer(_THIS, SDL_Window *window)
{
	SDL_WindowData *data = (SDL_WindowData *)window->driverdata;
	if (data != NULL && data->framebuffer != NULL)
	{
		SDL_FreeSurface(data->framebuffer);
		data->framebuffer = NULL;
	}
}

static void OHOS_DestroyWindow(_THIS, SDL_Window *window)
{
	SDL_WindowData *data;
	if (window == NULL || (data = (SDL_WindowData *)window->driverdata) == NULL)
	{
		return;
	}
	if (data->egl_surface != EGL_NO_SURFACE)
	{
		/* Destroy the surface on the SAME display that created it.
		 * eglGetCurrentDisplay() can return EGL_NO_DISPLAY when no context
		 * is current (e.g. after a failed GLES2_CreateRenderer attempt),
		 * which leaks the surface and corrupts the shared XComponent
		 * buffer queue for the AVPlayer and the software framebuffer. */
		EGLDisplay dpy = eglGetCurrentDisplay();
		if (dpy == EGL_NO_DISPLAY)
		{
			dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
		}
		if (dpy != EGL_NO_DISPLAY)
		{
			eglDestroySurface(dpy, data->egl_surface);
		}
		data->egl_surface = EGL_NO_SURFACE;
	}
	if (data->framebuffer != NULL)
	{
		SDL_FreeSurface(data->framebuffer);
		data->framebuffer = NULL;
	}
	SDL_free(data);
	window->driverdata = NULL;
}

static void OHOS_SetWindowTitle(_THIS, SDL_Window *window);
static void OHOS_SetWindowPosition(_THIS, SDL_Window *window);
static void OHOS_SetWindowSize(_THIS, SDL_Window *window);
static void OHOS_ShowWindow(_THIS, SDL_Window *window);
static void OHOS_HideWindow(_THIS, SDL_Window *window);
static int OHOS_CreateWindowFramebuffer(_THIS, SDL_Window *window, Uint32 *format, void **pixels, int *pitch);
static int OHOS_UpdateWindowFramebuffer(_THIS, SDL_Window *window, const SDL_Rect *rects, int numrects);
static void OHOS_DestroyWindowFramebuffer(_THIS, SDL_Window *window);
static void OHOS_SetWindowFullscreen(_THIS, SDL_Window *window, SDL_VideoDisplay *display, SDL_bool fullscreen);

VideoBootStrap OHOS_bootstrap = {
	"ohos", "OpenHarmony XComponent video driver", OHOS_CreateDevice, NULL
};

static void OHOS_DestroyDevice(SDL_VideoDevice *device)
{
	if (device->driverdata)
	{
		SDL_free(device->driverdata);
	}
	SDL_free(device);
}

static SDL_VideoDevice *OHOS_CreateDevice(void)
{
	SDL_VideoDevice *device;
	OHOS_VideoData *videodata;

	device = (SDL_VideoDevice *)SDL_calloc(1, sizeof(SDL_VideoDevice));
	if (device == NULL)
	{
		return NULL;
	}
	videodata = (OHOS_VideoData *)SDL_calloc(1, sizeof(OHOS_VideoData));
	if (videodata == NULL)
	{
		SDL_free(device);
		return NULL;
	}
	device->driverdata = videodata;

	device->VideoInit = OHOS_VideoInit;
	device->VideoQuit = OHOS_VideoQuit;
	device->GetDisplayModes = OHOS_GetDisplayModes;
	device->SetDisplayMode = OHOS_SetDisplayMode;
	device->GetDisplayBounds = OHOS_GetDisplayBounds;
	device->PumpEvents = OHOS_PumpEvents;
	device->CreateSDLWindow = OHOS_CreateSDLWindow;
	device->DestroyWindow = OHOS_DestroyWindow;
	device->CreateWindowFramebuffer = OHOS_CreateWindowFramebuffer;
	device->UpdateWindowFramebuffer = OHOS_UpdateWindowFramebuffer;
	device->DestroyWindowFramebuffer = OHOS_DestroyWindowFramebuffer;
	device->SetWindowTitle = OHOS_SetWindowTitle;
	device->SetWindowPosition = OHOS_SetWindowPosition;
	device->SetWindowSize = OHOS_SetWindowSize;
	device->ShowWindow = OHOS_ShowWindow;
	device->HideWindow = OHOS_HideWindow;
	device->SetWindowFullscreen = OHOS_SetWindowFullscreen;

	/* Register the OpenGLES backend so SDL_CreateRenderer(SDL_RENDERER_ACCELERATED)
	 * uses the hardware GLES render driver (EGL via OH_NativeWindow) instead of
	 * falling back to the software surface renderer. OHOS_GL_* (SDL_ohosgl.c)
	 * uses eglCreateWindowSurface/eglCreateContext on the GAME XComponent native
	 * window - the AVPlayer video now renders into its own separate XComponent
	 * surface (see ohos_video_player), so EGL on the game window no longer
	 * disturbs video playback. */
	device->GL_LoadLibrary = OHOS_GL_LoadLibrary;
	device->GL_GetProcAddress = OHOS_GL_GetProcAddress;
	device->GL_UnloadLibrary = OHOS_GL_UnloadLibrary;
	device->GL_CreateContext = OHOS_GL_CreateContext;
	device->GL_MakeCurrent = OHOS_GL_MakeCurrent;
	device->GL_SetSwapInterval = OHOS_GL_SetSwapInterval;
	device->GL_GetSwapInterval = OHOS_GL_GetSwapInterval;
	device->GL_SwapWindow = OHOS_GL_SwapWindow;
	device->GL_DeleteContext = OHOS_GL_DeleteContext;

	device->free = OHOS_DestroyDevice;
	return device;
}

static void OHOS_GetSurfaceSize(int *width, int *height)
{
	int w = 0;
	int h = 0;
	if (!SDL_OHOS_GetSurfaceSize(&w, &h) || w <= 0 || h <= 0)
	{
		w = OHOS_FALLBACK_WIDTH;
		h = OHOS_FALLBACK_HEIGHT;
	}
	*width = w;
	*height = h;
}

static int OHOS_VideoInit(_THIS)
{
	SDL_VideoDisplay display;
	SDL_DisplayMode mode;
	int w = 0;
	int h = 0;

	if (!SDL_OHOS_WaitForNativeWindow(60000))
	{
		return SDL_SetError("Timed out waiting for the XComponent native window");
	}

	OHOS_GetSurfaceSize(&w, &h);

	SDL_zero(display);
	display.name = "OpenHarmony XComponent";
	SDL_zero(mode);
	mode.format = SDL_PIXELFORMAT_RGB888;
	mode.w = w;
	mode.h = h;
	mode.refresh_rate = 60;
	mode.driverdata = NULL;
	display.desktop_mode = mode;
	display.current_mode = mode;
	SDL_AddVideoDisplay(&display, SDL_FALSE);
	return 0;
}

static void OHOS_VideoQuit(_THIS)
{
	OHOS_VideoData *videodata = (OHOS_VideoData *)_this->driverdata;
	if (videodata != NULL && videodata->window != NULL)
	{
		SDL_Window *window = videodata->window;
		if (window->driverdata != NULL)
		{
			SDL_free(window->driverdata);
			window->driverdata = NULL;
		}
		videodata->window = NULL;
	}
}

static void OHOS_GetDisplayModes(_THIS, SDL_VideoDisplay *display)
{
	SDL_DisplayMode mode;
	int w = 0;
	int h = 0;

	OHOS_GetSurfaceSize(&w, &h);

	SDL_zero(mode);
	mode.format = SDL_PIXELFORMAT_RGB888;
	mode.w = w;
	mode.h = h;
	mode.refresh_rate = 60;
	SDL_AddDisplayMode(display, &mode);
}

static int OHOS_SetDisplayMode(_THIS, SDL_VideoDisplay *display, SDL_DisplayMode *mode)
{
	(void)display;
	(void)mode;
	/* The surface size is controlled by the XComponent; accept any request. */
	return 0;
}

static int OHOS_GetDisplayBounds(_THIS, SDL_VideoDisplay *display, SDL_Rect *rect)
{
	int w = 0;
	int h = 0;

	(void)display;
	OHOS_GetSurfaceSize(&w, &h);
	rect->x = 0;
	rect->y = 0;
	rect->w = w;
	rect->h = h;
	return 0;
}

static int OHOS_CreateSDLWindow(_THIS, SDL_Window *window)
{
	OHOS_VideoData *videodata = (OHOS_VideoData *)_this->driverdata;
	SDL_WindowData *data;
	int w = 0;
	int h = 0;

	data = (SDL_WindowData *)SDL_calloc(1, sizeof(SDL_WindowData));
	if (data == NULL)
	{
		return SDL_OutOfMemory();
	}
	window->driverdata = data;
	data->window = window;
	data->egl_surface = EGL_NO_SURFACE;

	OHOS_GetSurfaceSize(&w, &h);
	window->w = w;
	window->h = h;

	/* The XComponent always covers the whole screen. */
	window->flags |= SDL_WINDOW_FULLSCREEN_DESKTOP | SDL_WINDOW_SHOWN;

	videodata->window = window;

	SDL_SetKeyboardFocus(window);
	SDL_SetMouseFocus(window);
	return 0;
}

static void OHOS_SetWindowTitle(_THIS, SDL_Window *window)
{
	(void)_this;
	(void)window;
}

static void OHOS_SetWindowPosition(_THIS, SDL_Window *window)
{
	(void)_this;
	(void)window;
}

static void OHOS_SetWindowSize(_THIS, SDL_Window *window)
{
	(void)_this;
	/* krkr2 settings-menu "resolution" switches funnel into
	 * SDL_SetWindowSize. The old stub made the control a silent no-op.
	 * Log the request so a dead control (script disables it before ever
	 * calling here) is distinguishable from a swallowed request. The
	 * compositor keeps presenting the fixed 1920x1080 surface. */
	if (SDL_OHOS_DiagLog)
	{
		char diagbuf[96];
		snprintf(diagbuf, sizeof(diagbuf),
			"driver: OHOS_SetWindowSize %dx%d", window->w, window->h);
		SDL_OHOS_DiagLog(diagbuf);
	}
}

static void OHOS_ShowWindow(_THIS, SDL_Window *window)
{
	(void)_this;
	(void)window;
}

static void OHOS_HideWindow(_THIS, SDL_Window *window)
{
	(void)_this;
	(void)window;
}

static void OHOS_SetWindowFullscreen(_THIS, SDL_Window *window, SDL_VideoDisplay *display, SDL_bool fullscreen)
{
	(void)_this;
	(void)window;
	(void)display;
	/* The XComponent surface is managed by the ArkTS shell: forward the
	 * switch to it (the 100 ms poll applies window.setFullScreen, which
	 * toggles the OS fullscreen state on HarmonyOS PC / 2-in-1 tablets).
	 * The SDL window keeps its logical size either way - the compositor
	 * stretches the buffer into the 16:9 surface. */
	if (SDL_OHOS_DiagLog)
	{
		SDL_OHOS_DiagLog("driver: OHOS_SetWindowFullscreen reached");
	}
	if (SDL_OHOS_SetAppFullscreen)
	{
		SDL_OHOS_SetAppFullscreen(fullscreen ? 1 : 0);
	}
}

/* --- Fullscreen/windowed request state ----------------------------------- */
/* Lives in this file (libkrkrsdl2.so) so the engine's SDLApplication.cpp and
 * this video driver resolve the bridge within their own .so at LINK time -
 * the previous placement in libentry.so relied on a run-time weak binding
 * across two .so files, which made the game-menu fullscreen switch a no-op
 * on some devices. libentry.so reaches these through the exported dynamic
 * symbols (napi pollFullscreen/ackFullscreen). */
#include <stdatomic.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>

static atomic_int g_ohos_fullscreen_request = -1; /* -1 none / 0 windowed / 1 fullscreen */
static atomic_int g_ohos_fullscreen_state = -1;   /* -1 unknown / 0 windowed / 1 fullscreen */
static atomic_int g_ohos_winsize_req_w = -1;      /* pending window-size request, -1 = none */
static atomic_int g_ohos_winsize_req_h = -1;      /* pending window-size request, -1 = none */

/* Diagnostic sink shared by engine, driver and shell (napi diagLog).
 * Appends one line to <data dir>/diag_fullscreen.log, falling back to the
 * app files dir. Callers throttle repeated values themselves. */
void SDL_OHOS_DiagLog(const char *line)
{
	/* Diagnostics disabled (shipping build): the fullscreen/window-size
	 * forensics are done, so the log file is no longer written. The symbol
	 * and all call sites are kept so the traces can be re-enabled by
	 * restoring this body. */
	(void)line;
}


void SDL_OHOS_SetAppFullscreen(int fullscreen)
{
	int v = fullscreen ? 1 : 0;
	atomic_store_explicit(&g_ohos_fullscreen_request, v,
		memory_order_release);
	if (SDL_OHOS_DiagLog)
	{
		char diagbuf[64];
		snprintf(diagbuf, sizeof(diagbuf), "state: request=%d", v);
		SDL_OHOS_DiagLog(diagbuf);
	}
}

int SDL_OHOS_GetAppFullscreenState(void)
{
	/* Prefer a PENDING request over the last applied state: the settings
	 * menu draws its toggle from this value, and returning the applied
	 * state makes the control lag one interaction behind - the request
	 * needs an ArkUI round trip (setFullScreen + recover + resize) before
	 * the ack lands, while the menu repaints immediately after the click.
	 * The ack clears the request to -1, so the real applied state wins
	 * again once the shell has caught up. */
	int r = atomic_load_explicit(&g_ohos_fullscreen_request, memory_order_acquire);
	if (r == 0 || r == 1)
	{
		return r;
	}
	int s = atomic_load_explicit(&g_ohos_fullscreen_state, memory_order_acquire);
	/* Throttled: the settings menu polls this constantly (FullScreenGuard);
	 * only log when the applied state actually changes. */
	static atomic_int logged = -999;
	int prev = atomic_exchange_explicit(&logged, s, memory_order_relaxed);
	if (prev != s && SDL_OHOS_DiagLog)
	{
		char diagbuf[64];
		snprintf(diagbuf, sizeof(diagbuf), "state: read=%d", s);
		SDL_OHOS_DiagLog(diagbuf);
	}
	return s;
}

int SDL_OHOS_PollFullscreenRequest(void)
{
	return atomic_load_explicit(&g_ohos_fullscreen_request, memory_order_acquire);
}

void SDL_OHOS_AckFullscreen(int applied)
{
	atomic_store_explicit(&g_ohos_fullscreen_state, applied, memory_order_release);
	/* Clear the pending request only when it still matches what the shell
	 * applied, so a newer request written in between (the user flipped the
	 * switch again) is not lost. */
	int expected = applied;
	atomic_compare_exchange_strong_explicit(&g_ohos_fullscreen_request,
		&expected, -1, memory_order_release, memory_order_acquire);
	if (SDL_OHOS_DiagLog)
	{
		char diagbuf[96];
		snprintf(diagbuf, sizeof(diagbuf), "state: ack=%d cleared=%d req_now=%d",
			applied, expected == applied ? 1 : 0,
			atomic_load_explicit(&g_ohos_fullscreen_request, memory_order_acquire));
		SDL_OHOS_DiagLog(diagbuf);
	}
}

/* --- Window-size request state (OHOS desktop "resolution" switch) --------- */
/* The engine's SetZoom forwards the requested logical size here (windowed
 * mode only); the shell's poll picks it up and resizes the OS window. One
 * atomic exchange consumes the pair, so a request is applied exactly once. */
void SDL_OHOS_SetAppWindowSize(int w, int h)
{
	if (w <= 0 || h <= 0)
	{
		return;
	}
	atomic_store_explicit(&g_ohos_winsize_req_w, w, memory_order_release);
	atomic_store_explicit(&g_ohos_winsize_req_h, h, memory_order_release);
	if (SDL_OHOS_DiagLog)
	{
		char diagbuf[96];
		snprintf(diagbuf, sizeof(diagbuf), "state: winsize request=%dx%d", w, h);
		SDL_OHOS_DiagLog(diagbuf);
	}
}

int SDL_OHOS_PollWindowSizeRequest(int *w, int *h)
{
	int rw = atomic_exchange_explicit(&g_ohos_winsize_req_w, -1,
		memory_order_acq_rel);
	int rh = atomic_exchange_explicit(&g_ohos_winsize_req_h, -1,
		memory_order_acq_rel);
	if (rw <= 0 || rh <= 0)
	{
		return 0;
	}
	if (w)
	{
		*w = rw;
	}
	if (h)
	{
		*h = rh;
	}
	return 1;
}
