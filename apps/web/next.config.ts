import type { NextConfig } from 'next'

/**
 * A ZONE, MOUNTED WHEREVER THE SHELL PUTS IT.
 *
 * `basePath` and `assetPrefix` come from the environment because a tool that hard-codes where it is
 * mounted can be mounted one way, and the second tool wanting that prefix cannot be mounted at all.
 * Empty by default, so this serves standalone exactly as the Java dashboard does today.
 *
 * STATIC EXPORT, and that is a decision worth its reason. Multi-Zones normally means a running
 * Next.js server per zone; here every page is a projection of files the Java side already holds, so
 * there is nothing to render on a server that the client cannot fetch. Exporting keeps the container
 * single-process — one JVM, no node beside it — in an image that is already 1.5 GB and already runs
 * arbitrary code from strangers' repositories. The cost is no RSC and no server-side theme cookie;
 * the theme is handled by a blocking script instead (see app/layout.tsx), which avoids the flash the
 * same way.
 */
const basePath = process.env.BASE_PATH?.replace(/\/$/, '') ?? ''

export default {
  output: 'export',
  basePath,
  // SPREAD RATHER THAN SET TO undefined: the shared tsconfig turns on exactOptionalPropertyTypes,
  // which draws a distinction between "absent" and "present and undefined" — and Next means the
  // first. Standalone has no asset prefix at all, not an undefined one.
  ...(basePath ? { assetPrefix: `${basePath}-static` } : {}),
  trailingSlash: true,
  images: { unoptimized: true },
  env: { NEXT_PUBLIC_BASE_PATH: basePath },
} satisfies NextConfig
