'use strict';
let DASH = {files:[],suspicions:[],bugs:[]};
// Socket state, declared up here because render() reads it for the header line; the implementation is
// at the bottom of the file with the rest of the live plumbing.
let LIVE = {sock:null, connected:false, last:0};
async function jget(u){try{return await (await fetch(u,{cache:'no-store'})).json();}catch(e){return null;}}
// The pipeline's booleans arrive as real JSON booleans from the Spring orchestrator and arrived as
// sqlite's 0/1 from the n8n dashboard. Reading them with String(v)==='1' was correct for exactly one of
// those and silently wrong for the other — a proven marker would have shown an empty Reproducer dot and
// dropped out of the "verified red→green" count, with nothing failing anywhere. One helper, so there is
// one answer to "is this true".
function yes(v){return v===true||v===1||v==='1'||v==='true';}
function stopLiveTimer(){if(window.__LIVET){clearInterval(window.__LIVET);window.__LIVET=null;}}
function closeModal(){stopLiveTimer();document.getElementById('modalbg').classList.remove('on');}
// THE MODAL TAKES THE FOCUS WHEN IT OPENS, and that one line is what makes everything inside it
// reachable from the keyboard. Sequential focus order follows the DOM, and this overlay is the LAST
// thing in the body — so without it, Tab from an unfocused document walks the header, the tiles and
// then every link in a 282-row table before it arrives anywhere near the modal. Which in practice
// means the comment box below is mouse-only, and a box you cannot type into without reaching for the
// mouse is a box nobody writes a paragraph in.
function openModal(title){stopLiveTimer();document.getElementById('mtitle').innerHTML=title;document.getElementById('mtabs').innerHTML='';document.getElementById('mbody').innerHTML='<div class=empty>loading…</div>';document.getElementById('modalbg').classList.add('on');const m=document.querySelector('#modalbg .modal');if(m)m.focus();}
document.addEventListener('keydown',e=>{if(e.key==='Escape')closeModal();});
/**
 * The modal's tab strip.
 *
 * KEYBOARD-OPERABLE, because a comment is written on the tab whose output it criticises: if the only
 * way to reach the Reproducer tab is a click, then "you can write this with the keyboard" is false
 * however good the box on it is. The tabs are spans and not buttons — changing that would restyle
 * every tab on the page — so they are given the three things a button has: a place in the focus
 * order, a role, and Enter/Space.
 *
 * @param focusTab move the focus onto the tab that was just activated. Set when the switch CAME from
 *        the keyboard: setTabs rebuilds the strip, so the element the person was standing on is gone
 *        by the end of this call and the focus would otherwise fall back to the top of the document,
 *        which is a keyboard user losing their place with every tab they open.
 */
function setTabs(tabs,active,focusTab){const tb=document.getElementById('mtabs');tb.innerHTML='';
  tabs.forEach((t,i)=>{const el=document.createElement('span');el.className='tab'+(i===active?' on':'');
    el.textContent=t.name;el.tabIndex=0;el.setAttribute('role','tab');
    el.setAttribute('aria-selected',i===active?'true':'false');
    el.onclick=()=>setTabs(tabs,i);
    el.onkeydown=(e)=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();setTabs(tabs,i,true);}};
    tb.appendChild(el);});
  document.getElementById('mbody').innerHTML=tabs[active].render();
  if(focusTab&&tb.children[active])tb.children[active].focus();
  // Whatever tab this is, if it carries a comment box the comments on this marker are fetched once the
  // tab is on screen — the same lazy rule as the source window and the machine critiques.
  const box=document.getElementById('cmt');
  if(box)setTimeout(()=>loadComments(box.dataset.key),0);}
async function showFile(f){ if(!f)return; openModal('methods · <code>'+esc(short(f))+'</code>');
  const rows=await jget('api/methods?file='+encodeURIComponent(f))||[];
  document.getElementById('mtabs').innerHTML='';
  if(!rows.length){document.getElementById('mbody').innerHTML=empty('no methods recorded for this file yet');return;}
  window.__M=rows;
  document.getElementById('mbody').innerHTML=tbl(['method','findings','tool calls','status','dialog'],
    rows.map(r=>['<code>'+esc(r.method)+'</code>',(r.findings||0)?'<b>'+r.findings+'</b>':'0',(r.tool_calls||0),esc(r.status||''),'<span class=link>view →</span>']),
    i=>"showDialog(window.__M["+i+"].method_key, window.__M["+i+"].method)"); }
async function showDialog(key,method){ openModal('suspector dialog · <code>'+esc(method||'')+'</code>');
  const d=await jget('api/dialog?key='+encodeURIComponent(key)); document.getElementById('mtabs').innerHTML='';
  document.getElementById('mbody').innerHTML=fmtDialog((d&&d.dialog)||''); }
async function showInvestigation(su){ if(!su)return;
  const mkey=su.method_key||(su.repo+'|'+su.file+'|'+su.method);   // producing run (class audits / callee findings differ)
  openModal('<code>'+esc(short(su.file))+':'+esc(su.svace_line!=null?num(su.svace_line):(su.line!=null?num(su.line):'?'))
    +'</code> <span class=tiny>'+esc(su.svace_checker||'')+'</span> — '+esc((su.description||su.title||'').slice(0,90)));
  // WHICH MARKER THIS MODAL IS ABOUT, in one place. Every tab renders a different artifact of the same
  // marker, and a comment written on any of them is filed against this key — including on a stub row
  // opened from the guidance panel, where there is no artifact and no table row to read it back off.
  MODAL={key:su.dedup_key||su.suspicion_key||'',comments:null};
  // the suspector transcript is gone with the suspector; only the artifact is fetched now
  const bug=await jget('api/bug?key='+encodeURIComponent(su.dedup_key||su.suspicion_key||''));
  const tabs=[{name:'Marker',render:()=>{ const h=renderMarker(su,bug);
    setTimeout(()=>{loadMarkerSource(su);loadMarkerCritiques(su);},0); return h; }}];
  if(bug&&bug.suspicion_key){
    tabs.push({name:'Reproducer '+(yes(bug.red_verified)?'●':'○'),render:()=>renderTest(bug)});
    tabs.push({name:'Fixer '+(yes(bug.green_verified)?'●':'○'),render:()=>renderFix(bug)});
    tabs.push({name:'PR maker '+(bug.state==='pr_ready'?'●':(bug.state==='pr_rejected'?'⛔':'○')),render:()=>renderPR(bug)});
  } else { tabs.push({name:'Reproducer / Fixer / PR',render:()=>empty('not proven yet — run the prover to generate a test, fix + PR decision')}); }
  setTabs(tabs,0); }
// The bugs rows already carry the marker columns (joined from suspicions server-side), so pass the
// whole row through rather than reconstructing a stub from the dedup_key — that stub was why opening
// a proven bug showed a marker tab with nothing in it.
function showBug(b){ if(!b)return;
  const su=DASH.suspicions.find(x=>x.dedup_key===b.suspicion_key);
  showInvestigation(su||{...b,dedup_key:b.suspicion_key}); }
// The marker tab. This replaced the suspector's ReAct transcript: Svace markers came from a scanner,
// not from an LLM investigation, so there was no dialog to show and the tab rendered empty. What a
// reviewer needs here is the report row itself — Severity, Checker, File, Line — beside what the
// pipeline made of it: where the line actually landed after re-anchoring, and how it was settled.
// Source around the marker, fetched when the tab opens and dropped into a placeholder. Lazy because
// it crosses to the java-runner: the tab must render immediately with everything we already know,
// and the code arrives a moment later rather than holding the whole modal on a network call.
async function loadMarkerSource(su){
  const el=document.getElementById('markersrc'); if(!el) return;
  const line=Number(su.svace_line!=null?su.svace_line:su.line)||0;
  const qs='repo='+encodeURIComponent(su.repo||'')+'&branch='+encodeURIComponent(su.branch||'main')
    +'&file='+encodeURIComponent(su.file||'')+'&line='+line;
  const d=await jget('api/source?'+qs);
  if(!d||d.error){ el.innerHTML='<div class=empty>source unavailable'
    +(d&&d.error?' — '+esc(d.error):'')+'</div>'; return; }
  const rows=(d.lines||[]).map(([n,t])=>{
    const hit=n===d.line;
    return '<div class="diff-line'+(hit?' diff-del':'')+'">'
      +'<span style="display:inline-block;width:4.5em;text-align:right;color:var(--dim);'
      +'margin-right:1em">'+n+'</span>'+(hit?'▶ ':'  ')+esc(t)+'</div>';
  }).join('');
  el.innerHTML=(d.past_eof
      ? '<div class=empty style="color:var(--red)">line '+d.line+' is past the end of this file ('
        +d.total+' lines) — the file changed since the scan, so the window below is its tail</div>'
      : '')
    +'<div class=diff-body>'+rows+'</div>'
    +'<div class=tiny style="padding:6px 12px">'+esc(d.file)+' · '+d.total+' lines · '
    +'from the runner\'s checkout, the same tree the prover tested'+(d.truncated?' · file truncated':'')+'</div>';
}

