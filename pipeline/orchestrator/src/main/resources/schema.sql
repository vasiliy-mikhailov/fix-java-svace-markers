-- The two tables the pipeline owns: the backlog, and the artifact each settled marker leaves.
--
-- EVERY STATEMENT IS `IF NOT EXISTS`, and that is the whole point of this file. A run takes 6-26 hours
-- across 282 markers; the orchestrator will be restarted mid-run, for a deploy or after a crash, and
-- `spring.sql.init.mode=always` replays this script on every one of those starts. A plain CREATE TABLE
-- would fail the start (and a DROP-then-CREATE would silently discard the backlog, which is worse).
--
-- THE COLUMN LISTS AND THEIR ORDER ARE PART OF THE CONTRACT. `Suspicion` and `Bug` name the same
-- columns in the same order, and MarkerProgressDao exists as a separate table rather than a 24th column
-- for exactly that reason. In particular `bugs` has NO `attempts` column — Record outcome emits one and
-- there is deliberately nowhere here to put it; the count lives on `suspicions.prove_attempts`, which
-- is the row the retry logic actually reads.

CREATE TABLE IF NOT EXISTS suspicions (
  -- repo/file/class/line, deduplicated by the ingester. The prover leases rows by this key and every
  -- artifact in `bugs` points back at it, so it is the primary key rather than a unique index.
  dedup_key      VARCHAR(512)  NOT NULL,
  marker_id      VARCHAR(512),
  repo           VARCHAR(512),
  branch         VARCHAR(512),
  file           VARCHAR(1024),
  class_name     VARCHAR(512),
  method         VARCHAR(512),
  -- DOUBLE, not INT: the engine holds both as JavaScript Numbers (ParseMarkers.Suspicion), and a
  -- column that truncates would change a value the re-anchoring compares against.
  line           DOUBLE PRECISION,
  -- What Svace reported, never overwritten — the only way to trace a re-anchor that went wrong.
  svace_line     DOUBLE PRECISION,
  anchor         VARCHAR(1024),
  anchor_status  VARCHAR(64),
  category       VARCHAR(128),
  severity       VARCHAR(64),
  svace_checker  VARCHAR(256),
  svace_severity VARCHAR(64),
  title          VARCHAR(2048),
  description    CLOB,
  evidence       CLOB,
  -- The queue/lifecycle column. Free-form on purpose: its vocabulary is authored by the engine
  -- (tech.mikhailov.fsm.nodes.Verdict decides `suspicion_status`), and constraining it here would
  -- turn a state the engine added into a failed UPDATE mid-run instead of a row on the dashboard.
  status         VARCHAR(64)   NOT NULL,
  -- The entire audit trail for a row that went back on the queue: which attempt, and why.
  note           CLOB,
  prove_attempts BIGINT        NOT NULL DEFAULT 0,
  version        VARCHAR(128),
  method_key     VARCHAR(1024),
  CONSTRAINT pk_suspicions PRIMARY KEY (dedup_key)
);

-- Claiming reads `WHERE status = 'new'` once per marker for the length of the run, and the startup
-- reconciliation reads `WHERE status = 'proving'`. Both are the same index.
CREATE INDEX IF NOT EXISTS ix_suspicions_status ON suspicions (status);

CREATE TABLE IF NOT EXISTS bugs (
  -- suspicions.dedup_key. Not declared as a foreign key: the artifact is evidence and must survive a
  -- re-ingest that rewrites the backlog. The two tables are independent on purpose.
  suspicion_key  VARCHAR(512)  NOT NULL,
  repo           VARCHAR(512),
  file           VARCHAR(1024),
  title          VARCHAR(2048),
  jdk            VARCHAR(32),
  test_path      VARCHAR(1024),
  test_code      CLOB,
  fix_diff       CLOB,
  red_verified   BOOLEAN,
  green_verified BOOLEAN,
  value_score    DOUBLE PRECISION,
  value_verdict  CLOB,
  pr_title       VARCHAR(2048),
  pr_body        CLOB,
  -- MarkerState.wire() for anything Record outcome produced, PLUS the three states the Verdict stage
  -- substitutes when an argument is written instead of a patch: false_positive, by_design, unprovable.
  -- See tech.mikhailov.fsm.orch.model.Bug#state for why this is not narrowed to the enum.
  state          VARCHAR(64),
  infra_reason   CLOB,
  branch         VARCHAR(512),
  -- The stage version stamps, as the JSON the engine handed over.
  versions       CLOB,
  verdict_text   CLOB,
  verdict_kind   VARCHAR(64),
  svace_checker  VARCHAR(256),
  -- WHAT HAPPENED TO THE ARGUMENT, and the only column here that was never a BUG_COL. `skipped` when
  -- fsm.prove.verdict-enabled was off and this marker was one of the three routes that would otherwise
  -- have been argued; empty on every other row. It is APPENDED, after the twenty-one, so the header's
  -- claim above still holds and an older row loads with this column null.
  --
  -- It exists because THREE outcomes are otherwise the same row — an empty verdict_text with the state
  -- unreplaced — and they send a reader to three different places: the prompt (asked, said nothing),
  -- the endpoint (never reached; infra_reason names it), and nowhere at all (never asked). The last one
  -- used to be indistinguishable from the first.
  verdict_status VARCHAR(64),
  CONSTRAINT pk_bugs PRIMARY KEY (suspicion_key)
);

