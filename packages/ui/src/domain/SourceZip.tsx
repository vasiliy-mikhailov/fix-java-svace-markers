'use client'

import { useState } from 'react'
import { Account, type Style } from '../primitives'
import { SettingCard } from 'ratchet-ui/components'
import { UploadForm } from './UploadForm'

export type SourceZipProps = {
  /**
   * A BOOLEAN IS GENUINELY ALL THE RECORD HOLDS. The check is `Files.isRegularFile` and nothing
   * else. The byte count is reported exactly once, in the flash message right after the upload
   * (1174-1175), and is unrecoverable on the next load — "uploaded 4.2MB on Tuesday" is new state
   * the Java side would have to start keeping, not a field this component is missing.
   */
  present: boolean
  onUpload: (f: File) => void
  onRemove: () => void
}

const ROW: Style = { display: 'flex', gap: '.5rem', alignItems: 'center', margin: '.5rem 0 0' }

const PLAIN: Style = {
  background: 'none',
  border: '1px solid var(--border-strong)',
  color: 'var(--text-tertiary)',
  borderRadius: '6px',
  padding: '.4rem 1rem',
  font: 'inherit',
  cursor: 'pointer',
}

const ARMED: Style = { ...PLAIN, color: 'var(--state-infra)', borderColor: 'var(--state-infra)' }

const CANCEL: Style = { ...PLAIN, border: 'none' }

/**
 * The subject's source, uploaded once instead of cloned every time.
 *
 * THE ACCENT IS INVERTED HERE AND THAT IS NOT A MISTAKE (1276). Everywhere else on this page the
 * highlight means "somebody changed this from the default"; here `present` is the highlighted state
 * because a zip means the network is bypassed, which is the fact that explains why a prove is fast,
 * or why it is building something other than what is on the remote.
 *
 * The remove arms before it fires, like `SaveRow`'s destructive slot, but it is not `SaveRow`:
 * there is no save on this row. Uploading IS the save, and a "save" button next to a file input
 * that has already been submitted is a button with nothing to do.
 */
export function SourceZip({ present, onUpload, onRemove }: SourceZipProps) {
  const [armed, setArmed] = useState(false)
  return (
    <SettingCard title="the source zip" provenance={present ? 'uploaded' : 'not uploaded'} changed={present}>
      {present ? (
        <Account quiet>
          a zip is in place; uploading another replaces it, and the size it was is not recorded
          anywhere this page can read
        </Account>
      ) : null}
      <UploadForm setting="zip" onUpload={onUpload} />
      {present ? (
        <div style={ROW}>
          {armed ? (
            <>
              <button
                type="button"
                style={ARMED}
                onClick={() => {
                  setArmed(false)
                  onRemove()
                }}
              >
                really remove it?
              </button>
              <button type="button" style={CANCEL} onClick={() => setArmed(false)}>
                cancel
              </button>
            </>
          ) : (
            <button type="button" style={PLAIN} onClick={() => setArmed(true)}>
              remove it
            </button>
          )}
        </div>
      ) : null}
    </SettingCard>
  )
}
