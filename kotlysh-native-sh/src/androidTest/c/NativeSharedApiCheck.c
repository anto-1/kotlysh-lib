#include "libkotlysh_native_sh_api.h"
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

typedef libkotlysh_native_sh_kref_com_kotlysh_nativesh_NativeShellSession Session;
typedef libkotlysh_native_sh_kref_com_kotlysh_nativesh_NativeShellResult Result;
typedef libkotlysh_native_sh_kref_com_kotlysh_nativesh_NativeOutputLimits Limits;
typedef libkotlysh_native_sh_ExportedSymbols *(*SymbolsFunction)(void);

static libkotlysh_native_sh_ExportedSymbols *symbols;
#define API (symbols->kotlin.root.com.kotlysh.nativesh)
/* Kotlin 2.1 Duration's nanosecond representation is the value shifted left
   once. These bounded values are verified by the real deadline control. */
#define MILLIS(value) ((libkotlysh_native_sh_KLong)(value) * 1000000LL * 2LL)

static int result_matches(Result result, const char *out, const char *err,
                          int status, int limited, int timed_out) {
    const char *stdout_text = API.NativeShellResult.get_stdoutText(result);
    const char *stderr_text = API.NativeShellResult.get_stderrText(result);
    libkotlysh_native_sh_kref_kotlin_Int exit_code = API.NativeShellResult.get_exitCode(result);
    int matches = stdout_text && stderr_text && strcmp(stdout_text, out) == 0 && strcmp(stderr_text, err) == 0;
    if (status < 0) matches &= exit_code.pinned == NULL;
    else matches &= exit_code.pinned && symbols->getNonNullValueOfInt(exit_code) == status;
    matches &= API.NativeShellResult.get_outputLimited(result) == limited;
    matches &= API.NativeShellResult.get_timedOut(result) == timed_out;
    matches &= !API.NativeShellResult.get_idleTimedOut(result);
    matches &= API.NativeShellResult.get_ok(result) == (status == 0 && !limited && !timed_out);
    if (stdout_text) symbols->DisposeString(stdout_text);
    if (stderr_text) symbols->DisposeString(stderr_text);
    if (exit_code.pinned) symbols->DisposeStablePointer(exit_code.pinned);
    return matches;
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fputs("Usage: native-shared-api-check <library> <shell>\n", stderr);
        return 2;
    }
    void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        fprintf(stderr, "dlopen failed: %s\n", dlerror());
        return 1;
    }
    void *address = dlsym(library, "libkotlysh_native_sh_symbols");
    SymbolsFunction exported;
    _Static_assert(sizeof(exported) == sizeof(address), "POSIX symbol pointer size");
    memcpy(&exported, &address, sizeof(exported));
    if (!address || !(symbols = exported())) {
        fputs("Missing exported API table\n", stderr);
        return 1;
    }
    puts("PASS:load-exported-api");

    Session session = {NULL};
    Result original = {NULL}, current = {NULL};
    Limits limits = {NULL}, small = {NULL};
    int outcome = 1;
    limits = API.NativeOutputLimits.NativeOutputLimits(32, 64000);
    session = API.NativeShellSession.NativeShellSession(argv[2]);
    original = API.NativeShellSession.invoke(session,
        "export KOTLYSH_C_STATE='shared-é😀'; printf '%s\\n' \"$KOTLYSH_C_STATE\"; printf 'shared-error\\n' >&2",
        MILLIS(5000), MILLIS(5000), limits);
    if (!result_matches(original, "shared-é😀", "shared-error", 0, 0, 0)) goto cleanup;
    puts("PASS:captures-streams-unicode");

    current = API.NativeShellSession.invoke(session, "printf '%s' \"$KOTLYSH_C_STATE\"",
        MILLIS(5000), MILLIS(5000), limits);
    if (!result_matches(current, "shared-é😀", "", 0, 0, 0)) goto cleanup;
    symbols->DisposeStablePointer(current.pinned); current.pinned = NULL;
    puts("PASS:persistent-state");

    current = API.NativeShellSession.invoke(session, "false", MILLIS(5000), MILLIS(5000), limits);
    if (!result_matches(current, "", "", 1, 0, 0)) goto cleanup;
    symbols->DisposeStablePointer(current.pinned); current.pinned = NULL;
    puts("PASS:nonzero-status");

    current = API.NativeShellSession.invoke(session, "printf reused", MILLIS(5000), MILLIS(5000), limits);
    if (!result_matches(current, "reused", "", 0, 0, 0) ||
        !result_matches(original, "shared-é😀", "shared-error", 0, 0, 0)) goto cleanup;
    symbols->DisposeStablePointer(current.pinned); current.pinned = NULL;
    puts("PASS:reuse-and-result-snapshot");

    small = API.NativeOutputLimits.NativeOutputLimits(10, 6);
    current = API.NativeShellSession.invoke(session, "printf abcdefghijklmnop", MILLIS(5000), MILLIS(5000), small);
    if (!result_matches(current, "abcdef", "", -1, 1, 0)) goto cleanup;
    symbols->DisposeStablePointer(current.pinned); current.pinned = NULL;
    API.NativeShellSession.close(session);
    symbols->DisposeStablePointer(session.pinned); session.pinned = NULL;
    puts("PASS:output-limit");

    session = API.NativeShellSession.NativeShellSession(argv[2]);
    current = API.NativeShellSession.invoke(session, "sleep 30", MILLIS(500), MILLIS(5000), limits);
    if (!result_matches(current, "", "", -1, 0, 1)) goto cleanup;
    symbols->DisposeStablePointer(current.pinned); current.pinned = NULL;
    API.NativeShellSession.close(session);
    symbols->DisposeStablePointer(session.pinned); session.pinned = NULL;
    puts("PASS:wall-deadline");

    session = API.NativeShellSession.NativeShellSession(argv[2]);
    API.NativeShellSession.close(session);
    API.NativeShellSession.close(session);
    symbols->DisposeStablePointer(session.pinned); session.pinned = NULL;
    puts("PASS:idempotent-close-and-disposal");
    outcome = 0;

cleanup:
    if (session.pinned) {
        API.NativeShellSession.close(session);
        symbols->DisposeStablePointer(session.pinned);
    }
    if (current.pinned) symbols->DisposeStablePointer(current.pinned);
    if (original.pinned) symbols->DisposeStablePointer(original.pinned);
    if (small.pinned) symbols->DisposeStablePointer(small.pinned);
    if (limits.pinned) symbols->DisposeStablePointer(limits.pinned);
    if (outcome) fputs("Shared API control failed\n", stderr);
    /* Keep the runtime loaded for the lifetime of the embedding process. */
    return outcome;
}
