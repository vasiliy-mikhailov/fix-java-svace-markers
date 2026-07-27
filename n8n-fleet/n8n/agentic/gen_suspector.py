#!/usr/bin/env python3
"""fsm-suspector: METHOD-BY-METHOD Java bug suspector with a MANUAL ReAct tool loop.

Input (POST /webhook/suspect): { repo, branch, file }
Each substantive method is analyzed by a hand-rolled ReAct loop (a Code node, NOT n8n's ToolsAgent):
  LLM(with tools) -> if tool_call, run it on the java-runner fs endpoints, feed result back, repeat
  (<= MAX_TOOL_CALLS) -> then a FINAL call with tool_choice='none' to FORCE the JSON verdict.
This is required because Qwen keeps calling tools forever and never emits a final answer on its own;
n8n's ToolsAgent loops to maxIterations and returns an empty tool-call turn -> 0 findings.
Candidates then go through the INTELLECTUAL dedup layer before upsert.
"""
import json

# Versions live in versions.py (one source of truth for all four lifecycle stages) so every dialog and
# every stored row is tagged with the code that produced it.
from versions import PIPELINE_VERSION, SUSPECTOR_VERSION, stamp

SUSPICIONS_TABLE = "o17lTGMNGX0RI8wY"

SYS = (
    "You are a meticulous Java bug hunter AND security reviewer. You are assigned ONE method: it is your "
    "ENTRY POINT, not a fence. Its full body is given, plus the class skeleton (sibling bodies elided).\n\n"
    "DIG for bugs and security weaknesses — do not passively skim and summarise. Attack the code: ask what "
    "input breaks it, what an attacker can control, what the callee actually guarantees vs. what this code "
    "assumes. Hunt both CORRECTNESS bugs and SECURITY weaknesses:\n"
    "- correctness: NPEs on realistic inputs, off-by-one / boundary errors, wrong logic, data corruption, "
    "resource/handle leaks, and API-contract violations (equals/hashCode/compareTo/iterator/close);\n"
    "- security: injection (SQL / command / LDAP / XPath / expression-language), path traversal & zip-slip, "
    "unsafe deserialization, XXE / unsafe XML|YAML parsing, SSRF, unsafe reflection or dynamic class "
    "loading, hardcoded or logged secrets/credentials, weak or misused crypto and predictable randomness "
    "(e.g. java.util.Random for tokens/keys, ECB, static IV/salt), TOCTOU / insecure temp-file handling, "
    "integer overflow that defeats a bounds check, and any untrusted input that reaches a dangerous sink.\n"
    "For a suspected security issue, trace whether this method's inputs are attacker-controllable — follow "
    "the callers / parameters with the tools — and whether they actually reach the dangerous operation.\n\n"
    "Start at your method and follow the call chain as deep as the bug needs. If, while walking that chain, "
    "you find a genuine bug or security weakness in a CALLEE (another method or class), REPORT IT TOO — "
    "never discard a real finding just because it lives elsewhere — but attribute it honestly using the "
    "`file` and `method` fields so it is filed where the defect actually is. The ONE thing you must not do "
    "is GUESS at bugs in sibling methods whose bodies you have not actually read (the skeleton shows only "
    "signatures): read them with the tools first, then report what you verified.\n\n"
    "Be exhaustive — check every branch, boundary (0 / 1 / empty / null), loop bound, and exception path. "
    "Do NOT stop after the first finding. Ignore style, naming, formatting, micro-optimizations, and "
    "purely theoretical issues with no reachable path.\n\n"
    "You have tools to walk the code like a debugger when THIS method depends on other code. To follow a "
    "call, use build_catastrophic_chain(symbol, current_exploration_ideas) — say WHY you are stepping in, and a dedicated "
    "investigator reads that callee in its OWN context (carrying your call chain) and reports back what "
    "it guarantees, how it can break you, and any defects it verified inside it (filed automatically). "
    "It resolves the exact declaration the call binds to, and follows polymorphic calls into the "
    "concrete overrides that actually run. "
    "(resolving the receiver type, the overload and inheritance); far more precise than grep. For a "
    "To understand a whole other area without pulling its files "
    "build_catastrophic_chain HANDS OFF a whole subtree of the investigation — it does not fetch anything for you. "
    "A fresh investigator takes ownership of that callee, reads it in its own context, digs deeper "
    "itself if needed, and reports back its contract, the risks it creates for you, and the defects it "
    "verified. You will NEVER see that code: asking to \"see the source\", \"read the file\" or \"show "
    "me X\" is a category error. Delegate a QUESTION TO BE SETTLED — 'does it copy or alias the list?', "
    "'can a null element reach the array write?' — and trust the answer that comes back.\n\n"
    "Delegate LARGE, not small: one hand-off that settles a whole branch beats five that each fetch a "
    "fragment. If you find yourself asking for a file, you are using it wrong.\n\n"
    "build_catastrophic_chain is your ONLY way to move: there is no file browsing, no grep, no directory listing. "
    "You have the FULL source of your file, and you may delegate ANY symbol — in this file or another. "
    "Move along real call edges, as deep "
    "as the bug needs (there is no limit), then STOP and report.\n\n"
    "You therefore cannot see who CALLS this code. Where a defect depends on that (is this input "
    "attacker-controlled? is this method public API?), state the assumption explicitly in the finding "
    "instead of guessing.\n\n"
    "Your final answer must be ONLY this JSON object, no prose:\n"
    '{\"suspicions\":[{\"file\":\"<repo-relative file the defect actually lives in — OMIT if it is the method you were assigned>\",'
    '\"method\":\"<the method the defect lives in — OMIT if it is the method you were assigned>\",\"line\":<int>,'
    '\"category\":\"correctness|npe|resource-leak|contract-violation|data-corruption|injection|path-traversal|deserialization|xxe|ssrf|crypto|secrets|unsafe-reflection|security\",'
    '\"severity\":\"high|medium|low\",\"title\":\"..\",\"description\":\"..\",\"evidence\":\"<concrete attacker input / call -> wrong output or exploit>\"}]}\n'
    "If you have genuinely dug and there is no bug or security weakness, answer {\"suspicions\":[]}."
)

INVESTIGATOR_SYS = (
    "You are exploring the EXECUTION PATHS of a Java repository, one node of a recursive exploration. "
    "Your job is to BUILD A CATASTROPHIC CHAIN: a concrete path through this code that behaves NOT as "
    "expected, and that leads to a bug, a malfunction, or a security vulnerability under attack.\n\n"
    "Code is static structure. Defects are not. They live in what actually HAPPENS when a specific value "
    "flows through a specific path — at a boundary, on an empty or null or negative or overflowing "
    "input, on a second call, on a concurrent call, when an exception unwinds halfway, or when one "
    "layer's assumption contradicts what the next layer actually guarantees. Do not describe the code. "
    "Trace what a value does to it.\n\n"
    "Assume an adversary chooses the input wherever an adversary plausibly can. Ask what reaches this "
    "path from outside — a file, a request, a parsed document, a model resource, a user-supplied name — "
    "and what it can be made to do here: wrong results that propagate silently, corrupted or aliased "
    "state, a crash, a hang or unbounded loop, an exhausted resource, an unclosed handle, an injection "
    "or traversal or deserialization reaching a dangerous sink, a secret written somewhere it should "
    "not be, a check that can be defeated by overflow.\n\n"
    "You may be told what this exploration has ALREADY established. Build on it; never re-derive it. If "
    "what you read CONTRADICTS something established above — a caller relying on a guarantee this code "
    "does not actually make — say so: that mismatch is usually where the chain breaks.\n\n"
    "To follow the path into a callee, use build_catastrophic_chain(symbol, current_exploration_ideas): "
    "a fresh explorer takes that branch, carrying what you suspect, and reports back what it "
    "guarantees, how it can break you, and what it verified. Report only defects you VERIFIED by "
    "reading the code, attributed to the file and method they live in. Every finding needs a concrete "
    "input or sequence — the evidence IS the chain. Never guess at code you did not read.\n\n"
    "Your final answer must be ONLY this JSON object, no prose:\n"
    '{"summary":"<=100 words: what this code does and what it does NOT guarantee>",'
    '"caller_risks":"<=60 words: how this can break whoever called it, or \'none\'>",'
    '"defects":[{"file":"<repo-relative file the defect lives in>","method":"<method it lives in>",'
    '"line":<int>,"category":"correctness|npe|resource-leak|contract-violation|data-corruption|'
    'injection|path-traversal|deserialization|xxe|ssrf|crypto|secrets|unsafe-reflection|security",'
    '"severity":"high|medium|low","title":"..","description":"..",'
    '"evidence":"<the chain: concrete input / call sequence -> what actually happens>"}]}\n'
    "If you traced the paths honestly and none of them break, return an empty defects array."
)

