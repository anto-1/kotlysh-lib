package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PublicDslSurfaceTest {
    private data class JvmSignature(
        val result: Class<*>,
        val parameters: List<Class<*>>,
    )

    @Test
    fun `the public runtime facade has exactly five suspend and five launch entries`() {
        val function = kotlin.jvm.functions.Function2::class.java
        val continuation = Continuation::class.java
        val context = CoroutineContext::class.java
        val scope = CoroutineScope::class.java
        val expected = mapOf(
            "runtime" to JvmSignature(Any::class.java,
                listOf(RuntimeSpec::class.java, context, function, continuation)),
            "sh" to JvmSignature(Any::class.java, listOf(context, function, continuation)),
            "py" to JvmSignature(Any::class.java, listOf(context, function, continuation)),
            "node" to JvmSignature(Any::class.java, listOf(context, function, continuation)),
            "ruby" to JvmSignature(Any::class.java, listOf(context, function, continuation)),
            "launchRuntime" to JvmSignature(Job::class.java,
                listOf(scope, RuntimeSpec::class.java, context, function)),
            "launchSh" to JvmSignature(Job::class.java, listOf(scope, context, function)),
            "launchPy" to JvmSignature(Job::class.java, listOf(scope, context, function)),
            "launchNode" to JvmSignature(Job::class.java, listOf(scope, context, function)),
            "launchRuby" to JvmSignature(Job::class.java, listOf(scope, context, function)),
        )
        val methods = Class.forName("com.kotlysh.core.RuntimeDslKt").declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) && !it.isSynthetic
        }
        val actual = methods.associate { method ->
            method.name to JvmSignature(method.returnType, method.parameterTypes.toList())
        }

        assertEquals(expected.size, methods.size, "Overloaded or additional public runtime entries changed the facade")
        assertEquals(expected, actual)
    }

    @Test
    fun `built in runtime names and public diagnostic boundary stay canonical`() {
        assertEquals(
            listOf("sh", "py", "node", "ruby"),
            listOf(RuntimeSpecs.Bash.name, RuntimeSpecs.Python.name, RuntimeSpecs.Node.name, RuntimeSpecs.Ruby.name),
        )
        assertFalse(RuntimeResult::class.java.methods.any { it.name == "getResponse" },
            "The internal response envelope must not become a public RuntimeResult property")
        assertEquals(RuntimeResult::class.java,
            RuntimeCallException::class.java.getMethod("getExecution").returnType)
        assertEquals(String::class.java,
            RuntimeCallException::class.java.getMethod("getErrorType").returnType)
        assertEquals(String::class.java,
            RuntimeCallException::class.java.getMethod("getErrorMessage").returnType)
    }
}

/** These wrappers make the Kotlin receiver and generic-result contract a compile-time fixture. */
@Suppress("unused")
private object ExactKotlinRuntimeEntries {
    suspend fun generic(
        spec: RuntimeSpec,
        context: CoroutineContext,
        block: suspend RuntimeScope.() -> String,
    ): String = runtime(spec, context, block)

    suspend fun shell(context: CoroutineContext, block: suspend ShScope.() -> String): String =
        sh(context, block)

    suspend fun python(context: CoroutineContext, block: suspend PyScope.() -> String): String =
        py(context, block)

    suspend fun javascript(context: CoroutineContext, block: suspend NodeScope.() -> String): String =
        node(context, block)

    suspend fun ruby(context: CoroutineContext, block: suspend RubyScope.() -> String): String =
        com.kotlysh.core.ruby(context, block)

    fun genericLaunch(
        owner: CoroutineScope,
        spec: RuntimeSpec,
        context: CoroutineContext,
        block: suspend RuntimeScope.() -> Unit,
    ): Job = owner.launchRuntime(spec, context, block)

    fun shellLaunch(
        owner: CoroutineScope,
        context: CoroutineContext,
        block: suspend ShScope.() -> Unit,
    ): Job = owner.launchSh(context, block)

    fun pythonLaunch(
        owner: CoroutineScope,
        context: CoroutineContext,
        block: suspend PyScope.() -> Unit,
    ): Job = owner.launchPy(context, block)

    fun javascriptLaunch(
        owner: CoroutineScope,
        context: CoroutineContext,
        block: suspend NodeScope.() -> Unit,
    ): Job = owner.launchNode(context, block)

    fun rubyLaunch(
        owner: CoroutineScope,
        context: CoroutineContext,
        block: suspend RubyScope.() -> Unit,
    ): Job = owner.launchRuby(context, block)
}
