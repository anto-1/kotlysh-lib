package com.kotlysh.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeJsonSnapshotTest {
    @Test
    fun `queued bindings and stores detach nested caller containers and repeated aliases`(): Unit = runBlocking {
        eachRuntime {
            for (operation in Submission.entries) {
                val items = mutableListOf<JsonElement>(JsonPrimitive(1), JsonPrimitive("before"))
                val sharedBacking = linkedMapOf<String, JsonElement>("items" to JsonArray(items))
                val shared = JsonObject(sharedBacking)
                val backing = linkedMapOf<String, JsonElement>("left" to shared, "right" to shared)
                val input = JsonObject(backing)
                val expected = Json.parseToJsonElement(
                    """{"left":{"items":[1,"before"]},"right":{"items":[1,"before"]}}""",
                )

                withOccupiedDispatch { release ->
                    val queued = async(start = CoroutineStart.UNDISPATCHED) { submit(operation, input) }
                    items[0] = JsonPrimitive(99)
                    items[1] = JsonPrimitive("after")
                    sharedBacking["added"] = JsonPrimitive(true)
                    backing["other"] = JsonPrimitive(false)
                    release()

                    assertEquals(expected, queued.await())
                }
                assertEquals(42, eval<Int>("42"))
            }
        }
    }

    @Test
    fun `caller mutation to a nonfinite number cannot corrupt a queued request or close its session`(): Unit = runBlocking {
        eachRuntime {
            for (operation in Submission.entries) {
                val backing = linkedMapOf<String, JsonElement>("number" to JsonPrimitive(1))
                val input = JsonObject(backing)

                withOccupiedDispatch { release ->
                    val queued = async(start = CoroutineStart.UNDISPATCHED) { submit(operation, input) }
                    backing["number"] = JsonPrimitive(Double.NaN)
                    release()

                    assertEquals(Json.parseToJsonElement("""{"number":1}"""), queued.await())
                }
                assertEquals(42, eval<Int>("42"))
            }
        }
    }

    @Test
    fun `a lazy JSON argument observes deliberate edits before each submission`(): Unit = runBlocking {
        eachRuntime {
            val backing = linkedMapOf<String, JsonElement>("number" to JsonPrimitive(1))
            val argument = jsonValue(JsonObject(backing))

            backing["number"] = JsonPrimitive(2)
            assertEquals(Json.parseToJsonElement("""{"number":2}"""), evaluateJson("payload", "payload" to argument).value)
            backing["number"] = JsonPrimitive(3)
            assertEquals(Json.parseToJsonElement("""{"number":3}"""), evaluateJson("payload", "payload" to argument).value)
        }
    }

    private enum class Submission { Binding, Store }

    private suspend fun StructuredRuntimeScope.submit(operation: Submission, input: JsonElement): JsonElement =
        when (operation) {
            Submission.Binding -> evaluateJson("payload", "payload" to jsonValue(input)).value
            Submission.Store -> storeJson(input) { readJson(it) }
        }

    private suspend fun <T> StructuredRuntimeScope.withOccupiedDispatch(block: suspend (() -> Unit) -> T): T = coroutineScope {
        val directory = Files.createTempDirectory("kotlysh-json-snapshot-")
        val released = directory.resolve("released")
        val ready = CompletableDeferred<Unit>()
        val script = if (runtime.name == "py") {
            """
                import os as snapshot_os, time as snapshot_time
                print('admitted', flush=True)
                while not snapshot_os.path.exists(${pythonLiteral(released.toString())}):
                    snapshot_time.sleep(0.01)
            """.trimIndent()
        } else {
            """
                console.log('admitted');
                (async () => {
                    const fs = require('fs');
                    while (!fs.existsSync(${jsLiteral(released.toString())})) {
                        await new Promise(resolve => setTimeout(resolve, 10));
                    }
                })();
            """.trimIndent()
        }
        val active = async {
            val result = runtime.exec(script, timeout = 5.seconds, output = {
                if (it.text == "admitted") ready.complete(Unit)
            })
            assertTrue(result.ok)
        }
        val release = { Files.writeString(released, "released"); Unit }
        try {
            withTimeout(5.seconds) { ready.await() }
            block(release)
        } finally {
            try {
                release()
                active.await()
            } finally {
                Files.deleteIfExists(released)
                Files.deleteIfExists(directory)
            }
        }
    }

    private suspend fun eachRuntime(block: suspend StructuredRuntimeScope.() -> Unit) {
        py { block() }
        node { block() }
    }
}
