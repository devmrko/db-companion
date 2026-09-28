import {t} from './i18n.mjs';
import {renderTokenAnalysis} from './business-glossary.mjs';
import {assistantApi} from './ai-assistant.mjs';
import {mountQuestionAnalysis,analysisEndpoint} from './question-analysis.mjs';
import {routeCoverage,coverageLabel,unmatchedLabel} from './ontology-route-coverage.mjs';

export const evidenceKey=value=>value?.hash||'';
export const evidenceReady=(enabled,value,question)=>!enabled||Boolean(value&&value.question===question&&value.hash);
export const evidenceMatches=(saved,enabled,current,question)=>evidenceReady(enabled,current,question)&&evidenceKey(saved)===(enabled?evidenceKey(current):'');
const status=value=>value==='APPROVED'?t('aitest.evApproved','승인'):value==='FK'?t('aitest.evFk','확인된 FK'):t('aitest.evMetadata','보관 메타데이터');
const kind=value=>value==='DEFINITION'?t('aitest.evDefinition','업무 정의'):value==='RELATION'?t('aitest.evRelation','관계'):t('aitest.evMetadata','보관 메타데이터');

export function renderEvidence(host,value){
  host.replaceChildren();host.hidden=!value;if(!value)return;
  const meta=document.createElement('p');meta.className='app-filter-message';
  meta.textContent=`${value.schema} · ${value.references.map(r=>`${r.table} v${r.revision}`).join(' · ')}`;host.append(meta);
  const wrapper=document.createElement('div');wrapper.className='table-responsive';
  const table=document.createElement('table');table.className='app-table';const body=document.createElement('tbody');
  for(const item of JSON.parse(value.source).evidence){
    const row=document.createElement('tr');
    const detail=item.kind==='RELATION'?`${item.source} (${item.from.join(', ')}) → ${item.target} (${item.to.join(', ')})`:item.description||item.title;
    for(const text of [item.id,kind(item.kind),detail,status(item.status)]){const cell=document.createElement('td');cell.textContent=text;row.append(cell);}body.append(row);
  }
  const head=document.createElement('thead'),line=document.createElement('tr');
  for(const text of ['ID',t('aitest.evType','구분'),t('aitest.evidence','온톨로지 근거'),t('aitest.evStatus','상태')]){const cell=document.createElement('th');cell.scope='col';cell.textContent=text;line.append(cell);}head.append(line);table.append(head,body);wrapper.append(table);host.append(wrapper);
  const details=document.createElement('details'),summary=document.createElement('summary'),pre=document.createElement('pre');
  summary.textContent=t('aitest.evSource','전송 근거 · RDF/JSON');pre.className='app-preview-value app-test-prompt';pre.textContent=value.source;details.append(summary,pre);host.append(details);
}

