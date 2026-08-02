package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import tech.mikhailov.fsm.nodes.ParseMarkers;

/**
 * The two coercions that decide what a report ingests, pinned where they are cheap to assert.
 *
 * <p>Both are invisible when they break. A {@code path_prefix} that lost its "present but empty"
 * meaning ingests 282 absolute CI paths that exist on no checkout; an {@code include_tests} read as a
 * string puts a day of prover time into the test tree the runner refuses to edit. Neither throws, and
 * both are only visible days later.
 */
class IngestRequestTest {

    @Test
    void anAbsentPathPrefixStaysAbsentSoTheEngineUsesItsDefault() {
        IngestRequest request = new IngestRequest(null, "WebGoat/WebGoat", "main", null, null,
                null, null);

        JobParameters parameters = request.toJobParameters();
        assertThat(parameters.getParameters()).doesNotContainKey(IngestRequest.PATH_PREFIX);
        assertThat(request.body()).doesNotContainKey("path_prefix");

        // …and it survives the round trip through the job's own parameters.
        assertThat(IngestRequest.of(parameters).pathPrefix()).isNull();
        assertThat(IngestRequest.of(parameters).body()).doesNotContainKey("path_prefix");
    }

    @Test
    void anEmptyPathPrefixIsADifferentRequestAndSurvivesAsOne() {
        IngestRequest request = new IngestRequest(null, "WebGoat/WebGoat", "main", "", null,
                null, null);

        JobParameters parameters = request.toJobParameters();
        assertThat(parameters.getParameters()).containsKey(IngestRequest.PATH_PREFIX);

        IngestRequest back = IngestRequest.of(parameters);
        assertThat(back.pathPrefix()).isEmpty();
        // The engine tells the two apart with containsKey, so the key has to BE there holding "".
        assertThat(back.body()).containsEntry("path_prefix", "");
    }

    @Test
    void includeTestsBecomesARealBooleanBecauseTheEngineTestsItStrictly() {
        JobParameters off = new IngestRequest(null, "r/r", "main", null, Boolean.FALSE, null, null)
                .toJobParameters();

        // The string "false" is TRUTHY in JavaScript. Reading it as a request for the test tree is
        // the failure this coercion exists to prevent, so the body must carry Boolean.FALSE.
        assertThat(off.getString(IngestRequest.INCLUDE_TESTS)).isEqualTo("false");
        Map<String, Object> body = IngestRequest.of(off).body();
        assertThat(body).containsEntry("include_tests", Boolean.FALSE);
        assertThat(Boolean.TRUE.equals(body.get("include_tests"))).isFalse();

        JobParameters on = new IngestRequest(null, "r/r", "main", null, Boolean.TRUE, null, null)
                .toJobParameters();
        assertThat(IngestRequest.of(on).body()).containsEntry("include_tests", Boolean.TRUE);
    }

    @Test
    void aCheckerListSurvivesOneScalarJobParameter() {
        IngestRequest request = new IngestRequest("/data/svace.csv", "WebGoat/WebGoat", "develop",
                "/builds/x/", Boolean.TRUE, List.of("DEREF_OF_NULL", "RESOURCE_LEAK"), "Major");

        IngestRequest back = IngestRequest.of(request.toJobParameters());

        assertThat(back).isEqualTo(request);
        assertThat(back.body()).containsEntry("only_checkers",
                List.of("DEREF_OF_NULL", "RESOURCE_LEAK"));
        assertThat(back.body()).containsEntry("min_severity", "Major");
        assertThat(back.body()).containsEntry("csv_path", "/data/svace.csv");
    }

    @Test
    void aBlankRepoIsPassedThroughSoTheEnginesOwnMessageIsTheOneReported() {
        // The operator greps for "ingest: `repo` is required"; a different failure invented here
        // would hide it.
        Map<String, Object> body = new IngestRequest(null, null, null, null, null, null, null).body();

        assertThat(body).containsEntry("repo", "");
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(ParseMarkers.IngestFailed.class,
                () -> ParseMarkers.parseMarkers(new ParseMarkers.Request(body, "i1"))))
                .hasMessageContaining("`repo` is required");
    }
}
