package com.kotlysh.samples

import com.kotlysh.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.serialization.Serializable

/*
 * A repository survey. Each function below is one runtime doing what it is best at and returning a Kotlin value;
 * main() wires them together with async/await, and the closing chart is drawn from the run's own trace.
 */

@Serializable data class Entry(val name: String, val lines: Int)
@Serializable data class Survey(val modules: List<Entry>, val largest: List<Entry>)
@Serializable data class Lane(val runtime: String, val from: Long, val to: Long, val busy: List<List<Long>>, val killed: Boolean)

suspend fun repository(): String = sh { "git rev-parse --show-toplevel"().orThrow().stdoutText }

suspend fun sources(repo: String): List<String> = sh { "git -C ${shLiteral(repo)} ls-files '*.kt'"().orThrow().stdout }

/** Commits per active day. The shell lists; Kotlin counts. */
suspend fun cadence(repo: String): List<Int> = sh {
    "git -C ${shLiteral(repo)} log --reverse --format=%ad --date=format:%Y-%m-%d"().orThrow().stdout
}.groupingBy { it }.eachCount().values.toList()

/** A table of named probes stays real shell. Held, nothing runs; invoked, each probe gets its own parallel session. */
suspend fun census(repo: String): Map<String, Int> = sh {
    val enter = "cd ${shLiteral(repo)}"(hold)
    val probes = mapOf(
        "commits" to "git rev-list --count HEAD"(hold),
        "tracked files" to "git ls-files | wc -l"(hold),
        "test classes" to "git ls-files '*Test.kt' | wc -l"(hold),
        "suspend funs" to "git grep -h 'suspend fun' -- '*.kt' | wc -l"(hold),
        "contributors" to "git shortlog -sn HEAD | wc -l"(hold),
    )
    probes.mapValues { (_, probe) ->
        async { sh { enter().orThrow(); probe().orThrow().stdoutText.trim().toInt() } }
    }.mapValues { it.value.await() }
}

/** Python reads the corpus once into a live object; Kotlin then asks it two typed questions. */
suspend fun survey(repo: String, sources: List<String>): Survey = py {
    """
        import collections, os

        class Corpus:
            def __init__(self, root, paths):
                self.lines = {}
                for path in paths:
                    with open(os.path.join(root, path), 'rb') as source:
                        self.lines[path] = sum(1 for _ in source)

            def modules(self, top):
                totals = collections.Counter()
                for path, count in self.lines.items():
                    totals['/'.join(path.split('/')[:2])] += count
                return [{'name': name, 'lines': lines} for name, lines in totals.most_common(top)]

            def largest(self, top):
                ranked = sorted(self.lines.items(), key=lambda item: -item[1])[:top]
                return [{'name': os.path.basename(path), 'lines': lines} for path, lines in ranked]
    """().orThrow()
    hold("Corpus(root, paths)", "root" to value(repo), "paths" to value(sources)) { corpus ->
        Survey(eval("corpus.modules(5)", "corpus" to corpus), eval("corpus.largest(5)", "corpus" to corpus))
    }
}

/** Three languages fingerprint the same sources. The first answer wins and the other interpreters are cancelled. */
suspend fun seal(repo: String, sources: List<String>): String = coroutineScope {
    val root = "root" to value(repo)
    val paths = "paths" to value(sources)
    select {
        async {
            sh { "cd ${shLiteral(repo)} && git ls-files -z '*.kt' | xargs -0 cat | sha256sum | cut -c1-16"().orThrow().stdoutText } + " by sha256sum"
        }.onAwait { it }
        async {
            py {
                "import hashlib, os"().orThrow()
                eval<String>("hashlib.sha256(b''.join(open(os.path.join(root, path), 'rb').read() for path in paths)).hexdigest()[:16]", root, paths)
            } + " by hashlib"
        }.onAwait { it }
        async {
            node {
                """
                    globalThis.fingerprint = (root, paths) => {
                      const fs = require('fs'), path = require('path'), hash = require('crypto').createHash('sha256');
                      for (const file of paths) hash.update(fs.readFileSync(path.join(root, file)));
                      return hash.digest('hex').slice(0, 16);
                    }
                """().orThrow()
                eval<String>("fingerprint(root, paths)", root, paths)
            } + " by node:crypto"
        }.onAwait { it }
    }.also { coroutineContext.cancelChildren() }
}