EXTRACT_METHODS = r"""
const t = $('Suspect webhook').first().json.body || {};
const repo = t.repo, file = t.file;
let src = '';
try { src = Buffer.from(($input.first().json.content || '').replace(/\s/g, ''), 'base64').toString('utf8'); } catch(e) { src = ''; }

function extractMethods(src, file) {
  if (src.length > 400000) src = src.slice(0, 400000);   // hard ceiling so a pathological file can't stall the scan
  const s = src
    .replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, m => ' '.repeat(m.length))
    .replace(/"(\\.|[^"\\\n])*"/g, m => '"' + ' '.repeat(Math.max(0, m.length - 2)) + '"')
    .replace(/'(\\.|[^'\\\n])*'/g, m => "'" + ' '.repeat(Math.max(0, m.length - 2)) + "'");
  const cm = s.match(/\b(?:class|interface|enum|record)\s+([A-Za-z_]\w*)/);
  const className = cm ? cm[1] : (file.split('/').pop() || 'X').replace('.java', '');
  const skip = new Set(['if','for','while','switch','catch','synchronized','return','new','else','do','try']);
  // bounded quantifiers everywhere: unbounded nesting here catastrophically backtracks on big dense
  // files (the generated stemmers ~78k chars) and the n8n runner kills the 60s synchronous stall.
  const sigRe = /(?:^|\n)([ \t]{0,80}(?:@[\w.]+(?:\([^)]*\))?[ \t\n]{0,40}){0,12}(?:(?:public|private|protected|static|final|abstract|synchronized|native|default)[ \t\n]{1,8}){0,8}[\w.<>\[\],?&\s]{1,160}?\s([A-Za-z_]\w*)\s*\([^;{)]{0,3000}\)\s*(?:throws[\w.,\s]{0,300})?)\{/g;
  const methods = []; let m;
  while ((m = sigRe.exec(s)) !== null) {
    const name = m[2]; if (skip.has(name)) continue;
    const braceAt = sigRe.lastIndex - 1; let depth = 0, end = -1;
    for (let i = braceAt; i < s.length; i++) { if (s[i]==='{') depth++; else if (s[i]==='}'){depth--; if(depth===0){end=i+1;break;}} }
    if (end < 0) continue;
    const sigStart = m.index + (m[0].startsWith('\n') ? 1 : 0);
    methods.push({ name, startLine: src.slice(0,sigStart).split('\n').length, src: src.slice(sigStart,end),
                   bodyStart: braceAt, end });
    sigRe.lastIndex = end;
  }
  // skeleton: keep the class verbatim but replace each method BODY ({...}) with { ... }. Span-based, so
  // it never touches signatures, fields, or javadoc — a brace inside javadoc ("{@link}") is not a body.
  const spans = methods.map(mm => ({ s: mm.bodyStart, e: mm.end })).sort((a, b) => a.s - b.s);
  let skeleton = '', pos = 0;
  for (const sp of spans) { if (sp.s < pos) continue; skeleton += src.slice(pos, sp.s) + '{ ... }'; pos = sp.e; }
  skeleton += src.slice(pos);
  return { className, methods, skeleton };
}
const CONTRACT = new Set(['equals','hashCode','compareTo','iterator','close','clone','readObject','writeObject','readResolve']);
function substantive(name, msrc) {
  if (CONTRACT.has(name)) return true;
  const body = msrc.slice(msrc.indexOf('{') + 1, msrc.lastIndexOf('}'));
  if (/\b(if|for|while|switch|catch|instanceof|new |throw)\b|&&|\|\||==|!=|[+\-*/%]=?/.test(body)) return true;
  if (body.replace(/\s/g, '').length > 120) return true;
  return false;
}
const { className, methods, skeleton } = extractMethods(src, file);
// the FULL file, not the body-elided skeleton: sibling bodies were costing a delegation each
let sk = src; if (sk.length > 30000) sk = skeleton.slice(0, 30000);   // fall back to the skeleton if huge
const picked = methods.filter(m => substantive(m.name, m.src));
if (picked.length === 0) {
  // Distinguish "nothing to investigate" from "the extractor failed", because they need opposite
  // responses: the first is a correct skip, the second is a bug that must stay visible.
  const decl = (src.match(/\b(interface|@interface|enum|class|record)\s+\w/) || [])[1] || '';
  const hasBodies = /\)\s*(?:throws[\w.,\s]*)?\{\s*[^\s}]/.test(src);   // any `(...) { something }`
  const reason = !hasBodies
      ? ('no method bodies to investigate (' + (decl || 'declaration') + '-only file)')
      : ('method extraction found no substantive method despite ' + methods.length
         + ' parsed — EXTRACTOR GAP, not an empty file');
  return [{ json: { repo, file, class_name: className, skip: true, skipped_reason: reason,
                    extractor_gap: !!hasBodies, method: '(no methods)', line: 1 } }];
}
const seenName = Object.create(null);   // NOT {}: 'toString'/'constructor' would hit Object.prototype
return picked.map(m => {
  // overloads share a name: suffix the 2nd+ occurrence so each row/transcript keeps its identity
  const n = (seenName[m.name] = (seenName[m.name] || 0) + 1);
  const label = n === 1 ? m.name : m.name + '#' + n;
  return { json: {
    repo, file, class_name: className, skeleton: sk,
    method: label,          // unique per file -> safe as part of method_key / dedup_key
    method_name: m.name,    // the real Java name, used when talking to the model
    line: m.startLine,
    method_src: m.src.length > 30000 ? m.src.slice(0, 30000) : m.src,
  } };
});
"""

