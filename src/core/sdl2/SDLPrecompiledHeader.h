/* SPDX-License-Identifier: MIT */
/* Copyright (c) Kirikiri SDL2 Developers */

// Precompiled header entry point
#include "tjsCommHead.h"

// On 32-bit ARM, include SIMDe through the compatibility layer so the LLVM
// roundeven workaround is defined before SIMDe's headers enter the PCH.
// Native x86 builds must keep the intrinsic names untouched because SDL also
// includes the compiler-provided intrinsic headers.
#if defined(__clang__) && defined(__arm__)
#include "SIMDeRenames.h"
#else
#if defined(__vita__) || defined(__SWITCH__)
#include <simde/simde/simde-common.h>
#undef SIMDE_HAVE_FENV_H
#endif
#include <simde/x86/sse.h>
#include <simde/x86/sse2.h>
#include <simde/x86/sse3.h>
#include <simde/x86/ssse3.h>
#include <simde/x86/sse4.1.h>
#include <simde/x86/avx.h>
#include <simde/x86/avx2.h>
#include <simde/x86/fma.h>
#endif
