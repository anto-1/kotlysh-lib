#ifndef KOTLYSH_NATIVE_LAUNCH_H
#define KOTLYSH_NATIVE_LAUNCH_H

#include <errno.h>
#include <fcntl.h>
#include <paths.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

extern char **environ;

typedef struct {
    char **environment;
    char **paths;
    size_t path_count;
    char **shell_arguments;
} kotlysh_launch_plan;

static inline void kotlysh_free_launch(kotlysh_launch_plan *plan) {
    if (plan->environment) {
        for (size_t i = 0; plan->environment[i]; ++i) free(plan->environment[i]);
        free(plan->environment);
    }
    if (plan->paths) {
        for (size_t i = 0; i < plan->path_count; ++i) free(plan->paths[i]);
        free(plan->paths);
    }
    free(plan->shell_arguments);
}

/* All allocation and PATH/environment preparation runs in the parent. Inherited
   environment bytes are copied as bytes, without a Unicode round trip. */
static inline int kotlysh_prepare_launch(
    kotlysh_launch_plan *plan, const char *executable, char *const arguments[],
    const char *const names[], const char *const values[], int search_path) {
    memset(plan, 0, sizeof(*plan));
    size_t inherited = 0, overrides = 0, argument_count = 0;
    while (environ && environ[inherited]) ++inherited;
    while (names && names[overrides]) ++overrides;
    while (arguments[argument_count]) ++argument_count;
    size_t pointer_limit = SIZE_MAX / sizeof(char *);
    if (overrides >= pointer_limit || inherited > pointer_limit - overrides - 1 ||
        argument_count > pointer_limit - 2) { errno = ENOMEM; return -1; }
    plan->environment = calloc(inherited + overrides + 1, sizeof(char *));
    plan->shell_arguments = calloc(argument_count + 2, sizeof(char *));
    if (!plan->environment || !plan->shell_arguments) return -1;
    size_t used = 0;
    for (size_t i = 0; i < inherited; ++i) {
        int replaced = 0;
        for (size_t j = 0; j < overrides; ++j) {
            size_t length = strlen(names[j]);
            if (strncmp(environ[i], names[j], length) == 0 && environ[i][length] == '=') {
                replaced = 1; break;
            }
        }
        if (!replaced) {
            plan->environment[used] = strdup(environ[i]);
            if (!plan->environment[used++]) return -1;
        }
    }
    for (size_t j = 0; j < overrides; ++j) {
        size_t name_size = strlen(names[j]), value_size = strlen(values[j]);
        if (value_size > SIZE_MAX - 2 || name_size > SIZE_MAX - value_size - 2) { errno = ENOMEM; return -1; }
        char *entry = malloc(name_size + value_size + 2);
        if (!entry) return -1;
        memcpy(entry, names[j], name_size);
        entry[name_size] = '=';
        memcpy(entry + name_size + 1, values[j], value_size + 1);
        plan->environment[used++] = entry;
    }

    plan->shell_arguments[0] = (char *)_PATH_BSHELL;
    for (size_t i = 1; i < argument_count; ++i) plan->shell_arguments[i + 1] = arguments[i];
    const char *path = NULL;
    for (size_t i = 0; i < used; ++i)
        if (strncmp(plan->environment[i], "PATH=", 5) == 0) { path = plan->environment[i] + 5; break; }
    if (!path) {
#ifdef __ANDROID__
        path = _PATH_DEFPATH;
#else
        path = "/bin:/usr/bin";
#endif
    }
    int literal = !search_path || strchr(executable, '/') != NULL;
    size_t count = 1;
    if (!literal) for (const char *cursor = path; *cursor; ++cursor) if (*cursor == ':') ++count;
    if (count > SIZE_MAX / sizeof(char *)) { errno = ENOMEM; return -1; }
    plan->paths = calloc(count, sizeof(char *));
    if (!plan->paths) return -1;
    size_t executable_size = strlen(executable);
    const char *start = path;
    for (size_t i = 0; i < count; ++i) {
        const char *end = literal ? start : strchr(start, ':');
        size_t prefix = literal ? 0 : (end ? (size_t)(end - start) : strlen(start));
        if (prefix > SIZE_MAX - executable_size - 2) { errno = ENOMEM; return -1; }
        char *candidate = malloc(prefix + executable_size + 2);
        if (!candidate) return -1;
        if (prefix) { memcpy(candidate, start, prefix); candidate[prefix++] = '/'; }
        memcpy(candidate + prefix, executable, executable_size + 1);
        plan->paths[plan->path_count++] = candidate;
        if (end) start = end + 1;
    }
    return 0;
}

static inline void kotlysh_child_message(const char *message, size_t size) {
    while (size) {
        ssize_t written = write(STDERR_FILENO, message, size);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) break;
        message += written;
        size -= (size_t)written;
    }
}

/* The child stays entirely in C and calls only async-signal-safe operations.
   owned_pid is written before any parent-side cleanup or return to Kotlin. */
static inline int kotlysh_spawn(
    const char *executable, char *const arguments[], const char *cwd,
    const char *const names[], const char *const values[], int search_path,
    int input_read, int input_write, int output_read, int output_write,
    int error_read, int error_write, const char *cwd_error, const char *exec_error,
    int *owned_pid) {
    kotlysh_launch_plan plan;
    int prepared = kotlysh_prepare_launch(&plan, executable, arguments, names, values, search_path);
    if (prepared != 0) {
        int failure = errno;
        kotlysh_free_launch(&plan);
        errno = failure;
        return -1;
    }
    size_t cwd_error_size = strlen(cwd_error), exec_error_size = strlen(exec_error);
    pid_t pid = fork();
    if (pid == 0) {
        if (setpgid(0, 0) != 0 || dup2(output_write, STDOUT_FILENO) < 0 ||
            dup2(error_write, STDERR_FILENO) < 0) _exit(126);
        /* Free the output pipe slots before opening /dev/null. The parent may
           have reached its descriptor limit when acquiring the final pipe. */
        close(output_read); close(output_write); close(error_read); close(error_write);
        if (input_read >= 0) {
            if (dup2(input_read, STDIN_FILENO) < 0) _exit(126);
        } else {
            int null_fd = open("/dev/null", O_RDONLY);
            if (null_fd < 0 || dup2(null_fd, STDIN_FILENO) < 0) _exit(126);
            if (null_fd != STDIN_FILENO) close(null_fd);
        }
        if (input_read >= 0) close(input_read);
        if (input_write >= 0) close(input_write);
        if (cwd && chdir(cwd) != 0) {
            kotlysh_child_message(cwd_error, cwd_error_size);
            _exit(126);
        }
        for (size_t i = 0; i < plan.path_count; ++i) {
            execve(plan.paths[i], arguments, plan.environment);
            if (errno == ENOEXEC && search_path) {
                plan.shell_arguments[1] = plan.paths[i];
                execve(_PATH_BSHELL, plan.shell_arguments, plan.environment);
                break;
            }
            if (errno != EACCES && errno != ENOENT && errno != ENOTDIR &&
                errno != ESTALE && errno != ENODEV && errno != ETIMEDOUT) break;
        }
        kotlysh_child_message(exec_error, exec_error_size);
        _exit(127);
    }
    if (pid > 0) *owned_pid = (int)pid;
    int failure = errno;
    kotlysh_free_launch(&plan);
    errno = failure;
    return pid > 0 ? 0 : -1;
}

#endif
