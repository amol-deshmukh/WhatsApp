package com.example.whatsapp.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

const val TARGET_PACKAGE = "com.example.whatsapp"

/** Title of the 2,000-message chat seeded by the app's `benchmark` build type. */
const val PERF_CHAT_TITLE = "Perf Load Test"

private const val TIMEOUT_MS = 15_000L
private const val FLING_SETTLE_MS = 800L

/**
 * The first launch of a fresh install seeds the large dataset. Do that once, outside any measured
 * block, so seeding never pollutes startup numbers.
 */
fun ensureDatasetSeeded() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    device.executeShellCommand("am start -W -n $TARGET_PACKAGE/.MainActivity")
    check(device.wait(Until.hasObject(By.text(PERF_CHAT_TITLE)), 60_000)) {
        "Perf dataset never appeared. Is the app built with the `benchmark` build type?"
    }
    device.pressHome()
}

/** Blocks until real conversations are on screen (not the loading spinner). */
fun MacrobenchmarkScope.waitForConversationList() {
    check(device.wait(Until.hasObject(By.res("conversation_row")), TIMEOUT_MS)) {
        "Conversation list never populated."
    }
}

/** Opens the 2,000-message chat and waits for its first page of messages. */
fun MacrobenchmarkScope.openPerfChat() {
    // Click the clickable row, not the Text node inside it.
    device.findObject(By.res("conversation_row").hasDescendant(By.text(PERF_CHAT_TITLE))).click()
    check(device.wait(Until.hasObject(By.res("message_row")), TIMEOUT_MS)) { "Chat never showed messages." }
}

/**
 * Flings a list one way then back. Fixed waits keep the scenario identical across runs.
 * For the chat (reverseLayout), flinging UP scrolls into older history, which makes Paging load pages.
 *
 * Uses UiDevice.swipe rather than UiObject2.fling: fling() waits up to 5s for a
 * TYPE_VIEW_SCROLLED accessibility event that Compose's LazyColumn doesn't send, which adds 5s of
 * idle time to every gesture (measured: ~6s per fling instead of ~0.8s).
 */
fun MacrobenchmarkScope.flingList(resId: String, times: Int = 3, firstDirection: Direction = Direction.DOWN) {
    val bounds = device.findObject(By.res(resId)).visibleBounds
    val x = bounds.centerX()
    // Keep gestures away from the edges so they aren't treated as system back/home gestures.
    val top = bounds.top + bounds.height() / 5
    val bottom = bounds.bottom - bounds.height() / 5
    // Direction is where the content scrolls to: DOWN reveals items below, so the finger moves up.
    fun fling(direction: Direction) = if (direction == Direction.DOWN) {
        device.swipe(x, bottom, x, top, FLING_STEPS)
    } else {
        device.swipe(x, top, x, bottom, FLING_STEPS)
    }
    val back = if (firstDirection == Direction.DOWN) Direction.UP else Direction.DOWN
    repeat(times) {
        fling(firstDirection)
        SystemClock.sleep(FLING_SETTLE_MS)
    }
    repeat(times) {
        fling(back)
        SystemClock.sleep(FLING_SETTLE_MS)
    }
}

/** ~5ms per step, so 8 steps is a fast swipe that releases with fling velocity. */
private const val FLING_STEPS = 8

/** Types and sends [count] messages through the real composer (optimistic insert -> outbox -> ack). */
fun MacrobenchmarkScope.sendMessages(count: Int) {
    repeat(count) { i ->
        device.findObject(By.res("composer_input")).text = "perf message $i"
        device.findObject(By.res("send_button")).click()
        SystemClock.sleep(300)
    }
}

/**
 * Main-thread responsiveness metric computed from the Perfetto trace: the leading indicator for
 * ANRs (the system raises an ANR when input isn't handled within 5s).
 *
 * Considers top-level slices on the app's main thread (e.g. Choreographer#doFrame, input,
 * binder calls, activity lifecycle) during the measured block and reports:
 *  - mainThreadLongestSliceMs:          the single longest blocking slice
 *  - mainThreadSlicesOver{N}msCount:    how many slices exceeded the threshold
 *  - mainThreadBlockedOver{N}msTotalMs: total time spent in those long slices
 */
@OptIn(ExperimentalMetricApi::class)
class MainThreadBlockingMetric(private val thresholdMs: Int = 100) : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val thresholdNs = thresholdMs * 1_000_000L
        val row = traceSession.query(
            """
            SELECT
              COALESCE(MAX(s.dur), 0) AS longest,
              COALESCE(SUM(CASE WHEN s.dur > $thresholdNs THEN 1 ELSE 0 END), 0) AS long_count,
              COALESCE(SUM(CASE WHEN s.dur > $thresholdNs THEN s.dur ELSE 0 END), 0) AS long_total
            FROM slice s
            JOIN thread_track tt ON s.track_id = tt.id
            JOIN thread USING (utid)
            JOIN process USING (upid)
            WHERE process.name = '${captureInfo.targetPackageName}'
              AND thread.tid = process.pid
              AND s.depth = 0
            """.trimIndent(),
        ).first()
        return listOf(
            Measurement("mainThreadLongestSliceMs", row.long("longest") / 1e6),
            Measurement("mainThreadSlicesOver${thresholdMs}msCount", row.long("long_count").toDouble()),
            Measurement("mainThreadBlockedOver${thresholdMs}msTotalMs", row.long("long_total") / 1e6),
        )
    }
}

/**
 * Fails the test if the app recorded a new ANR or crash while it ran. Reads the system DropBox,
 * which is where ActivityManager stores ANR/crash reports.
 */
class NoAnrOrCrashRule(private val packageName: String = TARGET_PACKAGE) : TestRule {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val anrsBefore = count("data_app_anr")
            val crashesBefore = count("data_app_crash")
            base.evaluate()
            val newAnrs = count("data_app_anr") - anrsBefore
            val newCrashes = count("data_app_crash") - crashesBefore
            check(newAnrs <= 0 && newCrashes <= 0) {
                "${description.methodName}: $newAnrs new ANR(s), $newCrashes new crash(es) in $packageName. " +
                    "Inspect with: adb shell dumpsys dropbox --print data_app_anr"
            }
        }
    }

    private fun count(tag: String): Int =
        device.executeShellCommand("dumpsys dropbox --print $tag")
            .split("Process: $packageName").size - 1
}
