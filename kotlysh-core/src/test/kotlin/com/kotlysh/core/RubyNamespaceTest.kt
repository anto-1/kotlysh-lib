package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class RubyNamespaceTest {
    @Test
    fun `Ruby top-level constants cannot replace transport or exception handling`(): Unit = runBlocking {
        val failures = mutableListOf<String>()
        for (name in listOf("JSON", "Base64", "Encoding", "Exception", "ArgumentError", "Kernel")) {
            try {
                val trace = RuntimeTraceBuffer()
                withRuntimeTrace(trace) {
                    ruby {
                        val rebound = "Object.send(:remove_const, :$name); $name = {marker: 'user data'}; retained = 42; puts $name[:marker]"(timeout = 5.seconds)
                        assertTrue(rebound.ok, "$name: ${rebound.stderrText}")
                        assertEquals(listOf("user data"), rebound.stdout)
                        val error = "raise 'namespace:café 😀'"(timeout = 5.seconds)
                        assertFalse(error.ok)
                        assertFalse(error.processExited)
                        assertTrue(runtime.isOpen)
                        assertEquals(listOf("42|user data"), "puts [retained, $name[:marker]].join('|')"(timeout = 5.seconds).stdout)
                    }
                }
                val error = trace.events.filterIsInstance<RuntimeDispatchCompleted>().single { it.exitCode == 1 }
                assertEquals("RuntimeError", error.errorType)
                assertEquals("namespace:café 😀", error.message)
                assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
            } catch (failure: AssertionError) {
                failures += "$name: ${failure.message}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `Ruby user JSON and Base64 customizations cannot rewrite protocol frames`(): Unit = runBlocking {
        ruby {
            val customized = """
                module JSON
                  def self.parse(*arguments); {user: 'parser'}; end
                  def self.generate(*arguments); 'user JSON'; end
                end
                module Base64
                  def self.strict_decode64(*arguments); 'user decode'; end
                  def self.strict_encode64(*arguments); 'user encode'; end
                end
            """(timeout = 5.seconds)
            assertTrue(customized.ok, customized.stderrText)
            val result = "puts JSON.generate({}); puts JSON.parse('{}')[:user]; puts Base64.strict_decode64(''); puts Base64.strict_encode64('')"(timeout = 5.seconds)
            assertTrue(result.ok, result.stderrText)
            assertEquals(listOf("user JSON", "parser", "user decode", "user encode"), result.stdout)
            assertEquals(listOf("ready"), "puts 'ready'"(timeout = 5.seconds).stdout)
        }
    }

    @Test
    fun `Ruby user Kernel eval customization cannot silently skip later fragments`(): Unit = runBlocking {
        ruby {
            assertTrue("Kernel.define_singleton_method(:eval) { |*arguments| 'user evaluator' }; retained = 42"(timeout = 5.seconds).ok)
            val result = "puts Kernel.eval('ignored'); puts retained; STDERR.write('separate diagnostic')"(timeout = 5.seconds)
            assertTrue(result.ok, result.stderrText)
            assertEquals(listOf("user evaluator", "42"), result.stdout)
            assertEquals(listOf("separate diagnostic"), result.stderr)
        }
    }
}
