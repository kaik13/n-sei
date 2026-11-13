/* Regression for the optional TCP-only bind compatibility in Box64.
 * With BLOCO_TEST_HELPER, exercise the helper directly on a native host.
 * Without it, call libc bind so the same binary tests the Box64 wrapper.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#ifdef BLOCO_TEST_HELPER
#include "bloco-tcp-reuse.h"
#define test_bind bloco_bind_with_tcp_reuse
#else
#define test_bind bind
#endif

static void require(int condition, const char *what) {
    if (!condition) {
        int saved = errno;
        fprintf(stderr, "FAIL: %s (errno %d: %s)\n", what, saved, strerror(saved));
        exit(1);
    }
}
static int option(int fd, int which) {
    int value = -1;
    socklen_t length = sizeof(value);
    require(getsockopt(fd, SOL_SOCKET, which, &value, &length) == 0, "ler opção de socket");
    return value;
}
static void restart_case(int family, int enabled) {
    struct sockaddr_storage storage;
    memset(&storage, 0, sizeof(storage));
    socklen_t length;
    if (family == AF_INET) {
        struct sockaddr_in *a = (struct sockaddr_in *)&storage;
        a->sin_family = AF_INET; a->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        length = sizeof(*a);
    } else {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *)&storage;
        a->sin6_family = AF_INET6; a->sin6_addr = in6addr_loopback;
        length = sizeof(*a);
    }
    int listener = socket(family, SOCK_STREAM, 0);
    if (family == AF_INET6 && listener == -1 && (errno == EAFNOSUPPORT || errno == EPROTONOSUPPORT)) {
        puts("IPv6 não disponível; caso IPv6 ignorado"); return;
    }
    require(listener >= 0, "criar TCP");
    if (family == AF_INET6) {
        int only = 1;
        require(setsockopt(listener, IPPROTO_IPV6, IPV6_V6ONLY, &only, sizeof(only)) == 0, "IPv6 separado");
    }
    int result = test_bind(listener, (struct sockaddr *)&storage, length);
    if (family == AF_INET6 && result == -1 && errno == EADDRNOTAVAIL) {
        close(listener); puts("IPv6 loopback não disponível; caso IPv6 ignorado"); return;
    }
    require(result == 0, "bind inicial");
    require(option(listener, SO_REUSEADDR) == enabled, "SO_REUSEADDR inicial");
    require(option(listener, SO_REUSEPORT) == 0, "SO_REUSEPORT deve permanecer desligado");
    require(getsockname(listener, (struct sockaddr *)&storage, &length) == 0, "porta escolhida");
    require(listen(listener, 1) == 0, "escutar");
    int rival = socket(family, SOCK_STREAM, 0);
    require(rival >= 0, "criar TCP concorrente");
    require(test_bind(rival, (struct sockaddr *)&storage, length) == -1 && errno == EADDRINUSE,
            "bloquear listener concorrente");
    close(rival);

    int client = socket(family, SOCK_STREAM, 0);
    require(client >= 0 && connect(client, (struct sockaddr *)&storage, length) == 0, "conectar TCP");
    int peer = accept(listener, NULL, NULL);
    require(peer >= 0, "aceitar TCP");
    require(shutdown(peer, SHUT_WR) == 0, "servidor inicia fechamento");
    char byte;
    require(recv(client, &byte, 1, 0) == 0, "cliente recebe FIN");
    require(shutdown(client, SHUT_WR) == 0, "cliente envia FIN");
    require(recv(peer, &byte, 1, 0) == 0, "servidor recebe FIN");
    close(peer); close(client); close(listener);
    struct timespec pause = {0, 30000000};
    while (nanosleep(&pause, &pause) == -1 && errno == EINTR) { }

    int replacement = socket(family, SOCK_STREAM, 0);
    require(replacement >= 0, "novo TCP");
    if (family == AF_INET6) {
        int only = 1;
        require(setsockopt(replacement, IPPROTO_IPV6, IPV6_V6ONLY, &only, sizeof(only)) == 0, "IPv6 separado no reinício");
    }
    result = test_bind(replacement, (struct sockaddr *)&storage, length);
    if (enabled) {
        require(result == 0, "reiniciar na mesma porta após TIME_WAIT");
        require(option(replacement, SO_REUSEADDR) == 1 && option(replacement, SO_REUSEPORT) == 0,
                "opções no reinício");
        require(listen(replacement, 1) == 0, "escutar após reiniciar");
    } else {
        require(result == -1 && errno == EADDRINUSE, "baseline deve detectar TIME_WAIT");
    }
    close(replacement);
}

int main(int argc, char **argv) {
    require(argc == 2 && (!strcmp(argv[1], "enabled") || !strcmp(argv[1], "baseline")), "modo de teste");
    int enabled = strcmp(argv[1], "enabled") == 0;
    require(setenv("BLOCO_REUSE_TCP", enabled ? "1" : "0", 1) == 0, "configurar teste");
    restart_case(AF_INET, enabled);
    restart_case(AF_INET6, enabled);

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr)); addr.sin_family = AF_INET; addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int udp = socket(AF_INET, SOCK_DGRAM, 0);
    require(udp >= 0, "criar UDP");
    int before = option(udp, SO_REUSEADDR);
    require(test_bind(udp, (struct sockaddr *)&addr, sizeof(addr)) == 0, "bind UDP");
    require(option(udp, SO_REUSEADDR) == before, "UDP não deve ser alterado");
    close(udp);
    require(test_bind(-1, NULL, 0) == -1 && errno == EBADF, "erro de descritor inválido");
    int bad = socket(AF_INET, SOCK_STREAM, 0);
    require(bad >= 0, "criar socket para ponteiro inválido");
    require(test_bind(bad, (const struct sockaddr *)(uintptr_t)1, sizeof(addr)) == -1 && errno == EFAULT,
            "ponteiro inválido deve retornar EFAULT");
    close(bad);
    printf("PASS: %s — reinício TCP, concorrência, UDP e erros\n", argv[1]);
    return 0;
}
