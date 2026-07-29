// @ts-nocheck
'use strict';
/**
 * Pre-deploy validation for the generated n8n workflows.
 *
 * A syntax check only catches SYNTAX. The failures that actually bit us were runtime ReferenceErrors
 * in Code nodes — `method is not defined` (class auditor) and `_toolCache is not defined` (deleted by
 * an edit that spanned its declaration). Under onError=continueRegularOutput n8n forwards the node's
 * INPUT on such a throw, so the failure looks like "no findings" rather than an error, and the run
 * completes green with nothing in it. Three of the prover's Code nodes are configured that way.
 *
 * So every Code node body is checked for (a) syntax, via the real V8 parser, (b) identifiers that
 * resolve to no declaration — exactly the shape of both regressions above, since each was a name whose
 * declaration was never there or had been deleted — and (c) a `const`/`let`/`class` read above its own
 * declaration, which throws in the temporal dead zone and cost 78% of one run.
 *
 * Why no eslint. The Python original shelled out to `npx eslint` for (b) and (c); a port could pull
 * eslint in as a devDependency instead. It is not carried here because the question that has to be
 * answered is narrow — "does this name resolve anywhere" — and a lexical pass answers it with no
 * dependency and no network fetch on a machine that only ever runs this before a deploy. The trade is
 * stated in `analyse`: scopes are functions, not blocks, so a name shadowed in a sibling block can
 * hide a genuine miss. It cannot go the other way. Deleting each declaration in the nine live Code
 * nodes in turn, the check reports 189 of the 197 breakages that produces, and reports nothing at all
 * on the untouched originals — which is the property that matters, since a validator that cries wolf
 * is a validator nobody reads.
 *
 * Staleness is checked too, because a generator that FAILED leaves the old JSON on disk and validating
 * that prints a false green. (Hit exactly that: a generator died with a NameError and this script
 * reported 0 problems.) The generators are deterministic and export their workflow, so instead of
 * comparing mtimes this re-runs each one and compares the result — which also survives a fresh clone,
 * where git gives every file the same checkout time and an mtime comparison is a coin flip.
 *
 * Usage: node src/check-workflows.js [workflow_*.json ...]
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

// n8n injects these into Code nodes, alongside the standard JS globals.
const N8N_GLOBALS = ['$json', '$input', '$env', '$', '$node', '$workflow', '$execution', '$prevNode',
  '$items', '$item', '$runIndex', '$mode', '$now', '$today', 'console', 'Buffer',
  'require', 'process', 'setTimeout', 'URL', 'URLSearchParams', 'TextEncoder',
  'TextDecoder', 'structuredClone', 'globalThis'];

// The ES built-ins, read off a bare vm context rather than hand-listed: eslint got these from its
// `ecmaVersion` and a copy pasted into this file would flag the next builtin V8 grows.
const ES_GLOBALS = vm.runInNewContext('Object.getOwnPropertyNames(globalThis)');

// `arguments` is an implicit binding in every non-arrow function, not a global. eslint's scope
// analysis knows that; the pass below only sees explicit bindings, so it is declared here instead.
const KNOWN_GLOBALS = new Set([...ES_GLOBALS, ...N8N_GLOBALS, 'arguments']);

const KEYWORDS = new Set(('break case catch class const continue debugger default delete do else '
  + 'enum export extends false finally for function if implements import in instanceof interface '
  + 'let new null package private protected public return static super switch this throw true try '
  + 'typeof var void while with yield async await of get set from as').split(' '));

// After a keyword a `/` opens a regex (`return /x/`), except after one that is itself a value.
const VALUE_KEYWORDS = new Set(['this', 'super', 'true', 'false', 'null']);

// A parenthesis after one of these heads a statement, so its contents are reads, not parameters.
// Leaving `if` out would make `if (missingName)` bind `missingName` instead of reporting it.
const STATEMENT_HEADS = new Set(['if', 'for', 'while', 'switch', 'with', 'do', 'return', 'typeof',
  'catch', 'else', 'case', 'new', 'delete', 'void', 'throw', 'yield', 'await']);

const TDZ_KINDS = new Set(['const', 'let', 'class']);

const OPENERS = new Set(['(', '[', '{', '${']);
const CLOSERS = new Set([')', ']', '}']);

const PUNCT = ['>>>=', '...', '===', '!==', '**=', '<<=', '>>=', '>>>', '&&=', '||=', '??=',
  '=>', '==', '!=', '<=', '>=', '&&', '||', '??', '?.', '++', '--', '+=', '-=', '*=', '/=', '%=',
  '&=', '|=', '^=', '<<', '>>', '**',
  '{', '}', '(', ')', '[', ']', ';', ',', '<', '>', '+', '-', '*', '/', '%', '&', '|', '^', '!',
  '~', '?', ':', '=', '.', '@'];

const isIdStart = (c) => /[A-Za-z_$#]/.test(c) || c.charCodeAt(0) > 127;
const isIdPart = (c) => /[A-Za-z0-9_$#]/.test(c) || c.charCodeAt(0) > 127;

/**
 * Tokenise JS far enough to tell a variable from a property, a string or a comment.
 *
 * The one genuinely ambiguous character is `/`: the standard rule is that it opens a regex unless the
 * previous token could end an expression. `s.split(/,/)` and `a / b` both come out right; the known
 * blind spot is `if (x) /re/.test(y)`, where the `)` reads as the end of an expression.
 */
