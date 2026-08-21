'use client'

import { useState } from 'react'
import { LabeledField, SaveRow, SecretField } from '../primitives'
import { SettingCard } from 'ratchet-ui/components'

export type GitCredentialProps = {
  /** The host the token is for: `github.com`, an internal GitLab, whatever the subject is cloned from. */
  host: string
  token: string
  onSave: (host: string, token: string) => void
  onForget: () => void
}

/**
 * The credential a prove uses to clone the subject and open a pull request.
 *
 * BLANK IS REFUSED HERE AND LEFT ALONE ON THE MODEL TAB, and that disagreement is real (1146-1147
 * against the prose at 1414-1420). A blank API key is deliberately ignored, because a browser that
 * clears the field must not be able to silently unset the key and leave every agent talking to an
 * endpoint that refuses them. A blank git token is refused outright. Two pages, two rules, so the
 * rule is stated in the field's own help — a shared field with one policy would teach a rule that is
 * true on one of the two pages.
 *
 * The server decides; this does not pre-empt it. What comes back is what was kept.
 *
 * `forget` is `SaveRow`'s destructive slot: its own handler and its own request, because the Java
 * told save from forget by which button submitted the form (#15) and a client that serialises its
 * state would post `forget=1` on every save.
 */
export function GitCredential({ host, token, onSave, onForget }: GitCredentialProps) {
  const [draftHost, setDraftHost] = useState(host)
  const [draftToken, setDraftToken] = useState(token)
  const set = host.trim().length > 0 || token.length > 0
  return (
    <SettingCard
      title="the git credential"
      provenance={set ? host.trim() || 'set' : 'not set'}
      changed={set}
    >
      <LabeledField
        name="git_host"
        label="host"
        value={draftHost}
        onChange={setDraftHost}
        help="The host the token is for. Everything cloned from it goes through this credential."
      />
      <SecretField
        name="git_token"
        label="token"
        value={draftToken}
        onChange={setDraftToken}
        help="A blank token is refused on this page — unlike the API key, which is left alone when blank. Clearing it here does not remove it; the button below does."
      />
      <SaveRow
        onSave={() => onSave(draftHost.trim(), draftToken)}
        destructive={{ label: 'forget it', onConfirm: onForget }}
      />
    </SettingCard>
  )
}