-- …AND THE SAME COLUMN FOR A DATABASE THAT ALREADY EXISTS. `CREATE TABLE IF NOT EXISTS` does nothing at
-- all to a table that is already there, so on every deployment that has run before — which is all of
-- them, mid-run, with hours of markers in the file — the column above would simply never appear, and
-- every write would fail on an unknown column. Idempotent, so the replay on the next restart is a
-- no-op, and additive, so it cannot touch a row that is already recorded.
ALTER TABLE bugs ADD COLUMN IF NOT EXISTS verdict_status VARCHAR(64);

CREATE INDEX IF NOT EXISTS ix_bugs_state ON bugs (state);

-- WHEN A MARKER LAST CHANGED STATE, which the effort model is the only thing that reads: machine time
-- is attributed to a marker by finding the prover run whose window CONTAINS the moment that marker
-- changed state. Neither table above carries a timestamp, and adding one to `suspicions` would break
-- the property its own header states — that its columns are exactly the ones `Suspicion` names, in
-- order.
--
-- So the observation lives beside them instead. It is written by the live watcher when it sees a
-- status change and by nothing else, which makes it an OBSERVED time rather than a claimed one: a
-- marker settled before this process ever ran has no row here, and the effort model leaves such a
-- marker out of the measured window instead of handing it an average. Losing this table costs the
-- retry/settled-only split on the Effort panel and nothing else — no verdict, no artifact, no state.
CREATE TABLE IF NOT EXISTS marker_progress (
  dedup_key  VARCHAR(512) NOT NULL,
  status     VARCHAR(64),
  updated_at TIMESTAMP    NOT NULL,
  CONSTRAINT pk_marker_progress PRIMARY KEY (dedup_key)
);

