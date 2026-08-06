package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * THE BOUND ON AN UNTRUSTED REPORT, and what a refused one leaves behind.
 *
 * <p>A Svace report now arrives IN the request rather than being read off a mount the client had to
 * have write access to. That is the gap it closes and it is also what makes it the one endpoint on this
 * service that takes bulk input from outside — over a port with no authentication in front of it. So
 * every case here is about the refusal rather than the happy path: a cap applied after the write is not
 * a cap, a partial file left behind is a disk somebody else filled, and a truncated report ingests as a
 * backlog that silently omits markers.
 */
class CsvSpoolTest {

    private static final String REPORT = """
            Severity,Checker,File,Line
            Critical,DEREF_OF_NULL,src/main/java/a/A.java,42
            """;

    @Nested
    class WhatArrives {

        @Test
        void anInlineReportBecomesAFileTheJobCanBeGivenThePathOf(@TempDir Path dir)
                throws Exception {
            Path file = spool(dir, 1024).spool(REPORT);

            assertThat(Files.readString(file)).isEqualTo(REPORT);
            assertThat(file.getParent()).isEqualTo(dir);
        }

        @Test
        void anUploadIsStreamedRatherThanBuffered(@TempDir Path dir) throws Exception {
            Path file = spool(dir, 10_000_000)
                    .spool(new ByteArrayInputStream("x".repeat(5_000_000).getBytes(StandardCharsets.UTF_8)));

            assertThat(Files.size(file)).isEqualTo(5_000_000);
        }

        @Test
        void utf8SurvivesBecauseAReportCarriesRealFilePaths(@TempDir Path dir) throws Exception {
            String report = "Severity,Checker,File,Line\nMinor,X,src/main/java/café/Ünicode.java,3\n";
            Path file = spool(dir, 4096).spool(report);

            assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(report);
        }

        @Test
        void theFileIsNamedByThisClassAndNeverByTheClient(@TempDir Path dir) throws Exception {
            // An upload's own filename is an untrusted string one `../` away from being a path. It is
            // not an input to this class at all, which is the only way to be sure of that.
            Path file = spool(dir, 1024).spool(REPORT);

            assertThat(file.getFileName().toString()).startsWith(CsvSpool.PREFIX)
                    .endsWith(CsvSpool.SUFFIX);
        }

        @Test
        void twoUploadsInTheSameMillisecondAreTwoFiles(@TempDir Path dir) throws Exception {
            CsvSpool spool = spool(dir, 4096);

            assertThat(spool.spool(REPORT)).isNotEqualTo(spool.spool(REPORT));
        }
    }

    @Nested
    class WhatIsRefused {

        @Test
        void aReportOverTheLimitIsRefusedRatherThanTruncated(@TempDir Path dir) {
            // Truncation is the dangerous answer: half a report ingests as a backlog that silently
            // omits markers, and nothing anywhere says so.
            assertThatThrownBy(() -> spool(dir, 10).spool(REPORT))
                    .isInstanceOf(CsvSpool.TooLarge.class);
        }

        @Test
        void theRefusalNamesTheLimitAndTheKnobThatChangesIt(@TempDir Path dir) {
            CsvSpool spool = spool(dir, 32);

            assertThatThrownBy(() -> spool.spool(REPORT))
                    .isInstanceOf(CsvSpool.TooLarge.class)
                    .hasMessageContaining("32")
                    // Without this a client sees "too large" and has no way to learn what "large" is
                    // on this deployment or who can change it.
                    .hasMessageContaining("FSM_INGEST_MAX_CSV_BYTES")
                    .satisfies(e -> assertThat(((CsvSpool.TooLarge) e).limit()).isEqualTo(32));
        }

        @Test
        void anUploadThatGoesOverIsStoppedMidWriteAndLeavesNothingBehind(@TempDir Path dir) {
            // THE ASSERTION THAT MATTERS: a cap applied to a file already written is not a cap. A
            // 4 GB upload against a 1 kB limit must cost 1 kB of disk, not 4 GB and then a refusal.
            CsvSpool spool = spool(dir, 1000);

            assertThatThrownBy(() -> spool.spool(endless()))
                    .isInstanceOf(CsvSpool.TooLarge.class);
            assertThat(listSpooled(dir))
                    .as("a refused upload must not leave its partial file on the disk it was "
                            + "supposed to protect")
                    .isEmpty();
        }

        @Test
        void anUploadExactlyAtTheLimitIsAccepted(@TempDir Path dir) throws Exception {
            // The boundary belongs to the accepted side: a documented limit that refuses a report of
            // exactly that size is a documented limit that is wrong.
            byte[] exact = "x".repeat(500).getBytes(StandardCharsets.UTF_8);
            Path file = spool(dir, 500).spool(new ByteArrayInputStream(exact));

            assertThat(Files.size(file)).isEqualTo(500);
        }