# --- the manual ReAct loop: LLM(+tools) -> run tool on java-runner -> feed back -> force final answer ---
REACT = r"""
const self = this;
const items = $input.all();
const branch = $('Suspect webhook').first().json.body.branch || 'main';

// Analyze ONE method in its OWN isolated context, so many methods can run CONCURRENTLY across the
// file (see the bounded-concurrency driver at the bottom).
async function analyze(j) {
const repo = j.repo, file = j.file, method = j.method, cls = j.class_name, line = j.line;
const _base = String(method).split('#')[0];
const _ov = String(method).indexOf('#') > 0 ? (' #' + String(method).split('#')[1]) : '';
// a constructor is not "Class.Class()" — name it as what it is, including which overload
const rootLabel = (_base === cls) ? (cls + ' constructor' + _ov) : (cls + '.' + method + '()');
if (j.skip) {   // entry points are METHODS: a file with none is recorded and skipped, not investigated
  const d = "▸ __VERSION__ SKIPPED " + cls + "\n\n" + (j.skipped_reason || '') + "\n";
  try {
    await self.helpers.httpRequest({ method:'POST', url: RUNNER + '/live/dialog',
      headers:{ 'Content-Type':'application/json', Connection:'close' },
      body: { method_key: repo + '|' + file + '|(no methods)', repo, file, class_name: cls,
              method: '(no methods)', dialog: d,
              status: j.extractor_gap ? 'error' : 'done', tool_calls: 0, findings: 0 },
      json:true, timeout:8000 });
  } catch(e) {}
  return { json: { method_key: repo + '|' + file + '|(no methods)', repo, file, class_name: cls,
                   method: '(no methods)', findings: 0, tool_calls: 0,
                   status: j.extractor_gap ? 'error' : 'skipped', dialog: d, candidates: [] } };
}
const rootSrc = j.method_src || '';
const RUNNER = 'http://fsm-java-runner:8090';
// NO fixed tool-call limit (operator: don't cap investigation). The loop runs until the model's
// context is nearly full, then forces the verdict — Qwen never stops calling tools on its own,
// so a context-budget terminator + forced final answer is the only bound. HARD_CAP is an
// absolute anti-infinite backstop, not an investigation limit.
const HARD_CAP = 200;
const PARENT_BUDGET = 320000;    // ~128k tok at a conservative 2.5 chars/tok; checked mid-turn, one overshoot max
const TRIM_CEIL = 420000;        // ~168k tok + 16k response reservation, comfortably inside the 262k window
const SUB_BUDGET = 300000;       // per sub-investigation (each level gets its own context)
const SUB_DEPTH_MAX = 3;         // entry + 3 delegated levels. The benchmark's chains are servlet ->
                                 // helper -> sink, so 3 covers them; raise freely, nothing else caps it.
const LLM_BUDGET = 1500;         // runaway backstop on TOTAL llm calls per method. Depth 33 with a
                                 // real call graph needs room; cycles/repeats are handled separately,
                                 // so this only ever catches genuine runaway.
const SYS = __SYS__;
const charsOf = ms => ms.reduce((n,m) => n + (JSON.stringify(m) || '').length, 0);
const trimToBudget = (msgs) => { for (let i = 2; i < msgs.length && charsOf(msgs) > TRIM_CEIL; i++) { if (msgs[i].role === 'tool' && (msgs[i].content || '').length > 200) msgs[i].content = '[trimmed to fit context]'; } };

const fsTools = [];   // no file browsing: movement happens along call edges, not paths
const navTools = [
  {type:'function',function:{name:'build_catastrophic_chain',description:'Hand a callee to a fresh investigator whose job is to BUILD A CATASTROPHIC CHAIN through it: the concrete input or sequence that makes this code, and everything it calls, produce a wrong or dangerous result. Code is static structure — the real defects live in edge cases and logic clashes ACROSS a call chain. It is NOT a file reader: you never get source back, you get findings (what it guarantees, how it can break you, the defects it verified there, filed automatically). Name the SYMBOL and carry over what you already suspect. There is no file or line parameter — you are walking a call graph, not browsing a codebase; the symbol is resolved from the code you are currently looking at, through the receiver type, the overload and inheritance, and polymorphic calls fan out to the concrete overrides that actually run. A dedicated investigator reads that callee in its OWN context with your call chain and reports what it guarantees, how it can break you, and any defects it verified (filed automatically, attributed to where they live).',parameters:{type:'object',properties:{symbol:{type:'string',description:'the symbol being called — a bare method / constructor / type name, e.g. "validateArguments", "ChunkSample", "List.copyOf"'},question:{type:'string',description:'why you are stepping in — what you need to know or suspect, e.g. "does it copy the list or store the caller\'s reference?"'}},required:['symbol','current_exploration_ideas']}}},
];
const readTools = fsTools.concat(navTools);     // read + navigate — available to the parent AND the sub-loops
const tools = readTools;    // one recursive investigator: build_catastrophic_chain, nothing else
// at the depth floor the tool stays offered (an EMPTY tools array is the configuration measured to
// return empty answers); digop itself refuses to recurse past SUB_DEPTH_MAX and returns raw source.
const leafTools = navTools;

// MEASURED against this endpoint (3-way A/B/C on a stack containing tool history):
//   tools + tool_choice:'none' -> finish_reason=stop, real content   <-- use this
//   NO tools at all            -> finish_reason=length, EMPTY content (the model answers in tool
//                                 markup because the history has tool_calls, and the parser drops it)
// So the tools array must STAY on the forced call; only tool_choice suppresses calling.
let lastFinish = '';
async function llm(messages, toolChoice, toolsOverride) {
  llmCalls++;
  // One generous ceiling, not a tight one. Of 22,238 measured replies, ~98.6% finished under 10k
  // GENERATION TOKENS and all but ~26 under 20k; only the top ~0.1% reach 20k-50k. 32k covers all but
  // that sliver, and a reply that DOES hit the cap is now recovered by parseVerdict's truncation repair,
  // so its complete defects still land. The point of the cap is the other failure: an unbounded call
  // let a degenerate loop generate to 108k tokens and exhaust the 262k context, stalling the pipeline.
  // A TIGHT cap is the real hazard — thinking tokens count against it, so 2000 left almost nothing for
  // content and severed verdicts mid-JSON.
  const body = { model: $env.QWEN_MODEL, messages, tools: toolsOverride || tools, temperature: 0.2,
                 max_tokens: 32000 };
  if (toolChoice) body.tool_choice = toolChoice;
  const r = await self.helpers.httpRequest({ method:'POST', url: $env.QWEN_BASE_URL + '/chat/completions',
    headers:{ Authorization:'Bearer '+$env.QWEN_API_KEY, 'Content-Type':'application/json', Connection:'close' },
    body, json:true, timeout:3600000 });
  const ch = (r.choices && r.choices[0]) || {};
  lastFinish = (ch.finish_reason || '') + '';
  return ch.message || {};
}
// Last resort when the verdict comes back empty: replay the SAME knowledge with the tool-call
// STRUCTURE flattened into plain text. With no tool_calls/tool roles in the history the model has
// nothing to imitate, so it answers in prose/JSON instead of markup.
function flattenToolHistory(msgs) {
  return msgs.map(m => {
    if (m.role === 'tool') return { role: 'user', content: '[tool result] ' + String(m.content || '').slice(0, 6000) };
    if (m.role === 'assistant' && m.tool_calls) {
      return { role: 'assistant', content: (m.content || '') + '\n[investigated: '
               + m.tool_calls.map(t => t.function && t.function.name).join(', ') + ']' };
    }
    return m;
  });
}
// n8n's httpRequest rejects with an OBJECT; String(e) on it is the useless '[object Object]' that hid a
// context-overflow for a whole run. Always pull the fields that actually say what happened.
// The model routinely ends a long verdict one brace short. A strict JSON.parse then returns nothing
// and everything the investigator found is silently discarded. Repair the truncation instead.
function parseVerdict(text) {
  const t = String(text || '');
  if (!t.trim()) return { obj: null, repaired: false };

  const tryParse = (raw) => { try { return JSON.parse(raw); } catch (e) { return null; } };
  const repair = (body) => {   // the model routinely ends a long verdict short of its closing braces
    // Close what is actually OPEN, innermost first. Counting braces and brackets separately and
    // emitting all ']' then all '}' produces "]}}" where a truncated defect list needs "}]}" — and a
    // truncated defect list ALWAYS has an object open inside an array, so a flat close never parses.
    const stack = []; let inS = false, esc = false;
    const marks = [];          // every position where a complete element closes, with the stack after it
    for (let i = 0; i < body.length; i++) {
      const ch = body[i];
      if (esc) { esc = false; continue; }
      if (ch === '\\') { esc = true; continue; }
      if (ch === '"') { inS = !inS; continue; }
      if (inS) continue;
      if (ch === '{' || ch === '[') stack.push(ch);
      else if (ch === '}' || ch === ']') { stack.pop(); marks.push({ i: i, s: stack.slice() }); }
    }
    const close = (text, st, needQuote) => {
      // strip a dangling ODD backslash run first: otherwise the injected closing '"' is escaped by it
      // and the string never closes, discarding a defect whose title was already complete.
      let out = (needQuote ? text.replace(/\\+$/, m => m.length % 2 ? m.slice(1) : m) + '"' : text);
      out = out.replace(/[\s,]+$/, '');        // a dangling comma cannot be closed
      if (/:\s*$/.test(out)) out += 'null';    // cut right after a key
      for (let k = st.length - 1; k >= 0; k--) out += (st[k] === '{' ? '}' : ']');
      return tryParse(out);
    };
    const whole = close(body, stack, inS);     // keeps the partial last element, text and all
    if (whole) return whole;
    // otherwise drop the half-written element and close after the last COMPLETE one
    for (let m = marks.length - 1, tried = 0; m >= 0 && tried < 400; m--, tried++) {
      const r = close(body.slice(0, marks[m].i + 1), marks[m].s, false);
      if (r) return r;
    }
    return null;
  };
  const usable = (o) => o && typeof o === 'object'
                        && (Array.isArray(o.defects) || Array.isArray(o.suspicions));

  // 1) a fenced ```json block wins: the model often writes prose first and fences the answer
  const fences = [...t.matchAll(/```(?:json)?\s*([\s\S]*?)```/gi)].map(m => m[1]);
  for (let i = fences.length - 1; i >= 0; i--) {
    const o = tryParse(fences[i].trim()) || repair(fences[i].trim());
    if (usable(o)) return { obj: o, repaired: !tryParse(fences[i].trim()) };
  }
  const starts = [];
  for (let i = 0; i < t.length; i++) if (t[i] === '{') starts.push(i);
  const tryAt = (p) => {
    const body = t.slice(p);
    const last = body.lastIndexOf('}');
    if (last > 0) { const o = tryParse(body.slice(0, last + 1)); if (usable(o)) return { obj: o, repaired: false }; }
    const rep = repair(body);
    return usable(rep) ? { obj: rep, repaired: true } : null;
  };
  // 2) anchor on the answer's own OUTER brace: it is the '{' immediately followed by a top-level key.
  //    That brace precedes every brace it contains, so on a defect-rich unfenced reply it can sit
  //    outside any fixed positional window — but a key-anchored scan always finds it, wherever it is.
  for (const p of starts) {
    if (/^\s*"(summary|defects|suspicions)"\s*:/.test(t.slice(p + 1))) { const r = tryAt(p); if (r) return r; }
  }
  // 3) fall back to positional '{' from BOTH ends. Starting at the FIRST '{' can land inside quoted
  //    Java in the prose ("... ) { // start"), which never parses; the END is tried first for that
  //    reason, then the FRONT, so a preamble-heavy reply is still reached.
  const order = [];
  for (let k = starts.length - 1; k >= 0 && starts.length - k <= 40; k--) order.push(starts[k]);
  for (let k = 0; k < starts.length && k < 40; k++) if (order.indexOf(starts[k]) < 0) order.push(starts[k]);
  for (const p of order) { const r = tryAt(p); if (r) return r; }
  return { obj: null, repaired: false };
}

function errText(e) {
  if (!e) return 'unknown';
  const c = [e.message, e.description, e.error && e.error.message, e.reason,
             (e.httpCode || e.statusCode) ? ('http ' + (e.httpCode || e.statusCode)) : ''].filter(Boolean).join(' | ');
  if (c) return c.slice(0, 300);
  try { return JSON.stringify(e).slice(0, 300); } catch (x) { return String(e).slice(0, 300); }
}
async function fsop(name, argstr) {
  let a = {}; try { a = JSON.parse(argstr || '{}'); } catch(e) {}
  // method-by-method: do NOT let the model slurp the whole current class (it satisfices after 1-2
  // findings and mis-attributes sibling-method bugs). It already has THIS method + the skeleton.
  // String() so a non-string path never throws out of the loop; normalize both sides + basename.
  const _norm = s => String(s == null ? '' : s).replace(/^\.?\/+/, '');
  const _req = _norm(a.path);
  if (name === 'read_file' && (_req === _norm(file) || (_req.indexOf('/') < 0 && _req === file.split('/').pop()))) {
    // terse: the method body and class skeleton are already in the prompt, so re-dumping them only
    // bloats the transcript. Redirect to OTHER files.
    return JSON.stringify({ note: "This is the file you are already analyzing — " + method + "()'s body and the class skeleton are ALREADY in your prompt above, so re-reading it returns nothing new. To go deeper, delegate the symbols this method calls." });
  }
  try {
    const r = await self.helpers.httpRequest({ method:'POST', url: RUNNER + '/fs/' + name,
      headers:{ 'Content-Type':'application/json', Connection:'close' },
      body: { repo, branch, ...a }, json:true, timeout:3600000 });
    return JSON.stringify(r).slice(0, 90000);   // full file to the model (vLLM ctx=256k, files up to ~78k)
  } catch(e) { return JSON.stringify({ error: String(e).slice(0,200) }); }
}
// Stepping into a callee is a SUBAGENT BOUNDARY, not a source dump. The definition is resolved
// deterministically and handed to a sub-investigator with its OWN context, which keeps the callee's
// tokens off the parent and gives the callee a reader of its own. It returns a caller-facing contract
// plus any defects it verified — attributed to where they actually live.
const allDefects = [];        // EVERY defect found anywhere in this call tree, at any depth

const learned = [];           // contracts established by each dig, in order — the exploration's memory
const notes = [];             // the caller's own reasoning as it goes, so a dig knows the hypothesis
// A dig that only knows the symbol names above it re-derives what the tree already established (and
// can contradict it silently). Hand every sub-investigator the accumulated picture: what has been
// learned, what has already been filed, and what the caller currently believes.
function contextSoFar(limit) {
  const out = [];
  if (learned.length) {
    out.push('ALREADY ESTABLISHED by this exploration (do NOT re-derive):');
    for (const l of learned.slice(-8)) {
      out.push('  · ' + l.symbol + ' — ' + String(l.contract || '').slice(0, 240)
               + (l.risks && !/^none/i.test(l.risks) ? ('  [risk to caller: ' + String(l.risks).slice(0, 160) + ']') : '')
               + (l.defects ? ('  [' + l.defects + ' defect(s) filed]') : ''));
    }
  }
  if (allDefects.length) {
    out.push('DEFECTS ALREADY FILED down this tree (do not repeat them):');
    for (const d of allDefects.slice(-8)) {
      out.push('  · [' + (d.severity || '?') + '] ' + (d.method || '?') + '() — ' + String(d.title || '').slice(0, 140));
    }
  }
  if (notes.length) {
    out.push("CALLER'S CURRENT REASONING:");
    out.push('  ' + String(notes[notes.length - 1]).slice(0, 900).replace(/\n+/g, ' '));
  }
  const t = out.join('\n');
  return t.length > (limit || 3000) ? t.slice(0, limit || 3000) + '\n  …(trimmed)' : t;
}
// nav returns absolute cache paths; the model only ever sees repo-relative ones
const relOf = fp => { const w = String(fp || '').split('/cache/fs/')[1] || String(fp || '');
                      return w.indexOf('/') >= 0 ? w.slice(w.indexOf('/') + 1) : w; };
const stripMarkup = t => String(t || '')
  .replace(/<tool_call>[\s\S]*?<\/tool_call>/g, '')   // paired
  .replace(/<\/?tool_call>/g, '')                       // dangling open/close (seen leaking into verdicts)
  .replace(/<\/?function[^>]*>/g, '')
  .replace(/<\|[^|]*\|>/g, '')
  .trim();
// ONE investigator, called recursively. depth 0 IS the entry-point method — it is not a different kind
// of agent, just the first symbol dug into on the way down from "find bugs in this repo".
async function investigate(target, depth, chain, question) {
  const src = (target.src || '').slice(0, 40000);
  const relFile = target.relFile || '';
  const me = target.label || relFile || 'callee';
  const isEntry = !!target.isEntry;
  const path = (chain || [rootLabel]).concat([me]);
  // A sub-investigator without the caller's context wanders off touring unrelated types. It is
  // exploring a CALL TREE on behalf of a specific entry point, so it gets the chain it was reached
  // through, the depth it is at, and the question that started it all.
  // Narrative order matters: it reads WHY the investigation exists, then everything its caller has
  // worked out, and only then what it is being asked to do — the callee source comes last, so it
  // knows what question it is answering before it starts reading code.
  const caller = path[path.length - 2] || rootLabel;
  const ctx = contextSoFar();
  let dmsgs = [{ role:'system', content: __INVESTIGATOR_SYS__ },
               { role:'user', content:
                   "=== THE INITIAL TASK ===\n"
                 + "Explore the execution paths of " + repo + " and build CATASTROPHIC CHAINS: paths that "
                 + "behave not as expected and lead to bugs, malfunctions, or security vulnerabilities "
                 + "under attack.\n"
                 + "That exploration runs one symbol at a time; this branch of it started at "
                 + rootLabel + " (" + file + "):\n"
                 + "```java\n" + rootSrc.slice(0, 4000) + "\n```\n\n"
                 + "=== THE INVESTIGATION SO FAR ===\n"
                 + "Call chain (you are at level " + (depth + 1) + " of " + (SUB_DEPTH_MAX + 1) + "):\n  "
                 + path.join("\n    ↓ ") + "\n"
                 + (ctx ? ("\n" + ctx + "\n") : "\n(nothing established yet — you are the first step into the tree)\n")
                 + (target.revisit ? "\nNOTE: this symbol already appears on the chain above — you are "
                     + "inside a recursion. That is allowed: reason about the recursion itself (what input "
                     + "makes it not terminate, or overflow?) as well as the code.\n" : "")
                 + "\n=== YOUR TASK ===\n"
                 + (isEntry
                    ? ("`" + me + "` is where this branch of the exploration starts. Trace the execution paths "
                       + "that run through it — including the ones a caller can steer — and follow them out "
                       + "as far as the chain goes.\n"
                       + (target.skeleton ? ("\nThe full file you are analysing:\n```java\n"
                                             + target.skeleton + "\n```\n") : ""))
                    : ("BUILD A CATASTROPHIC CHAIN through `" + me + "`, reached from " + caller + ".\n"
                       + "Do not merely describe this code. Find the concrete input or sequence — boundary "
                       + "values, empty/negative/overflowing quantities, contradictory assumptions between "
                       + "this layer and its callers — that makes the chain produce a wrong or dangerous "
                       + "result.\n"
                       + (question ? ("The caller carried over: " + question + "\n") : "")))
                 + "\nIts definition is " + relFile
                 + " (lines " + (target.beginLine || '?') + "-" + (target.endLine || '?') + "):\n"
                 + "```java\n" + src + "\n```\n\n"
                 + "Answer the caller's question and hunt for bugs HERE. Stay on this call chain — do "
                 + "not survey unrelated classes. "
                 + (depth >= SUB_DEPTH_MAX
                    ? "You are at the maximum depth: answer from what you can read here."
                    : "Delegate deeper (build_catastrophic_chain) only when the answer genuinely depends on what THIS calls.") }];
  // the prompt is the artifact worth reading: for a dig it shows exactly which context was inherited
  dialog += (isEntry ? "\nPROMPT:\n" : ("\n· d" + (depth + 1) + " PROMPT (context handed to this dig):\n"))
          + dmsgs[1].content.slice(0, isEntry ? 44000 : 9000) + "\n";
  const subTools = depth >= SUB_DEPTH_MAX ? leafTools : (isEntry ? tools : readTools);
  let dtext = '';
  for (let k = 0; k < HARD_CAP && llmCalls < LLM_BUDGET; k++) {
    if (llmCalls >= LLM_BUDGET - 1) { dialog += "\n[LLM budget for this method exhausted — stopping early]\n"; break; }
    if (charsOf(dmsgs) > (isEntry ? PARENT_BUDGET : SUB_BUDGET)) { dialog += "\n[context budget reached — forcing verdict]\n"; break; }
    const m = await llm(dmsgs, null, subTools);
    if (!m.tool_calls || !m.tool_calls.length) { if (m.content && m.content.trim()) dtext = m.content; break; }
    m.tool_calls.forEach((t, ti) => { if (!t.id) t.id = 'k' + depth + '_' + k + '_' + ti; });
    if (m.content && m.content.trim()) notes.push(m.content.trim());
    dmsgs.push({ role:'assistant', content: m.content || '', tool_calls: m.tool_calls });
    for (const tc of m.tool_calls) {
      toolCalls++;
      const r2 = await toolop(tc.function.name, tc.function.arguments, depth, path, relFile || file);
      dmsgs.push({ role:'tool', tool_call_id: tc.id, content: r2 });
      // record call_depth in the call itself: the transcript should state the level, not imply it
      let argsLog = (tc.function.arguments || '');
      try {
        const pa = JSON.parse(argsLog || '{}');
        pa.call_depth = depth + 1;
        argsLog = JSON.stringify(pa);
      } catch (e) { argsLog = argsLog + ' [call_depth=' + (depth + 1) + ']'; }
      dialog += "· d" + (depth + 1) + " " + tc.function.name
              + "(" + argsLog.slice(0, 400) + ")\n"
              + "<<< " + String(r2 || '').slice(0, 6000) + "\n";   // the node's body -> expandable
    }
    await live('analyzing', 0);
  }
  const ask = async (msgs, label, noTools) => {
    try {
      const fm = await llm(msgs, 'none', noTools ? [] : subTools);
      const t = (fm.content || fm.reasoning_content || '') + '';
      dialog += "· d" + (depth + 1) + " verdict:" + label + " (" + t.length + "c)\n";
      return t;
    } catch (e) {
      dialog += "· d" + (depth + 1) + " verdict:" + label + " FAILED " + errText(e) + "\n";
      return '';
    }
  };
  const done = t => (t || '').indexOf('"defects"') >= 0;
  if (!done(dtext)) {
    dmsgs.push({ role:'user', content:'Now output ONLY the JSON object described in your instructions. Do NOT call tools.' });
    const t = await ask(dmsgs, 'forced');
    if (done(t) || !dtext) dtext = t;
  }
  if (!done(dtext)) {                               // answered in tool markup -> drop the structure
    const flat = flattenToolHistory(dmsgs);
    flat.push({ role:'user', content:'Output ONLY the JSON object {"summary":"..","caller_risks":"..","defects":[...]}. No tags, no tool calls, no prose.' });
    const t = await ask(flat, 'flattened');
    if (done(t)) dtext = t;
  }
  if (!done(dtext)) {                               // last resort: minimal, cannot overflow
    const t = await ask([dmsgs[0], dmsgs[1],
      { role:'user', content:'Output ONLY the JSON object {"summary":"..","caller_risks":"..","defects":[...]} for the code above.' }], 'minimal', true);
    if (done(t)) dtext = t;
  }
  const _shown = stripMarkup(dtext);
  dialog += (isEntry ? "\n\n=== VERDICT ===\n" : ("\n· d" + (depth + 1) + " VERDICT\n"))
          + (_shown ? (_shown.length > 40000 ? _shown.slice(0, 40000) + "\n…(verdict truncated)" : _shown)
             : (dtext ? ("[the model answered in tool-call markup only — raw reply follows]\n"
                         + dtext.slice(0, 4000))
                      : "[empty reply]")) + "\n";
  const _pj = parseVerdict(dtext);
  const parsed = (_pj.obj && typeof _pj.obj === 'object') ? _pj.obj : {};
  if (_pj.repaired) dialog += "· d" + (depth + 1) + " [verdict JSON truncated — repaired]\n";
  else if (!_pj.obj && String(dtext).trim()) dialog += "· d" + (depth + 1)
       + " [verdict JSON unparseable even after repair — findings lost]\n";
  const defects = Array.isArray(parsed.defects) ? parsed.defects : [];
  for (const d of defects) {                        // one collector for the whole tree, at every depth
    if (d && d.title) allDefects.push({ ...d, file: (d.file || relFile), method: (d.method || me || ''),
                                        found_at_depth: depth, found_via: path.join(' -> ') });
  }
  learned.push({ symbol: me, contract: (parsed.summary || parsed.contract || '') + '',
                 risks: (parsed.caller_risks || '') + '', defects: defects.length });
  return {
    signature: me, file: relFile,
    lines: (target.beginLine || 0) + '-' + (target.endLine || 0),
    verdict_text: dtext,
    // strip any tool-call markup: leaking it into the CALLER's context both wastes tokens and teaches
    // the caller to answer in markup too.
    summary: (parsed.summary || parsed.contract
              || stripMarkup(dtext).slice(0, 600)
              || '(investigator did not return a usable answer)') + '',
    caller_risks: (parsed.caller_risks || '') + '',
    defects_found: defects.length,
  };
}

async function digop(argstr, depth, chain, fromFile) {   // resolve the symbol, then delegate
  // declared FIRST: everything below reads them, and a `const` read above its declaration is a
  // TemporalDeadZone throw that no-undef cannot see (it cost 78% of methods once already).
  const d = depth || 0;                 // the depth of the investigator MAKING this call
  const childDepth = d + 1;             // the depth of the investigator this call would create
  let a = {}; try { a = JSON.parse(argstr || '{}'); } catch(e) {}
  // The model naturally writes a full signature when disambiguating overloads
  // ("ChunkSample(String[], String[], String[])"), but the resolver binds by NAME. Normalise instead of
  // dead-ending: strip the parameter list, keep its arity as a hint, and fall back to the last
  // dot-segment for a qualified call ("List.copyOf" -> "copyOf").
  const rawSym = (a.symbol || '').toString().trim();
  const paren = rawSym.indexOf('(');
  const inner = paren > 0 ? rawSym.slice(paren + 1, rawSym.lastIndexOf(')')).trim() : '';
  // the parameter list is DISAMBIGUATION, not noise: an overload is a different edge of the call graph
  const arity = paren > 0 ? (inner ? inner.split(',').length : 0) : null;
  const bare = (paren > 0 ? rawSym.slice(0, paren) : rawSym).trim();
  if (/\s/.test(bare)) {   // "LinkedSpan extends BaseLink" is a sentence, not a symbol
    return JSON.stringify({ call_depth: (depth || 0) + 1, dug_into: rawSym, found: false,
      note: "'" + rawSym + "' is a phrase, not a symbol. Name one callee, fully qualified: "
          + "package.Class.member(ParamTypes) — e.g. opennlp.tools.entitylinker.LinkedSpan.equals(Object). "
          + "To ask which subclasses override a method, name the ABSTRACT member and its overrides will "
          + "be investigated for you." });
  }
  // a fully-qualified name splits into the owner (package/type) and the member actually called
  const dot = bare.lastIndexOf('.');
  const qualifier = dot > 0 ? bare.slice(0, dot) : '';
  const symbol = dot > 0 ? bare.slice(dot + 1) : bare;
  const question = (a.current_exploration_ideas || a.question || '').toString().trim();
  const callDepth = childDepth;
  if (!symbol) {
    return JSON.stringify({ call_depth: callDepth, error: 'symbol is required',
      note: 'Name the symbol to build the chain through. This delegates an investigation '
          + 'of that symbol — it is not a way to fetch a file or read source.' });
  }
  if (/^\s*(read|show|see|open|display|print|fetch|get)\b|i need to see|can you read/i.test(question)) {
    return JSON.stringify({ call_depth: callDepth, dug_into: rawSym, refused: true,
      note: 'That is a RETRIEVAL request, and this tool returns no source. Re-ask as the question that '
          + 'must be settled about `' + symbol + '` — the behaviour to confirm or refute (e.g. "does it '
          + 'validate its arguments before use?"). A fresh investigator will read it and report findings.' });
  }
  const inFile = (fromFile || file) + '';   // resolve in the code this investigator is reading

  const nav = async (op, body) => {
    try {
      return await self.helpers.httpRequest({ method:'POST', url: RUNNER + '/nav/' + op,
        headers:{ 'Content-Type':'application/json', Connection:'close' },
        body: { repo, branch, ...body }, json:true, timeout:3600000 });
    } catch(e) { return { error: errText(e) }; }
  };
  let r = await nav('definition', { file: inFile, symbol, qualifier, params: inner,
                                    arity: arity === null ? undefined : arity });
  // an owner like `List.copyOf` may name the TYPE rather than a package — retry binding on the owner
  if ((!r || !r.found) && !r.ambiguous && qualifier) {
    r = await nav('definition', { file: inFile, symbol: qualifier.split('.').pop(), arity: undefined });
  }

  // an interface / abstract declaration is not what RUNS — fan out to the concrete overrides
  const abstractish = r && r.found && !r.external && r.source
                      && /(\binterface\b|\babstract\b)/.test(String(r.source).slice(0, 400))
                      && !/\{[\s\S]*[^\s{}][\s\S]*\}/.test(String(r.source));
  if (!r || !r.found || abstractish) {
    const impl = await nav('implementations', { type: symbol, method: a.method || '' });
    const impls = (impl && Array.isArray(impl.implementations)) ? impl.implementations : [];
    if (impls.length) {
      if (childDepth > SUB_DEPTH_MAX) return JSON.stringify(impl).slice(0, 40000);
      const picked = impls.slice(0, 3);
      const reports = [];
      for (const im of picked) {
        if (!im || !im.source) { reports.push(im); continue; }
        reports.push(await investigate({ label: im.signature || '', relFile: relOf(im.defFile),
          src: im.source || '', beginLine: im.defBeginLine, endLine: im.defEndLine }, childDepth, chain, question));
      }
      return JSON.stringify({ dug_into: symbol, polymorphic: true, implementations_found: impls.length,
        investigated: reports.length, call_depth: callDepth, depth: childDepth,
        note: impls.length > picked.length ? ('only the first ' + picked.length + ' overrides were dug into') : '',
        reports }).slice(0, 40000);
    }
  }
  if (r && r.ambiguous) {
    return JSON.stringify({ call_depth: callDepth, dug_into: rawSym, ambiguous: true, candidates: r.candidates || [],
      note: (r.note || 'several declarations match')
          + ' The `candidates` below are FULLY QUALIFIED — copy the one you mean verbatim into `symbol`.' });
  }
  if ((!r || !r.found) && inFile !== file) {          // fall back to the entry file
    r = await nav('definition', { file, symbol, qualifier, params: inner,
                                  arity: arity === null ? undefined : arity });
  }
  if (!r || !r.found) {
    return JSON.stringify({ call_depth: callDepth, dug_into: rawSym, found: false, resolved_as: symbol,
      note: "could not bind '" + rawSym + "' from " + inFile + ". The `candidates` below are FULLY "
          + "QUALIFIED — copy one verbatim into `symbol` and call again. If none fit, name the callee as "
          + "package.Class.member(ParamTypes).",
      candidates: (r && r.candidates) || [] }).slice(0, 8000);
  }
  if (r.external || !r.source) return JSON.stringify(r).slice(0, 20000);
  if (childDepth > SUB_DEPTH_MAX) return JSON.stringify(r).slice(0, 40000);   // floor: raw source, no deeper
  // Same-file symbols are delegated exactly like cross-file ones: a fresh investigator on a sibling
  // member is worth its own context even when the caller can already see the source.
  const inSameFile = String(r.defFile || '').endsWith(String(file || '').split('/').slice(-2).join('/'));
  if (inSameFile) {
    const src0 = String(r.source || '');
    const isType = /^\s*(?:public\s+|final\s+|abstract\s+)*(?:class|interface|enum|record)\s/.test(src0);
    const isAbstract = /\b(abstract\s+class|interface)\b/.test(src0.slice(0, 200));
    if (isType && isAbstract && childDepth <= SUB_DEPTH_MAX) {
      // "which subclass actually runs?" — the real question when the entry point is abstract
      const impl = await nav('implementations', { type: symbol, method: a.method || '' });
      const impls = (impl && Array.isArray(impl.implementations)) ? impl.implementations : [];
      if (impls.length) {
        const picked = impls.slice(0, 3);
        const reports = [];
        for (const im of picked) {
          if (!im || !im.source) { reports.push(im); continue; }
          reports.push(await investigate({ label: im.signature || '', relFile: relOf(im.defFile),
            src: im.source || '', beginLine: im.defBeginLine, endLine: im.defEndLine }, childDepth, chain, question));
        }
        return JSON.stringify({ dug_into: symbol, polymorphic: true, implementations_found: impls.length,
          investigated: reports.length, call_depth: callDepth, depth: childDepth,
          note: 'abstract/interface in your own file — investigated the concrete subclasses that run'
              + (impls.length > picked.length ? (' (first ' + picked.length + ' of ' + impls.length + ')') : ''),
          reports }).slice(0, 40000);
      }
    }
    // otherwise: fall through and delegate it like anything else
  }
  // revisiting a symbol already on the chain is allowed; the child is simply told it is in a recursion
  const onChain = (chain || []).some(c => String(c).indexOf(r.signature || symbol) >= 0);
  const rep = await investigate({ label: r.signature || symbol, relFile: relOf(r.defFile),
    src: r.source || '', beginLine: r.defBeginLine, endLine: r.defEndLine,
    revisit: onChain }, childDepth, chain, question);
  return JSON.stringify({ stepped_into: rep.signature, file: rep.file, lines: rep.lines,
                          question, call_depth: callDepth, depth: childDepth,
                          chain: (chain || [rootLabel]).concat([rep.signature]),
                          investigated_by: 'sub-agent (own context)',
                          contract: rep.summary || rep.contract, caller_risks: rep.caller_risks,
                          defects_reported: rep.defects_found });
}

// No repetition limiting. The model may ask for the same symbol as often as it likes — a repeat is
// often a genuinely different question, and suppressing it costs recall. Termination is bounded by
// SUB_DEPTH_MAX alone.
const KNOWN_TOOLS = new Set(['build_catastrophic_chain']);
async function toolop(name, argstr, depth, chain, fromFile) {   // a recursive dig (the only tool)
  if (!KNOWN_TOOLS.has(name)) {
    // the model sometimes "calls" a tool named after its own output schema ("summary") instead of
    // writing the answer. Say so plainly rather than 404ing into a wasted turn.
    return JSON.stringify({ error: 'There is no tool called `' + name + '`.',
      available: [...KNOWN_TOOLS],
      note: 'If you are ready to answer, write the JSON object as your MESSAGE CONTENT — it is not a '
          + 'tool call. Only ' + [...KNOWN_TOOLS].join(', ') + ' are callable.' });
  }
  // no caching, no repeat suppression: the same symbol may be asked for as often as the investigator
  // wants, and it is served fresh every time.
  return (name === 'build_catastrophic_chain')
    ? await digop(argstr, depth || 0, chain || [rootLabel], fromFile) : await fsop(name, argstr);
}
// stream the in-flight transcript to the java-runner live sink so the dashboard shows it live
async function live(status, findings, cands) {
  try {
    await self.helpers.httpRequest({ method:'POST', url: RUNNER + '/live/dialog',
      headers:{ 'Content-Type':'application/json', Connection:'close' },
      body: { method_key: repo + '|' + file + '|' + method, repo, file, class_name: cls, method,
              dialog, status, tool_calls: toolCalls, findings: findings || 0,
              // the findings themselves, so they are visible the moment the METHOD finishes rather
              // than when its whole batch does
              candidates: (cands || []).map(x => ({ severity: x.severity, category: x.category,
                            file: x.file, method: x.method, title: x.title, line: x.line })) },
      json:true, timeout:8000 });
  } catch(e) {}
}
// SUBAGENT: an isolated ReAct sub-loop over the fs tools that answers ONE behavioural question about
// OTHER files and returns a distilled paragraph — the raw dependency files never enter the PARENT's
// context. Depth 1 (its tools[] omits delegate). Its sub-tool calls are logged compactly (not the
// blobs) so the parent transcript stays readable.

let dialog = "▸ __VERSION__ ANALYZING " + cls + "." + method + "()\n\n";
let toolCalls = 0, llmCalls = 0;
await live('analyzing', 0);

// The entry point is NOT a special kind of agent: it is depth 0 of the same recursion that handles
// every callee. "Find bugs in this repo" -> this method -> its callees -> theirs.
let finalText = '';
let entry = null;
try {
  entry = await investigate({ label: rootLabel, relFile: file, src: rootSrc,
                              beginLine: line, endLine: 0, isEntry: true, skeleton: j.skeleton || '' },
                            0, [repo], '');
  finalText = (entry && entry.verdict_text) || '';
} catch(e) { dialog += "\n[investigation error: " + errText(e) + "]"; }

const norm = s => (s||'').toString().toLowerCase().replace(/[^a-z0-9]+/g,'_').replace(/^_|_$/g,'');
const candidates = [];
const seenKey = new Set();
// every defect the tree found, wherever it was found, filed where it actually lives
for (const d of allDefects) {
  if (!d || !d.title) continue;
  const sfile = String(d.file || '').replace(/^\.?\/+/, '') || file;
  const smeth = String(d.method || '').trim() || method;
  const own = (sfile === file);
  const key = [repo, sfile, norm(smeth), norm(d.category), norm(d.title).slice(0, 40)].join('|');
  if (seenKey.has(key)) continue;
  seenKey.add(key);
  candidates.push({
    dedup_key: key, repo, file: sfile,
    class_name: own ? cls : (sfile.split('/').pop() || '').replace('.java', ''),
    method: smeth, line: Number(d.line) || (own ? line : 0) || 0,
    category: (d.category || 'correctness') + '', severity: (d.severity || 'medium') + '',
    title: (d.title || '') + '', description: (d.description || '') + '',
    evidence: (d.evidence || '') + '', status: 'new',
    note: (d.found_at_depth ? ('found at depth ' + d.found_at_depth + ' via ' + (d.found_via || '')) : ''),
    version: __SUSVER__, branch,
    method_key: repo + '|' + file + '|' + method,
  });
}
try { await live(finalText ? 'done' : 'error', candidates.length, candidates); } catch(e) {}   // finalize the live entry
// ONE item per method: carries the dialog (for method_runs) + its candidates (for dedup+upsert)
return { json: {
  method_key: repo + '|' + file + '|' + method,
  repo, file, class_name: cls, method,
  findings: candidates.length, tool_calls: toolCalls,
  // status must match what the live sink reports: no verdict text == the investigation did not
  // conclude. Recording 'done' here made an outage indistinguishable from a clean method.
  status: finalText ? 'done' : 'error',
  // keep head + tail so the VERDICT (at the end) survives the cap on read-heavy methods
  dialog: (dialog.length > 200000 ? dialog.slice(0, 130000) + "\n...(tool output truncated)...\n" + dialog.slice(-60000) : dialog),
  candidates,
}};
}   // ---- end analyze(j) ----

// DRIVER: run the file's methods with bounded concurrency.
// One flaky method must not sink the batch, so use allSettled + a per-method error fallback item.
const CONC = 5;
const out = [];
for (let bi = 0; bi < items.length; bi += CONC) {
  const batch = items.slice(bi, bi + CONC);
  const settled = await Promise.allSettled(batch.map(it => analyze(it.json)));
  settled.forEach((s, idx) => {
    if (s.status === 'fulfilled') { out.push(s.value); return; }
    const jj = batch[idx].json || {};
    out.push({ json: { method_key: jj.repo + '|' + jj.file + '|' + jj.method, repo: jj.repo, file: jj.file,
      class_name: jj.class_name, method: jj.method, findings: 0, tool_calls: 0, status: 'error',
      dialog: '[analyze threw: ' + String(s.reason).slice(0, 200) + ']', candidates: [] } });
  });
}
return out;
"""
REACT = (REACT.replace("__SYS__", json.dumps(SYS))
         .replace("__INVESTIGATOR_SYS__", json.dumps(INVESTIGATOR_SYS))
         .replace("__VERSION__", stamp(SUSPECTOR_VERSION))
         .replace("__SUSVER__", json.dumps(SUSPECTOR_VERSION)))

