#ifndef KOTLYSH_NATIVE_PROCESS_H
#define KOTLYSH_NATIVE_PROCESS_H

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* Keep owned descriptors above stdio, even when the embedding host closed it. */
static inline int kotlysh_pipe(int fds[2]) {
    if (pipe2(fds, O_CLOEXEC) != 0) return -1;
    for (int i = 0; i < 2; ++i) {
        if (fds[i] >= 3) continue;
        int replacement = fcntl(fds[i], F_DUPFD_CLOEXEC, 3);
        if (replacement < 0) {
            int failure = errno;
            close(fds[0]);
            close(fds[1]);
            errno = failure;
            return -1;
        }
        close(fds[i]);
        fds[i] = replacement;
    }
    return 0;
}

static inline int kotlysh_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL);
    return flags < 0 ? -1 : fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static inline int kotlysh_available(int fd) {
    int bytes = 0;
    return ioctl(fd, FIONREAD, &bytes) == 0 ? bytes : -1;
}

/* Observe status while retaining the root PID until group cleanup is complete. */
static inline int kotlysh_peek_exit(pid_t pid, int *status) {
    siginfo_t info;
    memset(&info, 0, sizeof(info));
    int result;
    do {
        result = waitid(P_PID, (id_t)pid, &info, WEXITED | WNOHANG | WNOWAIT);
    } while (result < 0 && errno == EINTR);
    if (result < 0) return -1;
    if (info.si_pid == 0) return 0;
    *status = info.si_code == CLD_EXITED ? (info.si_status << 8) : info.si_status;
    return 1;
}

/* Avoid allocating a status cell during cleanup. Statuses are nonnegative;
   -2 means running and -1 means an error with errno retained. */
static inline int kotlysh_exit_status(pid_t pid) {
    int status = 0;
    int observed = kotlysh_peek_exit(pid, &status);
    return observed < 0 ? -1 : (observed == 0 ? -2 : status);
}

/* A closed stdin must report EPIPE, without changing the host's signal handler. */
static inline ssize_t kotlysh_write_pipe(int fd, const void *bytes, size_t size) {
    sigset_t blocked, previous, pending;
    sigemptyset(&blocked);
    sigaddset(&blocked, SIGPIPE);
    int failure = pthread_sigmask(SIG_BLOCK, &blocked, &previous);
    if (failure != 0) { errno = failure; return -1; }
    if (sigpending(&pending) != 0) {
        failure = errno;
        pthread_sigmask(SIG_SETMASK, &previous, NULL);
        errno = failure;
        return -1;
    }
    int already_pending = sigismember(&pending, SIGPIPE);
    ssize_t result = write(fd, bytes, size);
    failure = errno;
    if (result < 0 && failure == EPIPE && !already_pending) {
#ifdef __ANDROID__
        /* sigtimedwait requires API 23; Android's older sigwait is sufficient
           once the write-generated, thread-pending signal is confirmed. */
        if (sigpending(&pending) == 0 && sigismember(&pending, SIGPIPE)) {
            int received;
            sigwait(&blocked, &received);
        }
#else
        struct timespec immediately = {0, 0};
        while (sigtimedwait(&blocked, NULL, &immediately) < 0 && errno == EINTR) {}
#endif
    }
    pthread_sigmask(SIG_SETMASK, &previous, NULL);
    errno = failure;
    return result;
}

#endif
