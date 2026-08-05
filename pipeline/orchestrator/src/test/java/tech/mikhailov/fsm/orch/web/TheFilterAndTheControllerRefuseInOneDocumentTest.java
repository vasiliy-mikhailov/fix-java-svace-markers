package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code POST /api/ingest} REFUSES IN ONE DOCUMENT, whichever of its two mouths does the refusing.
 *
 * <p>THE ENDPOINT HAS TWO. {@link IngestSizeLimit} answers the 413 from inside the filter chain, before
 * any controller method exists; {@link JobsController} answers the 400 and the 409 from inside
 * {@code @RequestMapping}. They are different layers, they are written by different code, and their
 * only shared thing is {@link JobsPresenter} and the {@code ObjectMapper} bean.
 *
 * <p>WHY THAT SHARING WAS DONE, AND WHY IT NEEDED THIS TEST. The filter used to interpolate its body
 * out of a text block — the one hand-written JSON document in the module — and the argument for
 * replacing it with the presenter and Jackson was that "does the filter's 413 still match the
 * controller's 400?" should be answered by the code rather than by a person comparing two documents by
 * eye. That argument is only worth anything if something FAILS when the two stop matching. Nothing did:
 * {@link IngestSizeLimitTest} asserts with {@code contains(…)}, which passes under any key order and any
 * reordering of the document, and it builds the filter with {@code new ObjectMapper()} while production
 * injects the bean — so it could not see a difference between the two mappers, which is precisely the
 * axis the change introduced. {@link NoControllerAssemblesItsOwnResponseTest} cannot help either: it
 * enumerates classes carrying controller ANNOTATIONS, and an {@code OncePerRequestFilter} carries none.
 *
 * <p>SO BOTH DOCUMENTS HERE ARE REAL WIRE BYTES, off real requests, through the real filter chain and
 * the real message converter. Nothing is constructed by hand — which also makes this the only test in
 * the suite that would fail if the filter bean stopped being REGISTERED at all, because a filter nobody
 * added to the chain answers nothing and a 34 MB request would simply be ingested.
 *
 * <p>THE BOUND IS LOWERED TO 1000 BYTES for this context, so that "one byte over" is a 1 MiB request
 * instead of a 33 MiB one. The number travels into the document, which is what makes the pin below a
 * fixed 260 bytes rather than something that moves with a deployment's configuration.
 */