# --- CLASS AUDITOR: one forced pass over the WHOLE class + the per-method leaf findings. Prioritises
# --- cross-member bugs (the blind spot method-by-method can't see) but also catches single-method bugs
# --- the isolated pass missed. Skips only what's already recorded. Toolless => can't loop.
CLASS_AUDIT_SYS = (
    "You are a Java bug hunter doing a WHOLE-CLASS audit — a second, holistic pass with the ENTIRE class "
    "in view. The per-method pass analyzed each method in isolation and MISSED things (some methods "
    "crashed, satisficed, or had less context than you do). You are given the full class source and the "
    "findings that pass ALREADY recorded.\n\n"
    "DIG for every genuine, user-reachable bug and security weakness in this class that is NOT already in "
    "the recorded list — attack it, do not skim. Your "
    "UNIQUE strength is cross-member defects that no single-method view could catch, so hunt those "
    "hardest: equals/hashCode/compareTo consistency (same fields; hashCode consistent with equals; if the "
    "class `extends` another, whether equals/hashCode must but do not incorporate super); an invariant a "
    "setter/mutator maintains that another method assumes (nullability, bounds, ordering); a resource "
    "opened in one method that must be closed in another (leak); initialization / state-machine ordering "
    "(a method used before its prerequisite, a field read before it is set); shared mutable state guarded "
    "inconsistently across methods; and cross-member SECURITY flaws — e.g. a validation/sanitizer/authz "
    "check one method enforces but another path bypasses, or untrusted input stored by one method and used "
    "in a dangerous sink (injection / path traversal / deserialization / reflection) by another. But ALSO "
    "report any SINGLE-method bug or security flaw the per-method pass missed — with the whole class in "
    "front of you, you have more context than it did. Only skip a finding if it is already in the recorded "
    "list. Ignore style, naming, formatting.\n\n"
    "Your final answer must be ONLY this JSON, no prose:\n"
    '{\"suspicions\":[{\"method\":\"<the member(s) involved, e.g. equals+hashCode or getPreds>\",\"line\":<int>,'
    '\"category\":\"contract-violation|correctness|resource-leak|data-corruption|injection|path-traversal|deserialization|crypto|secrets|security\",'
    '\"severity\":\"high|medium|low\",\"title\":\"..\",\"description\":\"..\",'
    '\"evidence\":\"<concrete input -> wrong behaviour>\"}]}\n'
    "If there is no genuine bug beyond what was already recorded, answer {\"suspicions\":[]}."
)

