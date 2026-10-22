package com.example.whatsapp.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Direction
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Performance suite for the chat app. Run for every release on the same physical device and
 * compare the JSON output with perf/compare.py. Every iteration also saves a Perfetto trace
 * (open in https://ui.perfetto.dev).
 *
 * Runs against the `benchmark` build type: minified, non-debuggable, seeded with 60 conversations
 * and a 2,000-message chat. All tests use the same compilation mode (baseline profile if present)
 * so results are comparable between releases.
 */
@RunWith(AndroidJUnit4::class)
class ChatBenchmarks {
    private val benchmarkRule = MacrobenchmarkRule()

    // ANR/crash check wraps the whole benchmark, so any ANR during any iteration fails the test.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(NoAnrOrCrashRule()).around(benchmarkRule)

    private val compilation = CompilationMode.DEFAULT

    @Before
    fun seed() = ensureDatasetSeeded()

    /**
     * Cold start -> conversation list populated. timeToInitialDisplayMs = first frame;
     * timeToFullDisplayMs = real rows on screen (ReportDrawnWhen). Includes SQLCipher open and
     * Keystore unwrap, the main security cost on the startup path.
     */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun startupCold() = startup(StartupMode.COLD)

    /** Warm start (process alive, activity recreated). */
    @Test
    fun startupWarm() = startup(StartupMode.WARM)

    @OptIn(ExperimentalMetricApi::class)
    private fun startup(mode: StartupMode) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            MainThreadBlockingMetric(),
            // Labels get the mode appended by the library ("appInit" -> "appInitMaxMs").
            TraceSectionMetric("ChatApplication.createContainer", TraceSectionMetric.Mode.Max, label = "appInit"),
            TraceSectionMetric("Security.loadSqlcipher", TraceSectionMetric.Mode.Max, label = "loadSqlcipher"),
            TraceSectionMetric("Security.keyUnwrap", TraceSectionMetric.Mode.Max, label = "keyUnwrap"),
            TraceSectionMetric("ChatList.mapRows", TraceSectionMetric.Mode.Max, label = "chatListMap"),
        ),
        compilationMode = compilation,
        startupMode = mode,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        waitForConversationList()
    }

    /** Scroll jank on the conversation list (61+ rows; each row = last-message + unread-count query). */
    @Test
    fun conversationListScrollJank() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric(), MainThreadBlockingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForConversationList()
        },
    ) {
        flingList("conversation_list")
    }

    /**
     * Scroll jank through chat history: flinging into older messages makes Paging 3 load pages
     * from the (encrypted) DB while day separators are inserted. The main paging stress test.
     */
    @Test
    fun chatHistoryScrollJank() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric(), MainThreadBlockingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForConversationList()
            openPerfChat()
        },
    ) {
        flingList("messages_list", times = 4, firstDirection = Direction.UP)
    }

    /**
     * Live traffic while the chat is open: send messages and receive receipts, typing events and
     * replies. Each server frame is one validated DB transaction ("SyncEngine.applyFrame"), and each
     * commit invalidates the PagingSource and recomposes the visible bubbles. Catches regressions
     * in per-frame sync cost and recomposition.
     */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun liveMessagesInOpenChat() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MainThreadBlockingMetric(),
            TraceSectionMetric("SyncEngine.applyFrame", TraceSectionMetric.Mode.Average, label = "applyFrame"),
            TraceSectionMetric("SyncEngine.applyFrame", TraceSectionMetric.Mode.Max, label = "applyFrame"),
            TraceSectionMetric("SyncEngine.applyFrame", TraceSectionMetric.Mode.Count, label = "applyFrame"),
        ),
        compilationMode = compilation,
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForConversationList()
            openPerfChat()
        },
    ) {
        sendMessages(3)
        SystemClock.sleep(6_000) // acks, delivered/read receipts, typing and bot replies arrive
    }
}
