'use strict';
let DASH = {files:[],suspicions:[],bugs:[]};
async function jget(u){try{return await (await fetch(u,{cache:'no-store'})).json();}catch(e){return null;}}
function stopLiveTimer(){if(window.__LIVET){clearInterval(window.__LIVET);window.__LIVET=null;}}
function closeModal(){stopLiveTimer();document.getElementById('modalbg').classList.remove('on');}
function openModal(title){stopLiveTimer();document.getElementById('mtitle').innerHTML=title;document.getElementById('mtabs').innerHTML='';document.getElementById('mbody').innerHTML='<div class=empty>loading…</div>';document.getElementById('modalbg').classList.add('on');}
document.addEventListener('keydown',e=>{if(e.key==='Escape')closeModal();});
function setTabs(tabs,active){const tb=document.getElementById('mtabs');tb.innerHTML='';tabs.forEach((t,i)=>{const el=document.createElement('span');el.className='tab'+(i===active?' on':'');el.textContent=t.name;el.onclick=()=>setTabs(tabs,i);tb.appendChild(el);});document.getElementById('mbody').innerHTML=tabs[active].render();}
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
  // the suspector transcript is gone with the suspector; only the artifact is fetched now
  const bug=await jget('api/bug?key='+encodeURIComponent(su.dedup_key||su.suspicion_key||''));
  const tabs=[{name:'Marker',render:()=>{ const h=renderMarker(su,bug);
    setTimeout(()=>loadMarkerSource(su),0); return h; }}];
  if(bug&&bug.suspicion_key){
    tabs.push({name:'Reproducer '+(String(bug.red_verified)==='1'?'●':'○'),render:()=>renderTest(bug)});
    tabs.push({name:'Fixer '+(String(bug.green_verified)==='1'?'●':'○'),render:()=>renderFix(bug)});
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
  const kind=pick(bug.verdict_kind), vtext=pick(bug.verdict_text);
  return '<div class=diff-hdr>Svace report row</div><table>'
    + row('severity','<span class=sev-'+esc(pick(su.severity,bug.severity))+'>'
        +esc(pick(su.svace_severity,bug.svace_severity))+'</span>')
    + row('checker','<code>'+esc(pick(su.svace_checker,bug.svace_checker))+'</code>')
    + row('claim',esc(pick(su.description,bug.description)))
    + row('file','<code>'+esc(pick(su.file,bug.file))+'</code>')
    + row('line',line===''?'':'<code>'+esc(num(line))+'</code>')
    + row('category',esc(pick(su.category,bug.category)))
    + row('marker id','<span class=tiny>'+esc(pick(su.marker_id,bug.marker_id))+'</span>')
    + '</table>'
    + '<div class=diff-hdr>Where it landed in the checked-out tree</div><table>'
    + row('anchor',anchor?('<code>'+esc(anchor)+'()</code>'):'<span class=tiny>—</span>')
    + row('confidence','<span style="color:'+conf[0]+'">'+esc(astatus||'—')+'</span>',conf[1])
    + row('status','<span class=st-'+esc(pick(su.status))+'>'+esc(pick(su.status))+'</span>')
    + row('attempts',su.prove_attempts!=null?esc(num(su.prove_attempts)):'')
    + row('note',pick(su.note)?esc(su.note):'')
    + '</table>'
    + (vtext?('<div class=diff-hdr>Verdict'+(kind?' — '+esc(kind):'')+'</div>'
       +'<div style="white-space:pre-wrap;padding:0 12px">'+esc(vtext)+'</div>'):'')
    + '<div class=diff-hdr>Source at the marker</div><div id=markersrc><div class=empty>loading…</div></div>'
    + (bug&&bug.versions?verline(stageVer(bug,'ingester',su.version),bug):'');
}
const esc=s=>(s==null?'':String(s)).replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
const short=p=>p?p.split('/').pop():'';
// folders before class: drop the noisy src/main/java segment, dim the folders, highlight the class
function pkg(p){ if(!p) return ''; const clean=p.replace('/src/main/java/','/').replace(/^src\/main\/java\//,''); const j=clean.lastIndexOf('/');
  return j<0 ? '<code>'+esc(clean)+'</code>'
    : '<span class=tiny>'+esc(clean.slice(0,j+1))+'</span><code>'+esc(clean.slice(j+1))+'</code>'; }
const fmtTime=s=>{if(!s)return '';const d=new Date(s.replace(' ','T')+'Z');return d.toLocaleTimeString();};
function dur(s){if(s==null)return '';s=Math.round(s);
  const d=Math.floor(s/86400),h=Math.floor(s%86400/3600),m=Math.floor(s%3600/60),ss=s%60;
  const p=n=>n.toString().padStart(2,'0');
  if(d)return d+'d'+p(h)+'h';
  if(h)return h+'h'+p(m)+'m';
  if(m)return m+'m'+p(ss)+'s';
  return ss+'s';}
async function tick(){
  let s; try{ s=await (await fetch('api/state',{cache:'no-store'})).json(); }catch(e){ return; }
  DASH=s;
  document.getElementById('ts').textContent='updated '+new Date().toLocaleTimeString();
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
  const proven=s.bugs.filter(b=>String(b.red_verified)==='1'&&String(b.green_verified)==='1').length;
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
  // reuses `settled` from the stats block above — re-declaring it with const here was a SyntaxError
  // that killed the whole inline script and left the page completely blank
  document.getElementById('verdictcount').textContent=
    verdicts.length+' of '+settled+' settled markers · '+all.length+' total';
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
      '<div class=tiny style="max-width:30em">'+esc(v.description||'')+'</div>',
      '<span class=pill-state style="background:'+(kindColour[v.verdict_kind]||'var(--amber)')+';color:#101418">'
        +esc(v.verdict_kind||'?')+'</span>',
      '<div style="white-space:pre-wrap;min-width:26em">'+esc(v.verdict_text)+'</div>']))
    : empty('no verdicts yet — every settled marker gets one, whatever the outcome');

  // markers table
  document.getElementById('suscount').textContent=all.length+' rows';
  document.getElementById('suspicions').innerHTML=all.length? tbl(
    ['sev','checker','category','file:line','anchor','status'],
    all.map(x=>['<span class=sev-'+esc(x.severity)+'>'+esc(x.svace_severity||x.severity)+'</span>',
      '<span class=tiny>'+esc(x.svace_checker||'')+'</span>', esc(x.category),
      pkg(x.file)+'<span class=tiny>:'+num(x.svace_line!=null?x.svace_line:x.line)+'</span>',
      '<span class=tiny>'+esc(x.anchor?x.anchor+'()':(x.anchor_status||''))+'</span>',
      '<span class=st-'+esc(x.status)+'>'+esc(x.status)+'</span>'
      +((x.note||'').trim()?'<div class=dedupnote>'+esc(String(x.note).slice(0,180))+'</div>':'')]),
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
function difffile(path,edits){ let b=''; edits.forEach(e=>{b+=difflines(e.old_str,'diff-del','- ')+difflines(e.new_str,'diff-add','+ ');});
  return '<div class=diff-file><div class=diff-path>'+esc(path)+'</div><div class=diff-body>'+b+'</div></div>'; }
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
    for(const p in byPath) h+=difffile(p,byPath[p]);
  } else { h+='<pre class=dlg>'+esc(bug.fix_diff||'(no fix)')+'</pre>'; }
  return h;
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
  h+='<div class=pr-title>'+esc(bug.pr_title||'(no title)')+'</div>';
  if(bug.pr_body) h+='<div class=pr-body>'+esc(bug.pr_body)+'</div>';
  return h;
}
function renderTest(bug){
  let h=verline(stageVer(bug,'reproducer'),bug);
  h+='<div class=pr-meta>fails-before-fix '+redgreen(bug.red_verified)+(bug.jdk?' · jdk '+esc(bug.jdk):'')+'</div>';
  h+='<div class=diff-file><div class=diff-path>'+esc(bug.test_path||'(test)')+'</div>'
    +'<pre class="dlg jcode" style="margin:0;padding:10px 12px">'+hlJava(bug.test_code||'(no test)')+'</pre></div>';
  return h;
}
function empty(t){return '<div class=empty>'+t+'</div>';}
function tbl(head,rows,rowclick){if(!rows.length)return empty('—');
  return '<table><thead><tr>'+head.map(h=>'<th>'+h+'</th>').join('')+'</tr></thead><tbody>'+
    rows.map((r,i)=>'<tr'+(rowclick?' class=clickrow onclick="'+rowclick(i)+'"':'')+'>'+r.map(c=>'<td>'+c+'</td>').join('')+'</tr>').join('')+'</tbody></table>';}
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
tick(); setInterval(tick,3000);
renderLive(); setInterval(renderLive,3000);
renderErrors(); setInterval(renderErrors,6000);
