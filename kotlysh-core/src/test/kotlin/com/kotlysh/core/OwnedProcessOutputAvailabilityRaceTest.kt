package com.kotlysh.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Timeout
import java.io.FilterInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Timeout(20)
class OwnedProcessOutputAvailabilityRaceTest {
    @Test
    fun `stdout final Unicode write survives root exit after zero availability observation`(): Unit = runBlocking {
        assertFinalWrite(RuntimeStream.Stdout)
    }

    @Test
    fun `stderr final Unicode write survives root exit after zero availability observation`(): Unit = runBlocking {
        assertFinalWrite(RuntimeStream.Stderr)
    }

    private suspend fun assertFinalWrite(stream: RuntimeStream) {
        val directory = Files.createTempDirectory("kotlysh-availability-exit-")
        val ready = directory.resolve("ready")
        val ack = directory.resolve("ack")
        val redirect = if (stream == RuntimeStream.Stderr) " >&2" else ""
        val source = """
            builtin printf ready > ${shLiteral(ready.toString())}
            while [[ ! -e ${shLiteral(ack.toString())} ]]; do builtin :; done
            builtin printf 'exit:'$redirect
            for ((i=0;i<1024;i++)); do builtin printf 'α🙃'$redirect; done
            builtin printf ':tail'$redirect
            exit 0
        """.trimIndent()
        val builder = ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c", source)
        builder.environment().remove("BASH_ENV")
        builder.environment().remove("ENV")
        var process: Process? = null
        try {
            val root = builder.start()
            process = root
            root.outputStream.close()
            assertEquals(ProcessHandle.current().pid(), root.toHandle().parent().orElseThrow().pid())
            withTimeout(5_000) {
                while (!Files.exists(ready)) {
                    assertTrue(root.isAlive, "Native root exited before reaching its acknowledgement loop")
                    delay(5)
                }
            }
            val native = if (stream == RuntimeStream.Stdout) root.inputStream else root.errorStream
            val availability = ArrayList<Int>()
            val reads = ArrayList<Int>()
            val chunks = ArrayList<String>()
            var aliveAtObservation = false
            var exitedBeforeDelivery = false
            var closeCount = 0
            var closedAfterExit = false
            val coordinated = object : FilterInputStream(native) {
                override fun available(): Int {
                    val observed = native.available()
                    availability += observed
                    if (availability.size == 1) {
                        // Only delivery of the authentic query result is delayed.
                        // Native bytes, reads, liveness and EOF remain real JDK pipe operations.
                        assertEquals(0, observed)
                        aliveAtObservation = root.isAlive
                        assertTrue(aliveAtObservation)
                        assertEquals(0L, root.toHandle().descendants().use { it.count() })
                        Files.writeString(ack, "continue")
                        assertTrue(root.waitFor(5, TimeUnit.SECONDS), "Native final writer did not exit")
                        assertEquals(0, root.exitValue())
                        exitedBeforeDelivery = !root.isAlive
                        assertTrue(exitedBeforeDelivery)
                    }
                    return observed
                }

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    native.read(bytes, offset, length).also { reads += it }

                override fun close() {
                    closeCount++
                    closedAfterExit = !root.isAlive
                    native.close()
                }
            }
            readOwnedProcessText(root, coordinated) { chunks += it }
            val expected = "exit:" + "α🙃".repeat(1024) + ":tail"
            val utf8 = expected.toByteArray(Charsets.UTF_8)
            assertTrue(aliveAtObservation && exitedBeforeDelivery)
            assertEquals(expected, chunks.joinToString(""))
            assertTrue(chunks.size >= 2)
            assertTrue(chunks.all { Charsets.UTF_8.newEncoder().canEncode(it) })
            assertEquals(4096, reads.first())
            assertEquals(utf8.size, reads.sum())
            assertTrue(availability.drop(1).any { it == utf8.size })
            // The actual first read ends inside a supplementary UTF-8 scalar.
            assertFailsWith<CharacterCodingException> {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(utf8, 0, reads.first()))
            }
            assertEquals(1, closeCount)
            assertTrue(closedAfterExit)
            assertFalse(root.isAlive)
            assertTrue(currentCoroutineContext().isActive)
            val hash = MessageDigest.getInstance("SHA-256").digest(chunks.joinToString("").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            println("@@kotlysh-availability-exit " + buildJsonObject {
                put("stream", stream.name); put("source", source); put("pid", root.pid())
                put("aliveAtObservation", aliveAtObservation); put("exitedBeforeDelivery", exitedBeforeDelivery)
                put("exitCode", root.exitValue()); put("nativeBytes", utf8.size); put("nativeCharacters", expected.length)
                put("availability", buildJsonArray { availability.forEach { add(it) } })
                put("readSizes", buildJsonArray { reads.forEach { add(it) } }); put("decodedChunks", chunks.size)
                put("sha256", hash); put("closedAfterExit", closedAfterExit); put("closeCount", closeCount)
                put("coordination", "delay delivery of first authentic native available() result until acknowledgement and actual root exit")
            })
        } finally {
            process?.let { root ->
                try {
                    OwnedProcessCleanup.finish(root, emptyList(), graceful = false)
                } finally {
                    listOf(root.outputStream, root.inputStream, root.errorStream).forEach { it.close() }
                }
            }
            directory.toFile().deleteRecursively()
        }
    }
}
