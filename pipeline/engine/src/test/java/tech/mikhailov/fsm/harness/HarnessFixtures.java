package tech.mikhailov.fsm.harness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import tech.mikhailov.fsm.lib.Json;

/**
 * The FROZEN REFERENCE ANSWERS the engine's differential harnesses compare against.
 *
 * <p>Three families of decision classes were proven by generating cases, running them against the
 * implementation this one replaced, and diffing the answers. That implementation is gone; its ANSWERS
 * are here — committed under {@code harness/fixtures} as gzipped data, one pair of files per family:
 *
 * <table><caption>the three families</caption>
 * <tr><th>prefix</th><th>family</th><th>cases</th></tr>
 * <tr><td>{@code ""}</td><td>verdict, fix skeptic, PR maker</td><td>3 357</td></tr>
 * <tr><td>{@code input-family-}</td><td>prep prover, build reproduce input, build fix input</td>
 *     <td>2 199</td></tr>
 * <tr><td>{@code json-family-}</td><td>json-extract, parse-test, parse-fix</td><td>1 354</td></tr>
 * </table>
 *
 * <p>Nothing in these files is machine-dependent: the cases are plain JSON, and no family touches the
 * filesystem, the clock or the network. That is why there is no placeholder substitution here, and it
 * is the reason the engine's harnesses were cheaper to freeze than the runner's.
 *
 * <p>WHAT IT COSTS is in {@code harness/README.md}, per family, without hedging.
 */
final class HarnessFixtures {

    /** Relative to the module directory, which is Surefire's working directory. */
    static final Path DIR = Path.of("harness", "fixtures");

    private HarnessFixtures() {
    }

    /** The cases the recorded reference generated for one family. */
    static List<Object> cases(String prefix) {
        return read(prefix + "cases.json.gz");
    }

    /** The answers the recorded reference gave, one per case, in the same order. */
    static List<Object> jsResults(String prefix) {
        return read(prefix + "js-results.json.gz");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> read(String name) {
        Path file = DIR.resolve(name);
        if (!Files.isReadable(file)) {
            throw new UncheckedIOException(new IOException(
                    "the frozen reference answers is missing: " + file.toAbsolutePath()));
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            in.transferTo(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return (List<Object>) Json.parse(raw.toString(StandardCharsets.UTF_8));
    }
}
