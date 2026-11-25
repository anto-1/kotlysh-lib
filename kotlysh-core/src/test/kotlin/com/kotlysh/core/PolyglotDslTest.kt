package com.kotlysh.core

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.Base64
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PolyglotDslTest {
    @Test
    fun `shell python and node can form a data pipeline`(): Unit = runBlocking {
        var csv = ""
        sh {
            csv = """
                cat <<'EOF'
                alpha,3
                beta,5
                alpha,7
                EOF
            """.trimIndent()().stdout.joinToString("\n")
        }

        val csv64 = csv.base64()
        var totalsJson = ""
        py {
            totalsJson = """
                import base64
                import csv
                import io
                import json

                raw = base64.b64decode("$csv64").decode()
                totals = {}
                for name, value in csv.reader(io.StringIO(raw)):
                    totals[name] = totals.get(name, 0) + int(value)
                print(json.dumps(totals, sort_keys=True))
            """.trimIndent()().stdout.first()
        }

        val totals64 = totalsJson.base64()
        var summary = ""
        node {
            summary = """
                const json = Buffer.from("$totals64", "base64").toString("utf8");
                const totals = JSON.parse(json);
                console.log(Object.entries(totals).map(([k, v]) => `${'$'}{k}=${'$'}{v}`).join(","));
            """.trimIndent()().stdout.first()
        }

        assertEquals("alpha=10,beta=5", summary)
    }

    @Test
    fun `each runtime preserves its own session state`(): Unit = runBlocking {
        var shellState = ""
        sh {
            "cd /tmp"()
            "export KOTLYSH_POLYGLOT_MODE=shell"()
            shellState = "printf '%s:%s\\n' \"\$PWD\" \"\$KOTLYSH_POLYGLOT_MODE\""().stdout.first()
        }

        var pythonState = ""
        py {
            "values = [2, 3, 5]"()
            "values.append(7)"()
            pythonState = "print(sum(values))"().stdout.first()
        }

        var nodeState = ""
        node {
            "globalThis.values = [11, 13]"()
            "globalThis.values.push(17)"()
            nodeState = "console.log(globalThis.values.reduce((a, b) => a + b, 0))"().stdout.first()
        }

        assertEquals("/tmp:shell", shellState)
        assertEquals("17", pythonState)
        assertEquals("41", nodeState)
    }

    @Test
    fun `independent runtime sessions can be orchestrated concurrently`(): Unit = runBlocking {
        var shellResult = ""
        var pythonResult = ""
        var nodeResult = ""

        val shellJob = async {
            sh {
                shellResult = "sleep 0.05; printf 'shell:%s\\n' ready"(
                    timeout = 2.seconds,
                    idleTimeout = 1.seconds,
                ).stdout.first()
            }
        }

        val pythonJob = async {
            py {
                pythonResult = "print('python:' + str(sum(range(6))))"().stdout.first()
            }
        }

        val nodeJob = async {
            node {
                nodeResult = "console.log('node:' + [1, 2, 3, 4].reduce((a, b) => a + b, 0))"().stdout.first()
            }
        }

        shellJob.await()
        pythonJob.await()
        nodeJob.await()

        assertEquals("shell:ready", shellResult)
        assertEquals("python:15", pythonResult)
        assertEquals("node:10", nodeResult)
    }

    @Test
    fun `node can consume shell produced file metadata transformed by python`(): Unit = runBlocking {
        var fileList = ""
        sh {
            val root = Files.createTempDirectory("kotlysh-polyglot-")
            println("Task-owned fixture directory: $root (alpha.txt and beta.txt)")
            try {
                "cd ${shLiteral(root.toString())}"()
                "printf 'one' > alpha.txt"()
                "printf 'three' > beta.txt"()
                fileList = "find . -type f -name '*.txt' -printf '%f:%s\\n' | sort"().stdout.joinToString("\n")
            } finally {
                Files.deleteIfExists(root.resolve("alpha.txt"))
                Files.deleteIfExists(root.resolve("beta.txt"))
                Files.delete(root)
            }
        }

        val fileList64 = fileList.base64()
        var recordsJson = ""
        py {
            recordsJson = """
                import base64
                import json

                raw = base64.b64decode("$fileList64").decode()
                records = []
                for line in raw.splitlines():
                    name, size = line.split(":")
                    records.append({"name": name, "size": int(size)})
                print(json.dumps(records, sort_keys=True))
            """.trimIndent()().stdout.first()
        }

        val records64 = recordsJson.base64()
        var formatted = ""
        node {
            formatted = """
                const records = JSON.parse(Buffer.from("$records64", "base64").toString("utf8"));
                const total = records.reduce((sum, r) => sum + r.size, 0);
                console.log(records.map(r => r.name).join("|") + ":" + total);
            """.trimIndent()().stdout.first()
        }

        assertEquals("alpha.txt|beta.txt:8", formatted)
    }

    @Test
    fun `polyglot pipeline keeps stderr and nonzero shell status explicit`(): Unit = runBlocking {
        var failed: RuntimeResult? = null
        var recovery = ""

        sh {
            failed = "printf 'diagnostic\\n' >&2; false"()
            recovery = "printf 'recovered\\n'"().stdout.first()
        }

        assertEquals(1, failed?.exitCode)
        assertEquals(listOf("diagnostic"), failed?.stderr)
        assertTrue(failed?.ok == false)
        assertEquals("recovered", recovery)
    }

    private fun String.base64(): String {
        return Base64.getEncoder().encodeToString(toByteArray(Charsets.UTF_8))
    }
}
