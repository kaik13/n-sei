/* Exercise the patched, real Box64 cleanup.c with native threads.
 * This file is a build-time test, not part of the Android package.
 */
#define _GNU_SOURCE
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>
#include "box64context.h"
#include "elfs/elfloader_private.h"
#include "cleanup.h"
#include "env.h"

box64context_t *my_context;
box64env_t box64env;
static elfheader_t object;
static pthread_barrier_t barrier;
static atomic_int global_calls, object_calls, late_calls, overlapping_writers;

void PrintfFtrace(int prefix, const char *fmt, ...) { (void)prefix; (void)fmt; }
uint64_t RunFunctionWithEmu(x64emu_t *emu, int quit, uintptr_t function, int nargs, ...) {
    (void)emu; (void)quit;
    va_list ap; va_start(ap, nargs);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    if (nargs == 0) ((void (*)(void))function)();
    else if (nargs == 1) ((void (*)(void *))function)(arg);
    else abort();
    return 0;
}
static void require(int condition, const char *message) {
    if (!condition) { fprintf(stderr, "FAIL: %s\n", message); exit(1); }
}
static void global_callback(void) { atomic_fetch_add(&global_calls, 1); }
static void object_callback(void *arg) {
    if (arg != &object) abort();
    atomic_fetch_add(&object_calls, 1);
}
static void late_callback(void *arg) {
    if (arg != &object) abort();
    atomic_fetch_add(&late_calls, 1);
}
static void add_late_object(void *arg) {
    if (arg != &object) abort();
    AddCleanup1Arg(NULL, (void *)late_callback, &object, &object);
}
static void add_late_global(void) { AddCleanup(NULL, (void *)global_callback); }
static void nested_finalize(void *arg) {
    if (arg != &object) abort();
    CallCleanup(NULL, &object);
}
static void *worker(void *unused) {
    (void)unused;
    pthread_barrier_wait(&barrier);
    for (int i = 0; i < 5000; ++i) {
        AddCleanup(NULL, (void *)global_callback);
        AddCleanup1Arg(NULL, (void *)object_callback, &object, &object);
    }
    return NULL;
}
static void *overlapping_worker(void *unused) {
    (void)unused;
    for (int i = 0; i < 1000; ++i) AddCleanup1Arg(NULL, (void *)object_callback, &object, &object);
    atomic_fetch_sub(&overlapping_writers, 1);
    return NULL;
}
static void *overlapping_finalize(void *unused) {
    (void)unused;
    while (atomic_load(&overlapping_writers) > 0) CallCleanup(NULL, &object);
    return NULL;
}
int main(void) {
    alarm(90);
    my_context = calloc(1, sizeof(*my_context)); require(my_context != NULL, "contexto");
    enum { THREADS = 8, EXPECTED = THREADS * 5000 };
    pthread_t threads[THREADS];
    require(pthread_barrier_init(&barrier, NULL, THREADS + 1) == 0, "barreira");
    /* Initialize the atfork protection before racing a fork against writers. */
    AddCleanup(NULL, (void *)global_callback);
    for (int i = 0; i < THREADS; ++i)
        require(pthread_create(&threads[i], NULL, worker, NULL) == 0, "criar thread");
    pthread_barrier_wait(&barrier);
    pid_t child = fork(); require(child >= 0, "fork");
    if (child == 0) {
        alarm(30);
        AddCleanup(NULL, (void *)global_callback);
        AddCleanup1Arg(NULL, (void *)object_callback, &object, &object);
        _exit(0);
    }
    int status = 0;
    require(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0,
            "registro no filho após fork");
    for (int i = 0; i < THREADS; ++i) require(pthread_join(threads[i], NULL) == 0, "join");
    pthread_barrier_destroy(&barrier);
    require(my_context->clean_sz == EXPECTED + 1 && object.clean_sz == EXPECTED,
            "todos os callbacks concorrentes foram registrados");
    CallCleanup(NULL, &object); CallAllCleanup(NULL);
    require(atomic_load(&object_calls) == EXPECTED && atomic_load(&global_calls) == EXPECTED + 1,
            "callbacks concorrentes executados uma vez");
    require(object.clean_sz == 0 && my_context->clean_sz == 0 && my_context->clean_cap == 0,
            "listas limpas e capacidade reiniciada");

    AddCleanup1Arg(NULL, (void *)object_callback, &object, &object);
    AddCleanup1Arg(NULL, (void *)add_late_object, &object, &object);
    CallCleanup(NULL, &object);
    require(object.clean_sz == 1 && atomic_load(&late_calls) == 0,
            "registro durante callback deve permanecer seguro");
    CallCleanup(NULL, &object);
    require(object.clean_sz == 0 && atomic_load(&late_calls) == 1, "callback tardio");

    AddCleanup1Arg(NULL, (void *)object_callback, &object, &object);
    AddCleanup1Arg(NULL, (void *)nested_finalize, &object, &object);
    CallCleanup(NULL, &object);
    require(object.clean_sz == 0 && atomic_load(&object_calls) == EXPECTED + 2,
            "finalização reentrante sem deadlock ou duplicação");

    atomic_store(&overlapping_writers, 4);
    pthread_t finalizer, writers[4];
    require(pthread_create(&finalizer, NULL, overlapping_finalize, NULL) == 0, "finalizador concorrente");
    for (int i = 0; i < 4; ++i)
        require(pthread_create(&writers[i], NULL, overlapping_worker, NULL) == 0, "escritor concorrente");
    for (int i = 0; i < 4; ++i) require(pthread_join(writers[i], NULL) == 0, "join escritor");
    require(pthread_join(finalizer, NULL) == 0, "join finalizador");
    CallCleanup(NULL, &object);
    require(object.clean_sz == 0 && atomic_load(&object_calls) == EXPECTED + 4002,
            "registro e finalização concorrentes não devem perder callbacks");

    AddCleanup(NULL, (void *)add_late_global);
    CallAllCleanup(NULL);
    require(my_context->clean_sz == 1, "registro global tardio preservado");
    CallAllCleanup(NULL);
    require(my_context->clean_sz == 0 && my_context->clean_cap == 0 && my_context->cleanups == NULL,
            "nova finalização global sem acesso à memória liberada");
    free(object.cleanups); free(my_context);
    puts("PASS: listas de limpeza Box64 — concorrência, fork, callbacks e reentrada");
    return 0;
}
