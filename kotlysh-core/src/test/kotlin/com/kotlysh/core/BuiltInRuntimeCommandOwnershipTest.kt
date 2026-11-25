package com.kotlysh.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BuiltInRuntimeCommandOwnershipTest {
    @Test fun `built in Bash command cannot be replaced through JVM list views`() = verify(RuntimeSpecs.Bash, "/bin/bash")
    @Test fun `built in Python command cannot be replaced through JVM list views`() = verify(RuntimeSpecs.Python, "python3")
    @Test fun `built in Node command cannot be replaced through JVM list views`() = verify(RuntimeSpecs.Node, "node")
    @Test fun `built in Ruby command cannot be replaced through JVM list views`() = verify(RuntimeSpecs.Ruby, "ruby")

    private fun verify(spec: RuntimeSpec, executable: String) {
        val command = spec.command
        val original = command.toList()
        assertEquals(executable, original.first())
        try {
            assertFailsWith<UnsupportedOperationException> { (command as MutableList<String>)[0] = "changed-default" }
            val iterator = command.listIterator() as MutableListIterator<String>
            iterator.next()
            assertFailsWith<UnsupportedOperationException> { iterator.set("changed-default") }
            assertFailsWith<UnsupportedOperationException> { (command.subList(0, 1) as MutableList<String>)[0] = "changed-default" }
            assertEquals(original, spec.command)
            assertEquals(original, spec.copy().command)
            assertSame(command, spec.command)
        } finally {
            // Baseline negative runs must not contaminate another test's defaults.
            if (command != original) original.forEachIndexed { index, argument -> (command as MutableList<String>)[index] = argument }
        }
    }
}
