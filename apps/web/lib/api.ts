/**
 * EVERY READ GOES THROUGH HERE, so the base path is applied in one place.
 *
 * A fetch written as `/api/index` works standalone and 404s the moment a shell mounts this tool at a
 * prefix — and it 404s at runtime, in the browser, on somebody else's deployment. The prefix is
 * baked at build by next.config.ts and read back here.
 */
const BASE = process.env.NEXT_PUBLIC_BASE_PATH ?? ''

export class ApiError extends Error {
  /**
   * THE URL THAT WAS ACTUALLY REQUESTED, not the path that was asked for.
   *
   * <p>This reported the bare path, so a build whose base path was wrong showed "/api/index answered
   * 404" while the request had gone to "/ui/api/index" — the one fact that would have named the
   * cause, hidden by the message meant to report it.
   */
  constructor(readonly status: number, readonly url: string) {
    super(`${url} answered ${status}`)
  }
}

export async function read<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, { ...init, cache: 'no-store' })
  if (!response.ok) {
    throw new ApiError(response.status, `${BASE}${path}`)
  }
  return (await response.json()) as T
}

/** A link within this zone. Same reason as above: the shell owns the prefix. */
export function href(path: string): string {
  return `${BASE}${path}`
}
