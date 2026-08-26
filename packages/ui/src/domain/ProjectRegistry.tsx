import { type Column, DataTable } from 'ratchet-ui/components'
import type { Style } from '../primitives'

/**
 * ONE PROJECT, AS THE REGISTRY NEEDS IT.
 *
 * `href` IS THE CALLER'S, and that is the same rule `ExportLink` and `MarkerCrumb` already follow: a
 * link built inside this package cannot know the base path a shell mounted the zone at, and a
 * hard-coded `/project?p=` here would join the six other places in this package that are correct
 * only because the prefix happens to be empty today.
 */
export type ProjectEntry = {
  name: string
  /**
   * THE CLONE URL, AND IT IS THE IDENTITY — the name is only its last path segment, and two
   * repositories whose URLs end the same collapse into one name.
   *
   * IT IS ALSO A PLACE A READER CAN GO, which it was not while these said `http://gitlab/root/…`:
   * a hostname that resolves only inside one docker network, shown to a person on a laptop. The
   * record names the canonical repository now and git's own `insteadOf` sends the fetch somewhere
   * this network can reach — so this is a working address and is linked as one.
   */
  repo: string
  /** From `projects.tsv`, `''` when the registry does not name this project. */
  jdk: string
  markers: number
  decided: number
  /** Of the decided, how many had a test that actually failed and then passed. */
  demonstrated: number
  modules: number
  href: string
}

export type ProjectRegistryProps = { projects: ProjectEntry[] }

const LINK: Style = { color: 'var(--accent-primary)', textDecoration: 'none' }

const QUIET: Style = { color: 'var(--text-tertiary)', fontSize: '11px' }

/** A number nobody has reached yet is a dash, not a zero — the rule the marker table already keeps. */
function count(n: number) {
  return n === 0 ? <span style={QUIET}>—</span> : <>{n.toLocaleString()}</>
}

/**
 * THE FIRST PAGE: WHAT THIS RUN IS ABOUT, ONE LINE EACH.
 *
 * <p>THE PAGE IT REPLACES WAS 857 ROWS. Every marker of every project, with the whole of whatever a
 * model had written about each, in one list — and the only sign that the run had stopped being one
 * repository was that the filenames started looking different. A reader who wanted ca2_back scrolled
 * past 356 rows of WebGoat to reach it, and the browser held 3.86 MB to draw the first screenful.
 *
 * <p>SO THE LEVELS FOLLOW THE QUESTIONS. Which projects is this run about, and how far has each got?
 * Then: which modules of one project, and which markers in them? Then: what happened to this marker?
 * Each page fetches only its own level — the registry is 860 bytes and answers in seven
 * milliseconds, against 3,863,289 bytes and a second and a half for the list it replaces.
 *
 * <p>A TABLE AND NOT CARDS, deliberately. These rows are compared column by column — decided against
 * markers, shown against decided — and cards put the numbers in different places on every row.
 */
export function ProjectRegistry({ projects }: ProjectRegistryProps) {
  return (
    <DataTable
      rows={projects}
      columns={COLUMNS}
      rowKey={project => project.name}
      rowClassName="hover:bg-[var(--state-hover-bg)]"
    />
  )
}

const COLUMNS: Column<ProjectEntry>[] = [
  {
    head: 'project',
    cell: project => (
      <>
        <a href={project.href} style={LINK} className="hover:underline">
          {project.name}
        </a>
        {/*
          A NEW TAB AND NO REFERRER. This leaves the dashboard for somebody else's host — GitHub for
          one of these, a private GitLab for the rest — and a reader following it has not finished
          with the page they were on.
        */}
        <div>
          <a
            href={project.repo}
            target="_blank"
            rel="noreferrer noopener"
            style={{ ...QUIET, textDecoration: 'none' }}
            className="hover:underline"
          >
            {project.repo}
          </a>
        </div>
      </>
    ),
  },
  {
    head: 'markers',
    align: 'right',
    cell: project => count(project.markers),
  },
  {
    head: 'decided',
    align: 'right',
    headTitle: 'reached an answer — a disposition, not `infra` or `interrupted`',
    cell: project => count(project.decided),
  },
  {
    head: 'shown by a test',
    align: 'right',
    // THE SPLIT THE RUN PROGRESS BAR MAKES, PER PROJECT. `decided` counts a marker closed by a
    // paragraph the same as one where a test failed before the patch and passed after it, and the
    // difference is the difference between an argument and evidence.
    headTitle: 'of the decided, how many had a build that actually went red and then green',
    cell: project => count(project.demonstrated),
  },
  {
    head: 'modules',
    align: 'right',
    headTitle: 'source roots — a single-module repository has one',
    cell: project => count(project.modules),
  },
  {
    head: 'jdk',
    align: 'right',
    /*
     * BLANK IS A REAL ANSWER AND IT IS THE INTERESTING ONE. A project the queue names and
     * `projects.tsv` does not is being built under the run-wide default, which is exactly the
     * silent inheritance the registry file was added to end — a second subject compiled under the
     * first one's Java version does not announce itself, it reports "no test executed".
     */
    headTitle: 'from projects.tsv — blank means it is using the run-wide default',
    cell: project =>
      project.jdk === '' ? <span style={QUIET}>default</span> : <>{project.jdk}</>,
  },
]
