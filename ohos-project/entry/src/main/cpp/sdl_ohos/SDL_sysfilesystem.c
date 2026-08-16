/* SPDX-License-Identifier: MIT */
/*
 * Filesystem hooks for the OpenHarmony SDL2 backend.
 *
 * SDL_GetBasePath returns the application sandbox files directory (where the
 * game data is extracted) and SDL_GetPrefPath returns a savedata directory
 * inside it. Both paths come from the NAPI entry through sdl_ohos_bridge.h.
 */

#include "../../SDL_internal.h"

#include <sys/stat.h>

#include "sdl_ohos_bridge.h"

static char *SDL_OHOS_PathForFilesDir(const char *subdir)
{
	const char *files_dir = SDL_OHOS_GetFilesDir();
	size_t base_length;
	size_t sub_length;
	size_t total;
	char *path;

	if (files_dir == NULL)
	{
		files_dir = "/";
	}
	base_length = SDL_strlen(files_dir);
	sub_length = subdir != NULL ? SDL_strlen(subdir) : 0;

	/* base + '/' + subdir + '/' + NUL */
	total = base_length + 1 + sub_length + 1 + 1;
	path = (char *)SDL_malloc(total);
	if (path == NULL)
	{
		return NULL;
	}
	SDL_snprintf(path, total, "%s/%s/", files_dir, subdir != NULL ? subdir : "");
	return path;
}

static void SDL_OHOS_MakeDirectoryRecursive(char *path)
{
	char *cursor;

	if (path == NULL || path[0] == '\0')
	{
		return;
	}
	for (cursor = path + 1; *cursor != '\0'; cursor++)
	{
		if (*cursor != '/')
		{
			continue;
		}
		*cursor = '\0';
		mkdir(path, 0700);
		*cursor = '/';
	}
}

char *SDL_GetBasePath(void)
{
	return SDL_OHOS_PathForFilesDir(NULL);
}

char *SDL_GetPrefPath(const char *org, const char *app)
{
	const char *leaf = app != NULL ? app : "krkrsdl2";
	char *path = SDL_OHOS_PathForFilesDir(leaf);

	(void)org;
	if (path != NULL)
	{
		SDL_OHOS_MakeDirectoryRecursive(path);
	}
	return path;
}
