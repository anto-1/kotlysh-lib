package com.kotlysh.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Timeout
import kotlin.test.*

@Timeout(20)
class StructuredRuntimeAdversarialTest {
    @Test
    fun `Python exceptions with broken string conversion retain the session and references`(): Unit = runBlocking {
        py {
            assertTrue("""
                class BrokenError(Exception):
                    def __str__(self):
                        raise RuntimeError('formatting failed')
                def fail():
                    print('before error', end='')
                    raise BrokenError()
            """.trimIndent()().ok)
            store(listOf(7)) { retained ->
                val failure = assertFailsWith<RuntimeCallException> { eval<JsonElement>("fail()") }
                assertEquals(1, failure.execution.exitCode)
                assertFalse(failure.execution.processExited)
                assertEquals(listOf("before error"), failure.execution.stdout)
                assertTrue(failure.message.orEmpty().contains("BrokenError: <exception str() failed>"))
                assertTrue(failure.execution.stderr.isNotEmpty())
                assertEquals(listOf(7), read<List<Int>>(retained))
                assertEquals(42, eval<Int>("42"))
            }
        }
    }

    @Test
    fun `Python exception metaclass cannot break error type reporting`(): Unit = runBlocking {
        py {
            assertTrue("""
                class BrokenTypeMetadata(type):
                    def __getattribute__(cls, name):
                        if name == '__name__':
                            raise RuntimeError('type formatting failed')
                        return super().__getattribute__(name)
                class BrokenError(Exception, metaclass=BrokenTypeMetadata):
                    pass
                def fail():
                    raise BrokenError('original message')
            """.trimIndent()().ok)
            store(listOf(7)) { retained ->
                val failure = assertFailsWith<RuntimeCallException> { eval<JsonElement>("fail()") }
                assertEquals(1, failure.execution.exitCode)
                assertFalse(failure.execution.processExited)
                assertTrue(failure.message.orEmpty().contains("Error: original message"))
                assertTrue(failure.execution.stderr.isNotEmpty())
                assertEquals(listOf(7), read<List<Int>>(retained))
                assertEquals(42, eval<Int>("42"))
            }
        }
    }

    @Test
    fun `Node hostile thrown values keep ordinary diagnostics and live references`(): Unit = runBlocking {
        node {
            val cases = listOf(
                "Object.create(null)" to "Error: <unprintable thrown value>",
                "({get stack() {throw new Error('formatting failed')}, name: 'OddError', message: 'original message'})" to
                    "OddError: original message",
                """({
                    get stack() {throw new Error('bad stack')},
                    get name() {throw new Error('bad name')},
                    get message() {throw new Error('bad message')},
                    [Symbol.toPrimitive]() {throw new Error('bad coercion')}
                })""" to "Error: <unprintable thrown value>",
                "Symbol('reason')" to "Error: Symbol(reason)",
            )
            store(listOf(7)) { retained ->
                for ((error, expected) in cases) {
                    val failure = assertFailsWith<RuntimeCallException> {
                        eval<JsonElement>("(process.stdout.write('before error'), Promise.reject($error))")
                    }
                    assertEquals(1, failure.execution.exitCode)
                    assertFalse(failure.execution.processExited)
                    assertEquals(listOf("before error"), failure.execution.stdout)
                    assertTrue(failure.message.orEmpty().contains(expected), failure.message)
                    assertTrue(failure.execution.stderr.isNotEmpty())
                    assertEquals(listOf(7), read<List<Int>>(retained))
                    assertEquals(42, eval<Int>("42"))
                }
            }
        }
    }

    @Test
    fun `Node rejects counterfeit plain objects without invoking constructor getters`(): Unit = runBlocking {
        node {
            assertTrue("globalThis.constructorReads = 0"().ok)
            val counterfeit = """
                Object.assign(Object.create({
                    get constructor() {constructorReads++; return Object;}
                }), {value: 7})
            """.trimIndent()
            for (expression in listOf(
                counterfeit,
                "Object.assign(Object.create({constructor: {name: 'Object'}}), {value: 7})",
                "new (class Object {constructor() {this.value = 7;}})()",
            )) {
                assertFailsWith<RuntimeCallException> { eval<JsonElement>(expression) }
                assertEquals(0, eval<Int>("constructorReads"))
            }
            assertEquals(mapOf("value" to 7), eval<Map<String, Int>>("({value: 7})"))
            assertEquals(mapOf("value" to 7), eval<Map<String, Int>>("Object.assign(Object.create(null), {value: 7})"))
            // Rejected arbitrary objects remain valid handles, including class names that mimic Object.
            hold("new (class Object {constructor() {this.value = 7;}})()") { reference ->
                assertEquals(7, eval<Int>("item.value", "item" to reference))
            }
        }
    }