function renderMarker(su,bug){
  su=su||{}; bug=bug||{};
  const pick=(...v)=>{for(const x of v){if(x!==undefined&&x!==null&&String(x).trim()!=='')return x;}return '';};
  const line=pick(su.svace_line,su.line,bug.svace_line,bug.line);
  const anchor=pick(su.anchor,bug.anchor), astatus=pick(su.anchor_status,bug.anchor_status);
  // Location confidence is the one thing that decides whether the rest can be trusted: the scanned
  // commit is unknown, so a marker resolved against upstream HEAD may point at code that has moved.
  const conf={exact:['var(--hi2)','the reported line falls inside this method in the checked-out tree'],
              'no-method':['var(--amber)','the line is a field, annotation or import — with Lombok the accessor Svace flagged is generated and has no source form'],
              unresolved:['var(--red)','the line is past the end of the file as checked out — the file changed since the scan'],
              pending:['var(--dim)','not resolved yet; the prover re-anchors when it fetches the source']}[astatus]
             ||['var(--dim)',''];
  const row=(k,v,extra)=>v===''||v==null?'':'<tr><td class=tiny style="white-space:nowrap;color:var(--dim)">'+k
    +'</td><td>'+v+(extra?'<div class=tiny style="color:var(--dim)">'+esc(extra)+'</div>':'')+'</td></tr>';
  // WHERE THE READER GOES NEXT. The marker's own location is the one thing on this tab that exists
  // outside the pipeline, so it is the one thing that can be opened. The repository link is rendered
  // ONLY beside a working file link, deliberately: it makes "this marker cannot be located" a single
  // statement — no anchors at all — rather than a tab with one link on it that does not go where the
  // reader assumes. The branch is taken from the marker and falls back to the artifact's, because
  // `Prep prover` is what resolves a blank ingest branch and it records the answer on the bug row.
  const lrepo=pick(su.repo,bug.repo), lbranch=pick(su.branch,bug.branch),
        lfile=pick(su.file,bug.file), lline=lineNo(line);
  const srcUrl=markerSourceUrl(lrepo,lbranch,lfile,lline);
  const openCell=srcUrl
    ? ext(srcUrl,short(lfile)+':'+lline,sourceTitle(lrepo,lbranch,lfile,lline))
      +' <span class=tiny>·</span> '+ext(repoUrl(lrepo),repoSlug(lrepo),'the repository this marker '
        +'was scanned from')
    : '<span class=tiny>'+esc(whyNoLink(lrepo,lfile,lline))
      +'. A URL built from an incomplete location would 404, and a reader would read that as the '
      +'file having been deleted from the repository — a finding this page would have invented.'
      +'</span>';
  const kind=pick(bug.verdict_kind), vtext=pick(bug.verdict_text);
  // THE ARGUMENT WAS NEVER ASKED FOR. bugs.verdict_status is 'skipped' only when the verdict stage was
  // switched off for this run, and it is the only thing separating this row from one whose model was
  // asked and answered with nothing — every other column is identical. Rendering it as a finished
  // verdict, or as a blank, are both the misdiagnosis this column exists to prevent.
  const vskipped=String(bug.verdict_status||'')==='skipped';
  const skipnote='<div class=diff-hdr style="color:var(--amber)">Verdict — NOT WRITTEN (stage switched'
    +' off)</div><div class=tiny style="padding:0 12px;color:var(--dim)">'
    +'The verdict stage was switched off for this run (fsm.prove.verdict-enabled=false), so nobody '
    +'argued this marker. This is NOT a model that was asked and had nothing to say, and NOT a finding: '
    +'re-queue the marker with the stage on to have the claim argued.</div>';
  return '<div class=diff-hdr>Svace report row</div><table>'
    + row('severity','<span class=sev-'+esc(pick(su.severity,bug.severity))+'>'
        +esc(pick(su.svace_severity,bug.svace_severity))+'</span>')
    + row('checker','<code>'+esc(pick(su.svace_checker,bug.svace_checker))+'</code>')
    + row('claim',esc(pick(su.description,bug.description)))
    + row('file','<code>'+esc(pick(su.file,bug.file))+'</code>')
    + row('line',line===''?'':'<code>'+esc(num(line))+'</code>')
    + row('category',esc(pick(su.category,bug.category)))
    + row('marker id','<span class=tiny>'+esc(pick(su.marker_id,bug.marker_id))+'</span>')
    + row('open',openCell,srcUrl
        ? 'the branch tip, not the commit Svace scanned — that commit is unknown, so check the '
          + 'confidence below before trusting the line'
        : '')
    + '</table>'
    + '<div class=diff-hdr>Where it landed in the checked-out tree</div><table>'
    + row('anchor',anchor?('<code>'+esc(anchor)+'()</code>'):'<span class=tiny>—</span>')
    + row('confidence','<span style="color:'+conf[0]+'">'+esc(astatus||'—')+'</span>',conf[1])
    + row('status','<span class=st-'+esc(pick(su.status))+'>'+esc(pick(su.status))+'</span>')
    + row('attempts',su.prove_attempts!=null?esc(num(su.prove_attempts)):'')
    + row('note',pick(su.note)?esc(su.note):'')
    + '</table>'
    // The banner comes FIRST and is shown even when there is verdict text: the exhausted-build route
    // keeps its composed "NOT SETTLED" wording when the argument is skipped, and that text is
    // character-for-character what a marker gets when the endpoint is down.
    + (vskipped?skipnote:'')
    + (vtext?('<div class=diff-hdr>'+(vskipped?'Composed from the run, not argued':'Verdict')
       +(kind?' — '+esc(kind):'')+'</div>'
       +'<div style="white-space:pre-wrap;padding:0 12px">'+esc(vtext)+'</div>'):'')
    // BESIDE THE MARKER, NOT IN A TAB OF ITS OWN. What was wrong with the test written for this
    // marker is part of judging the artifact, and a reviewer who has to go looking for it is a
    // reviewer who will not. It is also why this is not conditional on there being any: "nothing was
    // recorded about this marker" and "the recorder is off" are both answers worth reading, and a
    // section that appeared only when it had content could say neither.
    // THE HUMAN CHANNEL, BESIDE THE MACHINE ONE AND ABOVE IT. The verdict is directly overhead, which
    // is what a comment written here is about, and the pipeline's own complaints are directly below —
    // so the two kinds of criticism are read together and it is obvious which is which.
    + commentBox(MODAL.key,'verdict')
    + '<div class=diff-hdr>What the pipeline complained about</div>'
    + '<div id=markercrit><div class=empty>loading…</div></div>'
    + '<div class=diff-hdr>Source at the marker</div><div id=markersrc><div class=empty>loading…</div></div>'
    + (bug&&bug.versions?verline(stageVer(bug,'ingester',su.version),bug):'');
}
const esc=s=>(s==null?'':String(s)).replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
const short=p=>p?p.split('/').pop():'';
// folders before class: drop the noisy src/main/java segment, dim the folders, highlight the class
function pkg(p){ if(!p) return ''; const clean=p.replace('/src/main/java/','/').replace(/^src\/main\/java\//,''); const j=clean.lastIndexOf('/');
  return j<0 ? '<code>'+esc(clean)+'</code>'
    : '<span class=tiny>'+esc(clean.slice(0,j+1))+'</span><code>'+esc(clean.slice(j+1))+'</code>'; }
// ---- FROM A MARKER TO THE ACTUAL CODE ----------------------------------------------------------
// Every row on this page is a claim about somebody else's source: repo, branch, file, line. Until
// these helpers existed there was no way to read that source except to copy a path out of a table
// cell and go and find it by hand, 282 times.
//
// WHAT IS LINKED, AND WHAT DELIBERATELY IS NOT.
//   THE SOURCE FILE AT THE MARKER'S LINE is a real URL and is built. `repo` is `owner/name` — the
//   shape the ingest webhook requires and the one GithubSourceClient builds
//   /repos/{repo}/contents/{file}?ref={branch} out of — so a blob URL is the same three values
//   rearranged: https://github.com/{repo}/blob/{branch}/{file}#L{line}.
//
//   THE TEST THE PIPELINE WROTE IS NOT LINKED. bugs.test_path looks exactly like a repository path
//   (src/test/java/…) and is the most tempting thing here to turn into a blob URL — but it is a path
//   in the prover's own throwaway checkout, and nothing is ever pushed, so every one of those links
//   would 404. renderTest() prints the path and says so.
//
//   THE DRAFTED PR IS NOT LINKED, for the same reason and more sharply: this pipeline only ever
//   DRAFTS a pull request, so there is no pr_url column anywhere in the schema. The dashboard this
//   one is compared against renders `<a href="${esc(f.prUrl)}">PR ↗</a>`; the honest equivalent here
//   is the words DRAFTED — NOT OPENED. A /compare/ link would be no better: it presumes a pushed
//   branch that does not exist.
//
// AND THE HALF THAT MATTERS MORE: NOTHING IS EMITTED WHEN THE VALUES ARE NOT THERE. A link that
// 404s is worse than no link at all — it moves a reviewer from "I must go and look this up" to "I
// looked, and the file is gone", which is a FINDING, fabricated by the dashboard about the
// repository under analysis. So a URL is built only from values that can carry it, and where one
// cannot be built the plain text is rendered instead.
const GITHUB='https://github.com/';
// `owner/name` and nothing else. A full clone URL, a GitLab group/sub/project path or an empty cell
// all reach this column in principle, and github.com/<any of them> is a WELL-FORMED URL and a 404 —
// which is precisely the failure that is invisible until a reader follows it.
const REPO_SHAPE=/^[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+$/;
// esc() escapes &, < and > — the three that matter in TEXT. An ATTRIBUTE also ends at a quote, and
// everything below is interpolated into href="…" / title="…", so the quotes are escaped here too.
// Every dynamic component is percent-encoded before it gets here as well; this is the second lock.
const escA=s=>esc(s).replace(/"/g,'&quot;').replace(/'/g,'&#39;');
function repoSlug(v){const s=String(v==null?'':v).trim();return REPO_SHAPE.test(s)?s:'';}
function repoUrl(repo){const s=repoSlug(repo);return s?GITHUB+s:'';}
// A git ref, encoded but keeping its slashes: `release/1.2` is ONE branch name, and encoding the
// slash away turns it into a ref that does not exist.
function encRef(r){return String(r).split('/').map(encodeURIComponent).join('/');}
// Same rule for a path, and per SEGMENT: encodeURIComponent on the whole thing would eat every
// separator and ask GitHub for a single file with slashes in its name.
function encPath(p){return String(p==null?'':p).trim().replace(/^\/+/,'')
  .split('/').map(encodeURIComponent).join('/');}
// A line number as a link can use one: a positive whole number, or 0 for "there isn't one". n8n Data
// Table `number` columns come back as REAL, so 42 arrives as 42.0 — see num().
function lineNo(...v){for(const x of v){const n=Number(x);
  if(x!==null&&x!==undefined&&x!==''&&isFinite(n)&&n>=1)return Math.round(n);} return 0;}
/**
 * A file in a repository, optionally at a line. '' when it cannot be built.
 *
 * The BRANCH is allowed to be blank and falls back to HEAD, which GitHub resolves to the default
 * branch. That is not a guess: `branch` is stamped at ingest and the webhook accepts a blank one on
 * purpose, leaving `Prep prover` to resolve the repository's default branch per marker at prove
 * time — so a whole run's markers can legitimately carry an empty branch, and refusing to link them
 * would be refusing to link the entire backlog. HEAD is a real ref and the URL resolves.
 */
function blobUrl(repo,branch,file,line){
  const slug=repoSlug(repo), path=encPath(file);
  if(!slug||!path) return '';
  const ref=String(branch==null?'':branch).trim();
  return GITHUB+slug+'/blob/'+encRef(ref||'HEAD')+'/'+path+(line>=1?'#L'+line:'');
}
// The marker's own link, which requires ALL THREE of repo, file and line. Stricter than blobUrl on
// purpose: what this link promises is "the code Svace flagged", and without the line it cannot keep
// that promise. In practice nothing is lost — the ingester drops any report row missing a file or a
// finite line, so a marker without one never reaches the backlog at all.
function markerSourceUrl(repo,branch,file,line){
  const n=lineNo(line); return n?blobUrl(repo,branch,file,n):'';
}
// WHY THE LINK CANNOT BE BUILT, named. The alternative is a blank cell, which reads as a page that
// failed to render rather than as a marker whose location is incomplete.
function whyNoLink(repo,file,line){
  const missing=[];
  if(!repoSlug(repo)) missing.push(String(repo||'').trim()
    ? 'its repo is not owner/name (`'+String(repo).trim()+'`)' : 'no repo');
  if(!encPath(file)) missing.push('no file');
  if(!lineNo(line)) missing.push('no line');
  return 'no link — '+(missing.length?missing.join(', '):'nothing to point at');
}
/**
 * ONE ANCHOR, and every link on this page goes through it.
 *
 *   target=_blank      the dashboard is watched for the length of a run; navigating away from it
 *                      loses the live socket and the reader's place in a 282-row table.
 *   rel=noopener       the opened page must not get a handle on this one.
 *   stopPropagation()  THE ONE THAT IS INVISIBLE WHEN IT IS MISSING. Table rows here are clickable —
 *                      they open the investigation modal — so without this, following a link ALSO
 *                      opens a modal behind the new tab. Nothing errors; the reader simply comes
 *                      back to a dashboard in a state they never asked for.
 */
function ext(url,label,title){
  if(!url) return '';
  return '<a class=xlink href="'+escA(url)+'" target="_blank" rel="noopener noreferrer"'
    +(title?' title="'+escA(title)+'"':'')
    +' onclick="event.stopPropagation()">'+esc(label)+' ↗</a>';
}
// What the link is actually pointing at, in the tooltip. It is NOT the commit that was scanned —
// that commit is unknown, which is the whole reason this page carries an anchor-confidence row — so
// the tooltip says branch tip, and a reader who finds unfamiliar code knows why before they file it
// as a bug in the pipeline.
// One markers-table cell: the link, or the reason there is not one. NEVER an anchor that would 404.
function markerLinkCell(x){
  const line=lineNo(x.svace_line,x.line);
  const url=markerSourceUrl(x.repo,x.branch,x.file,line);
  return url ? ext(url,'source',sourceTitle(x.repo,x.branch,x.file,line))
    : '<span class=tiny title="'+escA(whyNoLink(x.repo,x.file,line))+'">—</span>';
}
function sourceTitle(repo,branch,file,line){
  const ref=String(branch==null?'':branch).trim();
  return 'open '+repoSlug(repo)+' at '+short(file)+':'+lineNo(line)+' — the tip of the '
    +(ref?ref:'default')+' branch, NOT the commit Svace scanned, so the line may have moved';
}
const fmtTime=s=>{if(!s)return '';const d=new Date(s.replace(' ','T')+'Z');return d.toLocaleTimeString();};
function dur(s){if(s==null)return '';s=Math.round(s);
  const d=Math.floor(s/86400),h=Math.floor(s%86400/3600),m=Math.floor(s%3600/60),ss=s%60;
  const p=n=>n.toString().padStart(2,'0');
  if(d)return d+'d'+p(h)+'h';
  if(h)return h+'h'+p(m)+'m';
  if(m)return m+'m'+p(ss)+'s';
  return ss+'s';}
// The poll. Kept as the FALLBACK, not the primary: it runs only while the socket is down or has gone
// quiet (see liveLoop at the bottom). It fetches and hands off to render(), which is the same function
// the socket calls with the same document — one render path, so the two transports cannot drift into
// showing subtly different pages.
async function tick(){
  let s; try{ s=await (await fetch('api/state',{cache:'no-store'})).json(); }catch(e){ return; }
  render(s);
}
function render(s){
  DASH=s;
  stamp('updated');
  // The scan header (files walked, methods counted, ETA) belonged to the orchestrator the marker
  // ingester replaced; every one of those figures is now permanently zero. The stage banner and the
  // Run progress tile carry the real state instead, so rendering the old ones only added noise.
  // Markers, not a file scan: the backlog is the Svace report, so progress is "how many markers are
  // still status=new" rather than files walked. There is no suspector and no dedup stage any more.
  const all=s.suspicions;
  const byStatus=(st)=>all.filter(x=>x.status===st).length;
  const pending=byStatus('new')+byStatus('infra_stuck');
  const settled=all.length-byStatus('new');
  const pct=all.length?Math.round(settled/all.length*100):0;
  const proven=s.bugs.filter(b=>yes(b.red_verified)&&yes(b.green_verified)).length;
  document.getElementById('stats').innerHTML=
     card('Run progress', settled+' / '+all.length,
          '<div class=progressbar><div style="width:'+pct+'%"></div></div>'+pct+'% settled', 'wide')
    +card('Queued', byStatus('new'), 'not yet attempted')
    +card('Proven', byStatus('verified'), proven+' verified red→green')
    +card('Refuted', byStatus('false_positive'), 'claim does not hold')
    +card('By design', byStatus('by_design'), 'intentional, nothing to fix')
    +card('Unprovable', byStatus('unprovable'), 'no test would compile')
    +card('Reproduced', byStatus('reproduced'), 'real, fix not found')
    +card('Infra stuck', byStatus('infra_stuck'), 'never became testable');

  // stage banner
  const running=(s.activity||[]).some(a=>a.status==='running');
  const bn=document.getElementById('stage-banner');
  bn.className='stage'+(running?' active':(pending?'':' done'));
  document.getElementById('stage-name').textContent =
    running?'proving':(pending?'idle':'complete');
  document.getElementById('stage-detail').innerHTML =
    (all.length?'<code>'+esc(all[0].repo||'')+'</code> · ':'')+'Svace markers · one at a time under the runner lease';
  document.getElementById('current').textContent = pending
    ? (pending+' marker(s) still to settle')
    : (all.length?'every marker settled':'no markers ingested yet — POST /webhook/ingest');

  // effort panel
  const w=s.work||{};
  const hrs=(h)=>h==null?'–':(h>=10?Math.round(h)+'h':h.toFixed(1)+'h');
  const eta=(sec)=>{if(sec==null)return '–';const d=Math.floor(sec/86400),h=Math.round(sec%86400/3600);
    return d?d+'d '+h+'h':h+'h';};
  document.getElementById('work').innerHTML=
     card('Human-equivalent work', hrs(w.humanHours), 'estimated, itemised by outcome')
    +card('Machine time', hrs(w.machineHours), 'measured wall-clock on the prover')
    +card('Human FTE equivalent', w.fte!=null?w.fte+'×':'–',
          'human-eq hours ÷ machine hours')
    +card('ETA', eta(w.etaSec), 'at the rate observed on this run')
    +card('Markers settled', (w.settled||0)+' / '+(w.totalMarkers||0),
          (w.remaining||0)+' remaining');
  document.getElementById('workbasis').textContent=
    w.fteBasis!=null?('over '+w.fteBasis+' settled marker(s) · all machine time charged, including '
      +hrs(w.retryHours)+' of retries'):'';
  const hm=w.humanMin||{};
  document.getElementById('workdetail').innerHTML=
    'Human-equivalent is an ESTIMATE, charged by outcome, not a measurement — per marker: triage '
    +(hm.triage||0)+'m + assess '+(hm.assess||0)+'m, plus write-test '+(hm.write_test||0)
    +'m and verify '+(hm.verify||0)+'m when it reproduced, plus write-fix '+(hm.write_fix||0)
    +'m when it was fixed, or write-up '+(hm.rebut||0)+'m when it ended in a verdict. '
    +'Machine time is real wall-clock on the prover, charged in full: about half of it goes on '
    +'attempts that end in a retry, and a human would not get those free either. Counting only the '
    +'attempts that settled a marker would read '+(w.fteSettledOnly!=null?w.fteSettledOnly+'x':'-')
    +' instead.';

  // Verdicts — every column of the Svace report row, then what we concluded about it.
  // Severity/Checker/File/Line ARE the report; a verdict shown without them asks the reviewer to
  // judge an answer without the question. The claim column is the checker's meaning, which is what
  // the verdict is actually arguing against.
  const verdicts=s.bugs.filter(b=>(b.verdict_text||'').trim());
  // MARKERS NOBODY ARGUED, because the stage was switched off — counted, and never silently absent.
  // Most of them have no verdict_text and so drop out of the table above; without this line the only
  // symptom of a run made cheap on purpose is a verdict count that is quietly short, which is
  // indistinguishable from a half-dead model endpoint.
  const skipped=s.bugs.filter(b=>String(b.verdict_status||'')==='skipped').length;
  const skipmark=b=>String(b.verdict_status||'')==='skipped';
  // reuses `settled` from the stats block above — re-declaring it with const here was a SyntaxError
  // that killed the whole inline script and left the page completely blank
  document.getElementById('verdictcount').textContent=
    verdicts.length+' of '+settled+' settled markers · '+all.length+' total'
    +(skipped?' · '+skipped+' NOT ARGUED (verdict stage switched off)':'');
  // every kind gets a colour; an unmapped one must stay visible rather than blend into the amber default
  const kindColour={'true-positive':'var(--hi2)','true-positive-unfixed':'var(--amber)',
    'false-positive':'var(--hi)','by-design':'var(--hi2)','unprovable':'var(--amber)',
    'needs-review':'var(--red)','undetermined':'var(--red)'};
  document.getElementById('verdicts').innerHTML=verdicts.length? tbl(
    ['severity','checker','file','line','category','anchor','claim','kind','verdict'],
    verdicts.map(v=>[
      '<span class=sev-'+esc(v.severity||'')+'>'+esc(v.svace_severity||v.severity||'?')+'</span>',
      '<span class=tiny>'+esc(v.svace_checker||'')+'</span>',
      pkg(v.file)+(v.marker_orphaned?'<div class=tiny style="color:var(--red)">marker row gone — re-ingested since</div>':''),
      '<span class=tiny>'+num(v.svace_line!=null?v.svace_line:v.line)+'</span>',
      '<span class=tiny>'+esc(v.category||'')+'</span>',
      '<span class=tiny>'+esc(v.anchor?v.anchor+'()':(v.anchor_status||''))+'</span>',
      // NO max-width HERE, AND NO min-width ON THE VERDICT BELOW. Both used to be inline, and at 203
      // rows of real model prose both cells reached their intrinsic minimum and the table demanded
      // 2266px inside a 1355px box — `kind` and `verdict` were laid out 911px past the right-hand edge
      // for every row. How wide a column is belongs to the stylesheet, which can size it as a share of
      // the table it is actually in; see .col-claim / .col-verdict in style.css.
      '<div class=tiny>'+esc(v.description||'')+'</div>',
      // A skipped row that still HAS text is the exhausted-build route: its wording is composed from
      // the run, not argued, and it is identical to what a dead endpoint leaves behind. The kind pill
      // says so rather than presenting `undetermined` as a conclusion somebody reached.
      (skipmark(v)?'<span class=pill-state style="background:var(--dim);color:#101418">not argued</span>'
        +'<div class=tiny style="color:var(--dim)">'+esc(v.verdict_kind||'?')+'</div>'
        :'<span class=pill-state style="background:'+(kindColour[v.verdict_kind]||'var(--amber)')+';color:#101418">'
          +esc(v.verdict_kind||'?')+'</span>')
        // A verdict somebody has argued with, marked in the panel where verdicts are read.
        +commentMark(v.suspicion_key),
      (skipmark(v)?'<div class=tiny style="color:var(--amber)">the verdict stage was switched off — '
        +'composed from the run, nobody argued this marker</div>':'')
        +'<div style="white-space:pre-wrap">'+esc(v.verdict_text)+'</div>']),
    // AND THE ROW OPENS. This was the ONE table on the page called without a row-click: 0 of 202
    // verdict rows carried `class=clickrow onclick=...` against 282/282 markers and 202/202 bugs. With
    // the argument too wide for its cell that left NO route to a verdict at all except finding the
    // same marker again in the table below — so the panel built to show the conclusion was the one
    // panel you could not read a conclusion from.
    i=>"showBug(DASH.__verdicts["+i+"])")
    : empty(skipped?('no verdicts — the verdict stage is switched off for this run, so '+skipped
        +' marker(s) settled with no argument written')
      :'no verdicts yet — every settled marker gets one, whatever the outcome');
  DASH.__verdicts=verdicts;

  // markers table
  document.getElementById('suscount').textContent=all.length+' rows';
  // The `code` column is the whole point of the table for anybody deciding whether a claim holds:
  // the other six columns describe a line of somebody else's source, and this is the one that opens
  // it. The row itself still opens the investigation modal — see ext() for why a click on the link
  // does not do both.
  document.getElementById('suspicions').innerHTML=all.length? tbl(
    ['sev','checker','category','file:line','anchor','status','code'],
    all.map(x=>['<span class=sev-'+esc(x.severity)+'>'+esc(x.svace_severity||x.severity)+'</span>',
      '<span class=tiny>'+esc(x.svace_checker||'')+'</span>', esc(x.category),
      pkg(x.file)+'<span class=tiny>:'+num(x.svace_line!=null?x.svace_line:x.line)+'</span>',
      '<span class=tiny>'+esc(x.anchor?x.anchor+'()':(x.anchor_status||''))+'</span>',
      '<span class=st-'+esc(x.status)+'>'+esc(x.status)+'</span>'
      +((x.note||'').trim()?'<div class=dedupnote>'+esc(String(x.note).slice(0,180))+'</div>':'')
      // IN THE STATUS CELL, not in a column of its own. @see commentMark
      +commentMark(x.dedup_key),
      markerLinkCell(x)]),
      i=>"showInvestigation(DASH.suspicions["+i+"])") : empty('no markers ingested yet');
  DASH.__all=all;

  // bugs
  document.getElementById('bugcount').textContent=s.bugs.length+' rows';
  document.getElementById('bugs').innerHTML = s.prover_built ? (s.bugs.length? tbl(
    ['file','title','red','green','state'],
    s.bugs.map(x=>['<span class=tiny>'+esc(short(x.file))+'</span>',esc(x.title),
      redgreen(x.red_verified),redgreen(x.green_verified),esc(x.state)]),
      i=>"showBug(DASH.bugs["+i+"])") : empty('no proven bugs yet'))
    : '<div class=soon>Prover not built yet — lights up when the suspicion→test→red→fix→green loop is wired.</div>';

  // activity
  document.getElementById('activity').innerHTML=tbl(['','flow','file','status','took'],
    s.activity.map(a=>['<span class="dot d-'+esc(a.status)+'"></span>'+fmtTime(a.started),esc(a.wf),
      '<span class=tiny>'+esc(a.file)+'</span>','<span class=st-'+esc(a.status)+'>'+esc(a.status)+'</span>',dur(a.dur)]));
}
// Metric tile in the same shape the improve-java-tests-n8n dashboard uses: label, big value, note.
function card(k,v,d,cls){return '<div class="card'+(cls?' '+cls:'')+'"><div class=k>'+k+'</div>'
  +'<div class=v>'+v+'</div><div class=d>'+(d||'')+'</div></div>';}
// n8n Data Table `number` columns come back as REAL, so a line number arrives as 26.0 and a source
// location reads "Foo.java:26.0". Render whole numbers as whole numbers.
function num(x){const n=Number(x);return (x===null||x===undefined||x===''||isNaN(n))?'?':String(Math.round(n));}
function redgreen(v){return v?'<span class=st-green>●</span>':'<span class=st-red>○</span>';}
// lightweight single-pass Java highlighter (esc first, then one alternation so keywords inside
// comments/strings are never separately matched -> no broken nesting)
function hlJava(code){
  return esc(String(code==null?'':code)).replace(
    /(\/\*[\s\S]*?\*\/|\/\/[^\n]*)|("(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*')|(@\w+)|\b(public|private|protected|static|final|abstract|class|interface|enum|record|extends|implements|new|return|if|else|for|while|switch|case|do|break|continue|throw|throws|try|catch|finally|void|int|long|double|float|boolean|char|byte|short|this|super|null|true|false|instanceof|package|import|synchronized|volatile|transient|native|default)\b|\b([A-Z][A-Za-z0-9_]*)\b|\b(\d[\d._]*[LlFfDd]?)\b/g,
    (m,com,str,ann,kw,typ,num)=> com?'<span class=j-com>'+com+'</span>' : str?'<span class=j-str>'+str+'</span>'
      : ann?'<span class=j-ann>'+ann+'</span>' : kw?'<span class=j-kw>'+kw+'</span>'
      : typ?'<span class=j-type>'+typ+'</span>' : '<span class=j-num>'+num+'</span>');
}
// `· dN name(args)` lines are call-TREE nodes emitted by the sub-investigators. Render them nested by
// depth with a connector, so the exploration reads as the tree it actually is.
function treeNode(depth, kind, label, detail){
  const pad = 10 + depth*20;
  return '<div class="tnode" style="padding-left:'+pad+'px">'
    + '<span class="tdep tdep-'+Math.min(depth,3)+'">d'+depth+'</span>'
    + '<span class=tglyph>'+(kind==='ret'?'↩':'↳')+'</span>'
    + '<code class=tname>'+esc(label)+'</code>'
    + (detail?'<span class=targs>'+esc(detail)+'</span>':'') + '</div>';
}
// a candidate may be a signature string OR an object from the resolver's fallback path
function candLabel(c){
  if(c==null) return '';
  if(typeof c==='string') return c;
  return c.signature || c.qualifiedSignature || c.name ||
         ((c.file?short(c.file):'')+(c.line?(':'+c.line):'')) || JSON.stringify(c).slice(0,120);
}
function renderStepInto(o){
  const d = Number(o.call_depth||o.depth||1);
  const pad = 10 + (d-1)*20;
  let h='<div class="tstep" style="margin-left:'+pad+'px">';
  h+='<div class=tstep-head><span class="tdep tdep-'+Math.min(d,3)+'">depth '+d+'</span>'
    +'<code>'+esc(o.stepped_into||'')+'</code>'
    +(o.lines?'<span class=tiny> '+esc(o.file||'')+':'+esc(o.lines)+'</span>':'')
    +'<span class="tbadge">sub-agent</span>'
    +((o.defects_reported||0)>0?'<span class="tbadge tbad">'+o.defects_reported+' defect(s)</span>':'')+'</div>';
  if(o.question) h+='<div class=tq><b>why</b> '+esc(o.question)+'</div>';
  if(Array.isArray(o.chain)&&o.chain.length>1)
    h+='<div class=tchain>'+o.chain.map(x=>esc(String(x).split('(')[0])).join(' <span class=tarrow>↓</span> ')+'</div>';
  if(o.contract) h+='<div class=tcontract>'+esc(o.contract)+'</div>';
  if(o.caller_risks&&!/^none/i.test(o.caller_risks)) h+='<div class=trisk><b>risk to caller</b> '+esc(o.caller_risks)+'</div>';
  return h+'</div>';
}
function fmtToolResult(s){
  let o=null;
  try{ o=JSON.parse(s.trim()); }catch(e){ o=repairJson(s.trim()); }   // transcripts truncate: repair it
  if(!o||typeof o!=='object') return '<div class=d-meta>'+esc(s.slice(0,3000))+'</div>';
  if(o && o.stepped_into) return renderStepInto(o);
  if(o && o.call_depth && !o.stepped_into && !Array.isArray(o.reports)){
    const d=Number(o.call_depth), pad=10+(d-1)*20;
    const why = o.refused ? 'refused — retrieval request'
              : o.ambiguous ? 'ambiguous — needs a precise symbol'
              : o.resolved_to ? 'no sub-agent — ' + o.resolved_to
              : (o.found===false ? 'unresolved' : (o.note ? 'no sub-agent needed' : ''));
    let h='<div class="tstep tstep-flat" style="margin-left:'+pad+'px"><div class=tstep-head>'
      +'<span class="tdep tdep-'+Math.min(d,3)+'">depth '+d+'</span>'
      +'<code>'+esc(o.symbol||o.dug_into||'')+'</code>'
      +(why?'<span class="tbadge">'+esc(why)+'</span>':'')+'</div>';
    if(o.note) h+='<div class=tcontract>'+esc(o.note)+'</div>';
    if(Array.isArray(o.candidates)&&o.candidates.length)
      h+='<div class=tchain>'+o.candidates.map(c=>esc(candLabel(c))).join('<br>')+'</div>';
    if(o.source) h+='<pre class="dlg jcode">'+hlJava(o.source)+'</pre>';
    return h+'</div>';
  }
  if(o && o.dug_into && Array.isArray(o.reports)){          // polymorphic call -> the overrides that run
    const d=Number(o.call_depth||o.depth||1), pad=10+(d-1)*20;
    let h='<div class="tstep" style="margin-left:'+pad+'px"><div class=tstep-head>'
      +'<span class="tdep tdep-'+Math.min(d,3)+'">d'+d+'</span><code>'+esc(o.dug_into)+'</code>'
      +'<span class="tbadge">polymorphic · '+esc(o.implementations_found||0)+' impl(s)</span></div>';
    if(o.note) h+='<div class=tchain>'+esc(o.note)+'</div>';
    o.reports.forEach(r=>{ if(!r) return;
      h+='<div class=timpl><code>'+esc(r.signature||r.file||'?')+'</code>'
        +(r.contract?'<div class=tcontract>'+esc(r.contract)+'</div>':'')
        +((r.defects_found||0)>0?'<span class="tbadge tbad">'+r.defects_found+' defect(s)</span>':'')+'</div>'; });
    return h+'</div>';
  }
  let h='';
  const codeF = o.source || o.content || o.method_src;   // the actual Java a read/goto returned
  if(codeF) h+='<pre class="dlg jcode">'+hlJava(codeF)+'</pre>';
  if(o.note) h+='<div class=d-note>'+esc(o.note)+'</div>';
  if(Array.isArray(o.matches)) h+='<pre class="dlg jcode">'+o.matches.map(x=>hlJava(x)).join('\n')+'</pre>';
  if(o.resolved_to) h+='<div class=d-note>'+esc(o.resolved_to)+'</div>';
  const skip={source:1,content:1,method_src:1,skeleton:1,note:1,matches:1,resolved_to:1};
  const meta={}; for(const k in o) if(!skip[k]) meta[k]=o[k];
  if(Object.keys(meta).length) h+='<div class=d-meta>'+esc(JSON.stringify(meta))+'</div>';
  return h || '<div class=d-meta>'+esc(s.slice(0,600))+'</div>';
}
// The VERDICT is a JSON object, not prose. Render each suspicion as a card: severity, where it
// lives, what breaks, and the concrete evidence.
function sevPill(v){ const x=(v||'').toLowerCase();
  return '<span class="vsev vsev-'+esc(x||'none')+'">'+esc(x||'?')+'</span>'; }
// the model often ends a long verdict one brace short; repair it rather than showing raw JSON
function repairJson(t){
  const a=t.indexOf('{'); if(a<0) return null;
  const body=t.slice(a), b=body.lastIndexOf('}');
  if(b>0){ try{ return JSON.parse(body.slice(0,b+1)); }catch(e){} }
  let curly=0,square=0,inStr=false,esc2=false,out='';
  for(const ch of body){
    out+=ch;
    if(esc2){esc2=false;continue;}
    if(ch==='\\'){esc2=true;continue;}
    if(ch==='"'){inStr=!inStr;continue;}
    if(inStr) continue;
    if(ch==='{')curly++; else if(ch==='}')curly--;
    else if(ch==='[')square++; else if(ch===']')square--;
  }
  if(inStr) out+='"';
  while(square-->0) out+=']';
  while(curly-->0) out+='}';
  try{ return JSON.parse(out); }catch(e){ return null; }
}
function renderVerdict(raw){
  const t=String(raw||'').trim();
  if(!t) return '<div class=vempty>(no verdict text — the model returned nothing)</div>';
  const o=repairJson(t);
  const list = o && (Array.isArray(o.defects) ? o.defects
                   : (Array.isArray(o.suspicions) ? o.suspicions : null));
  if(!list) return '<div class=d-prose>'+esc(t)+'</div>';
  let head='';
  if(o.summary) head+='<div class=vsummary><b>summary</b> '+esc(o.summary)+'</div>';
  if(o.caller_risks && !/^none/i.test(o.caller_risks))
    head+='<div class=vrisk><b>risk to caller</b> '+esc(o.caller_risks)+'</div>';
  if(!list.length) return head+'<div class=vempty>no defects reported for this method</div>';
  return head+list.map(x=>{
    const loc=[x.file?short(x.file):'', x.line?('line '+x.line):''].filter(Boolean).join(' · ');
    let h='<div class=vcard>';
    h+='<div class=vtop>'+sevPill(x.severity)+(x.category?'<span class=vcat>'+esc(x.category)+'</span>':'')
      +'<span class=vtitle>'+esc(x.title||'(untitled)')+'</span></div>';
    if(x.file||x.method||x.line) h+='<div class=vloc>'+(x.file?'<span class=tiny>'+esc(x.file)+'</span>':'')
      +(x.method?' <code>'+esc(x.method)+'()</code>':'')+(x.line?' <span class=tiny>:'+esc(x.line)+'</span>':'')+'</div>';
    if(x.description) h+='<div class=vdesc>'+esc(x.description)+'</div>';
    if(x.evidence) h+='<div class=vev><b>evidence</b> '+hlJava(x.evidence)+'</div>';
    return h+'</div>';
  }).join('');
}
// ------------------------------------------------------------------------------------------------
// TEAM GUIDANCE — the accumulated criticism, which until now nothing read.
//
// THE STORE HAS BEEN WRITTEN TO AND NEVER READ. Every settled marker appends its critiques —
// attributed, quotable, each under a stable `kind` — to feedback/gepa-feedback.jsonl, and the only
// way to see any of it was to cat the file on the host. A write-only diagnostic is a diagnostic
// nobody has.
//
// AND THE FOUR EMPTY STATES ARE THE FEATURE. The store is opt-in and OFF by default, so "no rows"
// is overwhelmingly likely to mean "nobody switched it on" rather than "nothing was wrong". The
// server decides which of off / waiting / clean / unreadable it is and hands over the sentence; this
// function renders that sentence FIRST and unconditionally. There is deliberately no branch here
// that draws an empty list.
// ------------------------------------------------------------------------------------------------
let GUIDE={state:'',markers:{}};
async function renderGuidance(){
  const el=document.getElementById('guidance'); if(!el) return;
  const d=await jget('api/feedback');
  if(!d){ // the fetch itself failed — say so rather than leaving the last state on screen
    el.innerHTML='<div class="fb-state fb-unreadable"><b>The guidance panel could not be loaded</b>'
      +'<div class=fb-hint>api/feedback did not answer. The rest of the page is unaffected.</div></div>';
    return; }
  GUIDE=d;
  const items=d.guidance||[];
  document.getElementById('guidancecount').textContent = items.length
    ? items.length+' distinct complaint(s) · '+d.critiques+' in total across '+d.records
      +' recorded prove(s)'
    : '';
  let h='<div class="fb-state fb-'+esc(d.state)+'"><b>'+esc(d.headline)+'</b>'
    +'<div class=fb-hint>'+esc(d.hint)
    // A count read off a file the reader has not finished is INCOMPLETE, not wrong. Saying so is
    // cheaper than the alternative, which is somebody quoting a partial total as a run's evidence.
    +(d.complete===false?' <b style="color:var(--amber)">Still reading the store — these counts are '
      +'partial.</b>':'')+'</div></div>';
  h+=items.map((g,i)=>{
    // WHICH PROMPT. The whole loop: a complaint recurs, you open that file, you change the wording,
    // the complaint stops. Named per stage, because the stage that CAUSED a complaint is regularly
    // not the one that noticed it.
    const prompts=(g.prompts||[]).map(p=>p.file
      ? '<span class=fb-prompt>'+esc(p.file)+'</span>'
      : '<span class=tiny>'+esc(p.stage)+' (no prompt file)</span>').join(' · ');
    const seen=(g.recent||[]).map((m,j)=>'<span class=fb-marker onclick="openCritiquedMarker('+i+','+j+')">'
      +esc(short(m.file))+(m.line?':'+esc(m.line):'')+'</span>').join('');
    const more=g.markers>(g.recent||[]).length
      ? '<span class=fb-more>+'+(g.markers-(g.recent||[]).length)+' more</span>' : '';
    return '<div class=fb-kind data-kind="'+esc(g.kind)+'">'
      +'<div class=fb-count>'+esc(g.count)+'</div>'
      +'<div><div class=fb-kindname>'+esc(g.kind)+'</div>'
      // BOTH NUMBERS. Eleven occurrences from nine markers and eleven from one are different facts:
      // the second is a bad marker, not a prompt worth rewriting.
      +'<div class=fb-meta>raised '+esc(g.count)+'× across <b>'+esc(g.markers)+'</b> marker(s)'
      +' · about the <b>'+esc((g.stages||[]).join(', '))+'</b> stage'
      +' · noticed by '+esc((g.sources||[]).join(', ')||'the pipeline')
      +'<br>fix it in '+(prompts||'<span class=tiny>no prompt file</span>')+'</div>'
      +(g.example?'<div class=fb-example>“'+esc(g.example)+'”</div>':'')
      +(seen?'<div class=fb-markers>'+seen+more+'</div>':'')
      +'</div></div>';
  }).join('');
  el.innerHTML=h;
}
// A marker chip on the guidance panel opens that marker's own modal, which is where the test, the
// diff and now the criticism all are. The row may not be in DASH.suspicions — the store accumulates
// ACROSS RUNS and a marker from an earlier ingest can be gone from the table — so a stub carrying the
// dedup_key is passed through rather than nothing at all.
function openCritiquedMarker(i,j){
  const g=(GUIDE.guidance||[])[i]; if(!g) return;
  const ref=(g.recent||[])[j]; if(!ref) return;
  const su=(DASH.suspicions||[]).find(x=>x.dedup_key===ref.key);
  showInvestigation(su||{dedup_key:ref.key,file:ref.file,svace_line:ref.line,
    svace_checker:ref.checker,status:'',note:'this marker is not in the current backlog — it comes '
      +'from the accumulated feedback store, which spans earlier runs'});
}
// ONE MARKER'S OWN CRITICISM, on its marker tab.
//
// Lazy, exactly like the source window above it: the tab renders immediately from what is already
// known and the complaints arrive a moment later, rather than holding the modal on a second fetch.
//
// THE THREE OUTCOMES ARE THREE DIFFERENT SENTENCES, and that is the point. "This marker produced no
// complaints" is a judgement about the marker; "nothing was ever recorded" is a statement about the
// deployment. Rendering the second as the first tells a reviewer the pipeline approved of a test
// nobody ever scored.
async function loadMarkerCritiques(su){
  const el=document.getElementById('markercrit'); if(!el) return;
  const key=(su&&(su.dedup_key||su.suspicion_key))||'';
  const d=await jget('api/feedback/marker?key='+encodeURIComponent(key));
  if(!d){ el.innerHTML='<div class=empty>criticism unavailable — api/feedback/marker did not answer</div>';
    return; }
  const rows=d.critiques||[];
  if(!rows.length){
    el.innerHTML='<div class="fb-state fb-'+esc(d.state)+'">'
      +'<b>'+(d.enabled?'No complaints recorded for this marker':esc(d.headline))+'</b>'
      +'<div class=fb-hint>'+(d.enabled
        ? 'The feedback store is on and holds '+esc(d.records)+' settled marker(s). Nothing in it is '
          +'filed against this one.'
        : esc(d.hint))+'</div></div>';
    return; }
  el.innerHTML=rows.map(c=>'<div class=fb-crit>'
    +'<div class=fb-crithead><span class=fb-kindname>'+esc(c.kind)+'</span>'
    +'<span>about the <b>'+esc(c.stage)+'</b> stage · noticed by '+esc(c.source)+'</span>'
    +(c.written_at?'<span>'+esc(String(c.written_at).slice(0,10))+'</span>':'')
    // THE RECURRENCE, CARRIED HERE TOO. It is the one thing a reader of a single marker cannot see
    // from the marker: the same complaint against forty others is what makes this one worth acting on.
    +(c.kind_count>1?'<span style="color:var(--amber)">seen '+esc(c.kind_count)+'× across '
      +esc(c.kind_markers)+' marker(s)</span>':'<span>first of its kind so far</span>')
    +(c.prompt&&c.prompt.file?'<span>fix it in <span class=fb-prompt>'+esc(c.prompt.file)
      +'</span></span>':'')
    +'</div><div class=fb-text>'+esc(c.text)+'</div></div>').join('');
}
// ------------------------------------------------------------------------------------------------
// HUMAN COMMENTS — a person's judgement about one artifact, written on the artifact.
//
// THIS IS NOT THE FEEDBACK STORE ABOVE, and the difference is the whole reason it exists. That store
// records what the PIPELINE thinks: the realness scorer counts "9 stub/mock setup(s) for
// collaborators", files it under a stable kind, and the guidance panel groups it. It is a machine's
// opinion of a machine's output and it is very good at the things a machine can decide.
//
// "I DON'T LIKE TOO MANY MOCKS, THIS ONE AND THIS ONE ARE REDUNDANT" is not one of them. WHICH two
// mocks is a judgement about a specific test, made by a person reading it, and no scorer will ever
// produce it. So this channel runs beside the machine's, into its own store, and the panels read both.
//
// WHERE THE BOX IS, AND WHY IT IS NOT ONE BOX ON ONE PAGE. A comment is only actionable if it is
// attributed to the stage whose PROMPT would have to change, and the person writing it should not have
// to work that out from a dropdown. So the box lives on the tab whose output is being criticised —
// too many mocks is the reproducer's prompt, a patch that reverts more than it fixes is the fixer's —
// and the tab fills the stage in. The vocabulary is the one the machine critiques already use
// (reproducer / fixer / fix_skeptic / pr_maker / verdict), so the two channels are countable together.
//
// THE THREE THINGS THAT KILL A FEATURE LIKE THIS, each handled below and each with a browser test:
//   - it needs a mouse. See openModal()/setTabs() above and the tab order here.
//   - the page eats the draft. This dashboard repaints itself every three seconds for hours; DRAFTS
//     below is what makes a repaint survivable, and render() never touches the modal.
//   - a failed post looks like a successful one. postComment() clears the box ONLY on a 2xx, and says
//     what went wrong when it is not.
// ------------------------------------------------------------------------------------------------

/** The marker whose modal is open, and the comments last fetched for it. @see showInvestigation */
let MODAL={key:'',comments:null};

/**
 * THE COMMENT INDEX — how many human comments each marker carries.
 *
 * <p>Held here rather than fetched per row because it is read by render(), which repaints 282 rows on
 * every poll. `ok:null` is "not asked yet" and is deliberately distinct from "asked, and nothing is
 * commented": a mark that has not loaded yet must not make a marker look uncommented, so nothing is
 * drawn either way and the marks appear when the answer does.
 */
let COMMENTS={ok:null,counts:{},total:0};

/**
 * WHAT IS TYPED BUT NOT YET FILED, keyed by marker and stage.
 *
 * <p>THE POLL IS THE REASON. The page re-renders on a three-second beat and on every socket push, and
 * a reviewer writing a paragraph about a reproducer is typing straight through several of them. The
 * modal's body is also thrown away and rebuilt whenever the reader switches tabs to check the diff
 * they are about to complain about. Losing the paragraph to either is how a comment box gets used
 * exactly once — so the text lives here, outside the DOM, and every render of the box restores it.
 */
const DRAFTS=new Map();

/** Who is writing. Remembered across markers, because nobody wants to type their name 40 times. */
let COMMENT_AUTHOR=(function(){try{return localStorage.getItem('fsm.comment.author')||'';}
  catch(e){return '';}})();

/**
 * THE VOCABULARY AND THE BOUNDS, SERVED RATHER THAN RESTATED.
 *
 * <p>Every comment answer carries a header block: the kinds the machine's critiques already use, the
 * five stages, and the length limits. It is read off the wire and never copied into this file, because
 * a second copy of a vocabulary drifts — and the specific way it drifts here is that a kind added to
 * the pipeline's list never reaches the human's, after which the two channels stop being countable
 * together, which is the entire reason a human comment is allowed to carry a kind at all.
 */
let COMMENT_KINDS=[], COMMENT_LIMITS={};
function readCommentHeader(d){
  if(!d) return;
  if(Array.isArray(d.known_kinds)) COMMENT_KINDS=d.known_kinds.map(String);
  if(d.limits&&typeof d.limits==='object') COMMENT_LIMITS=d.limits;
  fillKindList();
}
// The list is filled IN PLACE as well as at render time. The box is drawn the moment a tab opens and
// the vocabulary arrives with whichever answer comes back first — so without this, opening a marker
// before the index has landed gives a kind field that offers nothing, once, for no reason the reader
// could ever work out.
function fillKindList(){
  const list=document.getElementById('cmtkinds');
  if(!list||list.children.length||!COMMENT_KINDS.length) return;
  list.innerHTML=COMMENT_KINDS.map(k=>'<option value="'+escA(k)+'"></option>').join('');
}

/**
 * The five stages a comment can be about, in the words the box uses.
 *
 * The KEY is the wire value — {@code Critique}'s own vocabulary, which is what the feedback store, the
 * guidance panel and the prompt files are all keyed by. The value is what a person reads.
 */
const STAGE_WORDS={
  verdict:['this verdict','the argument the pipeline made about this marker'],
  reproducer:['this reproducer','the test the pipeline wrote to prove the marker'],
  fixer:['this fix','the patch the fixer produced'],
  fix_skeptic:['this review','what the fix skeptic said about the patch'],
  pr_maker:['this PR decision','what the PR maker decided to do with the fix']};

// ONE DRAFT PER MARKER AND STAGE, and the separator is written as an ESCAPE, never as the character
// itself. It WAS a raw NUL byte here for about an hour: valid JavaScript, correct at runtime, every
// browser test green - and `file` reported app.js as application/octet-stream, so `grep` skipped the
// whole page as a binary file and answered every search over it with silence. A source file that
// tooling refuses to read is a source file nobody can search, and it fails in the one direction that
// makes an empty answer look like an answer.
const draftKey=(key,stage)=>String(key)+'\u0000'+String(stage);

/**
 * THE BOX, on one tab, pre-attributed to that tab's stage.
 *
 * <p>Returns '' when there is no marker key: a modal opened on something that is not a marker has
 * nothing to file a comment against, and an inert box that silently fails to post would be worse than
 * no box. The author field comes FIRST in the DOM so the focus order reaches it before the text —
 * whoever fills it in once will tab straight past it after that.
 */
function commentBox(key,stage){
  if(!key) return '';
  const w=STAGE_WORDS[stage]||[stage,stage];
  const draft=DRAFTS.get(draftKey(key,stage))||{};
  const max=Number(COMMENT_LIMITS.text_max)||0;
  return '<div class=cmt id=cmt data-key="'+escA(key)+'" data-stage="'+escA(stage)+'">'
    +'<div class=diff-hdr>Your comment on '+esc(w[0])+'</div>'
    +'<div class=cmt-why>A judgement of your own about '+esc(w[1])+' — what the pipeline cannot score '
    +'about itself. It is filed against this marker and attributed to the <b>'+esc(stage)+'</b> stage, '
    +'which is the prompt that would have to change.</div>'
    +'<div class=cmt-form>'
    +'<div class=cmt-who-row>'
    +'<input id=cmtauthor class=cmt-author type=text placeholder="your name" aria-label="your name" '
      +'value="'+escA(COMMENT_AUTHOR)+'" oninput="commentAuthorTyped(this)">'
    // THE KIND IS OPTIONAL AND IT IS THE THING THAT MAKES THIS COUNTABLE. Pick one the pipeline
    // already files critiques under and this comment adds to the same total the guidance panel is
    // grouping; leave it blank, or type your own, and it is still a comment. The list is whatever the
    // server said it was — never a copy of the vocabulary kept here. @see COMMENT_KINDS
    +'<input id=cmtkind class=cmt-kind type=text list=cmtkinds aria-label="kind (optional)" '
      +'placeholder="kind (optional)" value="'+escA(draft.kind||'')+'" oninput="commentKindTyped(this)">'
    +'<datalist id=cmtkinds>'
      +COMMENT_KINDS.map(k=>'<option value="'+escA(k)+'"></option>').join('')+'</datalist>'
    +'</div>'
    +'<textarea id=cmttext class=cmt-text rows=3 aria-label="your comment on '+escA(w[0])+'" '
      +(max?'maxlength='+max+' ':'')
      +'placeholder="e.g. I don&#39;t like too many mocks, this one and this one are redundant" '
      +'oninput="commentTyped(this)" onkeydown="commentKeydown(event)">'+esc(draft.text||'')+'</textarea>'
    +'<div class=cmt-actions>'
      +'<button id=cmtpost type=button class=cmt-post onclick="postComment()">Post comment</button>'
      +'<span class=tiny>Ctrl+Enter (⌘+Enter) posts it. It is recorded under your name and is visible '
      +'to everyone reading this marker.</span></div>'
    +'<div id=cmterr class=cmt-error hidden></div>'
    +'<div id=cmtnote class=cmt-note hidden></div>'
    +'</div>'
    +'<div id=cmtlist class=cmt-list><div class=empty>loading…</div></div></div>';
}
/** Every keystroke, kept outside the DOM. @see DRAFTS */
function commentTyped(el){ draftOf(el).text=el.value; }
function commentKindTyped(el){ draftOf(el).kind=el.value; }
/** The draft this control belongs to, created on first use. */
function draftOf(el){
  const box=el.closest('.cmt');
  const k=draftKey(box?box.dataset.key:'',box?box.dataset.stage:'');
  if(!DRAFTS.has(k)) DRAFTS.set(k,{text:'',kind:''});
  return DRAFTS.get(k);
}
function commentAuthorTyped(el){
  COMMENT_AUTHOR=el.value;
  try{localStorage.setItem('fsm.comment.author',el.value);}catch(e){}
}
// Ctrl+Enter / ⌘+Enter, because a plain Enter has to stay a newline: this is a paragraph, not a search
// box, and a comment that files itself half-written is a comment somebody has to retract.
function commentKeydown(e){
  if((e.ctrlKey||e.metaKey)&&e.key==='Enter'){ e.preventDefault(); postComment(); }
}
/**
 * ONE COMMENT AS IT ARRIVES FROM THE STORE — ONE NAME PER FIELD, AND NO ALTERNATIVES.
 *
 * <p>These are exactly the names `MarkerComment.toMap()` puts on the wire, and reading a second
 * spelling of any of them was the tolerance that made this feature's two halves able to drift apart in
 * silence: a field renamed on the server falls through pick() to '' and renders BLANK — no error, no
 * 404, a browser suite that stays green and a deployment showing comments with no author or no date.
 * ThePageAndTheApiAgreeOnEveryFieldNameTest reads this function out of the shipped file and checks
 * every name against the record's own map, so a rename on either side is a red build rather than a
 * blank column.
 */
function asComment(o){
  o=o||{};
  const pick=(...k)=>{for(const x of k){const v=o[x];
    if(v!==undefined&&v!==null&&String(v).trim()!=='')return String(v);} return '';};
  return {id:pick('id'),
    key:pick('dedup_key'),
    stage:pick('stage'),
    kind:pick('kind'),
    // The server never sends a blank one — CommentService substitutes MarkerComment.ANONYMOUS — so
    // this is a rendering default and not a second spelling: an empty byline is worse than the word.
    author:pick('author')||'anonymous',
    text:pick('text'),
    when:pick('created_at'),
    retracted:o.retracted===true};
}
/** The array of comments in a response, or null when the answer is a shape this page cannot read. */
function commentsOf(d){
  if(Array.isArray(d)) return d;
  for(const k of ['comments','items','recent']) if(Array.isArray(d[k])) return d[k];
  return null;
}
/**
 * THE PER-MARKER COUNTS, AS THE SERVER COUNTED THEM — or null when the answer cannot be read.
 *
 * <p>It does NOT fall back to tallying the comments in the response, and that is the point rather than
 * a simplification. `api/comments` is a PAGE, bounded at PAGE_MAX; a tally of it marks the markers whose
 * comments happen to be recent and leaves every other commented marker looking exactly like a marker
 * nobody has written about — which is this feature's own question answered wrongly, in silence, and
 * only once the store is big enough to be worth asking. `api/comments/index` counts the whole table in
 * one GROUP BY, so there is no page here to fall off the end of.
 */
function countsOf(d){
  const c=d&&d.counts;
  if(!c||typeof c!=='object'||Array.isArray(c)) return null;
  const out={};
  for(const k in c){ const n=Number(c[k]); if(n>0) out[k]=n; }
  return out;
}
const fmtWhen=s=>{if(!s)return '';const d=new Date(String(s).replace(' ','T'));
  return isNaN(d.getTime())?String(s):d.toLocaleString();};
/**
 * THE COMMENTS ALREADY ON THIS MARKER — newest first, each with its stage, its author and its time.
 *
 * <p>All of the marker's comments on every tab, not just the ones about the tab that is open: a
 * reviewer reading the fix has to be able to see that somebody has already objected to the reproducer
 * it is built on. Only the BOX's attribution follows the tab.
 */
function renderComments(list){
  const el=document.getElementById('cmtlist'); if(!el) return;
  const rows=(list||[]).filter(c=>!c.retracted);
  if(!rows.length){
    el.innerHTML='<div class=cmt-none>No one has commented on this marker yet. What goes here is a '
      +'judgement the pipeline cannot make about itself — that a test mocks away the thing it claims '
      +'to prove, or that a verdict argues against the wrong checker.</div>';
    return;
  }
  el.innerHTML='<div class=cmt-count>'+rows.length+' comment'+(rows.length===1?'':'s')
    +' written by people about this marker · newest first</div>'
    +rows.map(c=>'<div class=cmt-one>'
      // WHO SAID IT, ON EVERY ROW. A few centimetres below this list the pipeline's OWN complaints are
      // rendered — same typeface, also headed by a kind, also attributed to a stage, and each of them
      // saying "noticed by <scorer>". The two are different kinds of statement and the difference
      // decides what the reader does: a scorer's count can be recomputed, a colleague's judgement
      // about which two mocks are redundant cannot. Putting it on the ROW rather than only in the
      // heading is deliberate — a reader scans rows, and a row quoted into a message loses the
      // heading altogether.
      +'<div class=cmt-head><b class=cmt-who>'+esc(c.author)+'</b>'
      +'<span class=cmt-src>a person</span>'
      +'<span class=cmt-stage>about the <b>'+esc(c.stage||'marker')+'</b> stage</span>'
      +(c.kind?'<span class=cmt-kind>'+esc(c.kind)+'</span>':'')
      +'<span class=cmt-when>'+esc(fmtWhen(c.when))+'</span></div>'
      +'<div class=cmt-body>'+esc(c.text)+'</div></div>').join('');
}
/**
 * Fetch one marker's comments.
 *
 * <p>A FAILED READ IS ITS OWN STATE and never an empty list — the same rule the guidance panel is
 * built on. "Nobody has commented on this" is a fact about the marker; "the comment store did not
 * answer" is a fact about the deployment, and a reviewer who reads the second as the first concludes
 * that a test nobody has looked at has been looked at and approved.
 */
async function loadComments(key){
  const el=document.getElementById('cmtlist'); if(!el) return;
  const d=await jget('api/comment?key='+encodeURIComponent(key||''));
  // THE ANSWER TO A MARKER NOBODY IS LOOKING AT ANY MORE IS DROPPED. Two clicks in quick succession
  // put two fetches in flight, and the first one landing last would draw one marker's comments under
  // another marker's heading — the modal-reuse defect this suite has already caught twice, and the
  // worst version of it, because the reader would be judging an artifact against somebody's opinion
  // of a different one.
  if(MODAL.key!==key||document.getElementById('cmtlist')!==el) return;
  if(!d){
    MODAL.comments=null;
    el.innerHTML='<div class="fb-state fb-unreadable"><b>The comments on this marker could not be '
      +'loaded</b><div class=fb-hint>api/comment did not answer, so this is NOT a marker with no '
      +'comments — it is a marker whose comments are unknown. Anything written below would probably '
      +'not be recorded either.</div></div>';
    return;
  }
  readCommentHeader(d);
  const list=commentsOf(d);
  if(list===null){
    MODAL.comments=null;
    el.innerHTML='<div class="fb-state fb-unreadable"><b>The comment store answered in a shape this '
      +'page does not recognise</b><div class=fb-hint>Expected a <code>comments</code> array; got '
      +esc(Object.keys(d).join(', ')||'nothing')+'. Rendering this as "no comments" would be the page '
      +'inventing a finding, so it says this instead.</div></div>';
    return;
  }
  MODAL.comments=list.map(asComment);
  renderComments(MODAL.comments);
  // A MARKER THE BACKLOG NO LONGER HAS. The store keeps comments across re-ingests, and the guidance
  // panel's chips open markers from runs that are long gone; the endpoint refuses a write against one,
  // so saying it here beats letting somebody type a paragraph and meet the refusal afterwards.
  if(d.marker_present===false){
    showCommentNote('This marker is not in the current backlog — it comes from an earlier ingest. '
      + 'Its comments are kept, but a new one cannot be filed against it until it is re-ingested.');
  }
}
function showCommentError(message){
  const err=document.getElementById('cmterr'); if(!err) return;
  err.textContent=message;
  err.hidden=false;
}
/**
 * A SUCCESS WITH A CAVEAT, and it is deliberately not the red box above.
 *
 * <p>"Stored, but the durable journal is off" is not a failure — the comment is in the store and is
 * being served — so painting it as one would train a reader to ignore the box that means their text
 * was never recorded at all. Amber, separate element, and cleared by the next successful post.
 */
function showCommentNote(message){
  const note=document.getElementById('cmtnote'); if(!note) return;
  note.textContent=message||'';
  note.hidden=!message;
}
/**
 * FILE THE COMMENT.
 *
 * <p>THE BOX IS CLEARED ON A 2xx AND ON NOTHING ELSE. Every other outcome — a refusal, a 500, a dead
 * endpoint, a browser that could not reach the network at all — leaves every character where it was
 * and puts the reason on the screen. That asymmetry is the feature: the failure this must never have
 * is the silent one, where somebody watches the box empty, believes they have filed a judgement, and
 * finds out weeks later that nothing was written anywhere.
 *
 * <p>AND IT DOES NOT RELOAD. The comment is prepended to the list that is already on screen and the
 * marker's count on the tables behind the modal is bumped, so the person who wrote it sees it land —
 * a full refresh here would throw away their place in a 282-row table to show them one paragraph.
 */
async function postComment(){
  const box=document.getElementById('cmt'); if(!box) return;
  const area=document.getElementById('cmttext');
  const button=document.getElementById('cmtpost');
  const key=box.dataset.key, stage=box.dataset.stage;
  const text=String(area.value||'').trim();
  const author=String((document.getElementById('cmtauthor')||{}).value||'').trim();
  const kind=String((document.getElementById('cmtkind')||{}).value||'').trim();
  const err=document.getElementById('cmterr'); if(err){err.hidden=true;err.textContent='';}
  if(!text){ showCommentError('Nothing to post — write the comment first.'); area.focus(); return; }
  button.disabled=true; const label=button.textContent; button.textContent='posting…';
  let response=null, body=null;
  try{
    response=await fetch('api/comment',{method:'POST',cache:'no-store',
      headers:{'content-type':'application/json'},
      body:JSON.stringify({dedup_key:key,stage:stage,kind:kind,author:author,text:text})});
    body=await response.json().catch(()=>null);
    readCommentHeader(body);
  }catch(e){ body={reason:String((e&&e.message)||e)}; }
  button.disabled=false; button.textContent=label;
  if(!response||!response.ok){
    // THE SENTENCE, NOT THE SLUG. A refusal carries both: `error` is a stable machine value
    // (`unknown_marker`, `text_too_long`) that this page could branch on, and `reason` is the
    // explanation written for the person who typed. Showing the slug alone tells them neither what
    // happened nor what to do about it.
    const why=String((body&&(body.reason||body.error||body.message))
      ||(response?('the server answered '+response.status):'the request never reached the server'));
    showCommentError('NOT POSTED — '+why+(/[.!?]$/.test(why.trim())?'':'.')
      +' Nothing has been recorded and what you wrote is still in the box, so try again rather than '
      +'typing it out a second time.');
    area.focus();
    return;
  }
  DRAFTS.delete(draftKey(key,stage));
  area.value='';
  const kindBox=document.getElementById('cmtkind'); if(kindBox) kindBox.value='';
  // STORED, BUT NOT DURABLY. The write answers with a `warning` when the row went into H2 and the
  // journal did not — the store a fresh deploy destroys, versus the file that survives one. It is not
  // an error and the comment is safe today, so it is shown as a note rather than as a failure; saying
  // nothing would be promising a durability the person did not get.
  showCommentNote(body&&body.warning?('Saved — but '+body.warning):'');
  const written=asComment(body&&body.comment?body.comment:body);
  if(written.text){
    MODAL.comments=[written].concat(MODAL.comments||[]);
    renderComments(MODAL.comments);
  } else {
    // The write succeeded but the answer did not carry the record: re-read rather than draw nothing.
    loadComments(key);
  }
  // The mark on the tables, immediately — the person who just wrote the comment is the one who most
  // needs to see that the marker now carries it. The index is re-fetched behind that to reconcile.
  COMMENTS.ok=true;
  COMMENTS.counts=COMMENTS.counts||{};
  COMMENTS.counts[key]=(COMMENTS.counts[key]||0)+1;
  repaintTables();
  refreshCommentIndex();
}
/**
 * THE MARK ON A ROW: how many people have written about this marker.
 *
 * <p>THIS IS THE QUESTION THAT COULD NOT BE ANSWERED — "which previous markers have negative
 * comments". Answering it by opening 282 modals is not answering it.
 *
 * <p>A COUNT AND NOT A DOT, because two people disagreeing about one marker is the row to open first.
 * And NOT A NEW COLUMN: the verdicts table's last two columns were once laid out 911px past the
 * right-hand edge because nine columns of intrinsic minimums added up past the box, so this goes
 * INSIDE a cell that is already there, under a word ({@code false_positive},
 * {@code true-positive-unfixed}) that is longer than anything here. See
 * EveryPanelIsReadableAtProductionVolumeTest, which measures that at 282 rows rather than trusting it.
 */
function commentMark(key){
  const n=(COMMENTS.counts||{})[key||'']||0;
  if(!n) return '';
  const title=n+' human comment(s) on this marker — open it to read them';
  return '<div class=cmt-mark title="'+escA(title)+'">'+n+' human comment'+(n===1?'':'s')+'</div>';
}
/** Redraw the tables from the state already in hand — no fetch, and never while there is none. */
function repaintTables(){ if(DASH&&(DASH.suspicions||[]).length) render(DASH); }
/**
 * The index the marks are drawn from, on the guidance panel's slow beat.
 *
 * <p>Not on the three-second poll: comments are written by people, minutes apart at the very best, and
 * this is a whole-backlog document. It is re-fetched immediately after a post, which is the only time
 * it changes under a reader who is looking at it.
 */
async function refreshCommentIndex(){
  // THE INDEX, NOT THE EXPORT. `api/comments` is a page of at most PAGE_MAX comments — the right shape
  // for reading them and the wrong one for counting them, because a marker whose comments fall off the
  // end of that page renders identically to a marker nobody has commented on. `api/comments/index` is
  // the server's own GROUP BY over the whole table: two numbers and a map, no comment bodies, which is
  // also what makes it cheap enough to re-read every thirty seconds for a 26-hour run.
  const d=await jget('api/comments/index');
  const counts=countsOf(d);
  // A FAILED READ KEEPS THE MARKS THAT WERE ALREADY THERE and records that this pass did not land.
  // Zeroing them would erase every mark on the page the first time one poll missed, which reads as
  // "nobody has commented on any of these" — the state this mark exists to disprove.
  if(counts===null){ COMMENTS={ok:false,counts:COMMENTS.counts||{},total:COMMENTS.total||0}; return; }
  // The same answer carries the vocabulary the box's kind list is built from — one fetch, and the
  // list is populated before anybody has opened a marker.
  readCommentHeader(d);
  COMMENTS={ok:true,counts:counts,total:Number(d.total_comments||0)};
  repaintTables();
}
// The transcript is a CALL TREE serialised as depth-tagged lines. Parse it back into a tree so each
// dig is a collapsible node holding the context it inherited, its own calls, and its verdict.
function parseTree(text){
  const root={depth:0,label:null,children:[],lines:[]}, stack=[root];
  for(const ln of String(text).split('\n')){
    const m=ln.match(/^·\s*d(\d+)\s+(.*)$/);
    if(m){
      const depth=Number(m[1]);
      while(stack.length>1 && stack[stack.length-1].depth>=depth) stack.pop();
      const node={depth,label:m[2],children:[],lines:[]};
      stack[stack.length-1].children.push(node);
      stack.push(node);
    } else {
      stack[stack.length-1].lines.push(ln);
    }
  }
  return root;
}
function nodeKind(label){
  if(/^dig_for_bugs/.test(label)) return 'dig';
  if(/^PROMPT/.test(label)) return 'prompt';
  if(/^VERDICT/.test(label)) return 'verdict';
  if(/^verdict:/.test(label)) return 'attempt';
  return 'call';
}
let __tkeyN={};
// stable, attribute-safe node id: the label is raw JSON and would break out of the attribute
function tkeyHash(str){ let h=5381; for(let i=0;i<str.length;i++) h=((h*33)^str.charCodeAt(i))>>>0; return h.toString(36); }
function renderTree(node){
  let h='';
  for(const c of node.children){
    const kind=nodeKind(c.label||'');
    const name=(c.label||'').split(/[\s({]/)[0];
    const rest=(c.label||'').slice(name.length).trim();
    const body=(c.lines.join('\n').trim()?fmtLines(c.lines.join('\n')):'')+renderTree(c);
    const badge='<span class="tdep tdep-'+Math.min(c.depth,3)+'">d'+c.depth+'</span>';
    if(!body){
      h+='<div class="tnode" style="padding-left:'+(10+c.depth*16)+'px">'+badge
        +'<span class=tglyph>'+(kind==='attempt'?'↩':'↳')+'</span><code class=tname>'+esc(name)+'</code>'
        +(rest?'<span class=targs>'+esc(rest.slice(0,160))+'</span>':'')+'</div>';
      continue;
    }
    // a node with content becomes an EXPANDING node; digs stay open one level so the tree is visible
    const open=(kind==='dig'||kind==='verdict')?' open':'';
    const kbase=c.depth+'-'+tkeyHash((c.label||'').slice(0,160));
    __tkeyN[kbase]=(__tkeyN[kbase]||0)+1;
    const tkey=kbase+'-'+__tkeyN[kbase];
    h+='<details class="tbranch tk-'+kind+'"'+open+' data-tkey="'+esc(tkey)+'"'
      +' style="margin-left:'+(c.depth*16)+'px">'
      +'<summary>'+badge+'<span class=tglyph>'+(kind==='attempt'?'↩':'↳')+'</span>'
      +'<code class=tname>'+esc(name)+'</code>'
      +(rest?'<span class=targs>'+esc(rest.slice(0,200))+'</span>':'')+'</summary>'
      +'<div class=tbody>'+body+'</div></details>';
  }
  return h;
}
function fmtLines(text){
  let out='', code=null;   // code != null -> inside a ```java fence
  for(const ln of String(text).split('\n')){
    if(code!==null){
      if(ln.trim()==='```'){ out+='<pre class="dlg jcode">'+hlJava(code)+'</pre>'; code=null; }
      else code += (code?'\n':'')+ln;
      continue;
    }
    if(ln.trim().startsWith('```')){ code=''; continue; }
    if(!ln.trim()) continue;
    if(ln.startsWith('▸')) out+='<div class=d-head>'+esc(ln)+'</div>';
    else if(ln.startsWith('>>>')) out+='<div class=d-tool>'+esc(ln.replace(/^>>>\s*/,'▸ '))+'</div>';
    else if(ln.startsWith('<<<')) out+=fmtToolResult(ln.slice(3));
    else if(/^\[(verdict|audit|investigation error|the model answered)/.test(ln.trim()))
      out+='<div class=d-diag>'+esc(ln.trim())+'</div>';
    else if(/^===\s*(THE INITIAL TASK|THE INVESTIGATION SO FAR|YOUR TASK)/.test(ln.trim()))
      out+='<div class=d-section>'+esc(ln.replace(/=/g,'').trim())+'</div>';
    else if(ln.startsWith('===')) out+='<div class=d-verdict>'+esc(ln.replace(/=/g,'').trim()||'VERDICT')+'</div>';
    else out+='<div class=d-prose>'+esc(ln)+'</div>';
  }
  if(code) out+='<pre class="dlg jcode">'+hlJava(code)+'</pre>';
  return out;
}
function fmtBody(text){
  __tkeyN={};
  const root=parseTree(text);
  return (root.lines.join('\n').trim()?fmtLines(root.lines.join('\n')):'')+renderTree(root);
}
function fmtDialog(text){
  if(!text) return empty('(no transcript)');
  const s=String(text);
  const MARK='=== VERDICT ===';
  const i=s.lastIndexOf(MARK);
  if(i<0) return fmtBody(s);
  return fmtBody(s.slice(0,i))+'<div class=d-verdict>VERDICT</div>'+renderVerdict(s.slice(i+MARK.length));
}
function difflines(s,cls,sign){return String(s==null?'':s).split('\n').map(l=>'<span class="diff-line '+cls+'">'+sign+esc(l)+'</span>').join('');}
// The patched files ARE upstream files — the fix is against the code the marker flagged — so unlike
// the reproducer's path each one opens. No line anchor: a hunk's line number is a position in the
// patch, not in the file as it stands, and a link to the wrong line is a link that misleads.
function difffile(path,edits,repo,branch){ let b=''; edits.forEach(e=>{b+=difflines(e.old_str,'diff-del','- ')+difflines(e.new_str,'diff-add','+ ');});
  const url=blobUrl(repo,branch,path,0);
  return '<div class=diff-file><div class=diff-path>'+esc(path)
    +(url?' '+ext(url,'file','open '+short(path)+' in '+repoSlug(repo)):'')
    +'</div><div class=diff-body>'+b+'</div></div>'; }
// --- pipeline/stage versions -------------------------------------------------------------------
// Every artifact carries the versions that produced it (bugs.versions JSON, suspicions.version), so a
// finding from an older suspector is never read as if the current one produced it.
function bugVersions(bug){ try{ const v=JSON.parse((bug&&bug.versions)||'{}'); return (typeof v==='string')?JSON.parse(v):v; }catch(e){ return {}; } }
function stageVer(bug,stage,fallback){ const v=bugVersions(bug); return v[stage]||fallback||''; }
function verline(stageV,bug){ const p=bugVersions(bug).pipeline||'';
  if(!stageV&&!p) return '';
  return '<div class=verline>'+(stageV?'<b>'+esc(stageV)+'</b>':'')+(p?'<span> · pipeline '+esc(p)+'</span>':'')+'</div>'; }

function renderFix(bug){   // the FIX itself (diff); the PR decision lives in its own tab
  let edits=[]; try{edits=JSON.parse(bug.fix_diff||'[]');}catch(e){}
  let h=verline(stageVer(bug,'fixer'),bug);
  h+='<div class=pr-meta>fails-before-fix '+redgreen(bug.red_verified)+' · passes-after-fix '+redgreen(bug.green_verified)
    +(bug.jdk?' · jdk '+esc(bug.jdk):'')+'</div>';
  h+='<div class=diff-hdr>Files changed</div>';
  if(Array.isArray(edits)&&edits.length){
    const byPath={}; edits.forEach(e=>{(byPath[e.path]=byPath[e.path]||[]).push(e);});
    for(const p in byPath) h+=difffile(p,byPath[p],bug.repo,bug.branch);
  } else { h+='<pre class=dlg>'+esc(bug.fix_diff||'(no fix)')+'</pre>'; }
  return h+commentBox(MODAL.key,'fixer');
}
function renderPR(bug){   // the PR-maker stage: make / reject (repo-specific) + the drafted PR
  const st=bug.state||'';
  let badge, note;
  if(st==='pr_ready'){ badge='<span class=pill-state>PR READY</span>'; note=' — worth opening upstream'; }
  else if(st==='pr_rejected'){ badge='<span class="pill-state" style="background:var(--red);color:#2b0906">PR REJECTED</span>'; note=' — proven bug, but the PR maker decided it is not PR-worthy for this repo'; }
  else if(st==='needs_review'){ badge='<span class="pill-state" style="background:var(--amber);color:#2b2106">NEEDS REVIEW</span>'; note=' — the fix skeptic flagged the fix, so the PR maker was skipped'; }
  else if(st==='infra_error'){ badge='<span class="pill-state" style="background:var(--amber);color:#2b2106">INFRA ERROR</span>'; note=' — the pipeline failed to run this one; NOT a judgement about the code (the suspicion stays queued for retry)'; }
  else { badge='<span class="pill-state" style="background:var(--pill);color:var(--dim)">'+esc(st||'—')+'</span>'; note=' — not PR-ready'; }
  let h=verline(stageVer(bug,'pr_maker'),bug);
  h+='<div class=pr-meta>'+badge+esc(note)+'</div>';
  if(bug.infra_reason) h+='<div class=d-note>infra: '+esc(bug.infra_reason)+'</div>';
  // THE SENTENCE THAT KEEPS THIS PAGE HONEST. Every other dashboard in this family links a PR from
  // here; this pipeline only ever DRAFTS one, so there is no pr_url column anywhere in the schema
  // and there is nothing to link. Saying so beats a blank space, which a reader fills in with the
  // assumption that a pull request was opened against a third-party repository on their behalf.
  h+='<div class=drafted>DRAFTED, NOT OPENED — nothing was pushed and no pull request exists, so '
    +'there is no URL to link to. Opening it is a human decision'
    +(repoUrl(bug.repo)?', upstream at '+ext(repoUrl(bug.repo),repoSlug(bug.repo),
      'the repository this PR would be opened against'):'')+'.</div>';
  h+='<div class=pr-title>'+esc(bug.pr_title||'(no title)')+'</div>';
  if(bug.pr_body) h+='<div class=pr-body>'+esc(bug.pr_body)+'</div>';
  return h+commentBox(MODAL.key,'pr_maker');
}
function renderTest(bug){
  let h=verline(stageVer(bug,'reproducer'),bug);
  h+='<div class=pr-meta>fails-before-fix '+redgreen(bug.red_verified)+(bug.jdk?' · jdk '+esc(bug.jdk):'')+'</div>';
  // THE PATH BELOW IS NOT A LINK, AND THAT IS THE POINT. It reads exactly like a repository path and
  // it is not one: the reproducer was written into the prover's own checkout and never pushed, so a
  // blob URL for it would 404 for every marker in the run. What IS linked is the code the test is
  // about, which is a real file and is what a reviewer reading a reproducer wants next.
  const src=markerSourceUrl(bug.repo,bug.branch,bug.file,lineNo(bug.svace_line,bug.line));
  h+='<div class=tiny style="padding:0 0 8px">This test was written into the prover\'s checkout'
    +(repoSlug(bug.repo)?' of <code>'+esc(repoSlug(bug.repo))+'</code>':'')
    +' and NEVER pushed, so its path is not a file in the repository and there is nothing to link '
    +'to.'+(src?' The code it is about is here: '+ext(src,short(bug.file),
      sourceTitle(bug.repo,bug.branch,bug.file,lineNo(bug.svace_line,bug.line))):'')+'</div>';
  h+='<div class=diff-file><div class=diff-path>'+esc(bug.test_path||'(test)')+'</div>'
    +'<pre class="dlg jcode" style="margin:0;padding:10px 12px">'+hlJava(bug.test_code||'(no test)')+'</pre></div>';
  // UNDER THE TEST ITSELF, because this is the tab the user's own example is about: "too many mocks,
  // this one and this one are redundant" can only be written by somebody looking at the code above it.
  h+=commentBox(MODAL.key,'reproducer');
  return h;
}
function empty(t){return '<div class=empty>'+t+'</div>';}
// `severity` -> col-severity, `file:line` -> col-file-line, '' -> no class at all. Every cell carries
// the class of the column it is in, so the stylesheet can size a column BY NAME. The alternative is
// td:nth-child(9), which starts sizing a different column the day one is inserted — silently, and in
// the direction that makes the page agree with whatever it now does.
function colcls(h){const s=String(h==null?'':h).toLowerCase().replace(/[^a-z0-9]+/g,'-')
  .replace(/^-+|-+$/g,''); return s?' class=col-'+s:'';}
function tbl(head,rows,rowclick){if(!rows.length)return empty('—');
  const cls=head.map(colcls);
  return '<table><thead><tr>'+head.map((h,j)=>'<th'+cls[j]+'>'+h+'</th>').join('')+'</tr></thead><tbody>'+
    rows.map((r,i)=>'<tr'+(rowclick?' class=clickrow onclick="'+rowclick(i)+'"':'')+'>'
      +r.map((c,j)=>'<td'+cls[j]+'>'+c+'</td>').join('')+'</tr>').join('')+'</tbody></table>';}
function livecls(st){return st==='analyzing'?'running':(st==='error'||st==='stale'||st==='gone')?'error':'done';}
async function renderLive(){
  // The live-dialog panel belonged to the LLM suspector, which the Svace ingester replaced — nothing
  // streams ReAct transcripts any more, so the card is gone from the page. Bail before touching it:
  // getElementById returns null here and the TypeError would kill this 3s interval every tick.
  if(!document.getElementById('live')) return;
  const d=await jget('api/live'); const rows=(d&&d.dialogs)||[]; window.__LIVE=rows;
  const na=rows.filter(r=>r.status==='analyzing').length;
  document.getElementById('livecount').textContent=rows.length?(na+' analyzing · '+rows.length+' recent'):'idle';
  document.getElementById('live').innerHTML=rows.length? tbl(['method','file','status','tools','transcript'],
    rows.map(r=>['<code>'+esc(r.method||'')+'</code>','<span class=tiny>'+esc(short(r.file||''))+'</span>',
      '<span class="dot d-'+livecls(r.status)+'"></span><span class=st-'+livecls(r.status)+'>'+esc(r.status||'')+'</span>',
      (r.tool_calls||0),'<span class=link>watch →</span>']),
    i=>"showLive("+i+")")
    : empty('no methods analyzing right now');
}
function showLive(i){ const r=window.__LIVE[i]; if(!r)return; const key=r.method_key;
  openModal('live · <code>'+esc(r.method||'')+'</code>'); document.getElementById('mtabs').innerHTML='';
  let __lastHtml='';
  async function refresh(){ const d=await jget('api/live?key='+encodeURIComponent(key));
    const t=(d&&d.dialog)||'(streaming…)'; const st=(d&&d.status)||'';
    const body=document.getElementById('mbody');
    const html='<div class=tiny>status <span class=st-'+livecls(st)+'>'+esc(st)+'</span> · '+((d&&d.tool_calls)||0)+' tool calls · auto-refreshing every 2s</div>'+fmtDialog(t);
    if(html!==__lastHtml){
      // a click expands a node; replacing innerHTML wholesale collapsed it again on the next tick
      const open=new Set([...body.querySelectorAll('details[data-tkey]')].filter(x=>x.open).map(x=>x.dataset.tkey));
      const top=body.scrollTop;
      body.innerHTML=html;
      __lastHtml=html;
      body.querySelectorAll('details[data-tkey]').forEach(x=>{ if(open.has(x.dataset.tkey)) x.open=true; });
      body.scrollTop=top;
    }
    if(st!=='analyzing')stopLiveTimer(); }
  stopLiveTimer(); refresh(); window.__LIVET=setInterval(refresh,2000);
}
async function renderErrors(){
  const rows=await jget('api/errors')||[]; window.__ERR=rows;
  document.getElementById('errcount').textContent=rows.length?rows.length+' recent':'none';
  document.getElementById('errors').innerHTML=rows.length? tbl(['','flow','node','file','when','error'],
    rows.map(e=>['<span class="dot d-error"></span>',esc(e.wf),'<span class=tiny>'+esc(e.node||'')+'</span>',
      '<span class=tiny>'+esc(e.file||'')+'</span>',fmtTime(e.started),
      '<span class=st-error>'+esc((e.message||'').slice(0,140))+'</span>']),
    i=>"showError("+i+")")
    : empty('no recent errors');
}
function showError(i){ const e=window.__ERR[i]; if(!e)return;
  openModal('error · <code>'+esc(e.wf)+'</code>'+(e.node?' · '+esc(e.node):'')); document.getElementById('mtabs').innerHTML='';
  document.getElementById('mbody').innerHTML='<div class=tiny>'+esc(e.wf)+' · exec '+esc(e.id)+' · <span class=st-error>'+esc(e.status)+'</span> · '+fmtTime(e.started)+(e.file?' · <code>'+esc(e.file)+'</code>':'')+'</div><pre class=dlg>'+esc(e.message||'')+'</pre>';
}
// ---- live updates over STOMP -------------------------------------------------------------------
// The page used to ask for the whole state document every three seconds for the entire length of a run
// — 6 to 26 hours, tens of thousands of requests, almost every one of them identical to the answer
// before it, and still up to three seconds behind the transition somebody was watching for. Now the
// orchestrator pushes; the poll below survives only as the fallback.
//
// WHY A HAND-WRITTEN STOMP CLIENT. The page is three static files served straight out of the jar, with
// no build step and no dependencies. The alternative is vendoring sockjs-client and stompjs — a couple
// of hundred kilobytes of library — to use three frames of a protocol: CONNECT, SUBSCRIBE, MESSAGE.
// The server registers a SockJS endpoint at the same path for a proxy that will not forward Upgrade;
// a browser that cannot open the socket at all lands on the poll, which serves the same JSON.
const LIVE_URL = (location.protocol==='https:'?'wss://':'ws://')+location.host
  +location.pathname.replace(/[^/]*$/,'')+'ws';
const STALE_MS = 20000;    // no frame for this long means a half-open socket: go back to polling
const RETRY_MS = 3000;

function stamp(what){ document.getElementById('ts').textContent=
  (LIVE.connected?'live · ':'')+what+' '+new Date().toLocaleTimeString(); }

function stompSend(cmd,headers,body){
  if(!LIVE.sock||LIVE.sock.readyState!==1) return;
  let f=cmd+'\n'; for(const k in headers) f+=k+':'+headers[k]+'\n';
  LIVE.sock.send(f+'\n'+(body||'')+'\0');
}
// One frame: the command, then a header block, then the body. Frames are NUL-terminated; a JSON body
// can never contain a raw NUL (the spec requires control characters to be escaped), so splitting on it
// is safe without honouring content-length.
function stompParse(text){
  const t=text.replace(/\r\n/g,'\n');
  const i=t.indexOf('\n\n');
  const head=(i<0?t:t.slice(0,i)).split('\n');
  const headers={};
  for(const line of head.slice(1)){ const j=line.indexOf(':'); if(j>0) headers[line.slice(0,j)]=line.slice(j+1); }
  return {command:head[0], headers, body:i<0?'':t.slice(i+2)};
}
function liveConnect(){
  let sock; try{ sock=new WebSocket(LIVE_URL); }catch(e){ setTimeout(liveConnect,RETRY_MS); return; }
  LIVE.sock=sock;
  let buf='';
  sock.onopen=()=>{
    // heart-beat:0,0 — the server's default heart-beats would need this page to answer them, and a
    // missed answer drops the connection. Liveness is measured instead from the tick the orchestrator
    // publishes on /topic/progress, which is what STALE_MS below is comparing against.
    stompSend('CONNECT',{'accept-version':'1.2','heart-beat':'0,0'});
  };
  sock.onmessage=(ev)=>{
    buf+=ev.data;
    for(;;){
      const end=buf.indexOf('\0'); if(end<0) break;
      const raw=buf.slice(0,end); buf=buf.slice(end+1);
      if(!raw.trim()) continue;                       // an EOL frame: a heart-beat, nothing to do
      liveFrame(stompParse(raw));
    }
  };
  sock.onclose=()=>{ LIVE.connected=false; LIVE.sock=null; setTimeout(liveConnect,RETRY_MS); };
  // onerror is always followed by onclose, so the reconnect is scheduled in one place only
  sock.onerror=()=>{ try{ sock.close(); }catch(e){} };
}
let __sub=0;
function subscribe(dest){ stompSend('SUBSCRIBE',{id:'sub-'+(__sub++),destination:dest}); }
function liveFrame(f){
  LIVE.last=Date.now();
  if(f.command==='CONNECTED'){
    LIVE.connected=true;
    subscribe('/topic/state');      // the whole document — replaces the poll
    subscribe('/topic/counts');     // status counts and run progress, on every transition
    subscribe('/topic/markers');    // one marker changing state
    subscribe('/topic/progress');   // job/step events from the batch listeners, plus the tick
    stompSend('SEND',{destination:'/app/refresh','content-length':'0'});
    stamp('live since');
    return;
  }
  if(f.command!=='MESSAGE') return;   // ERROR/RECEIPT: onclose handles the reconnect
  let d=null; try{ d=JSON.parse(f.body); }catch(e){ return; }
  switch(f.headers.destination){
    case '/topic/state': render(d); break;
    case '/topic/counts': liveCounts(d); break;
    case '/topic/markers': stamp('marker '+String(d.to||'')+' ·'); break;
    case '/topic/progress': liveProgress(d); break;
  }
}
// The cheap topic: it moves the captions and the "still to settle" line between snapshots. It
// deliberately does NOT re-render a table — the counts document carries no rows, and rebuilding one
// from it would be a second, disagreeing view of the same numbers.
function liveCounts(c){
  const el=(id)=>document.getElementById(id);
  if(el('suscount')) el('suscount').textContent=c.total+' rows';
  if(el('bugcount')) el('bugcount').textContent=c.bugs+' rows';
  if(el('current')) el('current').textContent=c.remaining
    ? (c.remaining+' marker(s) still to settle')
    : (c.total?'every marker settled':'no markers ingested yet — POST /webhook/ingest');
}
// Job and step lifecycle, straight from the Spring Batch listeners. It only stamps the header line: the
// stage banner itself is rendered from `activity` in the snapshot the orchestrator pushes immediately
// after every one of these events, and deriving the banner here as well would be a second, competing
// answer to "what stage is this run in" that could disagree with the tables underneath it.
function liveProgress(p){
  if(p.event==='tick') return;    // liveness only; LIVE.last is already updated
  stamp(p.event+(p.step?' · '+p.step:'')+(p.written!=null?' · '+p.written+' written ·':' ·'));
}
// The fallback loop. It fetches ONLY when there is no live connection or the connection has gone quiet
// — a socket that has half-closed looks exactly like a run in which nothing is happening, and a page
// that cannot tell the two apart stops updating and says nothing, which is how this dashboard has
// blanked itself before.
function liveLoop(){
  if(!LIVE.connected || Date.now()-LIVE.last>STALE_MS) tick();
}
tick();
liveConnect();
setInterval(liveLoop,3000);
// Both of these are answered by a permanently empty document — the live transcript panel and the error
// feed belonged to the LLM suspector the Svace ingester replaced. Called once so the "none"/"no recent
// errors" placeholders are painted; polling something that is empty by construction was the clearest
// waste in the old page.
renderLive();
renderErrors();
// THE GUIDANCE PANEL, ON ITS OWN SLOW BEAT.
//
// NOT on /api/state's two-second poll and not on the socket. The store is a file that only changes
// when a marker SETTLES — minutes apart at best — and the panel's numbers are cumulative across
// runs, so re-fetching them with the tables would be thirty requests a minute for a document that
// changes once an hour. Thirty seconds is well inside the time it takes to settle one marker.
renderGuidance();
setInterval(renderGuidance,30000);
// THE HUMAN COMMENTS' INDEX, on the same slow beat and for the same reason: it is a whole-backlog
// document that changes only when a PERSON writes something, which is minutes apart at the very best.
// A post refreshes it immediately, so the only reader it can be stale for is one who is not writing.
refreshCommentIndex();
setInterval(refreshCommentIndex,30000);