/** Node owns presentation: typed data in, finished text out. */
suspend fun render(census: Map<String, Int>, survey: Survey, cadence: List<Int>, timeline: List<Lane>): String = node {
    """
        const bars = entries => {
          const most = Math.max(...entries.map(entry => entry.lines));
          return entries.map(({name, lines}) => '   ' + name.padEnd(38) + '█'.repeat(Math.ceil(lines / most * 36)) + ' ' + lines);
        }
        const spark = counts => counts.map(n => '▁▂▃▄▅▆▇█'[Math.min(7, Math.floor(n / Math.max(...counts) * 8))]).join('');
        const chart = (lanes, width = 84) => {
          const end = Math.max(...lanes.map(lane => lane.to)), at = t => Math.min(width - 1, Math.floor(t / end * width));
          const color = {sh: 36, py: 33, node: 32};
          return lanes.map(lane => {
            const row = Array(width).fill(' ');
            for (let i = at(lane.from); i <= at(lane.to); i++) row[i] = '─';
            for (const [from, to] of lane.busy) for (let i = at(from); i <= at(to); i++) row[i] = '█';
            if (lane.killed) row[at(lane.to)] = '✗';
            return '   \x1b[' + color[lane.runtime] + 'm' + lane.runtime.padEnd(5) + row.join('') + '\x1b[0m';
          }).concat('        0' + String(end + ' ms').padStart(width - 1));
        }
        globalThis.report = (census, survey, cadence, timeline) => [
          '', ' ' + Object.entries(census).map(([name, count]) => count + ' ' + name).join(' · '),
          '', ' Kotlin by module', ...bars(survey.modules),
          '', ' Largest files', ...bars(survey.largest),
          '', ' Commits per active day', '   ' + spark(cadence) + '  peak ' + Math.max(...cadence),
          '', ' What ran, as the runtime recorded it   █ running  ─ alive  ✗ killed', ...chart(timeline), '',
        ].join('\n');
    """().orThrow()
    eval(
        "report(census, survey, cadence, timeline)",
        "census" to value(census), "survey" to value(survey), "cadence" to value(cadence), "timeline" to value(timeline),
    )
}

/** One lane per interpreter: when it lived, when it worked, and whether it was killed. */
fun timeline(events: List<RuntimeTraceEvent>): List<Lane> {
    val origin = events.minOf { it.atEpochMillis }
    return events.groupBy { it.sessionId }.values.map { session ->
        val started = session.filterIsInstance<RuntimeDispatchStarted>().associate { it.dispatchId to it.atEpochMillis - origin }
        val busy = session.mapNotNull { event ->
            when (event) {
                is RuntimeDispatchCompleted -> started[event.dispatchId]?.let { listOf(it, it + event.elapsedMillis) }
                is RuntimeDispatchFailed -> started[event.dispatchId]?.let { listOf(it, it + event.elapsedMillis) }
                else -> null
            }
        }
        Lane(session.first().runtime, session.first().atEpochMillis - origin, session.last().atEpochMillis - origin, busy,
            session.any { it is RuntimeSessionClosed && it.poisoned })
    }
}

fun main(): Unit = runBlocking {
    val trace = RuntimeTraceBuffer()
    val (census, survey, cadence, seal) = withRuntimeTrace(trace) {
        val repo = repository()
        val sources = async { sources(repo) }
        val census = async { census(repo) }
        val cadence = async { cadence(repo) }
        val survey = async { survey(repo, sources.await()) }
        val seal = async { seal(repo, sources.await()) }
        Results(census.await(), survey.await(), cadence.await(), seal.await())
    }
    println(render(census, survey, cadence, timeline(trace.events)))
    println(" Sources sealed as $seal, the first of three implementations to answer\n")
}

private data class Results(val census: Map<String, Int>, val survey: Survey, val cadence: List<Int>, val seal: String)