function lex(src) {
  const toks = [];
  const n = src.length;
  let i = 0;
  let line = 1;
  let depth = 0;          // brace depth, so a template's closing `}` can be told from a block's
  const tplBrace = [];    // brace depth at each open `${`

  const prev = () => toks[toks.length - 1];
  const push = (t, v) => toks.push({ t, v, line });

  function regexAllowed() {
    const p = prev();
    if (!p) return true;
    if (p.t === 'num' || p.t === 'str' || p.t === 'regex') return false;
    if (p.t === 'name') return KEYWORDS.has(p.v) && !VALUE_KEYWORDS.has(p.v);
    return !(p.v === ')' || p.v === ']' || p.v === '}' || p.v === '++' || p.v === '--');
  }

  /** Consume template text from `j` up to the closing backtick or the next `${`. */
  function tplChunk(j) {
    while (j < n) {
      const c = src[j];
      if (c === '\\') { j += 2; continue; }
      if (c === '\n') { line++; j++; continue; }
      if (c === '`') return { end: j + 1, expr: false };
      if (c === '$' && src[j + 1] === '{') return { end: j + 2, expr: true };
      j++;
    }
    throw new Error('unterminated template literal');
  }

  while (i < n) {
    const c = src[i];
    if (c === '\n') { line++; i++; continue; }
    if (c === ' ' || c === '\t' || c === '\r' || c === '\f' || c === '\v') { i++; continue; }
    if (c === '/' && src[i + 1] === '/') { while (i < n && src[i] !== '\n') i++; continue; }
    if (c === '/' && src[i + 1] === '*') {
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) { if (src[i] === '\n') line++; i++; }
      i += 2;
      continue;
    }
    if (c === '"' || c === "'") {
      const startLine = line;
      let j = i + 1;
      while (j < n && src[j] !== c) {
        if (src[j] === '\\') { j++; } else if (src[j] === '\n') line++;
        j++;
      }
      toks.push({ t: 'str', v: '', line: startLine });
      i = j + 1;
      continue;
    }
    if (c === '`') {
      const startLine = line;
      const r = tplChunk(i + 1);
      toks.push({ t: 'str', v: '', line: startLine });
      i = r.end;
      if (r.expr) { push('punct', '${'); tplBrace.push(depth); }
      continue;
    }
    if (c === '}' && tplBrace.length && tplBrace[tplBrace.length - 1] === depth) {
      tplBrace.pop();
      push('punct', '}');
      const r = tplChunk(i + 1);
      i = r.end;
      if (r.expr) { push('punct', '${'); tplBrace.push(depth); }
      continue;
    }
    if (c === '/' && regexAllowed()) {
      let j = i + 1;
      let inClass = false;
      while (j < n) {
        const d = src[j];
        if (d === '\\') { j += 2; continue; }
        if (d === '[') inClass = true;
        else if (d === ']') inClass = false;
        else if (d === '/' && !inClass) break;
        else if (d === '\n') throw new Error('unterminated regex literal');
        j++;
      }
      j++;
      while (j < n && /[a-z]/.test(src[j])) j++;    // flags
      push('regex', '');
      i = j;
      continue;
    }
    if (/[0-9]/.test(c) || (c === '.' && /[0-9]/.test(src[i + 1] || ''))) {
      let j = i;
      while (j < n && /[0-9a-zA-Z_.]/.test(src[j])) {
        if (/[eE]/.test(src[j]) && /[+-]/.test(src[j + 1] || '')) j++;
        j++;
      }
      push('num', src.slice(i, j));
      i = j;
      continue;
    }
    if (isIdStart(c)) {
      let j = i + 1;
      while (j < n && isIdPart(src[j])) j++;
      push('name', src.slice(i, j));
      i = j;
      continue;
    }
    const op = PUNCT.find((p) => src.startsWith(p, i));
    if (!op) { i++; continue; }
    if (op === '{') depth++;
    else if (op === '}') depth--;
    push('punct', op);
    i += op.length;
  }
  return toks;
}