export function mountEvidence(root,{post,isBusy,setBusy,changed,message,question}){
  const get=name=>root.querySelector(`[data-test-evidence-${name}]`);
  let selected=null,search=null,loadedSchema='',tables=[],picked=[],sequence=0;
  const enabled=()=>get('enabled').checked;
  const clearSelected=()=>{selected=null;renderEvidence(get('selected'),null);changed();};
  function renderDefinitions(){
    const host=get('definitions');host.replaceChildren();
    const matches=new Map();for(const concept of search?.concepts||[])for(const target of concept.targets){const terms=matches.get(target.table)||[];terms.push(concept.term);matches.set(target.table,terms);}
    const approved=tables.filter(row=>row.state==='APPROVED').sort((a,b)=>(matches.get(b.name)?.length||0)-(matches.get(a.name)?.length||0)||a.name.localeCompare(b.name));
    for(const row of approved){
      const label=document.createElement('label'),check=document.createElement('input'),text=document.createElement('span');
      check.type='checkbox';check.value=row.name;check.checked=picked.includes(row.name);check.disabled=isBusy();
      const terms=matches.get(row.name);text.textContent=`${row.name} · ${row.concept||''}${terms?' · '+t('aitest.evDefinitionMatch','검색 일치: {0}',terms.join(', ')):''}`;
      check.addEventListener('change',()=>{if(check.checked&&picked.length>=10){check.checked=false;message(t('aitest.evDefinitionsLimit','정의는 최대 10개까지 선택할 수 있습니다.'),true);return;}picked=check.checked?[...picked,row.name]:picked.filter(name=>name!==row.name);get('routes').value='';clearSelected();});
      label.append(check,text);host.append(label);
    }
    if(!approved.length){const note=document.createElement('p');note.textContent=t('aitest.evDefinitionsEmpty','선택할 승인 정의가 없습니다. 온톨로지 관리에서 정의를 승인해 주세요.');host.append(note);}
  }
  const reset=()=>{sequence++;selected=null;search=null;picked=[];get('tokens').replaceChildren();get('routes').replaceChildren();get('route-field').hidden=true;renderEvidence(get('selected'),null);renderDefinitions();changed();};
  const invalidate=()=>{reset();};
  const analysis=mountQuestionAnalysis(root,{changed:reset,isBusy});
  const controls=pending=>{
    get('enabled').disabled=pending;get('panel').hidden=!enabled();
    for(const name of ['schema','anchor','routes','refresh'])get(name).disabled=pending;
    get('find').disabled=pending||!question().trim()||!get('schema').value;
    for(const input of get('definitions').querySelectorAll('input'))input.disabled=pending;
    get('definitions-apply').disabled=pending||!question().trim()||!picked.length;
    get('hint').textContent=enabled()&&!selected?t('aitest.evChooseDefinitionHint','근거 찾기를 누르면 일치한 승인 정의가 자동 첨부됩니다. 필요하면 직접 선택하거나 관계 경로를 지정할 수 있습니다.'):'';
    analysis.controls(pending);
  };
  async function options(refresh=false){
    const schema=get('schema').value;if(!schema)return;
    if(!refresh&&loadedSchema===schema)return;
    const data=await assistantApi('/ai-test/evidence/options?'+new URLSearchParams({schema,refresh}));
    loadedSchema=schema;tables=data.tables;renderDefinitions();get('anchor').replaceChildren(new Option(t('aitest.evQuestionSearch','질문으로 찾기'),''));
    for(const row of data.tables)get('anchor').append(new Option(row.name+(row.concept?' · '+row.concept:''),row.name));
    get('checked').textContent=`${schema} · ${new Date(data.checkedAt).toLocaleString()}`;
    if(!data.tables.length)message(t('aitest.evNoTables','저장된 온톨로지 테이블이 없습니다.'));
    await analysis.load();
  }
  async function work(fn){if(isBusy())return;setBusy(true);message('');try{await fn();}catch(ex){message(ex.message,true);}finally{setBusy(false);}}
  get('enabled').addEventListener('change',()=>{changed();if(enabled())work(()=>options());});
  get('schema').addEventListener('change',()=>{tables=[];reset();loadedSchema='';get('anchor').replaceChildren();work(()=>options());});
  get('anchor').addEventListener('change',reset);
  get('refresh').addEventListener('click',()=>{reset();loadedSchema='';work(()=>options(true));});
  get('find').addEventListener('click',()=>work(async()=>{
    reset();const current=sequence,q=question(),schema=get('schema').value;await options();
    const found=await post(analysisEndpoint('evidence/search',analysis.language()),{schema,question:q,anchor:get('anchor').value});
    if(current!==sequence||q!==question()||schema!==get('schema').value)return;
    search=found;renderTokenAnalysis(get('tokens'),search.analysis);
    get('routes').replaceChildren(new Option(t('aitest.evChoose','경로 선택'),''));
    for(const route of search.routes){const coverage=routeCoverage(search,route);
      get('routes').append(new Option([route.tables.join(' — '),t('aitest.evRelations','관계 {0}개',route.relations.length),coverageLabel(coverage),unmatchedLabel(coverage)].filter(Boolean).join(' · '),route.id));}
    get('route-field').hidden=!search.routes.length;
    const matched=new Set(search.concepts.flatMap(c=>c.targets.map(v=>v.table)));
    picked=tables.filter(row=>row.state==='APPROVED'&&matched.has(row.name)).map(row=>row.name);
    renderDefinitions();
    if(search.analysis?.mode==='ORACLE_TEXT_UNCONFIGURED'&&!picked.length){message(t('questionAnalysis.missing','질문 형태소 분석 설정이 필요합니다. 설정 SQL을 확인하거나 승인 정의를 직접 선택해 주세요.'),true);return;}
    if(search.limited||picked.length>10){picked=[];renderDefinitions();message(t('aitest.evAutoLimit','검색 범위가 한도를 초과하여 자동 첨부하지 않았습니다. 질문을 좁히거나 정의를 직접 선택해 주세요.'),true);return;}
    const value=await post('evidence/definitions',{schema,question:q,tables:[...picked]});
    if(current!==sequence||q!==question()||schema!==get('schema').value)return;
    selected=value;renderEvidence(get('selected'),selected);changed();
    message(picked.length?t('aitest.evAutoAttached','검색에 일치한 승인 정의 {0}개를 자동 첨부했습니다. 관계 경로는 자동으로 선택하지 않습니다.',picked.length):t('aitest.evAutoEmpty','일치하는 승인 정의가 없어 온톨로지 참고정보 없이 진행합니다.'));
  }));
  get('definitions-apply').addEventListener('click',()=>work(async()=>{
    clearSelected();
    selected=await post('evidence/definitions',{schema:get('schema').value,question:question(),tables:[...picked]});
    get('routes').value='';renderEvidence(get('selected'),selected);changed();
  }));
  get('routes').addEventListener('change',()=>work(async()=>{
    selected=null;renderEvidence(get('selected'),null);changed();if(!get('routes').value)return;
    selected=await post('evidence/choose',{searchId:search.id,route:get('routes').value});picked=[];renderDefinitions();renderEvidence(get('selected'),selected);changed();
  }));
  return {
    controls,invalidate,enabled,current:()=>selected,
    ready:()=>evidenceReady(enabled(),selected,question()),
    matches:value=>evidenceMatches(value,enabled(),selected,question()),
    request:()=>({useOntology:enabled(),evidenceHash:enabled()?evidenceKey(selected):null}),
    async restore(data,refresh){
      const schema=refresh?get('schema').value:data.evidence?.schema||get('schema').value||data.evidenceSchema;
      get('schema').replaceChildren(...data.schemas.map(name=>new Option(name,name)));get('schema').value=data.schemas.includes(schema)?schema:data.evidenceSchema;
      if(refresh){tables=[];reset();loadedSchema='';}else if(data.evidence&&data.evidence.question===question()){selected=data.evidence;picked=selected.route==='DEFINITIONS'?selected.references.map(r=>r.table):[];get('enabled').checked=true;renderEvidence(get('selected'),selected);}
      if(enabled()){try{await options();}catch(ex){return ex.message;}}
      return '';
    }
  };
}
