'use strict';
/**
 * Robust JSON extraction from an LLM reply.
 *
 * The reproducer and fixer embed a whole Java file inside a JSON string, and the model wraps it in
 * prose or a ```json fence and occasionally truncates it. The naive indexOf('{')..lastIndexOf('}')
 * then grabs a stray brace out of the prose — a {@code} javadoc reference is enough — and a perfectly
 * good reply is discarded as unparseable. This tries a fenced block, then the '{' whose first key is
 * one the answer should actually have, then both ends, and finally repairs a truncated tail by
 * closing open delimiters innermost-first.
 */
function extractJson(text, keys) {
  const t = String(text || '');
  if (!t.trim()) return null;
  const tryParse = (raw) => { try { return JSON.parse(raw); } catch (e) { return null; } };
  const repair = (body) => {
    const stack = []; let inS = false, esc = false; const marks = [];
    for (let i = 0; i < body.length; i++) { const ch = body[i];
      if (esc) { esc = false; continue; }
      if (ch === '\\') { esc = true; continue; }
      if (ch === '"') { inS = !inS; continue; }
      if (inS) continue;
      if (ch === '{' || ch === '[') stack.push(ch);
      else if (ch === '}' || ch === ']') { stack.pop(); marks.push({ i: i, s: stack.slice() }); } }
    const close = (txt, st, q) => {
      let out = (q ? txt.replace(/\\+$/, m => m.length % 2 ? m.slice(1) : m) + '"' : txt);
      out = out.replace(/[\s,]+$/, ''); if (/:\s*$/.test(out)) out += 'null';
      for (let k = st.length - 1; k >= 0; k--) out += (st[k] === '{' ? '}' : ']'); return tryParse(out); };
    const whole = close(body, stack, inS); if (whole) return whole;
    for (let m = marks.length - 1, tr = 0; m >= 0 && tr < 400; m--, tr++) {
      const r = close(body.slice(0, marks[m].i + 1), marks[m].s, false); if (r) return r; }
    return null;
  };
  const usable = (o) => o && typeof o === 'object' && keys.some(k => k in o);
  const fences = [...t.matchAll(/```(?:json)?\s*([\s\S]*?)```/gi)].map(m => m[1]);
  for (let i = fences.length - 1; i >= 0; i--) { const o = tryParse(fences[i].trim()) || repair(fences[i].trim()); if (usable(o)) return o; }
  const starts = []; for (let i = 0; i < t.length; i++) if (t[i] === '{') starts.push(i);
  const tryAt = (p) => { const body = t.slice(p); const last = body.lastIndexOf('}');
    if (last > 0) { const o = tryParse(body.slice(0, last + 1)); if (usable(o)) return o; }
    const rep = repair(body); return usable(rep) ? rep : null; };
  const keyRe = new RegExp('^\\s*"(' + keys.join('|') + ')"\\s*:');
  for (const p of starts) { if (keyRe.test(t.slice(p + 1))) { const r = tryAt(p); if (r) return r; } }
  const order = []; for (let k = starts.length - 1; k >= 0 && starts.length - k <= 40; k--) order.push(starts[k]);
  for (let k = 0; k < starts.length && k < 40; k++) if (order.indexOf(starts[k]) < 0) order.push(starts[k]);
  for (const p of order) { const r = tryAt(p); if (r) return r; }
  return null;
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { extractJson };
