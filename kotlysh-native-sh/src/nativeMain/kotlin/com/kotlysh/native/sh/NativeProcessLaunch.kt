package com.kotlysh.nativesh

import com.kotlysh.nativesh.posixshim.kotlysh_spawn
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import platform.posix.errno

/** Prepare Kotlin values in the parent; forked children never return to the runtime. */
@OptIn(ExperimentalForeignApi::class)
internal fun nativeLaunch(
    child: NativeOwnedChild,
    executable: String,
    arguments: List<String>,
    cwd: String?,
    environment: Map<String, String>,
    searchPath: Boolean,
    input: NativePipe?,
    output: NativePipe,
    errors: NativePipe,
    checkCancellation: () -> Unit = {},
) = memScoped {
    val executableBytes = executable.cstr
    val cwdBytes = cwd?.cstr
    val argv = allocArray<CPointerVar<ByteVar>>(arguments.size + 2)
    argv[0] = executableBytes.ptr
    arguments.forEachIndexed { index, argument -> argv[index + 1] = argument.cstr.ptr }
    argv[arguments.size + 1] = null
    val names = allocArray<CPointerVar<ByteVar>>(environment.size + 1)
    val values = allocArray<CPointerVar<ByteVar>>(environment.size + 1)
    environment.entries.forEachIndexed { index, entry ->
        names[index] = entry.key.cstr.ptr
        values[index] = entry.value.cstr.ptr
    }
    names[environment.size] = null
    values[environment.size] = null
    val cwdError = "kotlysh exec: chdir failed: $cwd\n".cstr
    val execError = "kotlysh exec: exec failed: $executable\n".cstr
    checkCancellation()
    check(kotlysh_spawn(executableBytes.ptr, argv, cwdBytes?.ptr, names, values,
        if (searchPath) 1 else 0, input?.readFd ?: -1, input?.writeFd ?: -1,
        output.readFd, output.writeFd, errors.readFd, errors.writeFd,
        cwdError.ptr, execError.ptr, child.pidPointer) == 0) {
        "Failed to prepare or fork native process: errno=$errno"
    }
    child.establishGroup()
}
