#ifndef __ASSEMBLER__
#include <errno.h>
/* musl exposes the ARM register array, but not glibc's field aliases. */
#if defined(__arm__)
#define ARM_cpsr uregs[16]
#define PSR_T_BIT 0x20
#include <sys/ipc.h>
/* PRoot decodes raw guest IPC commands, not musl's libc command aliases. */
#undef IPC_STAT
#define IPC_STAT 2
#undef IPC_SET
#define IPC_SET 1
#endif
#ifndef MSG_COPY
#define MSG_COPY 040000
#endif
#ifndef TEMP_FAILURE_RETRY
#define TEMP_FAILURE_RETRY(expression) \
  (__extension__ ({ long int result; do result = (long int) (expression); \
     while (result == -1L && errno == EINTR); result; }))
#endif
#endif