/** Pair every bracket with its partner, both ways, so a construct can be recognised by lookahead. */
function matchBrackets(toks) {
  const stack = [];
  const match = new Map();
  for (let i = 0; i < toks.length; i++) {
    const tk = toks[i];
    if (tk.t !== 'punct') continue;
    if (OPENERS.has(tk.v)) stack.push(i);
    else if (CLOSERS.has(tk.v)) {
      const o = stack.pop();
      if (o !== undefined) { match.set(o, i); match.set(i, o); }
    }
  }
  return match;
}

/**
 * Which `:` closes a `?`. Without this, `{ status: s }` and `ok ? a : b` are indistinguishable and the
 * key rule would swallow `a` — a read — or the ternary rule would report `status` as an undefined
 * variable. The pending `?` is matched at its own bracket depth so `c ? { a: 1 } : 2` still works.
 */
function ternaryColons(toks) {
  const pending = [];
  const out = new Set();
  let depth = 0;
  for (let i = 0; i < toks.length; i++) {
    const tk = toks[i];
    if (tk.t !== 'punct') continue;
    if (OPENERS.has(tk.v)) depth++;
    else if (CLOSERS.has(tk.v)) depth--;
    else if (tk.v === '?') pending.push(depth);
    else if (tk.v === ':' && pending.length && pending[pending.length - 1] === depth) {
      pending.pop();
      out.add(i);
    }
  }
  return out;
}

/**
 * Walk a binding list — `const {a, b: c} = ...`, a parameter list — marking only the names it BINDS.
 *
 * Everything after a top-level `=` is an initialiser whose identifiers are ordinary reads, and that
 * distinction is the whole check: in `const x = method(y)` the point is that `method` is still
 * reported.
 */
function collectBindings(toks, from, to, bind, markKey) {
  for (let i = from; i < to; i++) {
    const tk = toks[i];
    if (tk.t === 'name') {
      if (KEYWORDS.has(tk.v)) continue;
      const nx = toks[i + 1];
      if (nx && nx.t === 'punct' && nx.v === ':') { markKey(i); continue; }  // `{ a: b }` binds b
      bind(i);
      continue;
    }
    if (tk.t !== 'punct' || tk.v !== '=') continue;
    let d = 0;                                          // skip this binding's default value
    i++;
    while (i < to) {
      const t2 = toks[i];
      if (t2.t === 'punct') {
        if (OPENERS.has(t2.v)) d++;
        else if (CLOSERS.has(t2.v)) { if (d === 0) break; d--; }
        else if (t2.v === ',' && d === 0) break;
      }
      i++;
    }
    i--;
  }
}

/**
 * Collect every declaration, every function region, and every identifier READ in a body.
 *
 * Scopes are FUNCTIONS, not blocks: a `const` inside an `if` is filed under the function that
 * encloses it. That is a deliberate over-approximation — a declaration is treated as visible to more
 * of the body than it really is, so a shadowed name can hide a genuinely undefined read (a miss), but
 * a name that resolves nowhere is still a name that resolves nowhere (no false alarm). Modelling
 * blocks too would trade that safety for a parser, and both regressions this file exists for were
 * names declared in NO scope at all.
 */