    @Test
    fun `Node sparse arrays accessors and symbol keys fail without evaluating getters`(): Unit = runBlocking {
        node {
            assertTrue("globalThis.getterReads = 0"().ok)
            for (expression in listOf(
                "new Array(2)",
                "Object.defineProperty([], '0', {get() {getterReads++; return 7;}, enumerable: true})",
                "({get value() {getterReads++; return 7;}})",
                "({[Symbol('key')]: 7})",
            )) {
                assertFailsWith<RuntimeCallException> { eval<JsonElement>(expression) }
                assertEquals(0, eval<Int>("getterReads"))
            }
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `Node rejects object and array proxies without running codec reflection traps`(): Unit = runBlocking {
        node {
            val cases = listOf(
                Triple(
                    """new Proxy({value: 7}, {
                        getPrototypeOf(target) {proxyReads++; return Reflect.getPrototypeOf(target)},
                        ownKeys(target) {proxyReads++; return Reflect.ownKeys(target)},
                        getOwnPropertyDescriptor(target, key) {proxyReads++; return Reflect.getOwnPropertyDescriptor(target, key)}
                    })""",
                    "({...item})",
                    buildJsonObject { put("value", 7) },
                ),
                Triple(
                    """new Proxy([7], {
                        get(target, key, receiver) {
                            if (key === 'length') proxyReads++;
                            return Reflect.get(target, key, receiver);
                        }
                    })""",
                    "Array.from(item)",
                    buildJsonArray { add(7) },
                ),
            )
            for ((expression, snapshot, expected) in cases) {
                assertTrue("globalThis.proxyReads = 0"().ok)
                hold(expression) { retained ->
                    for (call in listOf<suspend () -> Unit>(
                        { readJson(retained); Unit },
                        { evaluateJson("item", "item" to retained); Unit },
                    )) {
                        val failure = assertFailsWith<RuntimeCallException> { call() }
                        assertFalse(failure.execution.processExited)
                        assertTrue(failure.message.orEmpty().contains("proxies"))
                        assertEquals(0, eval<Int>("proxyReads"))
                    }
                    assertFalse(retained.isReleased)
                    assertEquals(expected, evaluateJson(snapshot, "item" to retained).value)
                    assertTrue(eval<Int>("proxyReads") > 0, "Explicit user conversion should execute the fixture's traps")
                    assertEquals(42, eval<Int>("42"))
                }
            }
        }
    }

    @Test
    fun `locally cyclic JSON is rejected before dispatch while shared aliases remain JSON`(): Unit = runBlocking {
        val objectMembers = mutableMapOf<String, JsonElement>()
        val objectCycle = JsonObject(objectMembers)
        objectMembers["self"] = objectCycle
        val arrayMembers = mutableListOf<JsonElement>()
        val arrayCycle = JsonArray(arrayMembers)
        arrayMembers.add(arrayCycle)
        val shared = buildJsonObject { put("value", 7) }
        val aliases = JsonArray(listOf(shared, shared))
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            eachRuntime {
                for (cycle in listOf(objectCycle, arrayCycle)) {
                    val stored = assertFailsWith<IllegalArgumentException> { storeJson(cycle) {} }
                    val bound = assertFailsWith<IllegalArgumentException> { evaluateJson("payload", "payload" to jsonValue(cycle)) }
                    assertTrue(stored.message.orEmpty().contains("cyclic"))
                    assertTrue(bound.message.orEmpty().contains("cyclic"))
                }
                assertEquals(aliases, evaluateJson("payload", "payload" to jsonValue(aliases)).value)
                assertEquals(42, eval<Int>("42"))
            }
        }
        assertEquals(List(4) { "eval" }, trace.events.filterIsInstance<RuntimeDispatchStarted>().map { it.operation })
    }

    @Test
    fun `supplied non finite JSON is rejected locally without a dispatch`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            eachRuntime {
                for (number in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                    assertFailsWith<IllegalArgumentException> { storeJson(JsonPrimitive(number)) {} }
                    assertFailsWith<IllegalArgumentException> {
                        evaluateJson("number", "number" to jsonValue(JsonPrimitive(number)))
                    }
                }
                assertEquals(42, eval<Int>("42"))
            }
        }
        assertEquals(listOf("eval", "eval"), trace.events.filterIsInstance<RuntimeDispatchStarted>().map { it.operation })
    }

    @Test
    fun `control characters and misleading object keys survive structured copies`(): Unit = runBlocking {
        val controls = (0..31).joinToString("") { it.toChar().toString() } + "\u007f\u2028\u2029😀"
        val input = buildJsonObject {
            put(controls, "@@kotlysh \n:value\u0000" + controls)
            put("__proto__", buildJsonObject { put("polluted", true) })
            put("constructor", "ordinary data")
            put("toJSON", "ordinary data")
        }
        py {
            storeJson(input) { original ->
                node {
                    copy(original) { copied ->
                        assertEquals(input, readJson(copied))
                        assertEquals("ordinary data", eval<String>("payload.constructor", "payload" to copied))
                        assertFalse(eval<Boolean>("({}).polluted === true"))
                    }
                }
                assertEquals(input, readJson(original))
            }
        }
    }

    @Test
    fun `lone UTF16 surrogates remain exact in structured bindings stores and object keys`(): Unit = runBlocking {
        val texts = unusualUtf16Strings()
        val input = buildJsonObject {
            texts.forEachIndexed { index, text -> put("key$index:$text", text) }
        }
        eachRuntime {
            for (text in texts) {
                assertEquals(text.map(Char::code), eval<String>("text", "text" to value(text)).map(Char::code))
            }
            assertEquals(input, evaluateJson("payload", "payload" to jsonValue(input)).value)
            storeJson(input) { stored ->
                val snapshot = readJson(stored).jsonObject
                assertEquals(input.keys.map { it.map(Char::code) }, snapshot.keys.map { it.map(Char::code) })
                assertEquals(input, snapshot)
            }
        }
    }

    @Test
    fun `lone UTF16 surrogate strings and keys survive copies in both directions`(): Unit = runBlocking {
        val text = unusualUtf16Strings().joinToString(" | ")
        val input = buildJsonObject {
            put("key\uD800", text)
            put("key\uDC00", text)
            put("key😀", text)
        }
        eachRuntime {
            store(text) { original ->
                otherRuntime {
                    copy(original) { copied ->
                        assertEquals(text.map(Char::code), read<String>(copied).map(Char::code))
                    }
                }
            }
            storeJson(input) { original ->
                otherRuntime {
                    copy(original) { copied ->
                        val snapshot = readJson(copied).jsonObject
                        assertEquals(input.keys.map { it.map(Char::code) }, snapshot.keys.map { it.map(Char::code) })
                        assertEquals(input, snapshot)
                        otherRuntime {
                            copy(copied) { roundTrip -> assertEquals(input, readJson(roundTrip)) }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a failed copy creates no target store or false lineage and leaves both sessions live`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            py {
                assertTrue("cyclic = []; cyclic.append(cyclic)"().ok)
                hold("cyclic") { original ->
                    node {
                        assertFailsWith<RuntimeCallException> { copy(original) {} }
                        assertTrue(trace.events.filterIsInstance<RuntimeDispatchStarted>().none {
                            it.sessionId == runtime.sessionId && it.operation == "store"
                        })
                        assertEquals(42, eval<Int>("42"))
                    }
                    assertEquals(1, eval<Int>("len(items)", "items" to original))
                    eval<JsonElement>("(items.clear(), items.append(7))[1]", "items" to original)
                    node {
                        copy(original) { copied -> assertEquals(listOf(7), read<List<Int>>(copied)) }
                    }
                    assertEquals(listOf(7), read<List<Int>>(original))
                }
            }
        }
        val source = trace.events.filterIsInstance<RuntimeSessionStarted>().single { it.runtime == "py" }
        val stores = trace.events.filterIsInstance<RuntimeDispatchStarted>().filter { it.operation == "store" }
        assertEquals(1, stores.size)
        assertEquals(listOf(source.sessionId), stores.single().inputSessionIds)
    }

    @Test
    fun `released handles cannot be reused while a foreign global still owns the object`(): Unit = runBlocking {
        eachRuntime {
            val python = runtime.name == "py"
            lateinit var released: RuntimeRef
            store(listOf(7)) { retained ->
                released = retained
                eval<JsonElement>(
                    if (python) "(globals().__setitem__('saved', items), None)[1]" else "(globalThis.saved = items, null)",
                    "items" to retained,
                )
            }
            assertTrue(released.isReleased)
            assertFailsWith<IllegalStateException> { readJson(released) }
            assertFailsWith<IllegalStateException> { eval<JsonElement>("items", "items" to released) }
            hold("saved") { survivingAlias ->
                assertNotEquals(released.id, survivingAlias.id)
                eval<JsonElement>(if (python) "items.append(9)" else "(items.push(9), null)", "items" to survivingAlias)
                assertEquals(listOf(7, 9), read<List<Int>>(survivingAlias))
            }
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `Node concurrent awaited reference mutations remain serialized`(): Unit = runBlocking {
        node {
            store(emptyList<Int>()) { rows ->
                val sizes = (1..12).map { number -> async {
                    eval<Int>(
                        "(await new Promise(resolve => setImmediate(resolve)), rows.push(number))",
                        "rows" to rows,
                        "number" to value(number),
                    )
                } }.awaitAll()
                assertEquals((1..12).toList(), sizes.sorted())
                assertEquals((1..12).toList(), read<List<Int>>(rows).sorted())
            }
        }
    }

    private suspend fun eachRuntime(block: suspend StructuredRuntimeScope.() -> Unit) {
        py { block() }
        node { block() }
    }

    private suspend fun StructuredRuntimeScope.otherRuntime(block: suspend StructuredRuntimeScope.() -> Unit) {
        if (runtime.name == "py") node { block() } else py { block() }
    }

    private fun unusualUtf16Strings(): List<String> = listOf(
        "a\uD800b",
        "a\uDC00b",
        "\uDC00\uD800",
        "\uD800\uD800\uDC00\uDC00",
        "\uDBFF\uDFFF😀",
        "\\uD800 \"quoted\" \\path\r\n\u0000" + (0..31).joinToString("") { it.toChar().toString() },
    )
}
