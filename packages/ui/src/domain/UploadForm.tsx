'use client'

import { useRef, useState } from 'react'
import { Account, type Style } from '../primitives'

export type UploadFormProps = {
  /** Which of the two uploads this is. It decides the wording, what is accepted and nothing else. */
  setting: 'markers' | 'zip'
  onUpload: (f: File) => void
}

const ROW: Style = { display: 'flex', gap: '.5rem', alignItems: 'center', flexWrap: 'wrap', margin: '.5rem 0' }

const BUTTON: Style = {
  background: 'var(--accent-action)',
  color: 'var(--accent-on-action)',
  border: '1px solid var(--accent-action)',
  borderRadius: '6px',
  padding: '.4rem 1rem',
  font: 'inherit',
  cursor: 'pointer',
}

const BUTTON_OFF: Style = {
  ...BUTTON,
  background: 'none',
  color: 'var(--text-tertiary)',
  border: '1px solid var(--border-strong)',
  cursor: 'not-allowed',
}

const SAYS: Record<'markers' | 'zip', { label: string; accept: string; account: string }> = {
  markers: {
    label: 'a markers file',
    accept: '.txt,text/plain',
    account:
      'One marker per line, as repo|file|line|checker. A line that will not parse is reported back with the others rather than dropped.',
  },
  zip: {
    label: 'a source zip',
    accept: '.zip,application/zip',
    account: 'The subject tree, so a prove never has to reach the network for it.',
  },
}

/**
 * Choose a file, then send it.
 *
 * IN TWO STEPS, NOT ONE. An `onChange` that uploads the moment a file is picked gives the reader no
 * moment to notice they picked the wrong one, and there is no undo behind this: a markers file
 * replaces the queue.
 *
 * The 64MB cap is the server's and is not enforced here. Over it the request is refused whole and
 * the answer comes back as an exception — `ClassName: message` — through {@link UploadOutcome},
 * which is where every answer to this form is rendered, refusals included.
 */
export function UploadForm({ setting, onUpload }: UploadFormProps) {
  const [file, setFile] = useState<File | null>(null)
  const input = useRef<HTMLInputElement>(null)
  const said = SAYS[setting]
  return (
    <div>
      <Account quiet>{said.account}</Account>
      <div style={ROW}>
        <input
          ref={input}
          type="file"
          accept={said.accept}
          aria-label={said.label}
          onChange={event => setFile(event.currentTarget.files?.[0] ?? null)}
        />
        <button
          type="button"
          disabled={file === null}
          style={file === null ? BUTTON_OFF : BUTTON}
          onClick={() => {
            if (file === null) {
              return
            }
            onUpload(file)
          }}
        >
          {`upload ${said.label}`}
        </button>
      </div>
    </div>
  )
}
