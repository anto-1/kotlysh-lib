package com.kotlysh.nativesh

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SIGKILL
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.closedir
import platform.posix.kill
import platform.posix.mkdtemp
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.usleep
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Linux fixture ownership uses both PID and birth time, including during cleanup. */
@OptIn(ExperimentalForeignApi::class)
internal data class NativeTestProcess(val pid: Int, val birth: String) {
    fun running(): Boolean {
        val stat = readNativeTestFile("/proc/$pid/stat") ?: return false
        val fields = stat.substringAfterLast(") ").trim().split(' ')
        return fields.getOrNull(19) == birth && fields[0] !in listOf("Z", "X")
    }

    fun assertStopped() {
        val started = TimeSource.Monotonic.markNow()
        while (running() && started.elapsedNow() < 1.seconds) usleep(10_000u)
        assertFalse(running(), "Owned process $pid remained running")
    }

    fun assertReaped() {
        val stat = readNativeTestFile("/proc/$pid/stat")
        val fields = stat?.substringAfterLast(") ")?.trim()?.split(' ')
        assertFalse(fields?.getOrNull(19) == birth, "Owned root $pid was not reaped")
    }

    fun cleanup() {
        if (running()) kill(pid, SIGKILL)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun readNativeTestFile(path: String): String? {
    val file = fopen(path, "r") ?: return null
    return try {
        val bytes = ByteArray(4096)
        val text = StringBuilder()
        while (text.length < 8192) {
            if (!bytes.usePinned { fgets(it.addressOf(0), bytes.size, file) != null }) break
            text.append(bytes.decodeToString().substringBefore('\u0000'))
        }
        text.toString()
    } finally { fclose(file) }
}

@OptIn(ExperimentalForeignApi::class)
internal fun nativeTestDescriptorCount(): Int {
    val directory = checkNotNull(opendir("/proc/self/fd"))
    return try {
        var count = 0
        while (true) {
            val entry = readdir(directory) ?: break
            if (entry.pointed.d_name.toKString() !in listOf(".", "..")) count++
        }
        count
    } finally { closedir(directory) }
}

@OptIn(ExperimentalForeignApi::class)
internal fun nativeTestTempDirectory(label: String): String {
    val template = "/tmp/kotlysh-native-$label.XXXXXX\u0000".encodeToByteArray()
    return template.usePinned { checkNotNull(mkdtemp(it.addressOf(0))).toKString() }
}

@OptIn(ExperimentalForeignApi::class)
internal fun writeNativeTestFile(path: String, text: String) {
    val file = checkNotNull(fopen(path, "w"))
    try { check(fputs(text, file) >= 0) } finally { fclose(file) }
}