-- HOW MANY TIMES IN A ROW A MARKER HAS FAILED BEFORE THE ENGINE EVER SAW IT — and why this is not
-- `suspicions.prove_attempts`.
--
-- `prove_attempts` counts JUDGEMENTS ATTEMPTED. tech.mikhailov.fsm.orch.client.InfraFailure means the
-- question was never answered, so Record outcome never ran and there is nothing to count: the marker
-- goes back on the queue with that column untouched, which is the rule that stops "the model was
-- unavailable" from ever being spent as "we looked at this three times". The cost of that rule is that
-- a marker whose repository answers 403 for ever is retried for ever, and with the reader now
-- ADVANCING past a skip (rather than ending the drain on it) that is one wasted claim per marker per
-- tick, on every tick, until somebody notices.
--
-- So the streak is counted HERE instead, in a column that is not a judgement and can never be mistaken
-- for one. Nothing reads it but the release path, no dashboard groups by it, and it says only "the
-- pipeline could not get an answer about this row N times running". A marker that reaches ANY verdict
-- clears it, because the streak is by definition consecutive; a re-ingest clears it with the backlog.
--
-- WHAT IS NOT COUNTED HERE, deliberately: a failure from an execution that ended FAILED. When the
-- runner or the model endpoint is down every marker fails, and charging the backlog for an outage
-- would retire hundreds of perfectly provable markers over an afternoon. See
-- tech.mikhailov.fsm.orch.batch.ClaimReleaseListener, which is the only writer of this table.
-- WHAT A PERSON TYPED, AND THE ONE TABLE ON THIS SCHEMA THAT INGEST MUST NEVER TOUCH.
--
-- Every other table here is DERIVED: `suspicions` is the CSV, `bugs` is what the pipeline produced from
-- it, `marker_progress` and `infra_strikes` are observations of a run. Each can be rebuilt by running
-- something again, and IngestTasklet correctly clears the first two on a re-ingest — the backlog is
-- whatever the newest Svace report says, and keeping rows the new report does not raise would leave
-- markers on the dashboard that nothing explains.
--
-- A COMMENT CANNOT BE REBUILT. It is a human judgement about a reproducer's output — "I don't like too
-- many mocks, this one and this one are redundant" — and re-running the pipeline a thousand times will
-- never produce it again. It is, by that measure, the most expensive data in the system.
--
-- Which is precisely why it is NOT a column on `bugs`, where it would sit next to the verdict it
-- criticises and read very naturally. `bugs` is DELETEd whole by every ingest ("cleared N suspicion(s)
-- and N bug(s)"), so a comment stored there would be destroyed by an operator loading a fresh report —
-- an action with no warning, no confirmation and no visible connection to somebody's paragraph of
-- review. Keyed by dedup_key in its own table, the same ingest costs it nothing at all.
--
-- THE KEY IS NOT A FOREIGN KEY, and that is the same decision `bugs.suspicion_key` already documents,
-- for a stronger reason: a re-ingest whose CSV no longer raises this marker MUST still leave the
-- comment on disk. A foreign key would either block the ingest or cascade the delete, and both of those
-- are "the backlog changed, so we destroyed what a person wrote". The read path reports whether the
-- backlog currently holds the marker (`marker_present`) instead of pretending the question is settled.
CREATE TABLE IF NOT EXISTS marker_comments (
  -- Minted by the writer as a UUID, NOT by the database. The same id identifies this comment in the
  -- durable JSONL journal, which outlives H2 by design; an id handed out by a table that a fresh deploy
  -- destroys could not tie a journalled retraction to the comment it retracts.
  comment_id   VARCHAR(64)   NOT NULL,
  -- Insertion order, and the TIE-BREAK for "newest first". Two comments typed inside one millisecond
  -- order by the clock alone arbitrarily, and a list that reshuffles between two polls is the kind of
  -- small wrongness nobody reports and everybody stops trusting.
  seq          BIGINT        NOT NULL,
  -- suspicions.dedup_key. See above for why this is not declared as a foreign key.
  dedup_key    VARCHAR(512)  NOT NULL,
  -- One of the five stage names as tech.mikhailov.fsm.feedback.Critique spells them, or '' for the
  -- marker as a whole. Not constrained here: the vocabulary is the ENGINE's, and a stage added there
  -- must not become a failed INSERT in front of a person who has just typed a paragraph.
  stage        VARCHAR(64)   NOT NULL,
  -- An optional stable slug so a human complaint can be counted alongside the harvested ones. '' is
  -- normal and expected — the free text is the payload, the kind is filing.
  kind         VARCHAR(64)   NOT NULL,
  -- SELF-DECLARED. There is no per-user identity in this service; see MarkerComment#AUTHOR_TRUST,
  -- which is served beside this value in every response so no reader can mistake it for authentication.
  author       VARCHAR(128)  NOT NULL,
  -- The comment itself. CLOB rather than VARCHAR because the bound belongs in the validator, where the
  -- refusal can say what the limit is, and not in a column whose overflow is a SQL exception.
  body         CLOB          NOT NULL,
  created_at   TIMESTAMP     NOT NULL,
  -- SOFT RETRACTION, and the row is never DELETEd. Three reasons, in order of weight: the journal is
  -- append-only and can never forget, so a hard delete would leave the two stores permanently
  -- disagreeing about the same comment; a person's paragraph deleted by a mis-click is unrecoverable
  -- while a retraction is one UPDATE away from being undone; and "somebody withdrew this" is itself
  -- information, distinct from "nobody ever said anything". NULL means it stands.
  retracted_at TIMESTAMP,
  retracted_by VARCHAR(128),
  CONSTRAINT pk_marker_comments PRIMARY KEY (comment_id)
);

-- The modal reads one marker's comments every time it opens. This is that query.
CREATE INDEX IF NOT EXISTS ix_marker_comments_key ON marker_comments (dedup_key);

-- The tie-break above. A sequence and not MAX(seq)+1: the latter races two concurrent writers into one
-- number, which is exactly the case the tie-break exists to order.
CREATE SEQUENCE IF NOT EXISTS marker_comment_seq START WITH 1;

CREATE TABLE IF NOT EXISTS infra_strikes (
  dedup_key  VARCHAR(512) NOT NULL,
  strikes    BIGINT       NOT NULL DEFAULT 0,
  -- The last InfraFailure reason, so the parked row can say what was wrong without a log search.
  reason     CLOB,
  updated_at TIMESTAMP    NOT NULL,
  CONSTRAINT pk_infra_strikes PRIMARY KEY (dedup_key)
);
