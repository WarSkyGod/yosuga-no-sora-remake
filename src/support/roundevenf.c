/* SPDX-License-Identifier: MIT */
/* Copyright (c) Kirikiri SDL2 Developers */

#include <math.h>
#include <stdint.h>
#if defined(__clang__) && defined(__arm__)
#define roundevenf roundevenf_workaround
#define KRKRSDL2_NEEDS_ROUNDEVENF_COMPAT_SYMBOL
#endif
float roundevenf(float v)
{
	float rounded = roundf(v);
	float diff = rounded - v;
	if ((fabsf(diff) == 0.5f) && (((int32_t)rounded) & 1))
	{
		rounded = v - diff;
	}
	return rounded;
}

#if defined(KRKRSDL2_NEEDS_ROUNDEVENF_COMPAT_SYMBOL)
#undef roundevenf
/*
 * Newer Android NDKs may emit a roundevenf libcall from SIMDe on 32-bit ARM.
 * Keep the workaround entry point for LLVM 18, but also provide the standard
 * symbol because it is not available at our minimum Android API level.
 */
float roundevenf(float v)
{
	return roundevenf_workaround(v);
}
#endif
