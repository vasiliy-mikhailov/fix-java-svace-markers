// THE HEADING IS THE LIBRARY'S, AND THE MARGIN STAYS OURS. Our four copies of these five
// declarations all omitted `fontWeight`, which is not a declaration that drifted but one nobody
// made: an `h3` with no weight takes the browser's bold, so ours drew at 700 beside the
// sibling's 500. The shared constant names the weight; only the margin is per-site.
import { HEADING } from 'ratchet-ui/components'
import { CodeBlock, type Style } from '../primitives'

export type TestArtifactProps = {
  /** `test_path` from `settlements.jsonl`. Where the reproduction was written in the subject tree. */
  path: string
  /** `test_code` from the same row. */
  code: string
}

const PATH: Style = {
  margin: '2px 0 0',
  fontSize: '12px',
  color: 'var(--text-secondary)',
  wordBreak: 'break-all',
}

const MISSING: Style = { margin: '4px 0 0', fontSize: '12px', color: 'var(--text-tertiary)' }

/**
 * The test that reproduces the marker.
 *
 * BUG NOT PORTED (#9, `marker()` 1890-1898): the Java recovered this by scanning the trace for the
 * LAST `write_file` tool call from ANY agent and rendering its argument. That is not a record of the
 * test, it is a guess about one — a later agent writing any file at all took the slot, and an
 * attempt whose trace had been archived showed nothing. `settlements.jsonl` has held `test_path` and
 * `test_code` the whole time and this screen ignored both. The scan is deleted; these two fields are
 * served.
 *
 * The language is decided from the path, not assumed: `CodeBlock` colours as Java only when told to,
 * because `block()` ran `colourJava()` over everything it was given and highlighted `int` and
 * `class` wherever they happened to appear in a Kotlin or XML fragment.
 */
export function TestArtifact({ path, code }: TestArtifactProps) {
  const written = code.trim().length > 0
  if (path.length === 0 && !written) {
    return null
  }
  return (
    <section>
      <h3 style={{ ...HEADING, margin: '12px 0 0' }}>the test</h3>
      {path.length === 0 ? null : <p style={PATH}>{path}</p>}
      {written ? (
        // exactOptionalPropertyTypes: `language` is ABSENT or a value. Passing `undefined` for a
        // non-Java file is a type error, and spreading is how the absence is expressed.
        <CodeBlock code={code} {...(path.endsWith('.java') ? { language: 'java' as const } : {})} />
      ) : (
        // A path with no body is a distinguishable state, and it is one the old scan could not even
        // represent: the settlement recorded where the test went and not what it was.
        <p style={MISSING}>the settlement recorded where the test was written but not what it said</p>
      )}
    </section>
  )
}
