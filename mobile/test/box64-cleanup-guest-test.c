/* x86-64 guest regression for the bundled Box64 __cxa_atexit wrapper. */
#define _GNU_SOURCE
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>

extern int __cxa_atexit(void (*function)(void *), void *arg, void *dso);
extern void __cxa_finalize(void *dso);
extern void *__dso_handle;

static pthread_barrier_t barrier;
static atomic_int called;
static void callback(void *arg) {
    if (arg != &called) _exit(5);
    atomic_fetch_add(&called, 1);
}
static void require(int condition, const char *message) {
    if (!condition) { fprintf(stderr, "FAIL: %s\n", message); exit(1); }
}
static void *worker(void *unused) {
    (void)unused;
    pthread_barrier_wait(&barrier);
    for (int i = 0; i < 1000; ++i)
        if (__cxa_atexit(callback, &called, __dso_handle) != 0) _exit(6);
    return NULL;
}
int main(void) {
    alarm(90);
    enum { THREADS = 8, EXPECTED = THREADS * 1000 + 1 };
    pthread_t threads[THREADS];
    require(pthread_barrier_init(&barrier, NULL, THREADS + 1) == 0, "barreira");
    /* Initialize the Box64 hook before the parent forks with active writers. */
    require(__cxa_atexit(callback, &called, __dso_handle) == 0, "registro inicial");
    for (int i = 0; i < THREADS; ++i)
        require(pthread_create(&threads[i], NULL, worker, NULL) == 0, "criar thread");
    pthread_barrier_wait(&barrier);
    pid_t child = fork(); require(child >= 0, "fork");
    if (child == 0) _exit(0);
    int status = 0;
    require(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0,
            "fork durante registros concorrentes");
    for (int i = 0; i < THREADS; ++i) require(pthread_join(threads[i], NULL) == 0, "join");
    pthread_barrier_destroy(&barrier);
    __cxa_finalize(__dso_handle);
    require(atomic_load(&called) == EXPECTED, "cada callback registrado deve executar uma vez");
    __cxa_finalize(__dso_handle);
    require(atomic_load(&called) == EXPECTED, "finalizar novamente não deve duplicar callbacks");
    /* After all other threads have joined, it is safe to use libc in a child. */
    child = fork(); require(child >= 0, "fork após join");
    if (child == 0) {
        alarm(30);
        if (__cxa_atexit(callback, &called, __dso_handle) != 0) _exit(7);
        __cxa_finalize(__dso_handle);
        _exit(0);
    }
    require(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0,
            "registro e finalização no filho após join");
    puts("PASS: __cxa_atexit concorrente e fork no guest x86-64");
    return 0;
}