CLASS_AUDIT = r"""
const items = $input.all();
// identity must NOT come from items[0]: if `ReAct investigate` fails wholesale it emits a bare
// {error:...} item (onError=continueRegularOutput) with no repo/file, and every row this node writes
// then lands as NULL. The webhook body is the authoritative identity; items only refine it.
const wh = (() => { try { return $('Suspect webhook').first().json.body || {}; } catch (e) { return {}; } })();
const first = (items.find(i => i && i.json && i.json.repo && i.json.file) || {}).json || {};
const repo = first.repo || wh.repo;
const file = first.file || wh.file;
const branch = (wh.branch || '') + '';   // recorded on each finding so the prover proves the same branch
const cls = first.class_name || (file || '').split('/').pop().replace('.java','');
const RUNNER = 'http://fsm-java-runner:8090';
const self = this;
const SYS = __CSYS__;
let fullClass = '';
try { fullClass = Buffer.from(($('Fetch file').first().json.content || '').replace(/\s/g,''), 'base64').toString('utf8'); } catch(e) { fullClass = ''; }
let clsCut = 0;
if (fullClass.length > 60000) { clsCut = fullClass.length; fullClass = fullClass.slice(0, 60000)
  + "\n// …TRUNCATED: only the first 60000 of " + clsCut + " chars of this class are shown."
  + "\n// Members past this point are NOT visible — do not conclude they are absent."; }
const leaf = [];
for (const it of items) {
  const r = it.json || {};
  if (Array.isArray(r.candidates)) { for (const x of r.candidates) leaf.push('- ' + x.method + '() [' + x.category + '] ' + x.title); }
  else if (r.title && (r.file || '') === file) leaf.push('- ' + r.method + '() [' + r.category + '] ' + r.title);
}
const USER = "Class " + cls + "  (file " + file + ").\n\n" +
  "Per-method analysis ALREADY recorded these findings (do NOT repeat them):\n" +
  (leaf.length ? leaf.join('\n') : "(none)") + "\n\n" +
  "Report every genuine bug NOT already listed above. Cross-member defects (only a whole-class view can " +
  "catch them) are your top priority, but also flag any single-method bug the per-method pass missed.\n\n" +
  (clsCut ? "CLASS SOURCE (TRUNCATED — first 60000 of " + clsCut + " chars):\n```java\n"
          : "FULL CLASS:\n```java\n") + fullClass + "\n```";
let dialog = "▸ __VERSION__ CLASS AUDIT " + cls + "\n\nleaf findings: " + leaf.length + "\n\nPROMPT:\n" + USER.slice(0, 64000) + "\n";
async function live(status, findings) {
  try {
    await self.helpers.httpRequest({ method:'POST', url: RUNNER + '/live/dialog',
      headers:{ 'Content-Type':'application/json', Connection:'close' },
      body: { method_key: repo + '|' + file + '|(class)', repo, file, class_name: cls, method: '(class)',
              dialog, status, tool_calls: 0, findings: findings || 0 }, json:true, timeout:8000 });
  } catch(e) {}
}
await live('analyzing', 0);
let finalText = '';
// The class auditor needs the same verdict escalation the per-method loop has: without it, a single
// call returning empty (or thinking-only) files status=error with zero findings for a fully-read
// class. Retry with a stricter ask, then with a trimmed prompt that cannot overflow.
function errText2(e) {
  if (!e) return 'unknown';
  const c = [e.message, e.description, e.error && e.error.message,
             (e.httpCode || e.statusCode) ? ('http ' + (e.httpCode || e.statusCode)) : ''].filter(Boolean).join(' | ');
  if (c) return c.slice(0, 300);
  try { return JSON.stringify(e).slice(0, 300); } catch (x) { return String(e).slice(0, 300); }
}
async function auditAsk(msgs, label, maxTok) {
  try {
    const r = await self.helpers.httpRequest({ method:'POST', url: $env.QWEN_BASE_URL + '/chat/completions',
      headers:{ Authorization:'Bearer '+$env.QWEN_API_KEY, 'Content-Type':'application/json', Connection:'close' },
      body: { model: $env.QWEN_MODEL, messages: msgs, temperature: 0.2, max_tokens: 32000 },
      json:true, timeout:3600000 });
    const ch = (r.choices && r.choices[0]) || {};
    const m = ch.message || {};
    const t = ((m.content || m.reasoning_content) || '') + '';
    dialog += "\n[audit:" + label + " -> finish=" + (ch.finish_reason || '?') + ", " + t.length + " chars]\n";
    return t;
  } catch (e) {
    dialog += "\n[audit:" + label + " FAILED -> " + errText2(e) + "]\n";
    return '';
  }
}
const skipAudit = items.length > 0 && items.every(it => (it.json || {}).skip
                  || ((it.json || {}).method === '(no methods)'));
const baseMsgs = [{role:'system',content:SYS},{role:'user',content:USER}];
if (skipAudit) {
  dialog += "\n[no method bodies in this file — class audit skipped]\n";
  finalText = '{"suspicions":[]}';
} else {
  finalText = await auditAsk(baseMsgs, 'forced');
}
if (!skipAudit && finalText.indexOf('"suspicions"') < 0) {
  const strictMsgs = baseMsgs.concat([
    { role:'assistant', content: finalText.slice(0, 2000) },
    { role:'user', content:'Output ONLY the raw JSON object {"suspicions":[...]} — no prose, no tags. If there is no genuine bug beyond what was recorded, output {"suspicions":[]}.' }]);
  const t2 = await auditAsk(strictMsgs, 'strict');
  if (t2.indexOf('"suspicions"') >= 0 || !finalText) finalText = t2 || finalText;
}
if (!skipAudit && finalText.indexOf('"suspicions"') < 0) {   // last resort: trimmed prompt, cannot overflow
  const t3 = await auditAsk([{role:'system',content:SYS},
    {role:'user',content: USER.slice(0, 24000) + '\n\nOutput ONLY {"suspicions":[...]}.'}], 'minimal');
  if (t3.indexOf('"suspicions"') >= 0 || !finalText) finalText = t3;
}
// IDENTICAL to the per-method parseVerdict — kept byte-for-byte so the two paths never drift. usable()
// accepts both `defects` (per-method) and `suspicions` (class audit), so one function serves both. It
// handles prose around the JSON, a fenced block, a key-anchored outer brace anywhere in the reply, and
// truncation mid-JSON (repair closes what is still open, innermost first, keeping every complete element).
function parseVerdict(text) {
  const t = String(text || '');
  if (!t.trim()) return { obj: null, repaired: false };

  const tryParse = (raw) => { try { return JSON.parse(raw); } catch (e) { return null; } };
  const repair = (body) => {   // the model routinely ends a long verdict short of its closing braces
    const stack = []; let inS = false, esc = false;
    const marks = [];
    for (let i = 0; i < body.length; i++) {
      const ch = body[i];
      if (esc) { esc = false; continue; }
      if (ch === '\\') { esc = true; continue; }
      if (ch === '"') { inS = !inS; continue; }
      if (inS) continue;
      if (ch === '{' || ch === '[') stack.push(ch);
      else if (ch === '}' || ch === ']') { stack.pop(); marks.push({ i: i, s: stack.slice() }); }
    }
    const close = (text, st, needQuote) => {
      let out = (needQuote ? text.replace(/\\+$/, m => m.length % 2 ? m.slice(1) : m) + '"' : text);
      out = out.replace(/[\s,]+$/, '');
      if (/:\s*$/.test(out)) out += 'null';
      for (let k = st.length - 1; k >= 0; k--) out += (st[k] === '{' ? '}' : ']');
      return tryParse(out);
    };
    const whole = close(body, stack, inS);
    if (whole) return whole;
    for (let m = marks.length - 1, tried = 0; m >= 0 && tried < 400; m--, tried++) {
      const r = close(body.slice(0, marks[m].i + 1), marks[m].s, false);
      if (r) return r;
    }
    return null;
  };
  const usable = (o) => o && typeof o === 'object'
                        && (Array.isArray(o.defects) || Array.isArray(o.suspicions));

  const fences = [...t.matchAll(/```(?:json)?\s*([\s\S]*?)```/gi)].map(m => m[1]);
  for (let i = fences.length - 1; i >= 0; i--) {
    const o = tryParse(fences[i].trim()) || repair(fences[i].trim());
    if (usable(o)) return { obj: o, repaired: !tryParse(fences[i].trim()) };
  }
  const starts = [];
  for (let i = 0; i < t.length; i++) if (t[i] === '{') starts.push(i);
  const tryAt = (p) => {
    const body = t.slice(p);
    const last = body.lastIndexOf('}');
    if (last > 0) { const o = tryParse(body.slice(0, last + 1)); if (usable(o)) return { obj: o, repaired: false }; }
    const rep = repair(body);
    return usable(rep) ? { obj: rep, repaired: true } : null;
  };
  for (const p of starts) {
    if (/^\s*"(summary|defects|suspicions)"\s*:/.test(t.slice(p + 1))) { const r = tryAt(p); if (r) return r; }
  }
  const order = [];
  for (let k = starts.length - 1; k >= 0 && starts.length - k <= 40; k--) order.push(starts[k]);
  for (let k = 0; k < starts.length && k < 40; k++) if (order.indexOf(starts[k]) < 0) order.push(starts[k]);
  for (const p of order) { const r = tryAt(p); if (r) return r; }
  return { obj: null, repaired: false };
}
dialog += "\n\n=== VERDICT ===\n" + finalText.slice(0, 40000);   // multi-defect verdicts exceed 8k
let arr = [];
const _pv = parseVerdict(finalText);
if (_pv.obj && Array.isArray(_pv.obj.suspicions)) arr = _pv.obj.suspicions;
if (_pv.repaired) dialog += "\n[verdict JSON was truncated by the model — repaired, " + arr.length + " suspicion(s) recovered]\n";
else if (!_pv.obj && finalText.trim()) dialog += "\n[verdict JSON unparseable even after repair — findings lost]\n";
const norm = s => (s||'').toString().toLowerCase().replace(/[^a-z0-9]+/g,'_').replace(/^_|_$/g,'');
const candidates = [];
for (const s of arr) {
  if (!s || !s.title) continue;
  candidates.push({
    dedup_key: [repo, file, 'class', norm(s.title).slice(0,40)].join('|'),   // title-based so multiple audit finds don't collide
    repo, file, class_name: cls, method: '(class) ' + ((s.method||'')+''), line: Number(s.line) || 0,
    category: (s.category||'correctness')+'', severity: (s.severity||'')+'', title: (s.title||'')+'',
    description: (s.description||'')+'', evidence: (s.evidence||'')+'', status: 'new', note: '',
    version: __SUSVER__, branch,
    method_key: repo + '|' + file + '|(class)',   // produced by the class auditor (no single method)
  });
}
try { await live(finalText ? 'done' : 'error', candidates.length, candidates); } catch(e) {}
// pass the per-method items THROUGH (Record methods + Collect candidates still consume them) + append
// the class-audit row (its own method_runs entry, viewable as method '(class)')
// Any item still carrying the RAW shape (method_src, no method_key) never went through the ReAct
// node: n8n forwards a Code node's INPUT unchanged when onError=continueRegularOutput and the task
// runner aborts it (N8N_RUNNERS_TASK_TIMEOUT). Turn it into an explicit error row so the dashboard
// shows red instead of a silent all-NULL row.
const out = items.map(it => {
  const j = (it && it.json) || {};
  if (j.method_key) return it;
  return { json: {
    method_key: repo + '|' + file + '|' + (j.method || '?'),
    repo, file, class_name: cls, method: j.method || '?', findings: 0, tool_calls: 0, status: 'error',
    dialog: '▸ per-method analysis DID NOT RUN for ' + (j.method || '?') + '()\n\n'
      + 'The ReAct node returned its input unchanged — no transcript was produced. This happens when the '
      + 'n8n task runner aborts the node (N8N_RUNNERS_TASK_TIMEOUT, default 300s) or it throws before '
      + 'its first step. Only the class audit ran for this file, so per-method findings are missing.',
    candidates: [],
  }};
});
out.push({ json: {
  method_key: repo + '|' + file + '|(class)', repo, file, class_name: cls, method: '(class)',
  findings: candidates.length, tool_calls: 0,
  status: (!finalText ? 'error' : (clsCut ? 'truncated' : 'done')),
  dialog: (dialog.length > 200000 ? dialog.slice(0,130000) + "\n...(truncated)...\n" + dialog.slice(-60000) : dialog),
  candidates,
}});
return out;
"""
CLASS_AUDIT = (CLASS_AUDIT.replace("__CSYS__", json.dumps(CLASS_AUDIT_SYS)).replace("__VERSION__", stamp(SUSPECTOR_VERSION))
               .replace("__SUSVER__", json.dumps(SUSPECTOR_VERSION)))

