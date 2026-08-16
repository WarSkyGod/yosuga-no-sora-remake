/* SPDX-License-Identifier: MIT */
/*
 * Bridge between the OpenHarmony NAPI entry module (libentry.so) and the
 * vendored SDL2 OpenHarmony backend.
 *
 * The declarations in this header are implemented in two places:
 *   - krkrsdl2_ohos_entry.cpp  (libentry.so): files directory, XComponent
 *     surface state and the native window wait/query helpers.
 *   - SDL_ohosevents.c         (libSDL2): touch event delivery.
 *
 * tools/setup_ohos_project.py copies this header into the vendored SDL tree
 * so both sides compile against identical declarations.
 */

#ifndef SDL_OHOS_BRIDGE_H
#define SDL_OHOS_BRIDGE_H

/* Touch types delivered to SDL_OHOS_OnTouchEvent. */
#define SDL_OHOS_TOUCH_DOWN 0
#define SDL_OHOS_TOUCH_UP 1
#define SDL_OHOS_TOUCH_MOVE 2

#ifdef __cplusplus
extern "C" {
#endif

/* Store the application sandbox files directory. */
void SDL_OHOS_SetFilesDir(const char *files_dir);

/* Return the sandbox files directory, or NULL when not set yet. */
const char *SDL_OHOS_GetFilesDir(void);

/* Block until the XComponent surface provides a native window. Returns 1 when
 * the window is ready, 0 on timeout. */
int SDL_OHOS_WaitForNativeWindow(int timeout_ms);

/* Return the OHNativeWindow, or NULL when the surface is not ready. */
void *SDL_OHOS_GetNativeWindow(void);

/* Return the current surface size in pixels. Returns 1 when valid. */
int SDL_OHOS_GetSurfaceSize(int *width, int *height);

/* Deliver an XComponent touch event. Called from the ACE UI thread. */
void SDL_OHOS_OnTouchEvent(int touch_type, float x, float y);

/* Notify the SDL video driver that the surface size changed. */
void SDL_OHOS_OnSurfaceChanged(int width, int height);

#ifdef __cplusplus
}
#endif

#endif /* SDL_OHOS_BRIDGE_H */
