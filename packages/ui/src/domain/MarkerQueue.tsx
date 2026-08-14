import { Account, type Style } from '../primitives'
import { SettingRow } from './SettingRow'

export type MarkerQueueProps = {
  /** How many markers `markers.txt` holds. */
  queued: number
  /** The distinct repositories those markers are in. */
  repos: string[]
}

const REPOS: Style = {
  margin: '.4rem 0 0',
  padding: 0,
  listStyle: 'none',
  display: 'grid',
  gap: '2px',
  fontSize: '12px',
  color: 'var(--text-secondary)',
  wordBreak: 'break-all',
}

/**
 * What is waiting to be proved.
 *
 * BUG NOT PORTED (`index()` 1202): this row was hardcoded to the grey `ev tool` class, the one that
 * means "still the default". AN EMPTY QUEUE THEREFORE LOOKED EXACTLY AS CALM AS A FULL ONE — a
 * pipeline with nothing to do and a pipeline with four hundred markers loaded rendered the same
 * grey box, on the page where the difference is the whole point. `changed` is now the honest fact:
 * markers have been loaded, which is something somebody did, as against the empty default the image
 * ships with.
 */
export function MarkerQueue({ queued, repos }: MarkerQueueProps) {
  return (
    <SettingRow
      name="the markers"
      state={queued === 0 ? 'nothing queued' : `${queued} queued`}
      changed={queued > 0}
    >
      {queued === 0 ? (
        <Account quiet>nothing is queued — upload a markers file or paste a list below</Account>
      ) : (
        <ul style={REPOS}>
          {repos.map(repo => (
            <li key={repo}>{repo}</li>
          ))}
        </ul>
      )}
    </SettingRow>
  )
}