function analyse(toks) {
  const match = matchBrackets(toks);
  const colons = ternaryColons(toks);
  const decls = new Map();          // name -> [{ index, kind, region }]
  const skip = new Set();           // indices that are bindings, keys or method names, not reads
  const regions = [];               // { start, end } of every function body
  const handledParens = new Set();
  const handledArrows = new Set();
  const pending = [];               // declarations whose scope is only known once regions are complete

  const markKey = (i) => skip.add(i);
  const declare = (i, kind, region) => {
    skip.add(i);
    const d = { index: i, kind, region: region || null };
    if (!region) pending.push(d);
    const name = toks[i].v;
    if (decls.has(name)) decls.get(name).push(d);
    else decls.set(name, [d]);
  };

  /** Where an arrow's body ends: its braces, or the expression up to the enclosing `,` or bracket. */
  const arrowEnd = (arrow) => {
    const body = toks[arrow + 1];
    if (body && body.t === 'punct' && body.v === '{') return match.get(arrow + 1) ?? toks.length - 1;
    let d = 0;
    for (let k = arrow + 1; k < toks.length; k++) {
      const tk = toks[k];
      if (tk.t !== 'punct') continue;
      if (OPENERS.has(tk.v)) d++;
      else if (CLOSERS.has(tk.v)) { if (d === 0) return k - 1; d--; }
      else if ((tk.v === ',' || tk.v === ';') && d === 0) return k - 1;
    }
    return toks.length - 1;
  };

  for (let i = 0; i < toks.length; i++) {
    const tk = toks[i];
    if (tk.t !== 'name') continue;

    if (tk.v === 'const' || tk.v === 'let' || tk.v === 'var') {
      let d = 0;
      let end = toks.length;
      for (let j = i + 1; j < toks.length; j++) {
        const t2 = toks[j];
        if (t2.t === 'punct') {
          if (OPENERS.has(t2.v)) d++;
          else if (CLOSERS.has(t2.v)) { if (d === 0) { end = j; break; } d--; }
          else if (t2.v === ';' && d === 0) { end = j; break; }
        } else if (t2.t === 'name' && d === 0 && (t2.v === 'of' || t2.v === 'in')) { end = j; break; }
      }
      collectBindings(toks, i + 1, end, (k) => declare(k, tk.v), markKey);
      continue;
    }

    if (tk.v === 'class') {
      const nx = toks[i + 1];
      if (nx && nx.t === 'name' && !KEYWORDS.has(nx.v)) declare(i + 1, 'class');
      continue;
    }

    if (tk.v === 'function') {
      let j = i + 1;
      if (toks[j] && toks[j].t === 'punct' && toks[j].v === '*') j++;
      if (toks[j] && toks[j].t === 'name' && !KEYWORDS.has(toks[j].v)) { declare(j, 'function'); j++; }
      const open = toks[j];
      if (open && open.t === 'punct' && open.v === '(' && match.has(j)) {
        const close = match.get(j);
        handledParens.add(j);
        const body = close + 1;
        // Parameters belong to the function they head, not to whatever encloses the `function`.
        const region = toks[body] && toks[body].v === '{' && match.has(body)
          ? { start: body, end: match.get(body) } : null;
        if (region) regions.push(region);
        collectBindings(toks, j + 1, close, (k) => declare(k, 'param', region), markKey);
      }
      continue;
    }
  }

  for (let i = 0; i < toks.length; i++) {
    const tk = toks[i];
    if (tk.t !== 'punct') continue;

    // `x => ...`: a single parameter needs no parentheses, so the binding sits before the arrow.
    if (tk.v === '=>') {
      if (handledArrows.has(i)) continue;
      const region = { start: i + 1, end: arrowEnd(i) };
      regions.push(region);
      const p = toks[i - 1];
      if (p && p.t === 'name' && !KEYWORDS.has(p.v)) declare(i - 1, 'param', region);
      continue;
    }
    if (tk.v !== '(' || handledParens.has(i) || !match.has(i)) continue;

    const close = match.get(i);
    const after = toks[close + 1];
    const before = toks[i - 1];
    if (after && after.t === 'punct' && after.v === '=>') {
      const arrow = close + 1;
      handledArrows.add(arrow);
      const region = { start: arrow + 1, end: arrowEnd(arrow) };
      regions.push(region);
      collectBindings(toks, i + 1, close, (k) => declare(k, 'param', region), markKey);
      continue;
    }
    if (before && before.t === 'name' && before.v === 'catch') {
      collectBindings(toks, i + 1, close, (k) => declare(k, 'param'), markKey);
      continue;
    }
    // `foo(a) { ... }` is a method: the name is a property, the parentheses are parameters. Guarded
    // by STATEMENT_HEADS so `if (cond) { ... }` keeps reporting an undefined `cond`. A computed or
    // quoted method name (`[k](a) {`, `'a-b'(a) {`) is the same shape and no other construct is.
    const named = before && ((before.t === 'name' && !STATEMENT_HEADS.has(before.v))
      || (before.t === 'punct' && before.v === ']') || before.t === 'str');
    if (after && after.t === 'punct' && after.v === '{' && named) {
      skip.add(i - 1);
      const region = match.has(close + 1) ? { start: close + 1, end: match.get(close + 1) } : null;
      if (region) regions.push(region);
      collectBindings(toks, i + 1, close, (k) => declare(k, 'param', region), markKey);
    }
  }

  // Innermost wins, so that a `const` inside a nested function is not filed under its parent.
  const holds = (r, i) => i >= r.start && i <= r.end;
  const regionOf = (i) => regions.reduce(
    (best, r) => (holds(r, i) && (!best || r.start > best.start) ? r : best), null);
  for (const d of pending) d.region = regionOf(d.index);

  const reads = [];
  for (let i = 0; i < toks.length; i++) {
    const tk = toks[i];
    if (tk.t !== 'name' || KEYWORDS.has(tk.v) || tk.v.startsWith('#') || skip.has(i)) continue;
    const p = toks[i - 1];
    if (p && p.t === 'punct' && (p.v === '.' || p.v === '?.')) continue;      // a property, not a name
    // `continue outer` names a label, which lives in its own namespace and is never a variable.
    if (p && p.t === 'name' && (p.v === 'break' || p.v === 'continue')) continue;
    const nx = toks[i + 1];
    const isCase = p && p.t === 'name' && p.v === 'case';
    if (nx && nx.t === 'punct' && nx.v === ':' && !colons.has(i + 1) && !isCase) continue;
    reads.push({ name: tk.v, index: i, line: tk.line, region: regionOf(i) });
  }

  // A declaration is in scope for a read when it sits at the top level or inside a function that also
  // contains the read — the scope chain, walked without building one.
  const visible = (read) => (decls.get(read.name) || [])
    .filter((d) => d.region === null || holds(d.region, read.index));

  return { decls, reads, visible };
}

