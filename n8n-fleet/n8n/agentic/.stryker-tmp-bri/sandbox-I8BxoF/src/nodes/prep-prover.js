// @ts-nocheck
'use strict';
// TODO(port): docstring
async function prepProver({ $json, $env, helpers }) {
  const s = $json;                                  // one suspicion row from Get new suspicions
  // Resolve the repo's REAL default branch. Hardcoding 'main' silently destroyed every finding on any
  // repo that uses develop / master / 4.x / v5-master: the source fetch 404s, the reproducer is handed
  // an empty file, and the suspicion ends up 'rejected' — indistinguishable from a real false positive.
  let branch = (s.branch || '').toString().trim(), branch_error = '';
  // the suspector already analysed a specific branch — reuse it (also avoids one API call per suspicion)
  if (!branch) try {
    const ri = await helpers.httpRequest({
      url: 'https://api.github.com/repos/' + s.repo,
      headers: { 'User-Agent': 'n8n-fsm', Accept: 'application/vnd.github+json',
                 Authorization: 'Bearer ' + $env.GITHUB_TOKEN, Connection: 'close' },
      json: true, timeout: 30000,
    });
    branch = (ri && ri.default_branch) || '';
  } catch (e) { branch_error = (e && (e.message || e.description)) ? String(e.message || e.description).slice(0, 200) : 'repo lookup failed'; }
  if (!branch) branch_error = branch_error || 'no default_branch returned';
  const file = (s.file || '').toString();
  // Split on 'src/main/java/' WITHOUT a leading slash. The parent split on '/src/main/java/', which only
  // matches when a module directory precedes it — true of its suspector's paths, false of the Svace
  // ingester's repo-relative ones. On a single-module repo like WebGoat every path starts with
  // 'src/main/java/', so the separator never matched: module, package and package directory all came out
  // empty and every generated test was written to the default package at the root of src/test/java.
  const MARK = 'src/main/java/';
  const at = file.indexOf(MARK);
  const module = at > 0 ? file.slice(0, at).replace(/\/+$/, '') : '';
  const rest = at >= 0 ? file.slice(at + MARK.length) : '';
  // lastIndexOf('/') is -1 for a class directly under src/main/java, and slice(0, -1) would silently
  // truncate the FILENAME's last character into a bogus package. Test for the separator instead.
  const pkgdir = rest.indexOf('/') >= 0 ? rest.slice(0, rest.lastIndexOf('/')) : '';
  const pkg = pkgdir.replace(/\//g, '.');
  const cls = (s.class_name || file.split('/').pop().replace('.java','')).toString().replace(/[^A-Za-z0-9_]/g,'');
  const test_class = cls + 'FsmProofTest';
  const test_path = (module ? module + '/' : '') + 'src/test/java/' + (pkgdir ? pkgdir + '/' : '') + test_class + '.java';
  // Svace provenance. `settle_by` comes from the ingester's checker map: 'test' = a JUnit test can
  // exhibit this, 'argue' = nothing observable at runtime distinguishes the flagged code (dead store,
  // hard-coded secret), so the only honest outcome is a written verdict. It decides whether a
  // non-reproduction is worth a second prove attempt.
  const ev = (s.evidence || '').toString();
  const sb = ev.match(/Settle-by:\s*(\w+)/);
  return {
    suspicion_key: s.dedup_key, repo: s.repo, branch, branch_ok: !!branch, branch_error,
    prove_attempts: Number(s.prove_attempts) || 0, file, module, pkg,
    class_name: cls, method: s.method, test_class, test_path,
    category: s.category, severity: s.severity, title: s.title,
    description: s.description, evidence: ev,
    marker_id: s.marker_id || '', svace_checker: s.svace_checker || '',
    svace_severity: s.svace_severity || '',
    svace_line: Number(s.svace_line) || Number(s.line) || 0,
    settle_by: sb ? sb[1] : 'test',
  };
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { prepProver };
