package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(20)
class StringLiteralRegexContextTest {
    @Test
    fun `class declarations do not make string encoders safe inside regex patterns`(): Unit = runBlocking {
        val value = "/; console.log('injected');//"
        node {
            for (prefix in listOf("class Holder {}", "class await {}", "class Holder extends Base {}", "class Holder extends mixin(Base) {}", "class Holder extends (class Parent {}) {}", "class Holder extends class Parent {} {}", "class Holder extends function Base(options = {}) {} {}")) {
                val setup = "const Base = class {}; const mixin = (...args) => Base;"
                val misplaced = "{ $setup $prefix /${jsLiteral(value)}/.test('data');\n }"()
                assertTrue(misplaced.ok, misplaced.stderrText)
                assertEquals(listOf("injected"), misplaced.stdout, prefix)
                val expression = "{ $setup $prefix console.log(${jsLiteral(value)}); }"()
                assertTrue(expression.ok, expression.stderrText)
                assertEquals(listOf(value), expression.stdout, prefix)
            }
        }
    }

    @Test
    fun `method and static block statement regex remains an unsafe string placement`(): Unit = runBlocking {
        val value = "/; console.log('injected');//"
        node {
            for ((opening, call) in listOf(
                "class Holder { run()" to "new Holder().run()",
                "class Holder { static run()" to "Holder.run()",
                "class Holder { async run()" to "new Holder().run()",
                "class Holder { *run()" to "new Holder().run().next()",
                "class Holder { get ready()" to "new Holder().ready",
                "class Holder { ['run']()" to "new Holder().run()",
                "class Holder { static" to "void 0",
                "const holder = { run()" to "holder.run()",
                "const holder = { class()" to "holder.class()",
            )) {
                val misplaced = "{ $opening { {} /${jsLiteral(value)}/.test('data');\n} }; $call; }"()
                assertTrue(misplaced.ok, misplaced.stderrText)
                assertEquals(listOf("injected"), misplaced.stdout, opening)
                val expression = "{ $opening { {} console.log(${jsLiteral(value)}); } }; $call; }"()
                assertTrue(expression.ok, expression.stderrText)
                assertEquals(listOf(value), expression.stdout, opening)
            }
        }
    }

    @Test
    fun `encoded data remains inert in division after class values and class named calls`(): Unit = runBlocking {
        val value = "/; console.log('injected');//"
        node {
            for (expression in listOf("class {}", "class Holder {}", "class Holder extends (class Parent {}) {}", "class Holder extends class Parent {} {}", "class Holder extends function Base() {} {}", "class Holder { run() { return 12; } }", "({ class() { return 12; } }).class()")) {
                val result = "{ const ratio = $expression / ${jsLiteral(value)} / 2; console.log(Number.isNaN(ratio)); }"()
                assertTrue(result.ok, result.stderrText)
                assertEquals(listOf("true"), result.stdout, expression)
                val forCondition = "for (; $expression / ${jsLiteral(value)} / 2;) { console.log('unexpected'); } console.log('inert');"()
                assertTrue(forCondition.ok, forCondition.stderrText)
                assertEquals(listOf("inert"), forCondition.stdout, expression)
            }
        }
    }

    @Test
    fun `JavaScript statement endings do not make string encoding safe inside regex patterns`(): Unit = runBlocking {
        val value = "/; console.log('injected');//"
        node {
            for (prefix in listOf(
                "{}", "{ const nested = { value: '}' }; }", "function run() {}", "async function run() {}",
                "function* run() {}", "async function* run() {}", "; function run() {}", "{} function run() {}",
            )) {
                val misplaced = "$prefix /${jsLiteral(value)}/.test('data');"()
                assertTrue(misplaced.ok, misplaced.stderrText)
                assertEquals(listOf("injected"), misplaced.stdout, prefix)
                val expression = "$prefix console.log(${jsLiteral(value)});"()
                assertTrue(expression.ok, expression.stderrText)
                assertEquals(listOf(value), expression.stdout, prefix)
            }
        }
    }

    @Test
    fun `regex data stays unsafe in nested function and arrow statement blocks`(): Unit = runBlocking {
        val value = "/; console.log('injected');//"
        node {
            for ((opening, call) in listOf(
                "function run()" to "run()",
                "const run = function named()" to "run()",
                "const run = async function* named()" to "run().next()",
                "const run = () =>" to "run()",
            )) for (prefix in listOf("{}", "function inner() {}")) {
                val misplaced = "{ $opening { $prefix /${jsLiteral(value)}/.test('data');\n}; $call; }"()
                assertTrue(misplaced.ok, misplaced.stderrText)
                assertEquals(listOf("injected"), misplaced.stdout, opening)
                val expression = "{ $opening { $prefix console.log(${jsLiteral(value)}); }; $call; }"()
                assertTrue(expression.ok, expression.stderrText)
                assertEquals(listOf(value), expression.stdout, opening)
            }
        }
    }

    @Test
    fun `JavaScript string encoders do not make regex contents safe after control blocks`(): Unit = runBlocking {
        val value = "/; console.log('injected');//"
        node {
            for (prefix in listOf(
                "if (true) {}",
                "if (false) {} else {}",
                "while (false) {}",
                "with ({}) {}",
                "for (; false ;) {}",
                "switch (0) { default: break; }",
                "try {} catch (error) {}",
                "try { throw 1; } catch {}",
                "try {} finally {}",
                "if (true) { const nested = { value: '}' }; }",
            )) {
                val misplaced = "$prefix /${jsLiteral(value)}/.test('data');"()
                assertTrue(misplaced.ok, misplaced.stderrText)
                assertEquals(listOf("injected"), misplaced.stdout, prefix)
                val expression = "$prefix console.log(${jsLiteral(value)});"()
                assertTrue(expression.ok, expression.stderrText)
                assertEquals(listOf(value), expression.stdout, prefix)
            }
        }
    }

    @Test
    fun `async JavaScript iteration does not turn regex contents into a string expression`(): Unit = runBlocking {
        val value = "/; console.log('injected');//"
        node {
            val misplaced = "async function run() { for await (const item of []) {} /${jsLiteral(value)}/.test('data');\n}; run();"()
            assertTrue(misplaced.ok, misplaced.stderrText)
            assertEquals(listOf("injected"), misplaced.stdout)
            val expression = "async function run() { for await (const item of []) {} console.log(${jsLiteral(value)}); }; run();"()
            assertTrue(expression.ok, expression.stderrText)
            assertEquals(listOf(value), expression.stdout)
        }
    }

    @Test
    fun `encoded data stays inert in division after object and function expressions`(): Unit = runBlocking {
        val value = "/; console.log('injected');//"
        node {
            for (expression in listOf("{}", "{ value: 12 }", "function() {}", "function named() {}", "async function named() {}", "function* named() {}", "async function* named() {}", "({ if() { return 12; } }).if()", "({ catch() { return 12; } })?.catch()")) {
                val result = "{ const ratio = $expression / ${jsLiteral(value)} / 2; console.log(Number.isNaN(ratio)); }"()
                assertTrue(result.ok, result.stderrText)
                assertEquals(listOf("true"), result.stdout, expression)
                val forCondition = "for (; $expression / ${jsLiteral(value)} / 2;) { console.log('unexpected'); } console.log('inert');"()
                assertTrue(forCondition.ok, forCondition.stderrText)
                assertEquals(listOf("inert"), forCondition.stdout, expression)
            }
        }
    }
}
