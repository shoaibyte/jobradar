package dev.shoaib.jobradar.sources.ats;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * Only covers the "not asked to detect anything" no-op path. {@link AtsDetectRunner#run} calls
 * {@code System.exit(...)} when {@code app.detect-ats} is set, which would tear down the whole
 * Surefire JVM (and every other test in it) if invoked here -- that behaviour is exercised
 * manually / by the orchestrator instead, not under a test runner.
 */
class AtsDetectRunnerTest {

    @Test
    void doesNothingWhenDetectAtsUrlIsBlank() throws Exception {
        AtsDetectRunner runner = new AtsDetectRunner("");
        runner.run(new DefaultApplicationArguments());
        // No exception, no exit -- reaching this line is the assertion.
    }

    @Test
    void doesNothingWhenDetectAtsUrlIsNull() throws Exception {
        AtsDetectRunner runner = new AtsDetectRunner(null);
        runner.run(new DefaultApplicationArguments());
    }
}
