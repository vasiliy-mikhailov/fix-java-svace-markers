'use strict';
// TODO(port): docstring
async function buildReproduceInput({ $, $json }) {
  const j = $('Prep prover').item.json;
  let src = '';
  try { src = Buffer.from(($json.content || '').replace(/\s/g,''), 'base64').toString('utf8'); } catch(e){ src = ''; }
  const SRC_MAX = 300000;                    // past the largest main-java file in the warm repos (~259k)
  const src_truncated = src.length > SRC_MAX;
  if (src_truncated) src = src.slice(0, SRC_MAX);

  // Blank out comments and string/char literals, preserving length AND newlines, so a brace or quote
  // inside them cannot desynchronise the body scan. Offsets stay valid against the original source.
  function mask(s) {
    return s
      .replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '))
      .replace(/\/\/[^\n]*/g, m => ' '.repeat(m.length))
      .replace(/"(\\.|[^"\\\n])*"/g, m => '"' + ' '.repeat(Math.max(0, m.length - 2)) + '"')
      .replace(/'(\\.|[^'\\\n])*'/g, m => "'" + ' '.repeat(Math.max(0, m.length - 2)) + "'");
  }

  // -> { name, startLine, endLine, text } for the method containing `line`, or null.
  //
  // The parameter list is scanned with a paren BALANCER, not matched by a regex. The parent's extractor
  // used `\([^;{)]*\)`, which stops at the first `)` — so on a Spring codebase like WebGoat, every method
  // with an annotated parameter (`@Value("${webgoat.user.directory}") final String home`,
  // `@RequestParam("x") String x`) failed to match and its whole body became invisible. Those methods
  // then reported "not inside any method", which reads as drift when it is really a parser gap.
  function enclosingMethod(source, line) {
    const s = mask(source);
    const skip = new Set(['if','for','while','switch','catch','synchronized','return','new','else','do','try']);
    // matches up to (and including) the method name's opening paren
    const sigRe = /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g;
    // offset -> 1-based line, without an O(n) scan per lookup
    const nl = []; for (let i = 0; i < source.length; i++) if (source[i] === '\n') nl.push(i);
    const lineOf = (off) => { let lo = 0, hi = nl.length; while (lo < hi) { const mid = (lo + hi) >> 1; if (nl[mid] < off) lo = mid + 1; else hi = mid; } return lo + 1; };
    let m;
    while ((m = sigRe.exec(s)) !== null) {
      const name = m[2];
      if (skip.has(name)) continue;
      // 1) balance the parameter list
      let depth = 0, close = -1;
      for (let i = sigRe.lastIndex - 1; i < s.length; i++) {
        if (s[i] === '(') depth++;
        else if (s[i] === ')') { depth--; if (depth === 0) { close = i; break; } }
      }
      if (close < 0) continue;
      // 2) optional throws clause, then the body's opening brace. No brace = an abstract/interface
      //    declaration or (far more often) an ordinary method CALL that happened to look like a signature.
      let k = close + 1;
      while (k < s.length && /\s/.test(s[k])) k++;
      if (s.startsWith('throws', k)) { while (k < s.length && s[k] !== '{' && s[k] !== ';') k++; }
      if (s[k] !== '{') continue;
      // 3) balance the body
      let d2 = 0, end = -1;
      for (let i = k; i < s.length; i++) {
        if (s[i] === '{') d2++;
        else if (s[i] === '}') { d2--; if (d2 === 0) { end = i + 1; break; } }
      }
      if (end < 0) continue;
      const startLine = lineOf(m.index + 1), endLine = lineOf(end);
      if (line >= startLine && line <= endLine) {
        return { name, startLine, endLine, text: source.slice(m.index, end) };
      }
      sigRe.lastIndex = end;
    }
    return null;
  }

  const lines = src.split('\n');
  const svLine = Number(j.svace_line) || 0;
  let anchor = '', anchor_status = 'unresolved', anchor_note = '', method_text = '', line_text = '';
  if (!src.trim()) {
    anchor_note = 'source file could not be fetched';
  } else if (svLine < 1 || svLine > lines.length) {
    // The file got SHORTER than the marker's line: the drift is proven, not merely suspected.
    anchor_status = 'unresolved';
    anchor_note = 'line ' + svLine + ' is past the end of the file as checked out (' + lines.length +
                  ' lines) — the file changed since the scan';
  } else {
    line_text = lines[svLine - 1];
    const em = enclosingMethod(src, svLine);
    if (em) {
      anchor = em.name;
      anchor_status = 'exact';
      method_text = em.text;
      anchor_note = 'line ' + svLine + ' falls inside ' + em.name + '() (lines ' + em.startLine + '-' + em.endLine + ')';
    } else {
      // Every remaining unanchored marker in the WebGoat report lands on a field or a Lombok annotation.
      // That is not drift and not a parser gap: Svace analysed the COMPILED code, where Lombok had
      // already generated the getter/setter/constructor it is complaining about. There is no source
      // method to point at, and an agent told only "not inside any method" will conclude the marker is
      // stale and wrongly clear it. Name the real situation instead.
      anchor_status = 'no-method';
      const lombok = /@(Getter|Setter|Data|Value|AllArgsConstructor|RequiredArgsConstructor|NoArgsConstructor|Builder|With)\b/.test(src);
      anchor_note = 'line ' + svLine + ' is not inside any method body (it is a field, annotation or import)'
        + (lombok
           ? ' — and this class uses Lombok, so the accessor or constructor the checker flagged is GENERATED at compile time and has no source form. Settle the claim against the generated API (for example the getter for this field), not against the annotation.'
           : '');
    }
  }

  const loc = j.file + ':' + svLine;
  const agent_input =
    "Repository: " + j.repo + "   (branch " + j.branch + ", module '" + j.module + "')\n" +
    "Source file: " + j.file + "\n\n" +
    "SVACE MARKER  [" + (j.svace_severity || '?') + "]  " + (j.svace_checker || '?') + "\n" +
    "Location as reported: " + loc + "\n" +
    "The checker's claim: " + (j.description || '') + "\n\n" +
    "LOCATION CONFIDENCE: " + anchor_status + " — " + anchor_note + "\n" +
    (line_text ? "Line " + svLine + " as it reads in the checked-out tree:\n```java\n" + line_text + "\n```\n" : "") +
    (method_text
      ? "\nThe enclosing method (this is where the claim should be settled):\n```java\n" + method_text + "\n```\n"
      : "\nNo enclosing method could be resolved — locate the construct the checker describes yourself.\n") +
    "\nWrite the proof test in package `" + j.pkg + "`, class `" + j.test_class + "`, at path `" + j.test_path + "`.\n" +
    "Only write the FAILING test — do not fix the defect.\n\n" +
    (src_truncated ? "SOURCE FILE (TRUNCATED — you are NOT seeing the whole file):\n```java\n"
                   : "FULL SOURCE FILE:\n```java\n") + src + "\n```";
  return { ...j, src, src_truncated, agent_input,
           anchor, anchor_status, anchor_note, line_text, method_text };
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { buildReproduceInput };
