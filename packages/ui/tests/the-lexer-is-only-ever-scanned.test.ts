import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * THE RULE THAT FALLS OUT OF HOW `matchAll` TREATS A SHARED CURSOR.
 *
 * `CodeBlock`'s `JAVA` pattern carries `/g` and is a module-level constant, so every block on the
 * page scans with the same object. That is safe for exactly one reason: `matchAll` never WRITES
 * `lastIndex`. Per spec it clones the pattern, copies the current cursor into the clone, and
 * advances only the clone — so a lexing pass cannot leave contamination behind for the next one, and
 * every block starts at zero only because nothing ever moves it off zero.
 *
 * `exec` and `test` have the opposite property and fail SILENTLY. One `exec` anywhere leaves the
 * cursor mid-source, and the next `matchAll` copies that cursor into its clone and returns fewer
 * tokens — or, from far enough in, none at all. No error, no exception; a block simply renders
 * uncoloured or half-coloured, and the file that broke it is not the file that looks wrong.
 *
 * The old comment called this re-entrancy, which is true and is not the load-bearing part. This
 * holds the part that is: one caller, one method, forever.
 */
describe('the Java lexer', () => {
  const source = readFileSync(join(__dirname, '..', 'src', 'primitives', 'CodeBlock.tsx'), 'utf8')

  /**
   * COMMENTS STRIPPED BEFORE SCANNING, and the first version of this test failed without it — on the
   * docblock that DEMONSTRATES the forbidden call. A guard that reads prose as code is the same
   * mistake as a Tailwind scan that reads prose as markup, and this file would have banned the
   * sentence explaining the ban.
   */
  const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

  it('is handed to matchAll and to nothing else', () => {
    const uses = [...code.matchAll(/\bJAVA\b\s*\)|\.(\w+)\(JAVA\)|JAVA\.(\w+)\(/g)]
    const methods = uses.map(m => m[1] ?? m[2]).filter(Boolean)
    expect(methods, 'JAVA.exec or JAVA.test leaves lastIndex mid-source and the NEXT block on the '
      + 'page silently loses its tokens').not.toContain('exec')
    expect(methods).not.toContain('test')
    expect(code, 'and it is still scanned at all').toContain('matchAll(JAVA)')
  })

  it('demonstrates why, so the rule is not folklore', () => {
    // The actual failure, reproduced against a throwaway pattern with the same shape.
    const pattern = /\b(?:int|public)\b/g
    pattern.exec('int a; int b;')
    expect(pattern.lastIndex, 'exec moved the shared cursor').toBe(3)
    expect([...'int a;'.matchAll(pattern)], 'every token lost, with no error').toEqual([])
    expect(pattern.lastIndex, 'and matchAll never wrote it back, so it stays broken').toBe(3)
  })

  it('and the branches are order-independent, which the comment used to get backwards', () => {
    // Disjoint opening characters mean alternation order decides no ties, because there are none.
    const forward = /(?<c>\/\/[^\n]*)|(?<s>"(?:\\.|[^"\\])*")|(?<w>\b(?:int|public|class)\b)|(?<n>\b\d[\w.]*)/g
    const reverse = /(?<n>\b\d[\w.]*)|(?<w>\b(?:int|public|class)\b)|(?<s>"(?:\\.|[^"\\])*")|(?<c>\/\/[^\n]*)/g
    for (const text of [
      '// the "public" API',
      'String s = "public int";',
      '/* "x" */ int y = 0x1F;',
      'char q = \'"\'; public int n = 1_000;',
    ]) {
      forward.lastIndex = 0
      reverse.lastIndex = 0
      expect([...text.matchAll(forward)].map(m => m[0]), text)
        .toEqual([...text.matchAll(reverse)].map(m => m[0]))
    }
  })
})
