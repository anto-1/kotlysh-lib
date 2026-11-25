package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Timeout
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Timeout(25)
class RuntimeResponseOwnershipTest {
    @Test
    fun `retained response objects reject mutation through every collection view`(): Unit = runBlocking {
        for (spec in listOf(RuntimeSpecs.Python, RuntimeSpecs.Node)) {
            runtime(spec) {
                val scope = StructuredRuntimeScope(coroutineContext, runtimeContext)
                val expression = if (spec == RuntimeSpecs.Python) "{'rows': [{'label': 'original'}]}" else "({rows: [{label: 'original'}]})"
                val evaluation = scope.evaluateJson(expression)
                val response = checkNotNull(evaluation.execution.response)
                val original = response.toJsonWireString()
                assertReadOnlyObject(response)
                assertReadOnlyObject(evaluation.value.jsonObject)
                assertReadOnlyObject(evaluation.value.jsonObject.getValue("rows").jsonArray.single().jsonObject)
                assertEquals(original, response.toJsonWireString())
                assertEquals(42, scope.eval<Int>("42"))
            }
        }
    }

    @Test
    fun `returned JSON arrays reject view mutations while decoded Kotlin lists stay mutable`(): Unit = runBlocking {
        for (spec in listOf(RuntimeSpecs.Python, RuntimeSpecs.Node)) {
            runtime(spec) {
                val scope = StructuredRuntimeScope(coroutineContext, runtimeContext)
                val evaluation = scope.evaluate<JsonElement>("[[1, 2], [3, 4]]")
                val response = checkNotNull(evaluation.execution.response)
                val original = response.toJsonWireString()
                assertReadOnlyArray(evaluation.value.jsonArray)
                evaluation.value.jsonArray.forEach { assertReadOnlyArray(it.jsonArray) }
                assertEquals(original, response.toJsonWireString())
                val decoded = scope.eval<MutableList<Int>>("[1, 2]")
                decoded += 3
                assertEquals(listOf(1, 2, 3), decoded)
                assertEquals(listOf(1, 2), scope.eval<List<Int>>("[1, 2]"))
            }
        }
    }

    @Test
    fun `unknown response metadata is read only without applying Node request number restrictions`(): Unit = runBlocking {
        val json = """{"version":1,"kind":"unit","future":{"numbers":[1e400,9007199254740993],"rows":[{"label":"original"}]}}"""
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val spec = RuntimeSpec("node", RuntimeSpecs.Bash.command) { _, marker ->
            "printf '%s\\n' '$marker:0:$encoded'; printf '%s\\n' '$marker:0' >&2\n"
        }
        runtime(spec) {
            repeat(2) {
                val result = "ignored"()
                assertTrue(result.ok)
                val response = checkNotNull(result.response)
                val original = response.toJsonWireString()
                val future = response.getValue("future").jsonObject
                assertEquals("1e400", future.getValue("numbers").jsonArray[0].toString())
                assertEquals("9007199254740993", future.getValue("numbers").jsonArray[1].toString())
                assertReadOnlyObject(future)
                assertReadOnlyArray(future.getValue("numbers").jsonArray)
                assertReadOnlyObject(future.getValue("rows").jsonArray.single().jsonObject)
                assertEquals(original, response.toJsonWireString())
                assertTrue(runtime.isOpen)
            }
        }
    }

    private fun assertReadOnlyObject(value: JsonObject) {
        val member = value.entries.first()
        assertFailsWith<UnsupportedOperationException> { (member as MutableMap.MutableEntry<String, JsonElement>).setValue(JsonNull) }
        assertFailsWith<UnsupportedOperationException> { (value.keys as MutableSet<String>).remove(member.key) }
        assertFailsWith<UnsupportedOperationException> { (value.values as MutableCollection<JsonElement>).clear() }
        val iterator = value.entries.iterator() as MutableIterator<Map.Entry<String, JsonElement>>
        iterator.next()
        assertFailsWith<UnsupportedOperationException> { iterator.remove() }
    }

    private fun assertReadOnlyArray(value: JsonArray) {
        val iterator = value.iterator() as MutableIterator<JsonElement>
        iterator.next()
        assertFailsWith<UnsupportedOperationException> { iterator.remove() }
        assertFailsWith<UnsupportedOperationException> { (value.subList(0, 1) as MutableList<JsonElement>)[0] = JsonPrimitive("changed") }
        assertFailsWith<UnsupportedOperationException> { (value.listIterator() as MutableListIterator<JsonElement>).add(JsonNull) }
    }
}
