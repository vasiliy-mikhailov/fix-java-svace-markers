package tech.mikhailov.fsm.orch.batch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A SVACE REPORT THE CLIENT SENT, put where the ingest job can read it.
 *
 * <p>THE GAP THIS CLOSES. {@code /api/ingest} took only {@code csvPath}, and that path was resolved in
 * the ORCHESTRATOR's filesystem — so ingesting required write access to the container's {@code /data}
 * mount BEFORE the API could be called at all. That is a shared-filesystem assumption wearing an HTTP
 * interface: a Guild with a report on a laptop had no way to use the endpoint. Now the report travels in
 * the request — inline in the JSON body, or as a multipart upload — and this class is the few lines
 * between the socket and the job.
 *
 * <p>WHY THERE IS A FILE AT ALL, when the engine's {@code Parse markers} accepts an inline
 * {@code csv_text} and is tested against it. Because the ingest is a Spring Batch JOB, and what the
 * tasklet reads is JOB PARAMETERS: one scalar each, in a {@code VARCHAR(2500)} column of the run history.
 * A 30 kB report does not fit in one and a 30 MB report is not close. The alternatives were holding the
 * text in a map keyed by a launch id — which a restart loses, with the job then failing on a report
 * nobody can resend — or streaming it into the job's execution context, which is the same column with an
 * extra step. So the bytes land in a file THIS PROCESS owns and the job is given its path, which is the
 * {@code csvPath} machinery that already exists and is already tested. The client's own {@code csvPath}
 * keeps working beside it, untouched, because the bundled example and the deployed runbooks use it.
 *
 * <p>AND THE DIRECTORY IS THE CONTAINER'S TEMP, deliberately NOT a mount. The whole point of accepting
 * the report in the request is that a client no longer needs write access to a volume this container
 * shares; requiring a new one to hold the upload would put the gap straight back. A container's temp
 * directory is writable by definition, needs no compose change, no bind and no chown.
 *
 * <p>A REPORT IS UNTRUSTED INPUT, and this class treats it as such:
 * <ul>
 *   <li>THE SIZE IS BOUNDED WHILE IT IS BEING WRITTEN, not after. A cap applied to a file already on
 *       disk is not a cap.</li>
 *   <li>IT REFUSES RATHER THAN TRUNCATES. Half a report ingests as a backlog that silently omits
 *       markers — the one outcome an operator cannot see. {@link TooLarge} becomes a 413 naming the
 *       limit.</li>
 *   <li>THE FILENAME IS THIS CLASS'S, never the client's. An upload's own name is an untrusted string
 *       one {@code ../} away from being a path.</li>
 *   <li>STALE FILES ARE SWEPT, by age, on every spool. A report holds somebody's source paths and
 *       findings; it should not sit in a shared temp directory for the life of the container.</li>
 * </ul>
 */
public class CsvSpool {

    private static final Logger log = LoggerFactory.getLogger(CsvSpool.class);

    /** Under {@code java.io.tmpdir} when no directory is configured. */
    public static final String DIR_NAME = "fsm-ingest";

    /** Every file this class writes starts with it, so the sweep can never touch anything else. */
    public static final String PREFIX = "ingest-";

    /** …and ends with it, so a stray file is recognisable in a {@code docker exec ls}. */
    public static final String SUFFIX = ".csv";

    /**
     * How long a spooled report may outlive its ingest.
     *
     * <p>Six hours rather than minutes: a job that failed on a bad {@code path_prefix} is re-launched
     * with the same parameters, and a file swept out from under it would answer that re-launch with
     * "no CSV at …" instead of the operator's actual mistake. Rather than seconds-accurate cleanup this
     * is a bound on how long third-party findings sit in a temp directory.
     */
    public static final Duration TTL = Duration.ofHours(6);

    /** Owner only. The report is somebody else's source paths, on a directory other things share. */
    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rwx------");

    /** The refusal, carrying the two numbers the answer has to quote. */
    public static final class TooLarge extends Exception {

        private static final long serialVersionUID = 1L;

        private final long limit;

        /**
         * Public because the container refuses an oversized multipart BEFORE any of this class's code
         * runs, and the endpoint answers that with the same document — one refusal, one message, one
         * number, whichever of the three enforcement points fired.
         */
        public TooLarge(long limit) {
            super("the report is larger than the " + limit + "-byte limit (fsm.ingest.max-csv-bytes, "
                    + "FSM_INGEST_MAX_CSV_BYTES). Filter the report, or raise the limit.");
            this.limit = limit;
        }

