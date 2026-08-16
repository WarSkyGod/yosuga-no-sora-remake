/* SPDX-License-Identifier: MIT */
/*
 * OpenHarmony (musl libc) compatibility shims for the Kirikiri engine.
 *
 * musl does not provide the GNU extension pthread_setaffinity_np; the
 * engine only uses it as a performance hint and ignores the result, so a
 * no-op implementation is safe.
 */

#include <pthread.h>
#include <sched.h>
#include <stddef.h>

int pthread_setaffinity_np(pthread_t thread, size_t cpusetsize, const cpu_set_t *cpuset)
{
	(void)thread;
	(void)cpusetsize;
	(void)cpuset;
	return 0;
}
