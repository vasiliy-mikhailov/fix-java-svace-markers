package tech.mikhailov.fsm.orch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * What one class actually wrote to the log, as a list a test can assert on.
 *
 * <p>WHY A LOG LINE IS AN OUTPUT HERE AND NOT DECORATION. Three stages of the prove fail CLOSED: they
 * catch their own exception, return a safe default and report SUCCESS. For those three the log line is
 * the only thing that says a call failed at all — the step is green, the run history is green, and the
 * row carries a verdict that reads like a decision. A pipeline whose only evidence of a half-dead model
 * endpoint is a warning nobody asserted on has already shipped that defect twice, so the warning is
 * pinned like any other output: it has to exist, and it has to name the marker and the stage.
 *
 * <p>It sits in this package rather than inside one test class because both halves of the mechanism are
 * tested — the line at the client seam ({@code tech.mikhailov.fsm.orch.client}) and the same line
 * reached through a whole prove ({@code tech.mikhailov.fsm.orch.batch}) — and two copies of an appender
 * is two chances for one of them to filter a level differently and quietly stop watching.
 */
public final class LogLines implements AutoCloseable {

    private final ch.qos.logback.classic.Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    /** Start listening to {@code type}'s logger; {@link #close} detaches. */
    public LogLines(Class<?> type) {
        this.logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
        this.appender.start();
        this.logger.addAppender(appender);
    }

    /**
     * WARN and above, formatted.
     *
     * <p>The deployed level is INFO, so a line below WARN is one an operator does not see during an
     * outage — which is the only moment these lines exist for. Asserting on the level is therefore
     * asserting on whether the message arrives at all.
     */
    public List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * Every line, at every level.
     *
     * <p>{@link #warnings()} is the right filter for a failure that has to reach an operator mid-run.
     * This one exists for the lines written at START-UP, where the deployed level is INFO and the line
     * is the only evidence that a wiring step happened at all —
     * {@link tech.mikhailov.fsm.orch.web.BatchLiveListener} attaching itself to the batch beans is
     * exactly that case. When the dashboard shows an idle system, this line in the boot log is what
     * says whether it was ever attached, so the count in it is an output and not decoration.
     */
    public List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
