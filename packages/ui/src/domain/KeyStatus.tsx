import { Account, Pill, type Style } from '../primitives'

/** Where the key the agents are using came from. */
export type KeySource = 'this page' | 'the environment'

export type KeyStatusProps = {
  keyed: boolean
  /** Only meaningful when `keyed`: with no key at all there is no source to report. */
  keySource: KeySource
}

const ROW: Style = { display: 'flex', gap: '8px', alignItems: 'baseline', flexWrap: 'wrap' }

/**
 * Whether the endpoint has a key, and whose.
 *
 * THE FEATURE THIS BELONGS TO HAS NEVER WORKED (#5). On `/settings?a=model` the `<form>` does not
 * open until 1364 — AFTER the key label at 1340 and the forget checkbox at 1359 — and neither
 * carries an HTML5 `form=` attribute. Nothing named `api_key` or `forget_key` is ever submitted, so
 * `Tuning.save()`'s carefully-reasoned key branches (Tuning 138-144) are unreachable from the page
 * that exists to reach them. Somebody typing a key in and pressing save was told it had saved.
 *
 * THE PORT FIXES IT BY CONSTRUCTION, and it is a fix this component cannot make on its own. It, the
 * `SecretField` holding the key and {@link ForgetKeyChoice} are three pieces of ONE row and ONE
 * request: they are controlled inputs whose state the screen holds, so there is no longer a DOM
 * subtree that can be inside or outside a form by accident. Put them in one `SettingRow` with one
 * `SaveRow` and the feature works for the first time.
 */
export function KeyStatus({ keyed, keySource }: KeyStatusProps) {
  if (!keyed) {
    return (
      <div>
        <div style={ROW}>
          <Pill tone="alarm">no key</Pill>
        </div>
        <Account quiet>
          nothing is set here and nothing is set in the environment, so every agent call is refused
          before it is made
        </Account>
      </div>
    )
  }
  return (
    <div>
      <div style={ROW}>
        <Pill tone="good">key set</Pill>
        <Account quiet>{`the agents are using the key from ${keySource}`}</Account>
      </div>
    </div>
  )
}
