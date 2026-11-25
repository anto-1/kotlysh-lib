@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.kotlysh.core

import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

class RuntimeExpressionRequestTest {
    private fun prepare(
        vararg bindings: Pair<String, RuntimeArgument>,
        expression: String = "payload",
        operation: String = "eval",
        policy: RuntimeJsonNumberPolicy = RuntimeJsonNumberPolicy.Json,
    ) = RuntimeExpressionRequest.prepare(operation, expression, bindings, policy)

    @Test
    fun wireDataSeparatesExactExecutableSourceFromBoundValues() {
        val expression = "('雪🙂', '\"\\\\', payload)\n"
        val argument = jsonValue(JsonPrimitive("\u0000\uD800\"\\\n"))
        for (operation in listOf("eval", "hold")) {
            val prepared = prepare("payload" to argument, expression = expression, operation = operation)
            assertEquals(listOf("op", "code", "bindings"), prepared.payload.keys.toList())
            assertEquals(operation, prepared.payload.getValue("op").jsonPrimitive.content)
            val source = Base64.getDecoder().decode(prepared.payload.getValue("code").jsonPrimitive.content)
            assertEquals(expression, source.toString(Charsets.UTF_8))
            assertEquals(argument.json, prepared.payload.getValue("bindings").jsonObject.getValue("payload").jsonObject["json"])
            assertTrue(prepared.references.isEmpty())
        }
        assertTrue(prepare().payload.getValue("bindings").jsonObject.isEmpty())
    }

    @Test
    fun duplicateNamesAreRejectedBeforeExecutableSourceNamesOrJson() {
        val invalid = jsonValue(JsonPrimitive(Double.NaN))
        val failure = assertFailsWith<IllegalArgumentException> {
            prepare("bad-name" to invalid, "bad-name" to invalid, expression = "\uD800")
        }
        assertEquals("Duplicate binding names", failure.message)
        assertEquals("Duplicate binding names", assertFailsWith<IllegalArgumentException> {
            prepare("a" to invalid, "b" to invalid, "a" to invalid)
        }.message)
    }

    @Test
    fun sourceAndIndividualBindingsKeepTheirOriginalRejectionOrder() {
        val invalid = jsonValue(JsonPrimitive(Double.NaN))
        assertTrue(assertFailsWith<IllegalArgumentException> {
            prepare("bad-name" to invalid, expression = "\uD800")
        }.message!!.startsWith("Executable runtime source contains an unpaired"))
        assertEquals("Invalid binding name: bad-name", assertFailsWith<IllegalArgumentException> {
            prepare("bad-name" to invalid)
        }.message)
        assertEquals("JSON numbers must use standard JSON syntax", assertFailsWith<IllegalArgumentException> {
            prepare("valid" to invalid, "bad-name" to invalid)
        }.message)
    }

    @Test
    fun bindingNamesUseTheExistingAsciiGrammarIncludingItsBoundaries() {
        val argument = jsonValue(JsonPrimitive(true))
        for (name in listOf("_", "a", "Z", "_0", "a0_Z9", "zA9_")) {
            assertEquals(setOf(name), prepare(name to argument).payload.getValue("bindings").jsonObject.keys)
        }
        for (name in listOf("", "0", "0a", "a-b", "a.b", "a b", "a\n", "a\r", "a\t", "é", "雪", "K", "a\u0000", "a😀")) {
            assertEquals("Invalid binding name: $name", assertFailsWith<IllegalArgumentException> {
                prepare(name to argument)
            }.message)
        }
    }

    @Test
    fun capturedJsonUsesTheSuppliedNumberPolicy() {
        val wide = JsonUnquotedLiteral("123456789012345678901234567890")
        for (policy in listOf(RuntimeJsonNumberPolicy.Json, RuntimeJsonNumberPolicy.Python)) {
            assertEquals(wide, prepare("value" to jsonValue(wide), policy = policy)
                .payload.getValue("bindings").jsonObject.getValue("value").jsonObject["json"])
        }
        assertFailsWith<IllegalArgumentException> {
            prepare("value" to jsonValue(wide), policy = RuntimeJsonNumberPolicy.JavaScript)
        }
        val overflow = jsonValue(JsonUnquotedLiteral("1e309"))
        prepare("value" to overflow, policy = RuntimeJsonNumberPolicy.Json)
        assertFailsWith<IllegalArgumentException> { prepare("value" to overflow, policy = RuntimeJsonNumberPolicy.Python) }
        assertFailsWith<IllegalArgumentException> { prepare("value" to overflow, policy = RuntimeJsonNumberPolicy.JavaScript) }
        for (policy in RuntimeJsonNumberPolicy.entries) {
            assertFailsWith<IllegalArgumentException> {
                prepare("value" to jsonValue(JsonUnquotedLiteral("01")), policy = policy)
            }
        }
    }

