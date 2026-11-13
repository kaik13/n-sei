/*
 * Bloco's optional Bedrock TCP restart compatibility. Included in the bundled
 * Box64 libc wrapper. It changes only IPv4/IPv6 TCP stream sockets in the
 * Bedrock process, never UDP or SO_REUSEPORT. A live listener remains exclusive.
 * The switch is set only for the current NetherNet server process.
 */
#ifndef BLOCO_TCP_REUSE_H
#define BLOCO_TCP_REUSE_H

#include <errno.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

static int bloco_bind_with_tcp_reuse(int fd, const struct sockaddr *address, socklen_t length) {
    int previous_errno = errno;
    const char *enabled = getenv("BLOCO_REUSE_TCP");
    if (enabled != NULL && strcmp(enabled, "1") == 0 && address != NULL && length >= sizeof(sa_family_t)) {
        int domain = 0, type = 0, protocol = 0;
        socklen_t size = sizeof(int);
        if (getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &domain, &size) == 0 &&
            (domain == AF_INET || domain == AF_INET6)) {
            size = sizeof(int);
            if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &size) == 0 && type == SOCK_STREAM) {
                size = sizeof(int);
                if (getsockopt(fd, SOL_SOCKET, SO_PROTOCOL, &protocol, &size) == 0 && protocol == IPPROTO_TCP) {
                    int reuse = 1;
                    /* If unsupported, fall back to the original bind result. */
                    (void)setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
                }
            }
        }
    }
    errno = previous_errno;
    return bind(fd, address, length);
}

#endif
