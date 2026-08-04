package tech.mikhailov.fsm.lib;

/**
 * HOW FAR THE MARKER'S LOCATION CAN BE TRUSTED — the {@code anchor_status} column.
 *
 * <p>The commit Svace scanned is unknown, so {@code File:Line} is resolved against upstream HEAD and the
 * line has almost certainly moved. This is the per-marker answer to "did we find the code the checker
 * was talking about?", and it is worth a type because ONE column is written by TWO nodes that never see
 * each other's spellings: {@code ParseMarkers} writes {@link #PENDING} at ingest, and
 * {@code BuildReproduceInput} overwrites it with one of the other three once the source has been read.
 *
 * <p>AND IT IS BRANCHED ON OUTSIDE THIS MODULE. The dashboard keys a colour and an explanation off these
 * four spellings and falls back to a grey badge with an EMPTY tooltip for anything else — so a fifth
 * spelling, or a rename of one of these, degrades silently to "no location confidence shown" on the one
 * panel a reviewer uses to decide whether the rest of the row is worth reading. The meanings below were
 * only ever written down in that dashboard's JavaScript; they live here now, beside the values.
 *
 * <p>Nothing in Java branches on this today, which is why there is no {@code of(String)}: a reader that
 * needed one would have to decide what an unrecognised status means, and no reader does yet. What the
 * type buys is that the four spellings, and their meanings, are declared once.
 */
public enum AnchorStatus {

    /** Not resolved yet; the prover re-anchors when it fetches the source. The ingest value. */
    PENDING("pending"),

    /** The reported line falls inside this method in the checked-out tree. */
    EXACT("exact"),

    /**
     * The line is a field, annotation or import — not inside any method body. NOT drift and not a
     * parser gap: with Lombok the accessor the checker flagged is generated and has no source form.
     */
    NO_METHOD("no-method"),

    /**
     * There is nothing to anchor against: the source could not be fetched, or the line is past the end
     * of the file as checked out, which proves the file changed since the scan.
     */
    UNRESOLVED("unresolved");

    private final String wire;

    AnchorStatus(String wire) {
        this.wire = wire;
    }

    /** The spelling written into the row, stored, and read by the dashboard. */
    public String wire() {
        return wire;
    }
}