@SpringBootTest(properties = "fsm.ingest.max-csv-bytes=1000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TheFilterAndTheControllerRefuseInOneDocumentTest {

    /** {@code fsm.ingest.max-csv-bytes}, as this context is configured above. */
    private static final long REPORT_LIMIT = 1000;

    /** What the filter therefore bounds the REQUEST at. @see IngestSizeLimit#ENVELOPE */
    private static final long REQUEST_LIMIT = REPORT_LIMIT + IngestSizeLimit.ENVELOPE;

    /**
     * THE 413, BYTE FOR BYTE — every key, every value, the order they are written in, and the fact that
     * there is no whitespace between them.
     *
     * <p>This is the document that replaced a hand-written text block, and it is byte-identical to the
     * one that block produced. Stating it as a literal rather than as four {@code contains(…)} calls is
     * the whole point: a reordering, an added field, an indenting mapper, a naming strategy or a second
     * {@code ObjectMapper} configured differently from the bean all change these bytes and none of them
     * change a {@code contains}.
     */
    private static final String THE_413 =
            "{\"started\":false,\"job\":\"ingest\",\"reason\":\"the request is larger than 1049576 "
            + "bytes. A Svace report may be up to 1000 bytes (fsm.ingest.max-csv-bytes, "
            + "FSM_INGEST_MAX_CSV_BYTES), plus room for the request that carries it. Filter the "
            + "report, or raise the limit.\"}";

    /**
     * …and the same thing said in a form nobody can edit into agreement by hand.
     *
     * <p>The literal above is readable and therefore correctable: a person changing the document could
     * change the expectation in the same keystroke and never notice they had. The digest is not
     * derivable from a diff, so restoring it after a deliberate change is a deliberate act.
     */
    private static final String THE_413_SHA256 =
            "1636b280983504278459d991264390b2548e5bd9e14d5844fcee67ddfa7a77c1";

    @Autowired
    private MockMvc mvc;

    /**
     * THE MAPPER THE CONTAINER INJECTS INTO THE FILTER — the bean, not a fresh one.
     *
     * <p>It is autowired here only so the round-trip check below can ask what the CONTEXT's mapper would
     * have written. The documents themselves come off the wire.
     */
    @Autowired
    private ObjectMapper json;

    /**
     * ONE BYTE OVER THE BOUND IS THESE 260 BYTES, and the request never reaches a controller.
     *
     * <p>The status alone would pass over a filter that answered 413 with an empty body, with Boot's
     * error page, or with a document a client cannot parse — which is what a filter answering outside
     * the {@code @ExceptionHandler} machinery would do if it were left to the container.
     */
    @Test
    void oneByteOverTheBoundIsThisExactDocumentAndNotOneThatMerelyMentionsTheNumbers()
            throws Exception {
        MvcResult refused = mvc.perform(post("/api/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[(int) REQUEST_LIMIT + 1]))
                .andReturn();
        MockHttpServletResponse response = refused.getResponse();

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsByteArray())
                .as("the 413 the filter writes is no longer the document it was written to write")
                .isEqualTo(THE_413.getBytes(StandardCharsets.UTF_8));
        assertThat(response.getContentAsByteArray()).hasSize(260);
        assertThat(sha256(response.getContentAsByteArray())).isEqualTo(THE_413_SHA256);
        // The document is JSON and says so, because a client that has to guess parses the 413 it can
        // read and gives up on the one it cannot.
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    /**
     * THE QUESTION THE SHARED PRESENTER WAS SUPPOSED TO ANSWER, ASKED AS A TEST.
     *
     * <p>Two refusals of the same endpoint, both fetched over HTTP: the filter's 413 and the
     * controller's 400. A caller polling {@code /api/ingest} branches on {@code started}, reads
     * {@code job} to learn what it failed to start, and shows {@code reason} to a person — and a client
     * that needs a second parser for one of the two is a client that will only have written the first.
     *
     * <p>THE KEY ORDER IS ASSERTED AND NOT ONLY THE KEY SET. Order is not semantically load-bearing in
     * JSON, and it is exactly what a hand-written template gets right by accident and a mapper with a
     * naming or sorting configuration gets differently — so it is the cheapest available evidence that
     * both documents came out of the same code.
     */
    @Test
    void theFiltersRefusalAndTheControllersRefusalAreTheSameDocument() throws Exception {
        Map<String, Object> tooLarge = wire(mvc.perform(post("/api/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new byte[(int) REQUEST_LIMIT + 1])).andReturn(), 413);

        // Nothing is launched by this one either: `repo` is refused before the census is read.
        Map<String, Object> noRepo = wire(mvc.perform(post("/api/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn(), 400);

        assertThat(List.copyOf(tooLarge.keySet()))
                .as("the filter and the controller refuse the same endpoint in two different shapes, "
                        + "so a client parsing one of them cannot parse the other. They share "
                        + "JobsPresenter precisely so this cannot happen — one of them has stopped "
                        + "going through it, or is going through it with a differently configured "
                        + "ObjectMapper")
                .isEqualTo(List.copyOf(noRepo.keySet()))
                .containsExactly("started", "job", "reason");

        // …and the values agree in type and in the two that are not free text. `started` false is the
        // field a caller branches on and `job` is what it failed to start; a 413 that named a different
        // job, or spelled the flag as the string "false", would be a second contract wearing the shape
        // of the first.
        assertThat(tooLarge.get("started")).isEqualTo(false).isEqualTo(noRepo.get("started"));
        assertThat(tooLarge.get("job")).isEqualTo("ingest").isEqualTo(noRepo.get("job"));
        assertThat(String.valueOf(tooLarge.get("reason"))).isNotBlank();
        assertThat(String.valueOf(noRepo.get("reason"))).isNotBlank();
    }

    /**
     * AND THE FILTER WRITES THROUGH THE CONTEXT'S MAPPER, not through one of its own.
     *
     * <p>This is the axis the old test could not see. A filter holding {@code new ObjectMapper()} and a
     * context holding a configured bean produce identical bytes for a document of three strings and a
     * boolean — until somebody sets an indenter, a naming strategy, a property-order or a serializer on
     * the bean, at which point every controller answer changes and the filter's does not. Asking whether
     * the bytes on the wire are what the CONTEXT's mapper would write for that same map fails the moment
     * the two mappers differ, and passes for no other reason.
     */
    @Test
    void theFilterWritesThroughTheMapperEveryControllerAnswersThrough() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[(int) REQUEST_LIMIT + 1]))
                .andReturn().getResponse();
        String written = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);

        assertThat(written).isEqualTo(json.writeValueAsString(parse(written)));
    }

    /**
     * THE NUMBERS IN THE DOCUMENT ARE THIS DEPLOYMENT'S, AND BOTH OF THEM ARE THERE.
     *
     * <p>The one that was exceeded, so the sentence is true; and the one an operator can act on, because
     * "the request" is not a thing anybody measures — the report is. This is the assertion that ties the
     * pinned literal to the configuration rather than to a constant somebody typed twice.
     */
    @Test
    void theRefusalNamesTheBoundThatWasExceededAndTheOneAnOperatorCanChange() throws Exception {
        Map<String, Object> tooLarge = wire(mvc.perform(post("/api/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new byte[(int) REQUEST_LIMIT + 1])).andReturn(), 413);

        assertThat(String.valueOf(tooLarge.get("reason")))
                .contains(String.valueOf(REQUEST_LIMIT))
                .contains(String.valueOf(REPORT_LIMIT))
                .contains("FSM_INGEST_MAX_CSV_BYTES")
                // This filter runs for EVERY content type, so advice that assumes a JSON body tells
                // somebody who just sent a multipart upload to send a multipart upload.
                .doesNotContain("multipart");
    }

    // ---- reading what came back --------------------------------------------------------------------

    private Map<String, Object> wire(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("%s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(expectedStatus);
        return parse(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    /** Into a LinkedHashMap, because the ORDER the keys arrived in is half of what is asserted. */
    private Map<String, Object> parse(String document) throws Exception {
        return json.readValue(document, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