COLLECT = r"""
const t = $('Suspect webhook').first().json.body || {};
const candidates = [];
for (const it of $input.all()) {
  if (it.json && Array.isArray(it.json.candidates)) candidates.push(...it.json.candidates);
}
// a candidate with no repo/file is unprovable (the prover keys off file) and pollutes the dashboard
// as a NULL row — drop it here rather than upserting it.
return [{ json: { repo: t.repo, file: t.file,
                  candidates: candidates.filter(c => c && c.title && c.repo && c.file) } }];
"""

SEMANTIC_DEDUP = r"""
const parsed = $('__COLLECTOR__').first().json;
const candidates = parsed.candidates || [];
if (!candidates.length) return [];
// candidates may be attributed to CALLEE files now, so compare against existing rows for every file present
const cfiles = new Set(candidates.map(c => c.file));
const existing = $input.all().map(i => i.json).filter(e => e && e.title && cfiles.has(e.file));
const existKeys = new Set(existing.map(e => e.dedup_key));
let pending = candidates.filter(c => !existKeys.has(c.dedup_key));
if (!pending.length) return [];
if (!existing.length) return pending.map(json => ({ json }));
const exList = existing.map((e,i) => `E${i}: [${e.category}] ${e.method} — ${e.title}: ${e.description}`).join('\n');
const caList = pending.map((c,i) => `C${i}: [${c.category}] ${c.method} — ${c.title}: ${c.description}`).join('\n');
const prompt =
  "You are deduplicating a Java bug-suspicion list for a class and the callees it was traced into.\n\nEXISTING:\n" + exList +
  "\n\nNEW candidates:\n" + caList + "\n\nFor each NEW candidate decide if it is the SAME underlying bug " +
  "(same root cause and location) as any EXISTING one, even if worded differently. Reply ONLY JSON " +
  '{"novel":[<indices of genuinely new candidates>]}.';
let novelIdx = pending.map((_,i) => i);
try {
  const resp = await this.helpers.httpRequest({ method:'POST', url:$env.QWEN_BASE_URL+'/chat/completions',
    headers:{Authorization:'Bearer '+$env.QWEN_API_KEY,'Content-Type':'application/json',Connection:'close'},
    body:{model:$env.QWEN_MODEL,messages:[{role:'user',content:prompt}],temperature:0,max_tokens:32000}, json:true, timeout:3600000 });
  const m=(resp.choices&&resp.choices[0]&&resp.choices[0].message)||{}; const text=((m.content||m.reasoning_content)||'')+'';
  const a=text.indexOf('{'),b=text.lastIndexOf('}');
  if(a>=0&&b>a){const jj=JSON.parse(text.slice(a,b+1)); if(Array.isArray(jj.novel)) novelIdx=jj.novel;}
} catch(e){}
return novelIdx.map(i=>pending[i]).filter(Boolean).map(json=>({json}));
"""

