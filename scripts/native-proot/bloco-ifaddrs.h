/*
 * IPv4 interface fallback for Linux hosts that deny RTM_GETLINK, as some
 * Android app sandboxes do. SIOCGIFCONF and the per-interface ioctls do not
 * require network-administration privileges. This does not bypass Android's
 * sandbox and cannot help when those ioctls are also denied.
 *
 * The result is a single allocation with struct ifaddrs as its first member.
 * Call bloco_free_ifaddrs() with the returned fallback flag. Box64's pinned
 * Ubuntu glibc freeifaddrs() also frees exactly this first pointer.
 */
#ifndef BLOCO_IFADDRS_H
#define BLOCO_IFADDRS_H

#include <errno.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

struct bloco_ifaddr_entry {
    struct ifaddrs ifa;
    char name[IFNAMSIZ];
    struct sockaddr_in address;
    struct sockaddr_in netmask;
    struct sockaddr_in broadcast_or_destination;
};

/* A link-local IPv4 address can still be useful to peers on that link. */
static inline int bloco_has_nonloopback_ipv4(const struct ifaddrs *list) {
    for (const struct ifaddrs *i = list; i != NULL; i = i->ifa_next) {
        if (i->ifa_addr == NULL || i->ifa_addr->sa_family != AF_INET) continue;
        unsigned long a = ntohl(((const struct sockaddr_in *)i->ifa_addr)->sin_addr.s_addr);
        if (a != 0 && (a >> 24) != 127 && !(i->ifa_flags & IFF_LOOPBACK)) return 1;
    }
    return 0;
}

static inline void bloco_ifreq_name(struct ifreq *request, const char *name) {
    memset(request, 0, sizeof(*request));
    size_t length = strnlen(name, IFNAMSIZ - 1);
    memcpy(request->ifr_name, name, length);
}

/* Linux SIOCGIFCONF returns fixed-size ifreq records containing IPv4
 * addresses. Grow until there is spare capacity rather than silently losing
 * entries on devices with many interfaces. The upper bound avoids unbounded
 * allocation if a kernel or an ioctl shim returns an invalid length. */
static inline int bloco_ifaddrs_ioctl(struct ifaddrs **out) {
    *out = NULL;
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct ifreq *requests = NULL;
    struct bloco_ifaddr_entry *entries = NULL;
    size_t capacity = 32, count = 0, used = 0;
    int saved = 0;
    for (;;) {
        struct ifreq *grown = realloc(requests, capacity * sizeof(*requests));
        if (grown == NULL) { saved = ENOMEM; goto fail; }
        requests = grown;
        struct ifconf conf;
        memset(&conf, 0, sizeof(conf));
        conf.ifc_len = (int)(capacity * sizeof(*requests));
        conf.ifc_req = requests;
        if (ioctl(fd, SIOCGIFCONF, &conf) < 0) { saved = errno; goto fail; }
        if (conf.ifc_len < 0 || (size_t)conf.ifc_len > capacity * sizeof(*requests) ||
            (size_t)conf.ifc_len % sizeof(*requests) != 0) { saved = EIO; goto fail; }
        count = (size_t)conf.ifc_len / sizeof(*requests);
        if (count + 1 < capacity) break;
        if (capacity >= 4096) { saved = EOVERFLOW; goto fail; }
        capacity *= 2;
    }
    if (count == 0) { free(requests); close(fd); return 0; }
    entries = calloc(count, sizeof(*entries));
    if (entries == NULL) { saved = ENOMEM; goto fail; }
    for (size_t n = 0; n < count; ++n) {
        const struct ifreq *item = &requests[n];
        if (item->ifr_addr.sa_family != AF_INET || item->ifr_name[0] == '\0') continue;
        struct bloco_ifaddr_entry *entry = &entries[used];
        size_t length = strnlen(item->ifr_name, IFNAMSIZ - 1);
        memcpy(entry->name, item->ifr_name, length);
        memcpy(&entry->address, &item->ifr_addr, sizeof(entry->address));
        entry->ifa.ifa_name = entry->name;
        entry->ifa.ifa_addr = (struct sockaddr *)&entry->address;
        struct ifreq query;
        bloco_ifreq_name(&query, entry->name);
        if (ioctl(fd, SIOCGIFFLAGS, &query) == 0) entry->ifa.ifa_flags = (unsigned short)query.ifr_flags;
        bloco_ifreq_name(&query, entry->name);
        if (ioctl(fd, SIOCGIFNETMASK, &query) == 0 && query.ifr_netmask.sa_family == AF_INET) {
            memcpy(&entry->netmask, &query.ifr_netmask, sizeof(entry->netmask));
            entry->ifa.ifa_netmask = (struct sockaddr *)&entry->netmask;
        }
        bloco_ifreq_name(&query, entry->name);
        int request = (entry->ifa.ifa_flags & IFF_POINTOPOINT) ? SIOCGIFDSTADDR : SIOCGIFBRDADDR;
        if ((entry->ifa.ifa_flags & (IFF_POINTOPOINT | IFF_BROADCAST)) &&
            ioctl(fd, request, &query) == 0 && query.ifr_addr.sa_family == AF_INET) {
            memcpy(&entry->broadcast_or_destination, &query.ifr_addr, sizeof(entry->broadcast_or_destination));
            entry->ifa.ifa_broadaddr = (struct sockaddr *)&entry->broadcast_or_destination;
        }
        if (used > 0) entries[used - 1].ifa.ifa_next = &entry->ifa;
        ++used;
    }
    free(requests);
    close(fd);
    if (used == 0) free(entries);
    else *out = &entries[0].ifa;
    return 0;
fail:
    free(entries);
    free(requests);
    close(fd);
    errno = saved;
    return -1;
}

/* Prefer the real interface list, preserving IPv6 and other families. If it
 * fails, or presents only loopback despite a usable ioctl IPv4, use the
 * IPv4-only fallback. PRoot's synthetic netlink can otherwise make a failed
 * enumeration look like a successful loopback-only result. */
static inline int bloco_get_ifaddrs(struct ifaddrs **out, int *using_ioctl) {
    struct ifaddrs *native = NULL, *fallback = NULL;
    *out = NULL;
    *using_ioctl = 0;
    int result = getifaddrs(&native);
    int original_errno = errno;
    if (result == 0 && bloco_has_nonloopback_ipv4(native)) {
        *out = native;
        return 0;
    }
    int fallback_result = bloco_ifaddrs_ioctl(&fallback);
    if (fallback_result == 0 && (result != 0 || bloco_has_nonloopback_ipv4(fallback))) {
        if (result == 0) freeifaddrs(native);
        *out = fallback;
        *using_ioctl = 1;
        return 0;
    }
    free(fallback);
    if (result == 0) {
        *out = native;
        errno = original_errno;
        return 0;
    }
    errno = original_errno;
    return -1;
}

static inline void bloco_free_ifaddrs(struct ifaddrs *list, int using_ioctl) {
    if (using_ioctl) free(list);
    else freeifaddrs(list);
}
#endif
