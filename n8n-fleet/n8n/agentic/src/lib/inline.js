'use strict';
/**
 * Turn a real .js module into an n8n Code-node body.
 *
 * n8n Code nodes cannot require anything local: whatever runs must be one self-contained script. That
 * is why the pipeline's logic used to live in Python string constants — nothing could import it, so
 * nothing could test it either.
 *
 * The fix is to keep each node as an ORDINARY module exporting an ordinary function, and have the
 * generator inline the function source and append a two-line shim that calls it with n8n's injected
 * globals. The file on disk is the same text that runs in production, so `node --test` imports it
 * directly and c8/Stryker see real source with real line numbers.
 *
 * Everything from the EXPORTS marker onward is stripped: `module.exports` is meaningless inside n8n
 * and would throw there.
 */
const fs = require('fs');
const path = require('path');

const EXPORTS_MARKER = '/* ---- test exports (stripped when inlined into n8n) ---- */';

/** Read a module's source with its export block and 'use strict' removed. */
function sourceOf(file) {
  let src = fs.readFileSync(file, 'utf8');
  const cut = src.indexOf(EXPORTS_MARKER);
  if (cut === -1) {
    throw new Error(
      `${path.basename(file)} has no EXPORTS_MARKER. Every inlined module must end with:\n` +
      `${EXPORTS_MARKER}\nmodule.exports = { ... };`);
  }
  src = src.slice(0, cut);
  // 'use strict' is implicit in n8n's wrapper and a stray directive mid-body is dead weight
  return src.replace(/^'use strict';\s*/m, '').trim();
}

/**
 * Build a node body: the deps' sources, then the node's own, then the call shim.
 * `call` is the expression n8n evaluates, e.g. "return recordOutcome({ $, $json });".
 */
function nodeBody(entry, { deps = [], call }) {
  const parts = deps.map(sourceOf);
  parts.push(sourceOf(entry));
  parts.push(call);
  return parts.join('\n\n');
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { EXPORTS_MARKER, sourceOf, nodeBody };
