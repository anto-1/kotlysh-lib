<p align="center">
  <img src="assets/kotlysh-logo.png" alt="Kotlysh" width="395">
</p>

<h1 align="center">Kotlysh</h1>

<p align="center">
  <b>The universal polyglot DSL, powered by coroutines. Kotlin as the control plane, for every language.</b>
</p>

Kotlysh is an LLM-centric DSL: built for language models to write and for
people to read. I share it because I notice that LLMs tend to produce more
reliable code when they write with it. I have also ported several existing
projects to it, which tends to improve their cohesion, maintainability,
scalability and overall reliability.

Real work spans languages. The shell has the tools, Python has the libraries,
and Kotlin has the types and coroutines to hold a plan together. Yet the glue
between languages tends to be ad hoc: a shell script calling Python, Python
shelling out through `subprocess`, values passed as text and parsed again on
the other side.

Kotlysh makes Kotlin the glue. The whole plan lives in one typed Kotlin file,
and the entire DSL is one construct: inside a runtime block, a string is code,
and invoking it runs it.

```kotlin
sh { "git status --short"() }
py { "print(sum(range(10)))"() }
```

Each `sh { }` or `py { }` block is a live interpreter running real code in its
own language. Kotlin coroutines decide what runs, in what order, with which
data, and what happens when something fails.

