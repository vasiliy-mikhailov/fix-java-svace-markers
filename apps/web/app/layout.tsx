import type { Metadata } from 'next'
import './globals.css'

export const metadata: Metadata = {
  title: 'Prove markers',
  description: 'Proving static-analysis markers: a failing test, a patch, the same test passing.',
}

/**
 * THE THEME IS SET BEFORE THE FIRST PAINT, and it has to be.
 *
 * The shell writes a `theme` cookie on the shared origin, because a zone renders its own document
 * and nothing the shell does to its own root element reaches this one. This page is statically
 * exported, so there is no server to read that cookie on the way out — it is read here instead, by a
 * blocking script in the head, before anything is drawn.
 *
 * Blocking on purpose. Doing this in an effect paints the page light and then corrects it, which is
 * the flash every reader notices and nobody reports. Absent or unrecognised follows the system
 * preference, which is also the right answer when nobody has mounted this tool at all.
 */
const THEME = `(function(){try{
  var m=document.cookie.match(/(?:^|;\\s*)theme=(dark|light)/);
  var t=m?m[1]:(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light');
  if(t==='dark')document.documentElement.classList.add('dark');
}catch(e){}})()`

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME }} />
      </head>
      <body>{children}</body>
    </html>
  )
}
