/* SPDX-License-Identifier: MIT */
/*
 * EGL/GLES glue for the OpenHarmony video backend. Mirrors the SDL2 RPi
 * driver: every GL entry point is a thin wrapper over SDL's EGL module.
 */

#include "../../SDL_internal.h"

#include "../SDL_egl_c.h"
#include <native_window/external_window.h>

#include "SDL_ohosvideo.h"
#include "SDL_ohosgl.h"
#include "sdl_ohos_bridge.h"

int OHOS_GL_LoadLibrary(_THIS, const char *path)
{
	(void)path;
	/* EGL/GLES are linked directly into the app (libEGL.so / libGLESv3.so),
	 * so SDL's dlopen-based EGL loader fails inside the app sandbox. Bypass
	 * the library lookup and initialize SDL's EGL bookkeeping directly. */
	return SDL_EGL_LoadLibrary(_this, NULL, (NativeDisplayType)EGL_DEFAULT_DISPLAY, 0);
}

void *OHOS_GL_GetProcAddress(_THIS, const char *proc)
{
	return SDL_EGL_GetProcAddress(_this, proc);
}

void OHOS_GL_UnloadLibrary(_THIS)
{
	SDL_EGL_UnloadLibrary(_this);
}

SDL_GLContext OHOS_GL_CreateContext(_THIS, SDL_Window *window)
{
	SDL_WindowData *data = (SDL_WindowData *)window->driverdata;
	OHNativeWindow *native_window;

	if (data == NULL)
	{
		return NULL;
	}
	native_window = (OHNativeWindow *)SDL_OHOS_GetNativeWindow();
	if (native_window == NULL)
	{
		SDL_SetError("The XComponent native window is unavailable");
		return NULL;
	}

	FILE *glf = fopen("/data/local/tmp/yosuga-egl.log", "a");
	if (glf) { fprintf(glf, "OHOS_GL_CreateContext: native_window=%p\n", (void*)native_window); fclose(glf); }
	data->egl_surface = (EGLSurface)SDL_EGL_CreateSurface(_this, (NativeWindowType)native_window);
	if (glf) { fprintf(glf, "  egl_surface=%p (NO_SURFACE=%p)\n", (void*)data->egl_surface, (void*)EGL_NO_SURFACE); fclose(glf); }
	if (data->egl_surface == EGL_NO_SURFACE)
	{
		return NULL;
	}
	SDL_GLContext ctx = SDL_EGL_CreateContext(_this, data->egl_surface);
	if (glf) { fprintf(glf, "  context=%p\n", (void*)ctx); fclose(glf); }
	return ctx;
}

int OHOS_GL_MakeCurrent(_THIS, SDL_Window *window, SDL_GLContext context)
{
	if (window && context)
	{
		SDL_WindowData *data = (SDL_WindowData *)window->driverdata;
		if (data == NULL)
		{
			return SDL_SetError("Window has no driver data");
		}
		return SDL_EGL_MakeCurrent(_this, data->egl_surface, context);
	}
	return SDL_EGL_MakeCurrent(_this, NULL, NULL);
}

int OHOS_GL_SetSwapInterval(_THIS, int interval)
{
	return SDL_EGL_SetSwapInterval(_this, interval);
}

int OHOS_GL_GetSwapInterval(_THIS)
{
	return SDL_EGL_GetSwapInterval(_this);
}

int OHOS_GL_SwapWindow(_THIS, SDL_Window *window)
{
	SDL_WindowData *data = (SDL_WindowData *)window->driverdata;
	if (data == NULL)
	{
		return SDL_SetError("Window has no driver data");
	}
	{
		static int swap_count = 0;
		if (++swap_count <= 5 || swap_count % 300 == 0)
		{
			FILE *sf = fopen("/data/local/tmp/yosuga-egl.log", "a");
			if (sf) { fprintf(sf, "swap #%d egl_surface=%p\n", swap_count, (void*)data->egl_surface); fclose(sf); }
		}
	}
	return SDL_EGL_SwapBuffers(_this, data->egl_surface);
}

void OHOS_GL_DeleteContext(_THIS, SDL_GLContext context)
{
	SDL_EGL_DeleteContext(_this, context);
}