        @Test
        void aStreamThatDiesMidWayLeavesNothingBehindEither(@TempDir Path dir) {
            assertThatThrownBy(() -> spool(dir, 10_000).spool(new InputStream() {
                private int served;

                @Override
                public int read() throws IOException {
                    throw new IOException("the socket went away");
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (served++ == 0) {
                        java.util.Arrays.fill(b, off, off + 100, (byte) 'x');
                        return 100;
                    }
                    throw new IOException("the socket went away");
                }
            })).isInstanceOf(IOException.class);

            assertThat(listSpooled(dir)).isEmpty();
        }
    }

    @Nested
    class WhatIsLeftLyingAround {

        @Test
        void aStaleReportIsSweptOnTheNextUpload(@TempDir Path dir) throws Exception {
            Path stale = Files.createFile(dir.resolve(CsvSpool.PREFIX + "old" + CsvSpool.SUFFIX));
            Files.setLastModifiedTime(stale, FileTime.from(
                    Instant.now().minus(CsvSpool.TTL).minus(Duration.ofMinutes(1))));

            Path fresh = spool(dir, 4096).spool(REPORT);

            // A report holds somebody else's source paths and findings. It should not sit in a shared
            // temp directory for the life of the container.
            assertThat(stale).doesNotExist();
            assertThat(fresh).exists();
        }

        @Test
        void aReportFromAnIngestStillRunningIsNotSwept(@TempDir Path dir) throws Exception {
            // A failed ingest is re-launched with the SAME parameters. A file swept out from under it
            // would answer that re-launch with "no CSV at …" instead of the operator's real mistake.
            Path recent = Files.createFile(dir.resolve(CsvSpool.PREFIX + "recent" + CsvSpool.SUFFIX));

            spool(dir, 4096).spool(REPORT);

            assertThat(recent).exists();
        }

        @Test
        void nothingThisClassDidNotWriteIsEverDeleted(@TempDir Path dir) throws Exception {
            // The directory is configurable, so it may be one somebody pointed at something else. A
            // sweep by age alone would be a scheduled `rm` on a path in a config file.
            Path someoneElses = Files.createFile(dir.resolve("please-keep-me.csv"));
            Files.setLastModifiedTime(someoneElses, FileTime.from(Instant.EPOCH));

            spool(dir, 4096).spool(REPORT);

            assertThat(someoneElses).exists();
        }

        /**
         * AND IT IS THE OWNER'S ALONE. The class comment lists four properties that make a report
         * untrusted input, and three of them are pinned above; this is the fourth, and it is the only
         * one that decides whether a third party's source paths and findings sit world-readable in a
         * directory under {@code java.io.tmpdir} that every process on the host can list. Untested
         * until 2026-08-06.
         *
         * <p>Guarded on POSIX being available, exactly as the production code is: the permissions are
         * best effort there — a filesystem that cannot say so is not a reason to refuse an upload —
         * so a test that demanded them everywhere would be asserting more than the code promises.
         */
        @Test
        void theSpoolDirectoryIsReadableOnlyByTheProcessThatOwnsIt(@TempDir Path parent)
                throws Exception {
            Path dir = parent.resolve("spool");
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                    "no POSIX permissions on this filesystem; the production code treats that as "
                    + "acceptable and so does this");

            spool(dir.toString(), 4096).spool(REPORT);

            assertThat(Files.getPosixFilePermissions(dir))
                    .as("a Svace report is somebody else's source paths and findings, spooled into a "
                            + "shared temp directory")
                    .containsExactlyInAnyOrderElementsOf(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }

        @Test
        void aSpoolDirectoryThatIsNotThereYetIsMadeRatherThanRefused(@TempDir Path parent)
                throws Exception {
            // The default is under java.io.tmpdir, which no deployment step creates, so the FIRST
            // upload a container ever receives takes this path. Refusing it would make the endpoint
            // work only on the second try.
            Path dir = parent.resolve("nested").resolve("deeper");

            assertThat(Files.readString(spool(dir.toString(), 4096).spool(REPORT))).isEqualTo(REPORT);
            assertThat(dir).isDirectory();
        }
    }

    @Test
    void withNoDirectoryConfiguredItLandsInTheContainersTempAndNotOnAMount() {
        // The point of accepting the report in the request is that a client no longer needs write
        // access to a volume this container shares. Demanding a NEW mount to hold the upload would put
        // that requirement straight back — so the default is the one directory every container has.
        CsvSpool spool = new CsvSpool(1024, "  ");

        assertThat(spool.dir())
                .isEqualTo(Path.of(System.getProperty("java.io.tmpdir")).resolve(CsvSpool.DIR_NAME));
    }

    private static CsvSpool spool(Path dir, long maxBytes) {
        return new CsvSpool(maxBytes, dir == null ? null : dir.toString());
    }

    private static CsvSpool spool(String dir, long maxBytes) {
        return new CsvSpool(maxBytes, dir);
    }

    /** More bytes than any limit under test, without allocating them. */
    private static InputStream endless() {
        return new InputStream() {
            @Override
            public int read() {
                return 'x';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                java.util.Arrays.fill(b, off, off + len, (byte) 'x');
                return len;
            }
        };
    }

    private static List<Path> listSpooled(Path dir) {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(CsvSpool.PREFIX)).toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