SEMANTIC_DEDUP_AUDIT = SEMANTIC_DEDUP.replace("__COLLECTOR__", "Collect audit")
SEMANTIC_DEDUP = SEMANTIC_DEDUP.replace("__COLLECTOR__", "Collect candidates")

N = []
def node(name, ntype, params, tv, x, y=300, extra=None):
    n = {"parameters": params, "id": f"n{len(N)+1}", "name": name, "type": ntype,
         "typeVersion": tv, "position": [x, y]}
    if extra: n.update(extra)
    N.append(n)
    return name

def code(js, per_item=False):
    return {"mode": "runOnceForEachItem" if per_item else "runOnceForAllItems", "jsCode": js.strip()}

METHOD_RUNS_TABLE = "HsKuoBC5FPT0iJNh"
DT = lambda: {"__rl": True, "mode": "list", "value": SUSPICIONS_TABLE, "cachedResultName": "suspicions"}
MR = lambda: {"__rl": True, "mode": "list", "value": METHOD_RUNS_TABLE, "cachedResultName": "method_runs"}

node("Suspect webhook", "n8n-nodes-base.webhook",
     {"httpMethod": "POST", "path": "suspect", "responseMode": "lastNode", "options": {}},
     2, 0, extra={"webhookId": "fsmsuspecthook1"})
