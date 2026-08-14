import type { Style } from '../primitives'

export type UploadOutcomeProps = {
  /**
   * WHETHER IT WAS REFUSED, AS A BOOLEAN — a wire convention that must not survive the port.
   *
   * The Java signalled refusal with a LEADING `!` on the message string and stripped it at render
   * (1130-1182, then 1196-1198). Every reader of that string had to know the sentinel, one of them
   * would eventually forget, and any complaint that legitimately began with `!` was one edit away
   * from being read as a refusal. The flag is a flag.
   */
  refused: boolean
  /**
   * The message, WITHOUT the sentinel, and genuinely multi-line.
   *
   * A bad markers file comes back as up to twelve complaints joined with `"\n  "` (1136), and an
   * exception lands here too, as `ClassName: message` (1181-1183) — including the one from the 64MB
   * upload cap. Newlines are therefore load-bearing and are preserved; several complaints are
   * numbered below so a reader can say "the fourth one" to somebody else.
   */
  text: string
}

const BOX: Style = {
  border: '1px solid var(--border-soft)',
  borderLeft: '2px solid var(--state-verified-pr-ready)',
  borderRadius: '6px',
  background: 'var(--bg-card)',
  padding: '10px 12px',
  margin: '10px 0',
  fontSize: '12.5px',
  lineHeight: 1.6,
  color: 'var(--text-secondary)',
}

const REFUSED: Style = { ...BOX, borderLeft: '2px solid var(--state-infra)' }

const ONE: Style = { margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }

const LIST: Style = { margin: 0, paddingLeft: '1.4rem', display: 'grid', gap: '2px' }

/**
 * What the server made of an upload.
 *
 * ONE COMPONENT FOR BOTH ANSWERS, because they arrive on the same path and a reader needs to tell
 * them apart at a glance rather than by reading. Accepted is a green edge; refused is `--state-infra`
 * red, which is this domain's "it threw" colour and is what a refusal is.
 *
 * The complaints would be better as a `string[]` on the wire — the split below cannot tell a
 * complaint containing a newline from two complaints. It is done here because the payload sends one
 * string today, and it is the only place that knows about the `"\n  "` join.
 */
export function UploadOutcome({ refused, text }: UploadOutcomeProps) {
  const lines = text
    .split('\n')
    .map(line => line.trim())
    .filter(line => line.length > 0)
  if (lines.length === 0) {
    return null
  }
  return (
    <div style={refused ? REFUSED : BOX}>
      {lines.length === 1 ? (
        <p style={ONE}>{lines[0]}</p>
      ) : (
        <ol style={LIST}>
          {lines.map((line, index) => (
            // Keyed by position: two identical complaints about two identical lines are a thing a
            // markers file does, and they are both worth showing.
            <li key={index} style={ONE}>
              {line}
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}
