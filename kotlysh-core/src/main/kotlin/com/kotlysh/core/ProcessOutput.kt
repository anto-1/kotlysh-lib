package com.kotlysh.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Reads available bytes without waiting for pipes retained by an orphaned child.
 * After the owned root exits, immediately available output is drained and the
 * text decoder is finished. A silent pipe is polled only while the root is alive.
 * Availability cannot distinguish a closed pipe from a silent live-root pipe;
 * that case remains subject to the caller's execution/idle deadline.
 * On cancellation with a live root, stream ownership remains with the caller
 * so closing a pipe cannot make the root exit before its descendants are captured.
 */
internal suspend fun readOwnedProcessText(
    process: Process,
    input: InputStream,
    isTerminating: () -> Boolean = { false },
    onText: suspend (String) -> Unit,
) {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val bytes = ByteBuffer.allocate(4096 + 4)
    val characters = CharBuffer.allocate(4096 + 4)

    fun nativeRead(operation: () -> Int): Int = try {
        operation()
    } catch (error: IOException) {
        if (!process.isAlive || isTerminating()) -1 else throw error
    }

    suspend fun emitCharacters() {
        characters.flip()
        if (characters.hasRemaining()) onText(characters.toString())
        characters.clear()
    }

    suspend fun decode(endOfInput: Boolean) {
        bytes.flip()
        while (true) {
            val result = decoder.decode(bytes, characters, endOfInput)
            emitCharacters()
            if (result.isError) result.throwException()
            if (!result.isOverflow) break
        }
        bytes.compact()
    }

    val closeAfterRootExit = Closeable { if (!process.isAlive) input.close() }
    closeAfterRootExit.use {
        var bytesRemainingAtExit: Int? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            if (bytesRemainingAtExit == null && !process.isAlive) {
                // Freeze the drain budget; later output from detached children is excluded.
                bytesRemainingAtExit = nativeRead { input.available() }
                if (bytesRemainingAtExit < 0) break
            }
            val ready = nativeRead { input.available() }
            if (ready < 0) break
            val available = bytesRemainingAtExit?.let { minOf(it, ready) } ?: ready
            if (available == 0) {
                if (bytesRemainingAtExit != null) break
                if (!process.isAlive) {
                    // Root exit can race the first availability check and its final write.
                    bytesRemainingAtExit = nativeRead { input.available() }
                    if (bytesRemainingAtExit < 0) break
                    continue
                }
                delay(10)
                continue
            }
            val size = nativeRead {
                input.read(bytes.array(), bytes.position(), minOf(available, 4096, bytes.remaining()))
            }
            if (size < 0) break
            if (size == 0) {
                delay(10)
                continue
            }
            bytes.position(bytes.position() + size)
            bytesRemainingAtExit = bytesRemainingAtExit?.minus(size)
            decode(endOfInput = false)
        }
        decode(endOfInput = true)
        while (true) {
            val result = decoder.flush(characters)
            emitCharacters()
            if (result.isError) result.throwException()
            if (!result.isOverflow) break
        }
    }
}