/** Syntax, via the real parser. The body runs inside an async wrapper, so top-level await is legal. */
function checkSyntax(js) {
  try {
    new vm.Script(`(async () => {\n${js}\n})();\n`, { filename: 'node.js' });
    return null;
  } catch (e) {
    const at = /^node\.js:(\d+)/m.exec(e.stack || '');
    const line = at ? Math.max(1, Number(at[1]) - 1) : null;      // undo the wrapper's opening line
    return { line, message: `SYNTAX: ${e.message}` };
  }
}

/** Validate one Code node body. Returns eslint-shaped problems (blocking) and warnings (advisory). */
function checkCode(js) {
  const syntax = checkSyntax(js);
  if (syntax) return { problems: [syntax], warnings: [] };

  let parsed;
  try {
    parsed = analyse(lex(js));
  } catch (e) {
    return { problems: [{ line: null, message: `UNREADABLE: ${e.message}` }], warnings: [] };
  }

  const problems = [];
  const warnings = [];
  const reported = new Set();
  for (const read of parsed.reads) {
    if (reported.has(read.name)) continue;
    const inScope = parsed.visible(read);
    if (!inScope.length) {
      if (KNOWN_GLOBALS.has(read.name)) continue;
      reported.add(read.name);
      problems.push({ line: read.line, message: `'${read.name}' is not defined` });
      continue;
    }
    // With blocks unmodelled, two visible declarations may be siblings in different blocks and there
    // is no telling which one this read resolves to — so only an unambiguous name is judged here.
    const [decl] = inScope;
    if (inScope.length !== 1 || !TDZ_KINDS.has(decl.kind) || read.index >= decl.index) continue;
    reported.add(read.name);
    const msg = `'${read.name}' was used before it was defined`;
    if (decl.region === read.region) {
      // Same function, so nothing defers the read past the declaration: this is a temporal-dead-zone
      // throw on the first execution — the failure that ate 78% of one run.
      problems.push({ line: read.line, message: msg });
    } else {
      // A helper referencing outer state declared later is legitimate as long as nobody calls it too
      // early, so this advises rather than blocks.
      warnings.push({ line: read.line, message: msg });
    }
  }
  return { problems, warnings };
}

