package tech.mikhailov.fsm.orch.client;

import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * {@link SourceReader} with no socket — the runner's read-only checkout, read out of this process.
 *
 * <p>NOT SERIALISED, exactly as the HTTP route was not: {@code /fs/read_file} is answered off a SEPARATE
 * read-only clone that no build ever patches, so a reviewer opening a marker does not queue behind a
 * ninety-minute prove and never sees a tree with the fixer's edit half applied.
 */
public class LocalSourceReader implements SourceReader {

    /** What the "source unavailable" line says instead of a URL, and what the boot log prints. */
    static final String WHERE = "the in-process runner (fsm.runner.mode=local)";

    private final UnaryOperator<Map<String, Object>> readFile;

    /**
     * @param readFile {@code tech.mikhailov.fsm.runner.LocalRunner#readFile} — narrowed to a function so
     *                 this class carries no knowledge of the workspace, the cache or the queue
     */
    public LocalSourceReader(UnaryOperator<Map<String, Object>> readFile) {
        this.readFile = readFile;
    }

    @Override
    public Object read(Map<String, Object> body) {
        return readFile.apply(body);
    }

    @Override
    public String describe() {
        return WHERE;
    }
}
