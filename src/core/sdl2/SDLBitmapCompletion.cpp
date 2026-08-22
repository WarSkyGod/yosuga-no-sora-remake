/* SPDX-License-Identifier: MIT */
/* Copyright (c) Kirikiri SDL2 Developers */

#include "SDLBitmapCompletion.h"
#include "DebugIntf.h"

TVPSDLBitmapCompletion::TVPSDLBitmapCompletion()
{
	surface = nullptr;
	update_rect.clear();
}

void TVPSDLBitmapCompletion::NotifyBitmapCompleted(iTVPLayerManager * manager,
	tjs_int x, tjs_int y, const void * bits, const class BitmapInfomation * bmpinfo,
	const tTVPRect &cliprect, tTVPLayerType type, tjs_int opacity)
{
	if (!surface)
	{
		return;
	}
	/* DIAGNOSTIC: log every layer bitmap submission so we can see whether
	 * the character (立绘) layer reaches here and with what rect/opacity.
	 * Background(CG) show but the character doesn't, so we need to know if
	 * the character layer is submitted at all and, if so, what region/order. */
	{
		char dbg[256];
		tjs_int pw = 0, ph = 0;
		if (manager) manager->GetPrimaryLayerSize(pw, ph);
		snprintf(dbg, sizeof(dbg), "BMPC type=%d x=%d y=%d cw=%d ch=%d clipL=%d clipT=%d clipW=%d clipH=%d op=%d prim=%dx%d",
			(int)type, (int)x, (int)y,
			(int)(cliprect.get_width()), (int)(cliprect.get_height()),
			(int)cliprect.left, (int)cliprect.top, (int)cliprect.get_width(), (int)cliprect.get_height(),
			(int)opacity, (int)pw, (int)ph);
		TVPAddLog(dbg);
	}
	const TVPBITMAPINFO *bitmapinfo = bmpinfo->GetBITMAPINFO();
	tjs_int w = 0;
	tjs_int h = 0;
	if (!manager)
	{
		return;
	}
	if (!manager->GetPrimaryLayerSize(w, h))
	{
		w = 0;
		h = 0;
	}
	if(
		!(x < 0 || y < 0 ||
			x + cliprect.get_width() > w ||
			y + cliprect.get_height() > h) &&
		!(cliprect.left < 0 || cliprect.top < 0 ||
			cliprect.right > bitmapinfo->bmiHeader.biWidth ||
			cliprect.bottom > bitmapinfo->bmiHeader.biHeight))
	{
		// bitmapinfo で表された cliprect の領域を x,y にコピーする
		long src_y       = cliprect.top;
		long src_y_limit = cliprect.bottom;
		long src_x       = cliprect.left;
		long width_bytes   = cliprect.get_width() * sizeof(tjs_uint32); // 32bit
		long dest_y      = y;
		long dest_x      = x;
		const tjs_uint8 * src_p = (const tjs_uint8 *)bits;
		long src_pitch;

		if (bitmapinfo->bmiHeader.biHeight < 0)
		{
			// bottom-down
			src_pitch = bitmapinfo->bmiHeader.biWidth * sizeof(tjs_uint32);
			//src_pitch = -bitmapinfo->bmiHeader.biWidth * sizeof(tjs_uint32);
			//src_p += bitmapinfo->bmiHeader.biWidth * sizeof(tjs_uint32) * (bitmapinfo->bmiHeader.biHeight - 1);
		}
		else
		{
			// bottom-up
			src_pitch = -bitmapinfo->bmiHeader.biWidth * sizeof(tjs_uint32);
			src_p += bitmapinfo->bmiHeader.biWidth * sizeof(tjs_uint32) * (bitmapinfo->bmiHeader.biHeight - 1);
			//src_pitch = bitmapinfo->bmiHeader.biWidth * sizeof(tjs_uint32);
		}

		if (surface)
		{
			SDL_LockSurface(surface);
			for (; src_y < src_y_limit; src_y++, dest_y++)
			{
				const void *srcp = src_p + src_pitch * src_y + src_x * sizeof(tjs_uint32);
				void *destp = (tjs_uint8*)surface->pixels + surface->pitch * dest_y + dest_x * sizeof(tjs_uint32);
				SDL_memcpy(destp, srcp, width_bytes);
			}
			SDL_UnlockSurface(surface);
		}
		tTVPRect r;
		r.set_offsets(x, y);
		r.set_size(cliprect.get_width(), cliprect.get_height());
		update_rect.do_union(r);
	}

}

TVPSDLBitmapCompletion::~TVPSDLBitmapCompletion()
{
}