function* codeNodes(wf) {
  for (const node of wf.nodes || []) {
    const js = (node.parameters || {}).jsCode;
    if (js) yield [node.name, js];
  }
}

/** Validate every Code node in one workflow JSON. */
function checkWorkflow(file) {
  const wf = JSON.parse(fs.readFileSync(file, 'utf8'));
  const problems = [];
  const warnings = [];
  for (const [name, js] of codeNodes(wf)) {
    const r = checkCode(js);
    const at = (m) => (m.line ? `line ${m.line}: ${m.message}` : m.message);
    for (const m of r.problems) problems.push([name, at(m)]);
    for (const m of r.warnings) warnings.push([name, at(m)]);
  }
  return { problems, warnings };
}

// The three live stages. The suspector, orchestrator and dedup generators were deleted when Svace
// markers replaced LLM detection: they were never re-run after the fsm rename, so they sat permanently
// STALE here and every validation run reported "3 problem(s)". A check that is always red is a check
// nobody reads, and it would have hidden a real staleness failure in the workflows that do ship.
const GEN_FOR = {
  'workflow_setup.json': 'src/gen-setup.js',
  'workflow_ingest.json': 'src/gen-ingest.js',
  'workflow_prover.json': 'src/gen-prover.js',
};

/**
 * Does the generator still produce this JSON?
 *
 * The generators are deterministic and export their workflow, so re-running one is the exact answer to
 * "did this artifact come from the current source" — sharper than the mtime comparison this replaces,
 * which could not see a hand-edited JSON and read as random noise after a clone, where git stamps
 * every file with the same checkout time.
 */
function staleness(file) {
  const gen = GEN_FOR[path.basename(file)];
  if (!gen) return null;
  const genPath = path.join(path.dirname(path.resolve(file)), gen);
  if (!fs.existsSync(genPath) || !fs.existsSync(file)) return null;
  let wf;
  try {
    wf = require(genPath).wf;
  } catch (e) {
    return `STALE: ${gen} throws on load (${String(e.message).split('\n')[0]}) — it cannot have `
      + 'produced this JSON. Fix it and re-run it.';
  }
  if (!wf) return null;
  let disk;
  try {
    disk = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    return `STALE: this JSON does not parse (${e.message}). Re-run ${gen}.`;
  }
  if (JSON.stringify(wf) === JSON.stringify(disk)) return null;
  return `STALE: ${gen} does not produce this JSON — the generator did not run (or it failed). `
    + 'Re-run it.';
}

/** Run the whole check. Returns the problem count; `log` receives the report line by line. */
function run(paths, log = console.log) {
  let bad = 0;
  for (const p of paths) {
    const st = staleness(p);
    if (st) {
      bad += 1;
      log(`✗ ${path.basename(p)}\n    ${st}`);
      continue;
    }
    const { problems, warnings } = checkWorkflow(p);
    if (problems.length) {
      bad += problems.length;
      log(`✗ ${path.basename(p)}`);
      for (const [node, msg] of problems) log(`    [${node}] ${msg}`);
    } else {
      log(`✓ ${path.basename(p)}`);
    }
    for (const [node, msg] of warnings) log(`    ⚠ [${node}] ${msg}`);
  }
  log(`\n${bad} problem(s)`);
  return bad;
}

function main(argv, log) {
  const paths = argv.length ? argv
    : fs.readdirSync(process.cwd()).filter((f) => /^workflow_.*\.json$/.test(f)).sort();
  return run(paths.map((p) => path.resolve(p)), log);
}

if (require.main === module) {
  process.exit(main(process.argv.slice(2)) ? 1 : 0);
}

module.exports = {
  N8N_GLOBALS, KNOWN_GLOBALS, GEN_FOR,
  lex, analyse, checkSyntax, checkCode, checkWorkflow, staleness, run, main,
};
