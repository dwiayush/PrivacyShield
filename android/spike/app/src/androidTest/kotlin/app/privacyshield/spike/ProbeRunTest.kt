package app.privacyshield.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs every probe once. Probes RECORD what the device does; they never fail on an unexpected
 * answer, because an unexpected answer is exactly the data we want. The test fails only if nothing
 * was emitted at all. Results are read from logcat (tag PS_PROBE) by tools/aggregate_probes.py.
 */
@RunWith(AndroidJUnit4::class)
class ProbeRunTest {
    @Test
    fun runAllProbes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val lines = Probes.runAll(instrumentation.targetContext, instrumentation)
        assertTrue("no probe output was produced", lines.isNotEmpty())
    }
}
