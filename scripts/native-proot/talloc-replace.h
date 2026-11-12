#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <limits.h>
#include <sys/types.h>
#define HAVE_VA_COPY 1
#define MIN(a,b) ((a)<(b)?(a):(b))
#define MAX(a,b) ((a)>(b)?(a):(b))
#define discard_const_p(type, ptr) ((type *)(uintptr_t)(const void *)(ptr))
#define _PUBLIC_
