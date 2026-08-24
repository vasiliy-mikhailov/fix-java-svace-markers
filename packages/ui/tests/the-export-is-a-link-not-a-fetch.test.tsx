import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'

import { ExportLink, PageHeader } from '../src/domain/PageHeader'

/**
 * THE CORNER IS FIXED ON PURPOSE, AND ONE SCREEN NEEDED A CONTROL ANYWAY.
 *
 * `Corner` is the same three on every page — findings, chat, settings — which is what makes them
 * findable. An export belongs on the markers list and nowhere else, so it goes through `extra`
 * rather than into `Corner`, and this holds both halves of that: the new control renders, and the
 * three that were always there still do. A regression here is a settings page wearing a download
 * button, or a markers page that quietly lost its way back to the supervisor.
 */
describe('the export control', () => {
  const draw = (extra?: React.ReactNode) =>
    renderToStaticMarkup(
      <PageHeader
        title="markers"
        subtitle="the queue and what has settled"
        findingsOpen={0}
        {...(extra === undefined ? {} : { extra })}
      />,
    )

  it('is a real download link, so the browser does the downloading', () => {
    const html = renderToStaticMarkup(<ExportLink href="/api/markers.csv" title="download CSV" />)
    // `download` and an href, NOT a button with a fetch behind it: the file is built by the
    // server and can run to megabytes, and a blob would have to be held in memory to hand back.
    expect(html).toContain('href="/api/markers.csv"')
    expect(html).toContain('download')
  })

  it('takes the href it is given rather than building one', () => {
    // THE BASE PATH IS THE APP'S. A link written `/api/...` inside the component works standalone
    // and 404s the moment a shell mounts this tool at a prefix.
    const html = renderToStaticMarkup(<ExportLink href="/ui/api/markers.csv" title="download CSV" />)
    expect(html).toContain('href="/ui/api/markers.csv"')
    expect(html).not.toContain('href="/api/markers.csv"')
  })

  it('is reachable by name, because a glyph is not a label', () => {
    const html = renderToStaticMarkup(<ExportLink href="/api/markers.csv" title="download CSV" />)
    expect(html).toContain('aria-label="download CSV"')
    // The glyph itself is decoration and must not be read out beside the label.
    expect(html).toContain('aria-hidden="true"')
  })

  it('renders in the header beside the three every screen wears', () => {
    const html = draw(<ExportLink href="/api/markers.csv" title="download CSV" />)
    expect(html).toContain('/api/markers.csv')
    expect(html).toContain('href="/chat"')
    expect(html).toContain('href="/settings"')
  })

  it('and a header given none looks exactly as it did', () => {
    const html = draw()
    expect(html).not.toContain('markers.csv')
    expect(html).toContain('href="/chat"')
    expect(html).toContain('href="/settings"')
  })
})