That one construct goes a long way. The tour below starts with a single
command and adds one capability per step, up to a program that runs several
languages in parallel under one deadline. Exact behaviour is in the
[reference](#reference).

## A tour, one step at a time

### 1. Run a command

```kotlin
val changes = sh { "git status --short"().stdoutText }
```

- `sh { }` starts one interpreter, runs the block, closes the interpreter and
  returns the block's last expression.
- `"code"()` or `"""code"""()` dispatches a scriptlet into that session and
  returns an immutable `RuntimeResult` once its output is captured.
- Dot access reads the result: `.stdout` and `.stderr` as lines, `.stdoutText`
  and `.stderrText` as text, `.exitCode`, `.ok`.

### 2. Keep state between commands

A session is one live interpreter, so what one scriptlet sets up, the next one
finds. This is a complete program:

```kotlin
import com.kotlysh.core.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val where = sh {
        "cd /tmp"()                         // state persists in the session
        "export NAME=kotlysh"()
        "echo \"\$NAME in \$PWD\""().stdoutText
    }
    println(where)                          // kotlysh in /tmp
}
```

Everything in this guide comes from one package, `com.kotlysh.core`.

### 3. Decide what a failure means

A scriptlet never throws because its command failed, so shell-style branching
stays ordinary Kotlin. Append `.orThrow()` where a failure should stop the
task.

```kotlin
sh {
    if (!"git diff --quiet"().ok) println("local changes")

    val branch = "git branch --show-current"().orThrow().stdoutText
}
```

`orThrow()` throws `RuntimeCallException` whenever `ok` is false. Its
`execution` keeps the full result, and the message names the status and the
foreign error or stderr:
`sh scriptlet failed with exit code 128: fatal: not a git repository`.

The status is the shell's own, so a pipeline reports only its last command.
Run `"set -o pipefail"()` once and the session keeps the stricter rule.

`ok` is false when a deadline, an output limit or an interpreter exit ended
the step, even if the exit code is zero. The flags that say which are listed
in [Results and errors](#results-and-errors).

### 4. Switch language

The construct does not change with the language. Python keeps its imports and
variables between scriptlets, as the shell keeps its directory:

```kotlin
val version = py {
    "import platform"()
    "print(platform.python_version())"().stdoutText
}
```

Four runtimes ship, plus a generic entry for your own:

| Block | Runs | [Typed values](#5-exchange-typed-values) | Launch form |
| --- | --- | --- | --- |
| `sh { }` | Bash | no | `launchSh { }` |
| `py { }` | Python 3 | yes | `launchPy { }` |
| `node { }` | JavaScript on Node | yes | `launchNode { }` |
| `ruby { }` | Ruby | no | `launchRuby { }` |
| `runtime(spec) { }` | [any interpreter you describe](#13-add-a-language) | no | `launchRuntime(spec) { }` |

A block returns once its session has closed, including any coroutines started
inside it. A launch form returns a `Job` immediately; cancelling it closes the
session.

The steps below stay with shell and Python, but each one applies to every
runtime that offers it.

### 5. Exchange typed values

In `py { }` and `node { }`, expressions return typed values and inputs travel
as data: a binding is never inserted into the expression. Printed output stays
in stdout and stderr.

```kotlin
val total: Int = py {
    eval<Int>("sum(items)", "items" to value(listOf(2, 3, 5)))
}

val longest = py {
    "print('measuring')"()
    evaluate<String>("max(words, key=len)", "words" to value(listOf("sh", "python", "kotlin")))
}
// longest.value == "python"; longest.execution is that dispatch's RuntimeResult
```

`eval<T>` returns the value alone. `evaluate<T>` adds the dispatch's
`RuntimeResult`, and `evaluateJson` is `evaluate` with the value left as a raw
`JsonElement`. Node expressions may use `await`.

These calls throw `RuntimeCallException` when the foreign expression fails
(its `errorType`, `errorMessage` and `execution` keep the foreign diagnostics
and logs) and `RuntimeDecodeException` when the returned JSON does not match
the Kotlin type. Lists, maps, strings and primitives work as they are; your
own types need `@Serializable` and Kotlin's serialization compiler plugin.

What crosses is plain JSON data. Anything else, a NumPy number or a `Date`
for instance, is converted in its own language first, or stays there behind a
reference.

### 6. Keep foreign objects alive

`hold` keeps a foreign object alive for the duration of a block, so expensive
state is built once and queried many times. That covers what JSON cannot
carry: a set, a model, a parser, a database client, a JavaScript function.

```kotlin
py {
    "import collections"().orThrow()
    hold("collections.Counter(words)", "words" to value(listOf("a", "b", "a", "c", "a"))) { counter ->
        val top = eval<String>("counter.most_common(1)[0][0]", "counter" to counter)
        val distinct = eval<Int>("len(counter)", "counter" to counter)
    }
}
```

### 7. Move data between languages

A reference points at the same live object every time, so mutations persist.
`store` does the same for Kotlin data, `read<T>(reference)` takes a JSON
snapshot back to Kotlin, and `copy` puts one into another live session:

```kotlin
val sum: Int = py {
    store(listOf(2, 3, 5)) { pythonNumbers ->
        node {
            copy(pythonNumbers) { nodeNumbers ->
                eval<Int>("items.reduce((sum, item) => sum + item, 0)", "items" to nodeNumbers)
            }
        }
    }
}
```

Two rules to rely on:

- A reference belongs to one session. It cannot be used after its block, after
  its session closes, or in another session, even of the same language.
- Releasing a reference drops Kotlysh's hold on the object. It does not call
  `close()` for you: shut a client or file down in its own language first.

The JSON and number rules and the size limits are in
[The typed bridge](#the-typed-bridge).

### 8. Run sessions in parallel

One session runs one dispatch at a time. Separate sessions run in parallel under
ordinary coroutines, and cancellation stops the interpreters they own.

```kotlin
coroutineScope {
    val tests = async { sh { "./gradlew test"(timeout = 10.minutes).orThrow() } }
    val answer = async { py { eval<Int>("6 * 7") } }
    tests.await(); answer.await()
}
```

### 9. Set deadlines

Every scriptlet and expression takes `timeout` (30 seconds by default, so
raise it for builds and test runs), `idleTimeout` (the longest allowed
silence) and an `output` sink for live lines. The clock starts when the call
is made, so time spent queued behind another dispatch counts.

```kotlin
withTimeout(15.minutes) {
    sh { "./gradlew test"(timeout = 10.minutes, idleTimeout = 2.minutes).orThrow() }
}
```

A deadline on one scriptlet ends that scriptlet and its session. `withTimeout`
around any amount of work stops every interpreter inside it, with the
processes they started. More in
[Sessions, deadlines and output](#sessions-deadlines-and-output).

### 10. Run one program without a shell

When exact argument boundaries matter more than shell semantics, `exec` runs
one program from an argument list, with no shell and no session:

```kotlin
val result = exec(listOf("git", "status", "--short"), cwd = repository)
```

More in [One-shot commands](#one-shot-commands).

### 11. Treat scriptlets as data

A scriptlet can be named, collected or chosen before it runs. The `hold`
modifier keeps the `"code"()` form, so scriptlets stored in values, lists and
maps still read as code.

```kotlin
sh {
    val probes = mapOf(
        "commits" to "git rev-list --count HEAD"(hold),
        "files" to "git ls-files | wc -l"(hold),
    )
    val commits = probes.getValue("commits")().orThrow().stdoutText
}
```

`"code"(hold)` dispatches nothing and returns a `HeldScriptlet`, which runs in
whichever session of the same runtime invokes it.

### 12. Trace a run

Wrap any part of a task in a trace and the runtime reports what it did, without
a logging framework and without changing the scriptlets:

```kotlin
val trace = RuntimeTraceBuffer()
withRuntimeTrace(trace) {
    sh { "git status --short"() }
    py { eval<Int>("6 * 7") }
}
trace.events    // sessions, dispatches, output and outcomes, in order
```

Swap the buffer for `RuntimeJsonlTraceSink(::println)` to stream one JSON
record per line, or pass `-Dkotlysh.trace.jsonl=true` to the JVM to get the
same stream with no code at all.

Records mark the start and end of each session and dispatch, and each captured
line. Every record carries its `sessionId`, and a `RuntimeResult` carries the
same `sessionId` and `dispatchId`, so a result in code matches its records and
parallel sessions untangle. A dispatch names the sessions its inputs came
from. A session's start record gives the interpreter's process id, which is
where to point `pstree` when a run hangs.

Dropped records are counted, so a partial trace is never mistaken for a
complete one, and a sink that throws does not fail the task. Record fields and
limits are in [Tracing](#tracing).

### 13. Add a language

Nothing in the DSL is specific to a language. `"code"()` is `String.invoke`
inside a runtime block, and a runtime is only a `RuntimeSpec`: the command that
starts an interpreter, plus a wrapper that makes it print a unique completion
marker with the exit status on both streams after each scriptlet. That marker
is how Kotlysh knows a step finished and which output belongs to it.

```kotlin
val posix = RuntimeSpec(
    name = "posix",
    command = listOf("/bin/sh", "-s"),
    wrap = { script, marker -> "$script\nprintf '%s:%s\\n' '$marker' \"\$?\" | tee /dev/stderr\n" },
)

val shell = runtime(posix) { "echo \"running in \$0\""().orThrow().stdoutText }
```

The four built-in runtimes are specs of the same kind, so sessions, deadlines,
cancellation and tracing apply to a new language unchanged.

To give your runtime a name of its own, wrap it:

```kotlin
suspend fun <T> posixSh(block: suspend RuntimeScope.() -> T): T =
    runtime(posix, block = block)
```

[Writing a runtime](#writing-a-runtime) covers `RuntimeSpec` in detail, and
[Architecture](#architecture) the layers and modules underneath.

### 14. Put it together

Here is a question about your own repository: which files are both large and
changed often? Git knows the facts, Python is good at ranking them, and Kotlin
holds the plan.

```kotlin
@Serializable
data class Hotspot(val file: String, val commits: Int, val lines: Int)

suspend fun hotspots(): List<Hotspot> = withTimeout(10.seconds) {
    val top = "sort -rn | head -500"    // the 500 largest counts are plenty

    // Two questions for git, asked at the same time, each in its own shell:
    // how often did each file change, and how long is each text file?
    val churn = async {
        sh { "git log --format= --name-only --relative | sort | uniq -c | $top"().orThrow().stdout }
    }
    val sizes = async {
        sh { "git grep -Ilz '' | xargs -0 wc -l | $top"().orThrow().stdout }
    }

    // Python is taught once, then asked. The lists go in as data, not as text.
    py {
        """
        def table(lines):
            rows = (line.split(None, 1) for line in lines)
            return {row[1]: int(row[0]) for row in rows if len(row) == 2}

        def rank(churn, sizes):
            churn, sizes = table(churn), table(sizes)
            files = sorted(churn.keys() & sizes.keys(), key=lambda f: (-churn[f] * sizes[f], f))
            return [{"file": f, "commits": churn[f], "lines": sizes[f]} for f in files[:5]]
        """().orThrow()

        eval<List<Hotspot>>(
            "rank(churn, sizes)",
            "churn" to value(churn.await()),
            "sizes" to value(sizes.await()),
        )
    }
}
```

It ships as a
[sample](kotlysh-samples/src/main/kotlin/com/kotlysh/samples/Hotspots.kt). Run
it on this repository, or on one of yours:

```bash
./gradlew :kotlysh-samples:runHotspots -q
./gradlew :kotlysh-samples:runHotspots -q -Prepo=/path/to/your/repository
```

Then change it, run it again, and watch what happens:

- **Set the deadline to `50.milliseconds`.** The run ends in a
  `TimeoutCancellationException`. Is a `git`, `sort` or `python3` process left
  behind?
- **Misspell `head` as `haed`.** Read the exception message.
- **Change `lines: Int` to `lines: String`.** Which language reports it?
- **Put `sleep 1;` in front of both pipelines.** Does the run take one second
  longer, or two?
- **Ask Python something else before the final `eval`,** such as
  `println(eval<Int>("len(table(sizes))", "sizes" to value(sizes.await())))`.
  Did `table` have to be defined again?
- **Sketch the same task as a shell script that calls Python.** Count the
  places where a list becomes text and is parsed back.

## See it run

`./gradlew :kotlysh-samples:runOrchestra -q` surveys a repository across
thirteen interpreters in well under a second, races three runtimes to
fingerprint the sources, and ends by charting itself from its own trace:

```
 What ran, as the runtime recorded it   █ running  ─ alive  ✗ killed
   sh   ─███████████───────
   sh                      ────────────────────────
   sh                      █████───────
   sh                      ████████─────
   sh                       ███████─────
   sh                       ███████████───────
   sh                       ███████████───────
   sh                       ███████─────
   sh                       ████████────
   py                                   ─██████████████████████████████████████─██████──────
   sh                                       ███████──────
   py                                       ██████████████████████████────✗
   node                                     ██████████████████████████────✗
        0                                                                             266 ms
```

The two `✗` marks are the losers of the race, stopped mid-computation. The
whole demo is
[one file](kotlysh-samples/src/main/kotlin/com/kotlysh/samples/Orchestra.kt).

## Built for LLMs, readable by people

Kotlysh lets an LLM express orchestration in very few tokens: parallelism,
ordering, retries, deadlines and data movement are each one familiar Kotlin
construct rather than a page of glue.

- **A plan instead of a transcript.** An agent that writes a Kotlysh task has
  produced code a person can read, diff, approve and run again, not a stream of
  one-off tool calls.
- **Inspectable as data.** `"code"(hold)` declares a scriptlet without running
  it, so a plan can be built, reviewed and scheduled before anything executes.
- **A handoff that survives the chat.** The file says which sessions hold
  state, which outputs feed later steps and which failures are tolerated. The
  next agent, or the next person, resumes from source instead of scrollback.
- **Data stays data.** Values reach foreign code as bindings, not as text
  spliced into a command, so untrusted input cannot become executable source.

A single one-off command is still quickest in a plain shell. Kotlysh earns its
place when a task has state, more than one language, a deadline, or a reviewer.

## Why Kotlysh

- **Coroutines are the backbone.** Every interpreter is a suspending session,
  so one model orders, parallelizes, bounds and cancels work in any language.
  `async`/`await` is the dependency graph. `select` races implementations.
  `withTimeout` around a block stops the interpreters inside it, along with the
  processes they started. A new language needs no new orchestration, only a
  way to start it.
- **Every ecosystem, one file.** Use pandas, requests and the whole Unix
  toolbox side by side, each in its native language, with no wrapper library
  in between.
- **A typed control plane.** Sequencing and data flow are ordinary Kotlin, so
  the compiler checks the shape of the plan before any command has a side
  effect.
- **State with a lifetime.** A session keeps its working directory, imports and
  variables between steps. A loaded dataset or model stays in the language that
  owns it, for exactly as long as its block, while Kotlin asks it typed questions.
- **Failure is defined, not improvised.** Exit codes, deadlines, output limits
  and dead interpreters all have one documented behaviour.
- **Every run explains itself.** The runtime records each session and dispatch
  with timing, status and data lineage, as structured events you can keep.

## Requirements and status

Linux, JDK 21 and Kotlin 2.1, plus the interpreters a task uses: Bash and
Python 3 for the examples here. The library is pre-1.0; see
[Stability](#stability).

Kotlysh is used from source: depend on `kotlysh-core` from a checkout, for
example as a Gradle included build:

```kotlin
// settings.gradle.kts
includeBuild("../kotlysh-lib")

// build.gradle.kts
dependencies {
    implementation("com.kotlysh:kotlysh-core")
}
```

Without a JVM, `kotlysh-native-sh` offers shell sessions and direct process
execution on POSIX; see [Kotlin/Native](#kotlinnative).

## Escaping and data in source

A scriptlet is foreign code inside a Kotlin string, so two sets of quoting
rules meet. They fit in one table:

| To send this to the interpreter | In `"..."` | In `"""..."""` |
| --- | --- | --- |
| `$HOME` | `\$HOME` | `${'$'}HOME` |
| `${VAR}` | `\${VAR}` | `${'$'}{VAR}` |
| `$?`, `$1`, `$(cmd)` | as written | as written |
| a double quote | `\"` | as written |
| a backslash sequence such as `\n` or `\d` | `\\n`, `\\d` | as written |
| a real line break | `\n` | a line break |
| a Kotlin value, as data | a binding, or `${shLiteral(value)}` | the same |

Five habits cover every case:

- **Reach for `"""..."""`** whenever the code has quotes, backslashes, regexes
  or more than one line. Only `$` needs attention there.
- **A `$` followed by a name or `{` is Kotlin.** Escape it when the interpreter
  should see it. Left bare, the Kotlin value becomes source code.
- **Send data as data.** Use a binding in `py { }` and `node { }`. Elsewhere
  use `shLiteral`, `pythonLiteral` or `jsLiteral`, which each return one
  complete quoted literal: put it outside any quotes, and never use it for
  code or identifiers.
- **Indent freely.** Common indentation is removed from a multiline scriptlet,
  so Python blocks keep their shape. Keep a here-document's closing word at the
  same indentation as the rest.
- **Inside a triple-quoted scriptlet, use `'''`** for Python docstrings, since
  `"""` would end the Kotlin string.

```kotlin
val pattern = "it's \$5"                      // untrusted data
sh {
    """
    for file in *.log; do
        grep -cF -- ${shLiteral(pattern)} "${'$'}file"
    done
    """()
}
py {
    "import re"().orThrow()
    eval<Int>("len(re.findall(r'\\d+', text))", "text" to value(pattern))
}
```

## Session rules worth knowing

- An ordinary failure leaves the session usable: a nonzero exit, a foreign
  exception, a decode failure or a malformed shell scriptlet.
- A timeout, output beyond the [capture limit](#capture-limits), cancellation
  or an interpreter exit (`exit` in a shell scriptlet) closes the session, and
  later dispatches fail. To retry, open a fresh session.
  [Every case](#what-closes-a-session) is in the reference.
- A scriptlet must finish its own background work, for example with `wait`:
  output that arrives later lands in another dispatch.
- Shell commands inherit an empty stdin. `cat`, `read` or `ssh` see end of
  input; supply input with a pipe, redirection or here-document.

## What it does not promise

- Kotlysh is not a sandbox: it runs trusted code with your own permissions.
- A session is live process state, not a checkpoint. Nothing is rolled back
  or recovered after a failure or a crash, including external side effects.
- Crossing a language boundary is cheap, not free: there is process I/O and
  JSON serialization on every call.
- Code inside a scriptlet is checked by its own language at run time, not by
  the Kotlin compiler. Kotlin checks the plan around it.
- Each runtime must be installed; Kotlysh does not manage interpreters or
  their packages.
- Compiling Kotlin costs more than starting a shell, which matters for tiny
  tasks.

## Run the samples

```bash
./gradlew :kotlysh-samples:runHotspots -q     # the last step of the tour
./gradlew :kotlysh-samples:run                # shell, Python and Node together
./gradlew :kotlysh-samples:runOrchestra -q    # thirteen interpreters, charted from their own trace
```

## Build and test

```bash
./gradlew :kotlysh-core:test :kotlysh-samples:test
```

Gradle must run on JDK 21 for the default Kotlin 2.1 build; set `JAVA_HOME`
when the system JDK is newer. To build on a newer JDK instead, select a matching
compiler and toolchain:

```bash
KOTLYSH_KOTLIN_VERSION=2.4.21 KOTLYSH_JVM_TOOLCHAIN=25 ./gradlew check
```

The core tests start real interpreters and need Bash, Python 3, Node and Ruby.

# Reference

Exact behaviour, for looking things up.

- [Entry points and scriptlets](#entry-points-and-scriptlets)
- [Results and errors](#results-and-errors)
- [The typed bridge](#the-typed-bridge)
- [Sessions, deadlines and output](#sessions-deadlines-and-output)
- [One-shot commands](#one-shot-commands)
- [Tracing](#tracing)
- [Writing a runtime](#writing-a-runtime)
- [Architecture](#architecture)
- [Kotlin/Native](#kotlinnative)
- [Stability](#stability)

## Entry points and scriptlets

| Term | Meaning |
| --- | --- |
| Runtime | An interpreter Kotlysh drives: `sh`, `py`, `node`, `ruby`, or one described by a spec. |
| Session | One interpreter process, owned by one block. |
| Scriptlet | Source text sent to a session with `"code"()`. |
| Dispatch | One request to a session: a scriptlet or a [typed bridge](#the-typed-bridge) call. |

### Blocks

- A block (`sh`, `py`, `node`, `ruby`, `runtime(spec)`) is a suspending
  function. It starts the interpreter, runs its body, closes the session and
  returns the body's value. Coroutines started in the body finish first.
- A launch form (`launchSh` and its siblings) is an extension on
  `CoroutineScope` that returns its `Job` at once. Its body returns `Unit`.
- Both take an optional coroutine `context` ahead of the body;
  `Dispatchers.IO` is the default.
- The body's receiver is a `ShScope`, `PyScope`, `NodeScope`, `RubyScope` or
  `RuntimeScope`, each a `CoroutineScope`, so a reusable step is an extension
  on one of them:
  `suspend fun ShScope.branch() = "git branch --show-current"().orThrow().stdoutText`.
- `runtime.processId` in that scope is the interpreter's process id.
- `sh` runs `/bin/bash --noprofile --norc`, so no profile or rc file is read.
  `py`, `node` and `ruby` run `python3`, `node` and `ruby` from the JVM's
  `PATH`: to use a virtual environment, start the JVM with it activated.
- An interpreter that cannot be started makes the block throw `IOException`
  before its body runs.

### Scriptlets

```kotlin
suspend operator fun String.invoke(
    timeout: Duration = 30.seconds,
    idleTimeout: Duration = timeout,
    output: RuntimeOutputSink? = null,
): RuntimeResult
```

`"code"()` sends the string to the session, suspends until its output is
captured, and returns a [`RuntimeResult`](#results-and-errors). Its options
are described under
[Sessions, deadlines and output](#sessions-deadlines-and-output).

- **Indentation.** A string containing a line break is trimmed as by Kotlin's
  `trimIndent()`: blank first and last lines and the common indentation go,
  and line endings become LF. A single-line string is sent unchanged.
- **Queueing.** Dispatches started concurrently in one session run in turn.
  The wait counts against each one's `timeout`.
- **Holding.** `"code"(hold)` returns a `HeldScriptlet` without touching the
  session: `script` is the trimmed source and `runtime` the name of the
  runtime whose block it was written in. It can leave its block and run any
  number of times, in any session of that runtime. A session of another
  runtime rejects it with `IllegalArgumentException` and stays usable. The
  `hold` modifier is unrelated to the bridge call
  [`hold(expression) { }`](#the-typed-bridge).

### Runtime notes

| Runtime | Kept between scriptlets | An error in a scriptlet | Ends the interpreter |
| --- | --- | --- | --- |
| `sh` | working directory, environment, variables, functions, shell options | `exitCode` is the scriptlet's `$?`; malformed source gives 2 | `exit` |
| `py` | globals, imports | exit code 1 and the traceback on stderr, for any exception including `SystemExit` | `os._exit` |
| `node` | globals, top-level declarations, loaded modules | exit code 1 and the stack on stderr | `process.exit` |
| `ruby` | local variables, methods | exit code 1 and the backtrace on stderr, for any exception including `SystemExit` | `exit!` |

Only the last column [closes the session](#what-closes-a-session).

- **Shell.** `$?` does not carry over to the next scriptlet: read a status
  from its result.
- **Python.** Stdout and stderr are UTF-8 whatever the locale says.
- **Node.** A scriptlet runs as a script. Top-level `var`, `function`, `let`
  and `const` persist, so declaring the same `let` or `const` again fails.
  There is no top-level `await`, but a scriptlet whose value is a promise is
  awaited: wrap asynchronous work as `(async () => { ... })()`. `require`,
  `Buffer`, `process`, `console` and timers are available.
- **Transport.** A scriptlet that reassigns standard names such as Python's
  `json.dumps` or JavaScript's `JSON` changes its own calls only, never
  Kotlysh's exchange with the interpreter.

### Literals

| Helper | Returns | Rejects |
| --- | --- | --- |
| `shLiteral(value)` | one single-quoted Bash word; line breaks in the value survive indentation trimming | NUL and unpaired surrogates, with `IllegalArgumentException` |
| `pythonLiteral(value)` | a double-quoted Python string expression, with quotes, backslashes and control characters escaped | nothing |
| `jsLiteral(value)` | the same, for JavaScript | nothing |

Inside another string, a template, a regular expression, a here-document or a
comment, the surrounding grammar applies and the value can still run as code.

## Results and errors

### `RuntimeResult`

A scriptlet returns one `RuntimeResult`, an immutable description of that
dispatch.

| Property | Type | Meaning |
| --- | --- | --- |
| `stdout`, `stderr` | `List<String>` | captured lines, one list per stream |
| `stdoutText`, `stderrText` | `String` | the lines joined with `\n` |
| `exitCode` | `Int?` | the scriptlet's status; the interpreter's own if it exited; `null` if none was observed |
| `timedOut` | `Boolean` | the `timeout` expired |
| `idleTimedOut` | `Boolean` | the `idleTimeout` expired |
| `outputLimited` | `Boolean` | capture stopped at the [capture limit](#capture-limits) |
| `processExited` | `Boolean` | the interpreter exited during the dispatch |
| `ok` | `Boolean` | `exitCode == 0` and none of the four flags |
| `runtime` | `String` | the runtime's name: `sh`, `py`, `node`, `ruby`, or a spec's `name` |
| `sessionId`, `dispatchId` | `String`, `Long` | the session, and the dispatch's number within it, counted from 1; both appear in the [trace](#tracing) |
| `elapsedMillis` | `Long` | time from the call to the result, queueing included |

### What a scriptlet call throws

Failure of the foreign code is returned, never thrown. The call itself throws
only:

| Exception | When |
| --- | --- |
| `IllegalArgumentException` | a `timeout` or `idleTimeout` that is not positive; source containing an unpaired UTF-16 surrogate; a held scriptlet from another runtime. The session stays usable. |
| `IllegalStateException` | the session is closed |
| `CancellationException` | the calling coroutine was cancelled |
| whatever the `output` sink throws | the sink threw; the session is closed first |

### `orThrow()`

`result.orThrow()` returns the result when it is `ok`. Otherwise it throws
`RuntimeCallException`, an `IllegalStateException`, and leaves the session as
it was.

| Property | Value |
| --- | --- |
| `execution` | the failed `RuntimeResult` |
| `errorType`, `errorMessage` | the foreign error's type name and full message, such as `ZeroDivisionError` and `division by zero`, as `py`, `node` and `ruby` report them; `null` for `sh` |
| `message` | the runtime, the exit code, the flag that ended the dispatch, then the foreign error or else the first 1,000 characters of stderr; 4,096 characters at most |

```text
sh scriptlet failed with exit code 128: fatal: not a git repository
py scriptlet failed with exit code 1: ZeroDivisionError: division by zero
```

Bridge calls throw the same exception themselves: see [Failures](#failures).

## The typed bridge

`py { }` and `node { }` exchange JSON values with Kotlin and keep references to
objects that stay in the interpreter. Bridge calls and scriptlets share one
namespace.

### Calls

| Call | Does | Returns |
| --- | --- | --- |
| `eval<T>(expression, bindings...)` | evaluates an expression | the value, decoded to `T` |
| `evaluate<T>(expression, bindings...)` | the same | `Evaluation<T>`: the `value`, and the dispatch's `RuntimeResult` as `execution` |
| `evaluateJson(expression, bindings...)` | the same, undecoded | `Evaluation<JsonElement>` |
| `hold(expression, bindings...) { ref -> }` | evaluates and keeps the result, whatever its type | the block's value |
| `store(value) { ref -> }` | keeps a Kotlin value, sent as JSON | the block's value |
| `storeJson(element) { ref -> }` | keeps a `JsonElement` | the block's value |
| `read<T>(ref)` | takes a JSON snapshot of a kept object | `T` |
| `readJson(ref)` | the same, undecoded | `JsonElement` |
| `copy(ref) { copied -> }` | snapshots another session's object into this one | the block's value |

- Every call takes `timeout`. The four that evaluate an expression also take
  `idleTimeout` and `output`.
- An expression is a single expression of its language; statements belong in
  a scriptlet. It is sent as written, without a scriptlet's indentation
  trimming, and Python rejects one that starts with indentation: keep it on
  one line, or call `.trimIndent()` on it.
- The value travels apart from stdout and stderr, so nothing printed can alter
  it. What was printed is in `execution`.
- `T` is decoded with kotlinx.serialization. A returned `JsonElement` is
  read-only at every depth.

### Bindings

A binding is `"name" to argument`, where the argument is `value(x)` for a
Kotlin value, `jsonValue(element)` for a `JsonElement`, or a reference.

- Bindings travel as data beside the expression, never inside its text.
- A name matches `[A-Za-z_][A-Za-z0-9_]*` and is unique within the call. Avoid
  the target language's reserved words.
- The data is validated and copied when the call is made, before it waits for
  the session. Later changes to your collections do not reach it.

### Expression scope

| | Python | Node |
| --- | --- | --- |
| Runs | in the scriptlets' globals | as the body of an async function, in the scriptlets' context |
| A binding is | a global for the duration of the call, then restored | a parameter of that function |
| Asynchrony | none: a coroutine object is not awaited | `await` works, and a returned promise is awaited |
| A kept function | must capture a binding to use it later: `lambda x=items: len(x)` | captures bindings as any closure does |

### References

A `RuntimeRef` names one kept object in one session. Binding it passes that
same object.

- `hold`, `store`, `storeJson` and `copy` release their reference when the
  block ends, also on failure or cancellation. Closing the session drops every
  reference.
- A reference used after release, or after its session closed, throws
  `IllegalStateException`. One bound in another session throws
  `IllegalArgumentException`. Neither sends a request.
- A reference block opens no coroutine scope. Wrap coroutines that use the
  reference in `coroutineScope { }` inside the block, or they can outlive it.

### Copies across sessions

`copy(ref) { }` reads a JSON snapshot in the reference's session and stores it
in the current one: two requests, each with the given `timeout`. Both sessions
must be live. The copy is independent of the original, and the target's
[number rules](#numbers) apply to it. A failed copy stores nothing and leaves
both sessions usable.

### What crosses as JSON

| Crosses as it is | Needs converting in its own language, or a reference |
| --- | --- |
| strings, exactly: any Unicode, NUL, line breaks | Python values that are not exactly `dict`, `list`, `tuple`, `str`, `int`, `float`, `bool` or `None`: sets, bytes, NumPy and pandas values, subclasses |
| booleans and null | Python dictionaries with non-string keys |
| numbers, within the [rules below](#numbers) | JavaScript `undefined`, functions, dates, class instances, proxies, array subclasses |
| arrays; a Python tuple arrives as one | non-finite numbers and cyclic structures |
| objects with string keys | |

A value that cannot cross fails the call with a `RuntimeCallException` whose
`errorType` is `TypeError`, or Python's `ValueError` for a non-finite number
or a cycle. Usual conversions are `int(x)`, `float(x)`, `x.tolist()`,
`list(s)`, `Array.from(a)` and `d.toISOString()`.

### Numbers

Numbers sent to a runtime are checked before dispatch against its
`RuntimeJsonNumberPolicy`:

| Policy | Used by | Integers | Decimals and exponents |
| --- | --- | --- | --- |
| `Python` | `py` | exact, at any size | a finite `float` |
| `JavaScript` | `node` | within ±9,007,199,254,740,991 | a finite `Number` |
| `Json` | custom specs, by default | unchecked | unchecked |

- A number outside these rules throws `IllegalArgumentException`, and nothing
  is sent.
- Results obey the same limits: Node refuses to return an integer outside its
  exact range, and neither runtime returns `NaN` or an infinity.
- Send wide integers to Node as decimal strings. A Python integer wider than
  `Long` arrives intact through `evaluateJson`.

### Failures

Bridge calls throw; they never return a failed result.

| What happened | Thrown |
| --- | --- |
| A bad binding name or an out-of-range number | `IllegalArgumentException`, before dispatch |
| A released reference, or one from another session | `IllegalStateException` or `IllegalArgumentException`, before dispatch |
| The expression raises, or its value cannot cross as JSON | `RuntimeCallException` |
| A deadline expires | `RuntimeCallException` |
| The value does not fit `T` in `eval` or `evaluate` | `RuntimeDecodeException` |
| The snapshot does not fit `T` in `read` | the decoder's own exception |
| The response exceeds the [size limits](#response-limits) | `IllegalStateException` |
| The caller is cancelled | `CancellationException` |

Which of these close the session is under
[What closes a session](#what-closes-a-session).

`RuntimeCallException` is the one [`orThrow()`](#orthrow) throws. Here its
message reads like
`py request failed: NameError: name 'total' is not defined`.
`RuntimeDecodeException` is an `IllegalArgumentException` raised in Kotlin
after the foreign call succeeded: `execution` is that successful result and
`cause` the decoder's exception.

### Response limits

A response may carry 8 MiB of JSON, nested 128 levels deep. Sessions opened
inside this scope take its limits instead:

```kotlin
withRuntimeResponseLimits(
    RuntimeResponseLimits(maxPayloadBytes = 16 * 1024 * 1024, maxNestingDepth = 128),
) {
    py { evaluateJson("list(range(1000))") }
}
```

Requests are not capped. For bulk data, keep it in its runtime behind a
reference, or exchange a file path.

## Sessions, deadlines and output

### Closing

When a block ends, Kotlysh terminates the child processes the interpreter
still has, background jobs included, then closes its stdin and terminates it
too if it does not exit at once. A process that has detached itself from the
interpreter is not looked for. The close always completes, also when the block
fails or is cancelled, and the block's own exception is the one thrown.

### What closes a session

A session survives whatever the foreign code does wrong, and whatever Kotlysh
rejects before or decodes after a dispatch. It closes when a dispatch that was
running cannot be trusted to have finished cleanly.

| Event | Session afterwards |
| --- | --- |
| A nonzero exit code, a foreign exception, a malformed shell scriptlet | usable |
| A bridge call rejected before dispatch, or whose value does not cross or decode | usable |
| A deadline that expires, or a caller that is cancelled, while the dispatch is still queued | usable |
| A deadline that expires while the dispatch runs | closed |
| A caller cancelled while its dispatch runs | closed |
| Output beyond the [capture limit](#capture-limits) | closed |
| An exception from the `output` sink | closed |
| A bridge response beyond its [size limits](#response-limits) | closed |
| The interpreter exits | closed; the result has `processExited` |

A closed session is never reused, because late output from the abandoned
scriptlet could be taken for the next one's. Further dispatches into it throw
`IllegalStateException`.

### Deadlines and cancellation

| Option | Default | Limits | Sets |
| --- | --- | --- | --- |
| `timeout` | 30 seconds | wall-clock time from the call: waiting behind other dispatches, sending the scriptlet, running it and collecting its output | `timedOut` |
| `idleTimeout` | the value of `timeout` | silence on both streams once the scriptlet has been sent; any output, even part of a line, restarts it | `idleTimedOut` |

- Both must be positive.
- An expired deadline returns a result holding the output captured so far.
- A `timeout` that expires while the dispatch is still queued returns
  `timedOut` with no output: the scriptlet never ran.
- With the default `idleTimeout`, a silent scriptlet that overruns can set
  either flag, or both. Test `ok`, or both flags.
- Cancelling the calling coroutine, whether directly, through `withTimeout`
  around the block or by cancelling a launch `Job`, stops the interpreter and
  its child processes, then propagates as cancellation.

### Output capture

- Stdout and stderr are captured separately, as lines of text. Their relative
  order is not recorded.
- Output is read as UTF-8, and invalid bytes become U+FFFD. Binary output does
  not survive: write it to a file.
- A line ends at LF, and a CR just before it is dropped.
- Kotlysh's completion records never appear in the output, even after a last
  line without a line break.

#### Capture limits

Each dispatch, and each [`exec`](#one-shot-commands) call, captures at most
10,000 lines and 1,000,000 characters, both streams together. Sessions opened
and `exec` calls made inside this scope take its limits instead:

```kotlin
withRuntimeOutputLimits(
    RuntimeOutputLimits(maxLinesPerDispatch = 50_000, maxCharactersPerDispatch = 5_000_000),
) {
    sh { "./gradlew build --info"(timeout = 10.minutes) }
}
```

Output beyond a limit ends the dispatch at once with `outputLimited`. The
result keeps the output captured up to the limit, and the session is closed.
Output that exactly fills a limit is accepted.

#### Live output

`output` takes a `RuntimeOutputSink`, called with a
`RuntimeOutputLine(runtime, stream, text)` for each captured line as it
arrives; `stream` is `RuntimeStream.Stdout` or `RuntimeStream.Stderr`.
`RuntimeOutputBuffer()` is a ready-made sink whose `lines` keep both streams
in arrival order.

```kotlin
"./gradlew build"(timeout = 10.minutes, output = { line -> println(line.text) })
```

## One-shot commands

```kotlin
suspend fun exec(
    command: List<String>,
    cwd: Path? = null,
    env: Map<String, String> = emptyMap(),
    timeout: Duration? = 30.seconds,
    output: RuntimeOutputSink? = null,
): ExecResult
```

`exec` starts one process from an argument list, with no shell and no session,
so no argument is parsed again.

| Parameter | Rules |
| --- | --- |
| `command` | The program and its arguments. A bare program name is found on the `PATH` the JVM inherited. |
| `cwd` | The working directory; the JVM's own when omitted. |
| `env` | Added to the inherited environment. A `PATH` given here is what the program sees, not where the program is looked for. |
| `timeout` | Positive, or `null` for no deadline. |
| `output` | A [sink](#live-output) for lines as they arrive, with `exec` as their runtime name. |

- The process gets an empty stdin.
- A program or `cwd` that cannot be used throws `IOException`. The command is
  never run somewhere else.
- At the deadline, at a [capture limit](#capture-limits) and on cancellation,
  the process and its child processes are stopped before `exec` returns.

`ExecResult` has `command`, the argument list that ran, and `stdout`,
`stderr`, `stdoutText`, `stderrText`, `exitCode`, `timedOut`, `outputLimited`
and `ok` with their [`RuntimeResult`](#runtimeresult) meanings. `exitCode` is
`null` after a deadline or a capture limit. A line ends at LF, CRLF or a lone
CR.

## Tracing

Every session start, dispatch, captured line and session close is published as
an immutable event to the sink in scope. Without a sink, nothing is recorded.
A trace holds script previews, command lines and captured output: protect it
like the run's terminal output.

### Enabling

| How | Effect |
| --- | --- |
| `withRuntimeTrace(sink) { }` | traces the sessions opened inside the block, child coroutines included |
| `-Dkotlysh.trace.jsonl=true` on the JVM | traces every session to standard output as [JSONL](#jsonl), with no code change |

`withRuntimeTrace` takes precedence over the property.

### Sinks

A sink implements `RuntimeTraceSink`, a `fun interface` with one method,
`onEvent(event: RuntimeTraceEvent)`. Two are built in:

| Sink | Does | Holds at most |
| --- | --- | --- |
| `RuntimeTraceBuffer(maxEvents = 4_096, maxCharacters = 1_048_576)` | keeps events in memory; `events` returns a snapshot | that many events and characters |
| `RuntimeJsonlTraceSink(writeLine)` | hands each event to `writeLine` as one line of text, in order, also across parallel sessions | 1,024 events or 1,048,576 characters waiting for the writer (`maxPendingEvents`, `maxPendingCharacters`) |

Both are thread-safe. An event that does not fit is dropped and never blocks
the run. `droppedEventCount` on either sink counts the drops, and the JSONL
sink also writes a `trace-dropped` record with their number in
`droppedEvents`. After a drop the trace is incomplete: a dispatch without an
end record has an unknown outcome, not a failed one.

For a sink of your own:

- Sessions running in parallel call `onEvent` concurrently. One session
  delivers its events in order.
- `onEvent` runs on the dispatch path, and deadlines do not interrupt it.
  Return quickly.
- An exception thrown by `onEvent` is ignored. To stop a task, cancel its
  coroutine.

### Records

Each event is a data class whose properties carry the names below, and
`event.toTraceJson()` gives its record as a `JsonObject`. Every record has
`protocolVersion` (1), `event`, `sessionId`, `runtime` and `atEpochMillis`.

| `event` | Class | Further fields |
| --- | --- | --- |
| `session-started` | `RuntimeSessionStarted` | `processId`, `command`, `commandTruncated` (only when true) |
| `dispatch-started` | `RuntimeDispatchStarted` | `dispatchId`, `operation`, `scriptSha256`, `scriptPreview`, `inputSessionIds` |
| `dispatch-output` | `RuntimeDispatchOutput` | `dispatchId`, `sequence`, `stream`, `text` |
| `dispatch-completed` | `RuntimeDispatchCompleted` | `dispatchId`, `elapsedMillis`, `exitCode`, `timedOut`, `idleTimedOut`, `outputLimited`, `processExited`, `stdoutLines`, `stderrLines`, `errorType`, `message` |
| `dispatch-failed` | `RuntimeDispatchFailed` | `dispatchId`, `elapsedMillis`, `errorType`, `message`, `stdoutLines`, `stderrLines` |
| `session-closed` | `RuntimeSessionClosed` | `processId`, `poisoned` |

`sh { "echo hi"() }` produces these five:

```text
@@kotlysh {"protocolVersion":1,"event":"session-started","sessionId":"3f0c2a9e-6f0b-4b7e-9a57-0d1c5e8b2f41","runtime":"sh","atEpochMillis":1767225600000,"processId":4242,"command":["/bin/bash","--noprofile","--norc","-c","exec /bin/bash --noprofile --norc /dev/fd/3 3<&0 </dev/null"]}
@@kotlysh {"protocolVersion":1,"event":"dispatch-started","sessionId":"3f0c2a9e-6f0b-4b7e-9a57-0d1c5e8b2f41","runtime":"sh","atEpochMillis":1767225600000,"dispatchId":1,"scriptSha256":"56a79f3b115448072387c2480044bfa2cf8f90e4f5fddd8c943b4e051b81f80b","scriptPreview":"echo hi","operation":"exec","inputSessionIds":[]}
@@kotlysh {"protocolVersion":1,"event":"dispatch-output","sessionId":"3f0c2a9e-6f0b-4b7e-9a57-0d1c5e8b2f41","runtime":"sh","atEpochMillis":1767225600010,"dispatchId":1,"sequence":0,"stream":"stdout","text":"hi"}
@@kotlysh {"protocolVersion":1,"event":"dispatch-completed","sessionId":"3f0c2a9e-6f0b-4b7e-9a57-0d1c5e8b2f41","runtime":"sh","atEpochMillis":1767225600010,"dispatchId":1,"elapsedMillis":10,"exitCode":0,"timedOut":false,"idleTimedOut":false,"outputLimited":false,"processExited":false,"stdoutLines":1,"stderrLines":0,"errorType":null,"message":null}
@@kotlysh {"protocolVersion":1,"event":"session-closed","sessionId":"3f0c2a9e-6f0b-4b7e-9a57-0d1c5e8b2f41","runtime":"sh","atEpochMillis":1767225600019,"processId":4242,"poisoned":false}
```

- `sessionId` and `dispatchId` match the dispatch's `RuntimeResult`.
- `operation` is `exec` for a scriptlet. For a bridge call it is `eval`
  (also for `evaluate` and `evaluateJson`), `hold`, `store`, `get` (a `read`,
  and the first half of a `copy`) or `release`.
- `scriptSha256` is the SHA-256, in hex, of the scriptlet or expression as it
  was sent, and `scriptPreview` its first 4,096 characters. Both are null for
  operations that carry no source.
- `inputSessionIds` is the data lineage: the sessions owning the references
  the dispatch used. A `copy` records the source session on the target's
  `store`.
- `sequence` numbers a dispatch's lines from 0 across both streams, in
  arrival order.
- `dispatch-completed` means the dispatch produced a result, failed or not,
  with a foreign error's type and message in `errorType` and `message`.
  `dispatch-failed` means it produced none: its caller was cancelled, its
  `output` sink threw, its response was rejected, its session closed under it,
  or its deadline expired while it was queued. `errorType` is then the Kotlin
  exception's class name.
- `poisoned` is true when a failure closed the session, not the end of its
  block.
- `command` is the interpreter's command line, cut at 1,024 characters.
  `errorType` and `message` are cut at 4,096.

`withRuntimeTrace` takes `RuntimeTraceLimits` as a second argument to change
two of these lengths: `maxScriptPreviewCharacters` and `maxCommandCharacters`.

### JSONL

`RuntimeJsonlTraceSink` writes each record as `@@kotlysh ` followed by one
JSON object on one line. The stream switched on by the system property puts an
empty line before each record, so a record never starts in the middle of the
program's own output. To read a stream that mixes records with other output:

- take only the lines that start with the prefix;
- split on LF alone;
- skip a `protocolVersion` you do not know, and ignore unknown fields;
- count `trace-dropped` records.

## Writing a runtime

A runtime is a `RuntimeSpec` given to `runtime(spec) { }` or
`launchRuntime(spec) { }`.

| Property | Type | Meaning |
| --- | --- | --- |
| `name` | `String` | names the runtime in results and traces; not blank |
| `command` | `List<String>` | starts the interpreter; not empty |
| `wrap` | `(script: String, marker: String) -> String` | returns the text written to the interpreter's stdin for one scriptlet |
| `requestWrapper` | `((request: JsonObject, marker: String) -> String)?` | frames a typed-bridge request; `null` by default |
| `jsonNumberPolicy` | `RuntimeJsonNumberPolicy` | [number rules](#numbers) for bridge values; `Json` by default |

### The `wrap` contract

Kotlysh generates a fresh marker for every dispatch. The wrapped text must run
the scriptlet and then make the interpreter print this record, followed by a
line break, on stdout and again on stderr:

```text
<marker>:<status>
```

- `<status>` is a decimal integer and becomes the dispatch's `exitCode`. A
  shell reports `$?`. A language without exit statuses reports 0 for success
  and a nonzero value for an error it caught.
- The interpreter must survive a failing scriptlet. If it exits, the session
  ends.
- Scriptlets arrive on the interpreter's stdin, so a command that reads stdin
  swallows the scriptlets that follow. `RuntimeSpecs.Bash` avoids this by
  feeding scriptlets through another descriptor.
- Keep the wrapper's own bookkeeping out of the user's namespace.

An interpreter that prints prompts or banners, or cannot be framed with plain
text, needs a small driver program as its `command`: one that reads a request
per line, evaluates the source in a persistent namespace, catches errors and
prints the completion records itself. `RuntimeSpecs.Python`, `Node` and `Ruby`
work this way.

### Built-in specs

`sh`, `py`, `node` and `ruby` use `RuntimeSpecs.Bash`, `Python`, `Node` and
`Ruby`. To change one, copy it:

```kotlin
val python = RuntimeSpecs.Python
val python312 = python.copy(
    name = "py312",
    command = listOf("/opt/python3.12/bin/python3") + python.command.drop(1),
)
```

`runtime(python312) { }` runs scriptlets. The typed bridge belongs to
`py { }` and `node { }` alone, and the protocol behind `requestWrapper` is
internal and may change.

## Architecture

### Layers

| Layer | Responsibility |
| --- | --- |
| Process engine | Starts one interpreter per session, writes requests to its stdin, reads both output streams concurrently, runs one dispatch at a time, enforces deadlines and capture limits, builds each result and shuts the process down. It knows nothing about any language, nor about the typed bridge. |
| Runtime spec | The command line, the framing of one scriptlet, the completion record, and the mapping from a language's errors to an exit status. |
| Kotlin DSL | `runtime(spec) { }` owns a session and `"code"()` dispatches into it. `sh`, `py`, `node` and `ruby` are that block with a built-in spec; the typed bridge and the literal helpers sit on top. |

Because the engine is shared, every runtime, built-in or your own, gets the
same sessions, deadlines, capture limits and tracing.

### Modules

| Module | Purpose | Depends on |
| --- | --- | --- |
| `kotlysh-core` | sessions, dispatch, results, the typed bridge and tracing, on the JVM | kotlinx-coroutines, kotlinx-serialization-json |
| `kotlysh-native-sh` | shell sessions and direct process execution for Kotlin/Native | kotlinx-coroutines |
| `kotlysh-samples` | runnable demos | `kotlysh-core` |

## Kotlin/Native

`kotlysh-native-sh`, package `com.kotlysh.nativesh`, runs a persistent shell
and direct processes on POSIX without a JVM. It has no `py` or `node` blocks,
no custom runtimes, no typed bridge and no tracing. It builds a library for
`linuxX64`, the tested platform, and for `androidNativeArm64`, there also as a
shared library with a C header.

| Function | Form |
| --- | --- |
| `nativeSh(shellPath = "/bin/sh") { }` | blocking; one shell for the block |
| `nativeExec { }` | blocking; one process per call inside the block, no shell |
| `nativeExecSuspending(executable, arguments...)` | suspending and cancellable; one process |

Every dispatch and process call takes `timeout` (30 seconds), `idleTimeout`
(the value of `timeout`) and `outputLimits`. Both timeouts must be positive;
`Duration.INFINITE` is allowed.

### `nativeSh`

```kotlin
nativeSh {
    "cd /tmp"()
    val where = "pwd"(timeout = 5.seconds).stdoutText
}
```

The receiver is a `NativeShellSession`, which can also be created directly and
is `AutoCloseable`. Invoking a string dispatches it and returns a
`NativeShellResult`.

- Working directory, variables and functions persist between dispatches.
- A nonzero exit code is returned, not thrown, and leaves the session usable.
- A timeout, an output limit or the shell exiting ends the session: the result
  holds the output received so far, and later dispatches throw
  `IllegalStateException`.
- One session runs one dispatch at a time. A dispatch that waits for another
  spends its own timeouts; if one expires first it returns a timed-out result,
  its source is never run, and the session carries on.
- `close()` stops the shell and its process group, also in the middle of a
  dispatch. It is idempotent and thread-safe.

### `nativeExec` and `nativeExecSuspending`

```kotlin
nativeExec {
    val result = execute(
        "/usr/bin/printf", "%s\n", "payload; stays an argument",
        cwd = "/tmp",
        env = mapOf("MODE" to "daemon"),
    )
    val uid = "/usr/bin/id"("-u").stdoutText
}

val ready = nativeExecSuspending("/usr/bin/printf", "%s", "ready").stdoutText
```

`execute` and `nativeExecSuspending` take an executable, its arguments and
optional `cwd` and `env`, and return a `NativeExecResult`. The string form
takes arguments only.

- The process inherits the caller's environment, with `env` added, and gets
  `/dev/null` as stdin.
- A bare executable name is looked up on `PATH` after the change to `cwd`.
- A `cwd` that cannot be entered gives exit code 126, and an executable that
  cannot be run gives 127, each with a diagnostic on stderr.
- A blank executable, an environment name that is empty or contains `=`, and
  any text containing NUL throw `IllegalArgumentException` before a process
  exists.
- Cancelling a `nativeExecSuspending` caller stops the process without
  waiting for its output or its deadline.

### Results

| Field | `NativeShellResult` | `NativeExecResult` |
| --- | --- | --- |
| `stdout`, `stderr`, `stdoutText`, `stderrText` | lines, and the lines joined with `\n` | the same |
| `exitCode` | `null` when the scriptlet did not complete | `null` when the process did not exit normally |
| `timedOut`, `idleTimedOut`, `outputLimited` | the flag that ended the dispatch | the same |
| `processExited` | the shell itself exited | n/a |
| `signal` | n/a | the signal that killed the process, else `null` |
| `ok` | exit code 0 and no flag | exit code 0, no flag and no signal |

`NativeOutputLimits` bounds both streams together, at 10,000 lines
(`maxLinesPerDispatch`) and 1,000,000 bytes (`maxBytesPerDispatch`) by default.
It counts bytes where the JVM library counts characters.

### Process ownership

- Each shell and each direct process gets its own process group, which is
  killed when the call or session ends. A process that leaves the group is
  not stopped.
- A direct call returns as soon as its process exits, with the output
  available at that moment. It does not wait for children still holding the
  output pipes.
- The host program must leave these child processes to Kotlysh: do not reap
  them, and do not enable automatic `SIGCHLD` reaping.

## Stability

Kotlysh is pre-1.0, and any release can change the API. These parts are meant
to hold from 1.0:

- the runtime block shape and `"code"()` dispatch
- the `RuntimeResult` properties
- the `RuntimeSpec` extension model
- session persistence
- one dispatch at a time per session

## License

Apache License 2.0. See [LICENSE](LICENSE).
