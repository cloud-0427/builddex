#ifndef JIAGU_SYSCALL_ARCH_H
#define JIAGU_SYSCALL_ARCH_H

#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

// Keep syscall invocation in one small, architecture-independent boundary. Android's
// NDK provides syscall(2) for every ABI supported by this project; using it avoids the
// incomplete hand-written register bindings that previously returned uninitialized
// values on armeabi-v7a and x86.
static __attribute__((always_inline)) inline long raw_syscall(long number) {
    return ::syscall(number);
}

static __attribute__((always_inline)) inline long raw_syscall(long number, long arg1) {
    return ::syscall(number, arg1);
}

static __attribute__((always_inline)) inline long raw_syscall(
        long number, long arg1, long arg2, long arg3) {
    return ::syscall(number, arg1, arg2, arg3);
}

static __attribute__((always_inline)) inline long raw_syscall(
        long number, long arg1, long arg2, long arg3, long arg4) {
    return ::syscall(number, arg1, arg2, arg3, arg4);
}

static __attribute__((always_inline)) inline long raw_syscall_open(
        const char* path, int flags, int mode) {
#if defined(SYS_openat)
    return raw_syscall(SYS_openat, AT_FDCWD, reinterpret_cast<long>(path), flags, mode);
#else
    return raw_syscall(SYS_open, reinterpret_cast<long>(path), flags, mode);
#endif
}

#endif // JIAGU_SYSCALL_ARCH_H
