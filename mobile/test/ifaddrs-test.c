/* Compare getifaddrs against an independent IPv4 SIOCGIFCONF lookup.
 * BLOCO_TEST_HELPER tests the shared fallback natively; without it, this
 * executable tests a libc guest, including through PRoot and ARM64 Box64.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>
#ifdef BLOCO_TEST_HELPER
#include "bloco-ifaddrs.h"
#endif

static void require(int ok, const char *message) {
    if (!ok) {
        fprintf(stderr, "FAIL: %s (errno %d: %s)\n", message, errno, strerror(errno));
        exit(1);
    }
}

static void test_once(void) {
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    require(fd >= 0, "create IPv4 ioctl socket");
    struct ifreq requests[256];
    struct ifconf conf;
    memset(&conf, 0, sizeof(conf));
    conf.ifc_len = sizeof(requests);
    conf.ifc_req = requests;
    require(ioctl(fd, SIOCGIFCONF, &conf) == 0 && conf.ifc_len >= 0 &&
            (size_t)conf.ifc_len < sizeof(requests) && conf.ifc_len % sizeof(*requests) == 0,
            "read independently enumerated IPv4 addresses");
    struct ifaddrs *list = NULL;
#ifdef BLOCO_TEST_HELPER
    int using_ioctl = 0;
    require(bloco_get_ifaddrs(&list, &using_ioctl) == 0, "getifaddrs with fallback");
    const char *expect = getenv("BLOCO_EXPECT_IOCTL");
    if (expect) require(using_ioctl == (strcmp(expect, "1") == 0), "expected fallback selection");
#else
    require(getifaddrs(&list) == 0, "guest getifaddrs");
#endif
    int external = 0;
    for (size_t n = 0; n < (size_t)conf.ifc_len / sizeof(*requests); ++n) {
        struct ifreq *item = &requests[n];
        if (item->ifr_addr.sa_family != AF_INET) continue;
        struct ifreq query;
        memset(&query, 0, sizeof(query));
        memcpy(query.ifr_name, item->ifr_name, IFNAMSIZ);
        require(ioctl(fd, SIOCGIFFLAGS, &query) == 0, "read interface flags");
        unsigned expected = (unsigned short)query.ifr_flags;
        const struct sockaddr_in *ip = (const struct sockaddr_in *)&item->ifr_addr;
        unsigned long address = ntohl(ip->sin_addr.s_addr);
        if (address != 0 && (address >> 24) != 127 && !(expected & IFF_LOOPBACK)) ++external;
        int matched = 0;
        for (struct ifaddrs *current = list; current != NULL; current = current->ifa_next) {
            if (!current->ifa_name || !current->ifa_addr || current->ifa_addr->sa_family != AF_INET) continue;
            const struct sockaddr_in *actual = (const struct sockaddr_in *)current->ifa_addr;
            if (!strcmp(current->ifa_name, item->ifr_name) && actual->sin_addr.s_addr == ip->sin_addr.s_addr) {
                unsigned relevant = IFF_UP | IFF_LOOPBACK | IFF_RUNNING | IFF_BROADCAST | IFF_POINTOPOINT;
                require((current->ifa_flags & relevant) == (expected & relevant), "interface flags match");
                memset(&query, 0, sizeof(query));
                memcpy(query.ifr_name, item->ifr_name, IFNAMSIZ);
                if (ioctl(fd, SIOCGIFNETMASK, &query) == 0 && query.ifr_netmask.sa_family == AF_INET) {
                    require(current->ifa_netmask && current->ifa_netmask->sa_family == AF_INET &&
                            ((struct sockaddr_in *)current->ifa_netmask)->sin_addr.s_addr ==
                            ((struct sockaddr_in *)&query.ifr_netmask)->sin_addr.s_addr,
                            "interface network mask matches");
                }
                matched = 1;
                break;
            }
        }
        require(matched, "getifaddrs omitted an IPv4 interface visible to ioctl");
    }
    require(external > 0, "test needs at least one non-loopback IPv4 interface");
#ifdef BLOCO_TEST_HELPER
#ifdef BLOCO_TEST_GLIBC_FREE
    /* Box64 forwards freeifaddrs to the bundled Ubuntu glibc. */
    freeifaddrs(list);
#else
    bloco_free_ifaddrs(list, using_ioctl);
#endif
#else
    freeifaddrs(list);
#endif
    close(fd);
}

static void *thread_main(void *unused) {
    (void)unused;
    for (int i = 0; i < 12; ++i) test_once();
    return NULL;
}
int main(void) {
    enum { THREADS = 4 };
    pthread_t workers[THREADS];
    for (int i = 0; i < THREADS; ++i) require(pthread_create(&workers[i], NULL, thread_main, NULL) == 0, "create thread");
    for (int i = 0; i < THREADS; ++i) require(pthread_join(workers[i], NULL) == 0, "join thread");
    puts("PASS: getifaddrs exposes all independently enumerated IPv4 addresses, flags and masks");
    return 0;
}