        /** The bound that was exceeded, so the caller does not restate it and get it wrong. */
        public long limit() {
            return limit;
        }
    }

    private final long maxBytes;
    private final Path dir;

    /**
     * @param maxBytes {@code fsm.ingest.max-csv-bytes}
     * @param dir      {@code fsm.ingest.spool-dir}; blank means {@code java.io.tmpdir/}{@value #DIR_NAME}
     */
    public CsvSpool(long maxBytes, String dir) {
        this.maxBytes = maxBytes;
        this.dir = dir == null || dir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir")).resolve(DIR_NAME)
                : Path.of(dir.trim());
    }

    /** The bound, so the 413 quotes the number that actually applied. */
    public long maxBytes() {
        return maxBytes;
    }

    /** Where reports land. In the boot log, because an operator debugging an ingest wants it. */
    public Path dir() {
        return dir;
    }

    /**
     * A report that arrived inline in the JSON body.
     *
     * <p>CHECKED IN BYTES, TWICE, and never in characters: the string is encoded first and it is the
     * encoded length that is compared here, then compared again as the stream is written. The
     * duplicate is worth its line — Jackson has already materialised the whole string by the time this
     * is called, so refusing it here saves creating and deleting a temp file for a body already known
     * to be over, and it is the encoded length that makes the 413 quote a number the sender can act
     * on. (It said "in CHARACTERS before it is encoded" until 2026-08-06, which mattered: characters
     * and bytes differ by up to 4× on a UTF-8 report, so a reader trusting that sentence would have
     * believed a 32 MiB cap could refuse an 8 MiB file.) The write check is what makes the bound real
     * for the multipart path that shares it.
     */
    public Path spool(String text) throws TooLarge, IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new TooLarge(maxBytes);
        }
        return spool(new java.io.ByteArrayInputStream(bytes));
    }

    /**
     * A report that arrived as an upload, streamed rather than buffered.
     *
     * <p>THE CAP IS APPLIED WHILE WRITING. A check after the fact is not a check: the bytes are already
     * on the disk it was supposed to protect. A stream that goes over is abandoned and its partial file
     * removed, so a refused upload leaves nothing behind.
     */
    public Path spool(InputStream in) throws TooLarge, IOException {
        prepareDirectory();
        sweep();
        Path file = Files.createTempFile(dir, PREFIX, SUFFIX);
        long written = 0;
        try (OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                written += read;
                if (written > maxBytes) {
                    // Closed and deleted in the finally-shaped catch below; nothing partial survives.
                    throw new TooLarge(maxBytes);
                }
                out.write(buffer, 0, read);
            }
        } catch (TooLarge | IOException | RuntimeException failed) {
            Files.deleteIfExists(file);
            throw failed;
        }
        log.info("[ingest] spooled {} byte(s) of report to {}", written, file);
        return file;
    }

    /**
     * Make the directory, owner-readable only where the filesystem can say so.
     *
     * <p>Best effort on the permissions and NOT a refusal to start: a spool that cannot be locked down
     * on a filesystem with no POSIX bits is still the right place for the file, and refusing here would
     * mean an endpoint that works on Linux and 500s on a developer's machine.
     */
    private void prepareDirectory() throws IOException {
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException notPosix) {
            log.debug("[ingest] could not restrict {} to its owner: {}", dir, notPosix.toString());
        }
    }

    /**
     * Delete this class's own leftovers older than {@link #TTL}.
     *
     * <p>ONLY files this class named — {@link #PREFIX}/{@link #SUFFIX} — because the configured
     * directory may be one somebody pointed at something else, and a sweep that deleted by age alone
     * would be a scheduled {@code rm} on a path in a config file.
     *
     * <p>It never fails a request. A spool that could not tidy up is still a spool that works, and the
     * ingest a client is waiting on must not fail because a stale file is held open.
     */
    private void sweep() {
        long cutoff = System.currentTimeMillis() - TTL.toMillis();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) {
                    continue;
                }
                try {
                    if (Files.isRegularFile(file)
                            && Files.getLastModifiedTime(file).toMillis() < cutoff) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException stubborn) {
                    log.debug("[ingest] could not sweep {}: {}", file, stubborn.toString());
                }
            }
        } catch (IOException cannotList) {
            log.debug("[ingest] could not sweep {}: {}", dir, cannotList.toString());
        }
    }
}