    @Test
    fun requestSnapshotsDetachNestedAliasesAndExposeReadOnlyCollectionViews() {
        val items = mutableListOf<JsonElement>(JsonPrimitive(1))
        val sharedBacking = linkedMapOf<String, JsonElement>("items" to JsonArray(items))
        val shared = JsonObject(sharedBacking)
        val backing = linkedMapOf<String, JsonElement>("left" to shared, "right" to shared)
        val argument = jsonValue(JsonObject(backing))
        val prepared = prepare("payload" to argument)
        items[0] = JsonPrimitive(2)
        sharedBacking["later"] = JsonPrimitive(true)
        backing.clear()
        val bindings = prepared.payload.getValue("bindings").jsonObject
        val member = bindings.getValue("payload").jsonObject
        val snapshot = member.getValue("json").jsonObject
        assertEquals(Json.parseToJsonElement("""{"left":{"items":[1]},"right":{"items":[1]}}"""), snapshot)
        assertEquals(JsonObject(emptyMap()), prepare("payload" to argument).payload.getValue("bindings").jsonObject
            .getValue("payload").jsonObject["json"])
        for (map in listOf(prepared.payload, bindings, member, snapshot, snapshot.getValue("left").jsonObject)) {
            assertFailsWith<UnsupportedOperationException> { (map.keys as MutableSet<String>).clear() }
            assertFailsWith<UnsupportedOperationException> {
                (map.entries.first() as MutableMap.MutableEntry<String, JsonElement>).setValue(JsonNull)
            }
        }
        val array = snapshot.getValue("left").jsonObject.getValue("items").jsonArray
        val iterator = array.listIterator() as MutableListIterator<JsonElement>
        iterator.next()
        assertFailsWith<UnsupportedOperationException> { iterator.remove() }
    }

    @Test
    fun callerCollectionCallbacksCannotChangeTheCapturedBindingSequence() {
        lateinit var supplied: Array<Pair<String, RuntimeArgument>>
        var armed = false
        val backing = object : AbstractMap<String, JsonElement>() {
            override val entries: Set<Map.Entry<String, JsonElement>>
                get() {
                    if (armed) supplied[1] = "first" to jsonValue(JsonPrimitive(99))
                    return mapOf("nested" to JsonPrimitive(1)).entries
                }
        }
        supplied = arrayOf("first" to jsonValue(JsonObject(backing)), "second" to jsonValue(JsonPrimitive(2)))
        armed = true
        val prepared = RuntimeExpressionRequest.prepare("eval", "first", supplied, RuntimeJsonNumberPolicy.Json)
        val bindings = prepared.payload.getValue("bindings").jsonObject
        assertEquals(listOf("first", "second"), bindings.keys.toList())
        assertEquals(JsonPrimitive(2), bindings.getValue("second").jsonObject["json"])
        assertEquals("first", supplied[1].first)
    }

    @Test
    fun referenceIdentitiesStayOrderedAndLiveAdmissionIsDeferred() {
        fun owner() = ProcessRuntime(
            CoroutineScope(EmptyCoroutineContext), RuntimeSpec("test", listOf("unused"), { _, _ -> "" }),
        )
        val first = RuntimeRef(owner(), "first")
        val second = RuntimeRef(owner(), "second")
        first.markReleased()
        val prepared = prepare(
            "a" to first, "data" to jsonValue(JsonPrimitive(1)), "b" to second, "c" to first,
        )
        assertEquals(3, prepared.references.size)
        assertSame(first, prepared.references[0])
        assertSame(second, prepared.references[1])
        assertSame(first, prepared.references[2])
        assertEquals(JsonPrimitive("first"), prepared.payload.getValue("bindings").jsonObject.getValue("a").jsonObject["ref"])
        assertFailsWith<UnsupportedOperationException> { (prepared.references as MutableList<RuntimeRef>).clear() }
    }

    @Test
    fun jsonCaptureRemainsIterativeAndRejectsCycles() {
        var nested: JsonElement = JsonPrimitive(1)
        repeat(2_000) { nested = JsonArray(listOf(nested)) }
        var snapshot = prepare("data" to jsonValue(nested)).payload.getValue("bindings").jsonObject
            .getValue("data").jsonObject.getValue("json")
        repeat(2_000) { snapshot = snapshot.jsonArray.single() }
        assertEquals(JsonPrimitive(1), snapshot)
        val backing = linkedMapOf<String, JsonElement>()
        val cycle = JsonObject(backing)
        backing["self"] = cycle
        assertEquals("JSON cannot represent cyclic objects", assertFailsWith<IllegalArgumentException> {
            prepare("data" to jsonValue(cycle))
        }.message)
    }
}
