import { TraceEvent, type TraceEventRecord } from './TraceEvent'

export type EventFeedProps = {
  events: TraceEventRecord[]
  /**
   * BOTH ORDERS ARE DELIBERATE AND BOTH HAVE TO SURVIVE.
   *
   * `/trace` and `/marker?a=trace` sort ascending (2127) and append at the bottom (2139-2141,
   * `beforeend` at line 266). The supervisor sorts descending (624, 657) and says why (643-649): a
   * prove is read after it settles and its story runs forwards, while the supervisor is read while it
   * is running and the question is always what it just said.
   *
   * One earlier pass wrote "newest first" for `/trace`. The code says otherwise, and appending is
   * also what makes the live update non-destructive — new rows land below the reader instead of
   * pushing everything they had open down the page.
   */
  order: 'oldest-first' | 'newest-first'
  showMarker: boolean
  markerHrefFor: (e: TraceEventRecord) => string | null
  defaultOpen: boolean
  feedbackBack: string
  /**
   * How many events this feed has already been given, published for the live tail to resume from —
   * Java `cursor()` (2386-2388), which wrote it to `document.body.dataset.events`.
   *
   * ABSENT MEANS THIS FEED DOES NOT TAKE FRAGMENTS, and that is a real setting, not an oversight.
   * `LIVE` returns early when the dataset attribute is undefined (255-259) and no supervisor view
   * emits one, so the whole supervisor screen is static and updates only on reload. Keep that or
   * cursor the supervisor's events too — but do not poll a page whose folds belong to the reader.
   */
  cursor?: number
}

/**
 * The stamp two rows are ordered by, with `num()`'s answer for a bad one (2355): zero, which sorts to
 * the top ascending. That is visible and therefore fixable; dropping the row would not be.
 */
function stamp(event: TraceEventRecord): number {
  return Number.isFinite(event.at) ? event.at : 0
}

/**
 * EVERYTHING THAT HAPPENED, IN ORDER — one component where the Java had two doing the same job in
 * opposite directions (`events()` 2117-2167 and `supervisorEvents()` 629-640).
 *
 * UNCAPPED, ON PURPOSE (609-617). Roughly eight megabytes after an afternoon, and the position it is
 * kept for is that a page silently showing part of a record reads as the record — every cut this
 * program has made to a payload for a reader's convenience later turned out to remove the field
 * somebody needed. So nothing here slices. The cost is paid in `TraceEvent`, whose rows are
 * `content-visibility: auto`: the browser skips laying out what nobody has scrolled to while every
 * event stays in the document, findable and anchorable. Windowing would have kept the frame rate and
 * broken find-in-page, which on a record is the feature.
 */
export function EventFeed({
  events,
  order,
  showMarker,
  markerHrefFor,
  defaultOpen,
  feedbackBack,
  cursor,
}: EventFeedProps) {
  // A COPY. Sorting the list in place threw `UnsupportedOperationException` out of the Java handler,
  // which `HttpServer` answers by closing the connection with no status and no log line — an empty
  // reply in twenty milliseconds, which reads exactly like a page too big to build and is nothing of
  // the kind (619-622). The JavaScript failure is quieter and worse: `sort` mutates, so sorting the
  // prop would reorder the caller's array, and a payload held by whatever fetched it comes back
  // reordered on the next render.
  //
  // The sort is stable (guaranteed since ES2019), which matters here: four workers' files
  // concatenated give four ordered runs, and rows sharing a stamp keep the order they were read in
  // rather than being shuffled by the comparator.
  const ordered = [...events].sort((a, b) =>
    order === 'oldest-first' ? stamp(a) - stamp(b) : stamp(b) - stamp(a),
  )

  return (
    // WITH A CURSOR, EVEN AT ZERO. The Java's empty branch returned before setting one (2158-2163),
    // so a marker page opened before its first event declared itself unable to take fragments and
    // sat empty for the whole prove. An empty feed still publishes where it is.
    //
    // What a page says when there is nothing here is the SCREEN's copy, not this component's: the
    // sentences differ per screen and they do real work ("the supervisor looks on its own
    // schedule"). `/trace` hard-coded the marker page's "Nothing traced for this marker" (2191) and
    // showed it on the whole-trace view, where there is no marker and nothing traced anywhere.
    <div {...(cursor === undefined ? {} : { 'data-events': cursor })}>
      {ordered.map((event) => (
        // Keyed by the event's own id, never by index: a live append renumbers an index-keyed list
        // from the insertion point down, and React then reuses the wrong `<details>` for the wrong
        // event — bug #10 again, one layer up from the fold ids.
        <TraceEvent
          key={event.id}
          event={event}
          showMarker={showMarker}
          markerHref={markerHrefFor(event)}
          defaultOpen={defaultOpen}
          feedbackBack={feedbackBack}
        />
      ))}
    </div>
  )
}