node("Fetch file", "n8n-nodes-base.httpRequest",
     {"url": "=https://api.github.com/repos/{{ $json.body.repo }}/contents/{{ $json.body.file }}?ref={{ $json.body.branch }}",
      "sendHeaders": True,
      "headerParameters": {"parameters": [
          {"name": "User-Agent", "value": "n8n-fsm"},
          {"name": "Accept", "value": "application/vnd.github+json"},
          {"name": "Authorization", "value": "=Bearer {{ $env.GITHUB_TOKEN }}"},
          {"name": "Connection", "value": "close"}]},
      "options": {"timeout": 60000}}, 4.2, 220,
     extra={"retryOnFail": True, "maxTries": 3, "waitBetweenTries": 3000})
node("Extract methods", "n8n-nodes-base.code", code(EXTRACT_METHODS), 2, 440)
node("ReAct investigate", "n8n-nodes-base.code", code(REACT), 2, 880, y=200,
     extra={"onError": "continueRegularOutput"})
node("Record methods", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "upsert", "dataTableId": MR(),
      "filters": {"conditions": [{"keyName": "method_key", "condition": "eq", "keyValue": "={{ $json.method_key }}"}]},
      "columns": {"mappingMode": "defineBelow", "matchingColumns": ["method_key"], "schema": [],
                  "attemptToConvertTypes": False, "convertFieldsToString": True,
                  "value": {"method_key": "={{ $json.method_key }}", "repo": "={{ $json.repo }}",
                            "file": "={{ $json.file }}", "class_name": "={{ $json.class_name }}",
                            "method": "={{ $json.method }}", "findings": "={{ $json.findings }}",
                            "tool_calls": "={{ $json.tool_calls }}", "status": "={{ $json.status }}",
                            "dialog": "={{ $json.dialog }}"}},
      "options": {}}, 1.1, 880, y=620)
node("Loop methods", "n8n-nodes-base.splitInBatches", {"batchSize": 5, "options": {}}, 3, 660)
node("Class audit", "n8n-nodes-base.code", code(CLASS_AUDIT), 2, 700, y=560,
     extra={"onError": "continueRegularOutput"})
node("Collect candidates", "n8n-nodes-base.code", code(COLLECT), 2, 880, y=440)
# --- the whole-class pass runs once, after every batch is durable, on its own persistence chain
node("Get file findings", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "get", "dataTableId": DT(),
      "filters": {"conditions": [{"keyName": "file", "condition": "eq",
                                  "keyValue": "={{ $(\'Suspect webhook\').first().json.body.file }}"}]},
      "returnAll": True, "options": {}}, 1.1, 520, y=560, extra={"alwaysOutputData": True})
node("Record audit", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "upsert", "dataTableId": MR(),
      "filters": {"conditions": [{"keyName": "method_key", "condition": "eq", "keyValue": "={{ $json.method_key }}"}]},
      "columns": {"mappingMode": "defineBelow", "matchingColumns": ["method_key"], "schema": [],
                  "attemptToConvertTypes": False, "convertFieldsToString": True,
                  "value": {"method_key": "={{ $json.method_key }}", "repo": "={{ $json.repo }}",
                            "file": "={{ $json.file }}", "class_name": "={{ $json.class_name }}",
                            "method": "={{ $json.method }}", "findings": "={{ $json.findings }}",
                            "tool_calls": "={{ $json.tool_calls }}", "status": "={{ $json.status }}",
                            "dialog": "={{ $json.dialog }}"}},
      "options": {}}, 1.1, 880, y=700)
node("Collect audit", "n8n-nodes-base.code", code(COLLECT), 2, 880, y=560)
node("Get existing audit", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "get", "dataTableId": DT(),
      "filters": {"conditions": [{"keyName": "repo", "condition": "eq", "keyValue": "={{ $json.repo }}"}]},
      "returnAll": True, "options": {}}, 1.1, 1100, y=560, extra={"alwaysOutputData": True})
node("Dedup audit", "n8n-nodes-base.code", code(SEMANTIC_DEDUP_AUDIT), 2, 1320, y=560)
node("Upsert audit", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "upsert", "dataTableId": DT(),
      "filters": {"conditions": [{"keyName": "dedup_key", "condition": "eq", "keyValue": "={{ $json.dedup_key }}"}]},
      "columns": {"mappingMode": "autoMapInputData", "value": {}, "matchingColumns": [],
                  "schema": [], "attemptToConvertTypes": False, "convertFieldsToString": True},
      "options": {}}, 1.1, 1540, y=560)
node("Get existing", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "get", "dataTableId": DT(),
      "filters": {"conditions": [{"keyName": "repo", "condition": "eq", "keyValue": "={{ $json.repo }}"}]},
      "returnAll": True, "options": {}}, 1.1, 1100, y=440, extra={"alwaysOutputData": True})
node("Semantic dedup", "n8n-nodes-base.code", code(SEMANTIC_DEDUP), 2, 1320, y=440)
node("Upsert suspicion", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "upsert", "dataTableId": DT(),
      "filters": {"conditions": [{"keyName": "dedup_key", "condition": "eq", "keyValue": "={{ $json.dedup_key }}"}]},
      "columns": {"mappingMode": "autoMapInputData", "value": {}, "matchingColumns": [],
                  "schema": [], "attemptToConvertTypes": False, "convertFieldsToString": True},
      "options": {}}, 1.1, 1540, y=440)

conns = {
    "Suspect webhook":     {"main": [[{"node": "Fetch file", "type": "main", "index": 0}]]},
    "Fetch file":          {"main": [[{"node": "Extract methods", "type": "main", "index": 0}]]},
    "Extract methods":     {"main": [[{"node": "Loop methods", "type": "main", "index": 0}]]},
    # output 0 = done (all batches processed) -> the whole-class pass; output 1 = the next batch
    "Loop methods":        {"main": [[{"node": "Get file findings", "type": "main", "index": 0}],
                                     [{"node": "ReAct investigate", "type": "main", "index": 0}]]},
    "Get file findings":   {"main": [[{"node": "Class audit", "type": "main", "index": 0}]]},
    "ReAct investigate":   {"main": [[{"node": "Record methods", "type": "main", "index": 0},
                                      {"node": "Collect candidates", "type": "main", "index": 0}]]},
    "Collect candidates":  {"main": [[{"node": "Get existing", "type": "main", "index": 0}]]},
    "Get existing":        {"main": [[{"node": "Semantic dedup", "type": "main", "index": 0}]]},
    # each batch's findings are durable BEFORE the next batch starts
    "Semantic dedup":      {"main": [[{"node": "Upsert suspicion", "type": "main", "index": 0}]]},
    "Upsert suspicion":    {"main": [[{"node": "Loop methods", "type": "main", "index": 0}]]},
    "Class audit":         {"main": [[{"node": "Record audit", "type": "main", "index": 0},
                                      {"node": "Collect audit", "type": "main", "index": 0}]]},
    "Collect audit":       {"main": [[{"node": "Get existing audit", "type": "main", "index": 0}]]},
    "Get existing audit":  {"main": [[{"node": "Dedup audit", "type": "main", "index": 0}]]},
    "Dedup audit":         {"main": [[{"node": "Upsert audit", "type": "main", "index": 0}]]},
}

wf = {"id": "fsmsuspector0001", "name": "fsm-suspector", "active": False,
      "nodes": N, "connections": conns, "settings": {"executionOrder": "v1"}}
open("workflow_suspector.json", "w").write(json.dumps(wf, indent=2))
print(f"wrote workflow_suspector.json — {len(N)} nodes (manual ReAct tool loop)")
