#include <assert.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <unistd.h>
#include "native_process.h"

static int fail_after = -1;
static int live_allocations = 0;
static int allocation_calls = 0;
static pid_t parent_pid;
static int deny_fork = 0;
static int fork_calls = 0;
static pid_t checked_fork(void) {
    assert(getpid() == parent_pid);
    ++fork_calls;
    if (deny_fork) { errno = EAGAIN; return -1; }
    return fork();
}
static int deny_allocation(void) {
    assert(getpid() == parent_pid);
    ++allocation_calls;
    if (fail_after < 0) return 0;
    if (fail_after == 0) { errno = ENOMEM; return 1; }
    --fail_after;
    return 0;
}
static void *checked_malloc(size_t bytes) {
    if (deny_allocation()) return NULL;
    void *result = malloc(bytes);
    if (result) ++live_allocations;
    return result;
}
static void *checked_calloc(size_t count, size_t size) {
    if (deny_allocation()) return NULL;
    void *result = calloc(count, size);
    if (result) ++live_allocations;
    return result;
}
static char *checked_strdup(const char *text) {
    if (deny_allocation()) return NULL;
    char *result = strdup(text);
    if (result) ++live_allocations;
    return result;
}
static void checked_free(void *pointer) {
    assert(getpid() == parent_pid);
    if (pointer) --live_allocations;
    free(pointer);
}
#define malloc checked_malloc
#define calloc checked_calloc
#define strdup checked_strdup
#define free checked_free
#define fork checked_fork
#include "native_launch.h"
#undef malloc
#undef calloc
#undef strdup
#undef free
#undef fork

/* An isolated child can lower hard limits and close stdio without changing
   the checker or CI runner. Cover failure at both descriptor replacements. */
static void check_pipe_normalization_rollback(int close_all_stdio) {
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        /* Give the isolated fixture a known four-slot layout, even if the
           runner inherited an additional descriptor at 3. */
        close(3);
        struct rlimit limit = {4, 4};
        assert(setrlimit(RLIMIT_NOFILE, &limit) == 0);
        close(STDIN_FILENO);
        if (close_all_stdio) { close(STDOUT_FILENO); close(STDERR_FILENO); }
        int ends[2];
        assert(kotlysh_pipe(ends) == -1 && errno == EMFILE);
        assert(fcntl(3, F_GETFD) == -1 && errno == EBADF);
        if (close_all_stdio) {
            assert(fcntl(1, F_GETFD) == -1 && errno == EBADF);
            assert(fcntl(2, F_GETFD) == -1 && errno == EBADF);
        } else {
            assert(fcntl(1, F_GETFD) >= 0 && fcntl(2, F_GETFD) >= 0);
        }
        int available = open("/dev/null", O_RDONLY);
        assert(available == 0);
        close(available);
        _exit(0);
    }
    int status;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

int main(void) {
    parent_pid = getpid();
    assert(setenv("KOTLYSH_SHIM_RAW", "\xff\xc0", 1) == 0);
    const char *names[] = {"PATH", "KOTLYSH_SHIM_OVERRIDE", NULL};
    const char *values[] = {".:/usr/bin", "value", NULL};
    char *arguments[] = {"printf", "literal", NULL};
    kotlysh_launch_plan plan;
    assert(kotlysh_prepare_launch(&plan, "printf", arguments, names, values, 1) == 0);
    int found_raw = 0, found_override = 0;
    for (size_t i = 0; plan.environment[i]; ++i) {
        if (strncmp(plan.environment[i], "KOTLYSH_SHIM_RAW=", sizeof("KOTLYSH_SHIM_RAW=") - 1) == 0) {
            assert(strcmp(plan.environment[i] + sizeof("KOTLYSH_SHIM_RAW=") - 1, "\xff\xc0") == 0);
            found_raw = 1;
        }
        if (strcmp(plan.environment[i], "KOTLYSH_SHIM_OVERRIDE=value") == 0) found_override = 1;
    }
    assert(found_raw && found_override);
    assert(plan.path_count == 2);
    assert(strcmp(plan.paths[0], "./printf") == 0);
    assert(strcmp(plan.paths[1], "/usr/bin/printf") == 0);
    int preparation_allocations = allocation_calls;
    kotlysh_free_launch(&plan);
    assert(live_allocations == 0);
    for (int failure = 0; failure < preparation_allocations; ++failure) {
        fail_after = failure;
        int owned_pid = -1;
        assert(kotlysh_spawn("printf", arguments, NULL, names, values, 1,
            -1, -1, -1, -1, -1, -1, "cwd error", "exec error", &owned_pid) == -1);
        assert(errno == ENOMEM);
        assert(owned_pid == -1);
        assert(live_allocations == 0);
        assert(fork_calls == 0);
    }
    fail_after = -1;
    deny_fork = 1;
    for (int attempt = 0; attempt < 3; ++attempt) {
        int unpublished = -1;
        assert(kotlysh_spawn("printf", arguments, NULL, names, values, 1,
            -1, -1, -1, -1, -1, -1, "cwd error", "exec error", &unpublished) == -1);
        assert(errno == EAGAIN && unpublished == -1);
        assert(live_allocations == 0);
    }
    assert(fork_calls == 3);
    deny_fork = 0;
    int owned_pid = -1;
    assert(kotlysh_spawn("printf", arguments, NULL, names, values, 1,
        -1, -1, -1, -1, -1, -1, "cwd error", "exec error", &owned_pid) == 0);
    int status;
    assert(waitpid(owned_pid, &status, 0) == owned_pid);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 126);
    assert(live_allocations == 0);
    check_pipe_normalization_rollback(0);
    check_pipe_normalization_rollback(1);
    return 0;
}
