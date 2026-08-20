import type { ReactNode } from 'react'
import { CORNER, PageHeader as Shared, type Crumb } from 'ratchet-ui/components'

export type { Crumb }
import { FindingsButton } from './FindingsButton'

/**
 * THE PAGE HEADER, FROM `ratchet-ui`, WITH THE CORNER STILL GUARANTEED.
 *
 * `HEADER`, `TITLE`, `SUB`, `CRUMB` and `ACTIONS` were identical between the two repositories,
 * declaration for declaration; the private `GEAR` here was the shared `CORNER`, the same six
 * declarations in the same order. Both had reached `subtitle: ReactNode` independently. There was
 * nothing in the layout to reconcile.
 *
 * <p>THE DIFFERENCE WAS THE CORNER, AND THE SHARED ONE IS MORE GENERAL: it takes `actions`, where
 * this took a required `findingsOpen` and hard-coded the three controls. Passing `actions` at every
 * call site would have been the literal translation — and it would have given up the one thing this
 * version guarantees, that no screen CAN forget the corner, because the component supplies it.
 * A screen written next year is exactly the one that would forget.
 *
 * <p>So the layout is shared and the corner is not. This is the arrangement `ADOPTING.md` itself
 * recommends: `open` stays required here, `Corner` is passed from here, and the eight call sites
 * change by zero characters.
 */
export type PageHeaderProps = {
  title: string
  /**
   * A NODE, NOT A STRING, and this is the single most reconciled prop in the catalogue: nine passes
   * named this component three ways and five of them typed the subtitle `string`.
   *
   * `head()` escaped the title and appended the subtitle RAW (2470-2471). It had to: callers push
   * entities through it (`&middot;`, `&mdash;`) and `/marker` (1849) and `/trace` (2181) push a
   * whole `<span class='s …'>` state pill. A `string` prop here re-opens that hole on the first
   * screen that puts a repo name — or a checker with an `&` in it — in its subtitle. So the
   * screens compose: `<>{key} · <StateBadge state={state} /></>`.
   */
  subtitle: ReactNode
  /** Absent on `/`: `index()` calls the two-arg overload, because the list is where back GOES. */
  back?: Crumb
  findingsOpen: number
}

/**
 * The three corner controls every screen wears. Was this component's body; is now its caller's.
 *
 * <p>`FindingsButton` does not move and could not: it counts `holds` plus `unjudged`, which is this
 * pipeline's supervisor vocabulary and nothing shared has any business knowing.
 */
export function Corner({ open }: { open: number }) {
  return (
    <>
      <FindingsButton open={open} />
      <a href="/chat" style={CORNER} title="ask the supervisor" aria-label="ask the supervisor">
        <span aria-hidden="true">{'✉'}</span>
      </a>
      <a href="/settings" style={CORNER} title="settings" aria-label="settings">
        <span aria-hidden="true">{'⚙'}</span>
      </a>
    </>
  )
}

export function PageHeader({ title, subtitle, back, findingsOpen }: PageHeaderProps) {
  // SPREAD, NOT `back={back}`. `exactOptionalPropertyTypes` distinguishes an absent property from
  // one present and undefined, and `/` genuinely has no crumb — the list is where back GOES.
  return (
    <Shared
      title={title}
      subtitle={subtitle}
      {...(back === undefined ? {} : { back })}
      actions={<Corner open={findingsOpen} />}
    />
  )
}
