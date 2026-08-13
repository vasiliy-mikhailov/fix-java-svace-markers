import { Account, type Style } from '../primitives'

export type ClaimCardProps = {
  checker: string
  file: string
  /**
   * AS THE KEY HOLDS IT, which is a string.
   *
   * A marker key is `repo|file|line|checker` and the line is whatever the marker file put between
   * the second and third bar. Parsing it to a number here would turn an odd record — a range, a
   * blank, a `?` — into `NaN` on the one page whose job is to say what the record contains.
   */
  line: string
  /**
   * What this checker claims, from the bundled note, or `null` when there is no note.
   *
   * `claimIs()` (1524-1539) sanitises the checker name to `[A-Za-z0-9._-]`, reads
   * `/checkers/<name>.txt`, drops the first line (the checker's own name, already on this page) and
   * takes everything up to the first blank line. ITS NO-NOTE ANSWER WAS PROSE, not a fact: the
   * reader got a sentence back and could not tell "this checker has no bundled note" from "the note
   * says that sentence". `null` is the fact; the sentence is written below, where it can change
   * without a redeploy of the record.
   */
  claimNote: string | null
}

const CARD: Style = {
  border: '1px solid var(--border-soft)',
  borderRadius: '8px',
  background: 'var(--bg-card)',
  padding: '12px 14px',
  margin: '12px 0',
}

const CHECKER: Style = {
  margin: 0,
  fontSize: '15px',
  fontWeight: 600,
  color: 'var(--text-primary)',
}

const WHERE: Style = {
  margin: '3px 0 0',
  fontSize: '12px',
  color: 'var(--text-tertiary)',
  wordBreak: 'break-all',
}

/**
 * What was flagged, where, and what the checker that flagged it claims.
 *
 * FOUR FIELDS ARRIVE, NOT A KEY. `marker()` split the key on `|` itself (1829-1834) — a fifth place
 * that knows the shape of a marker key, next to two copies of `slug()` and two copies of the chain
 * order (#12). A component that splits a key is the next copy waiting to be written, and it is the
 * copy that will disagree the day a repository name contains a bar.
 */
export function ClaimCard({ checker, file, line, claimNote }: ClaimCardProps) {
  return (
    <section style={CARD}>
      <h2 style={CHECKER}>{checker}</h2>
      <p style={WHERE}>
        {file}
        {':'}
        {line}
      </p>
      {claimNote === null ? (
        <Account quiet>
          {`nothing is bundled for ${checker}, so what it claims is only what its name says — the flagged line below is the whole of the evidence on this page.`}
        </Account>
      ) : (
        <Account>{claimNote}</Account>
      )}
    </section>
  )
}
