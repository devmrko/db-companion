import {t} from './i18n.mjs';
import {assistantApi} from './ai-assistant.mjs';

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
  let selected=null,search=null,loadedSchema='';
  const enabled=()=>get('enabled').checked;
  const reset=()=>{selected=null;search=null;get('routes').replaceChildren();get('route-field').hidden=true;renderEvidence(get('selected'),null);changed();};
  const invalidate=()=>{reset();};
  const controls=pending=>{
    get('enabled').disabled=pending;get('panel').hidden=!enabled();
    for(const name of ['schema','anchor','routes','refresh'])get(name).disabled=pending;
    get('find').disabled=pending||!question().trim()||!get('schema').value;
    get('hint').textContent=enabled()&&!selected?t('aitest.evChooseHint','근거를 찾고 경로를 선택해 주세요.'):'';
  };
  async function options(refresh=false){
    const schema=get('schema').value;if(!schema)return;
    if(!refresh&&loadedSchema===schema)return;
    const data=await assistantApi('/ai-test/evidence/options?'+new URLSearchParams({schema,refresh}));
    loadedSchema=schema;get('anchor').replaceChildren(new Option(t('aitest.evQuestionSearch','질문으로 찾기'),''));
    for(const row of data.tables)get('anchor').append(new Option(row.name+(row.concept?' · '+row.concept:''),row.name));
    get('checked').textContent=`${schema} · ${new Date(data.checkedAt).toLocaleString()}`;
    if(!data.tables.length)message(t('aitest.evNoTables','저장된 온톨로지 테이블이 없습니다.'));
  }
  async function work(fn){if(isBusy())return;setBusy(true);message('');try{await fn();}catch(ex){message(ex.message,true);}finally{setBusy(false);}}
  get('enabled').addEventListener('change',()=>{changed();if(enabled())work(()=>options());});
  get('schema').addEventListener('change',()=>{reset();loadedSchema='';get('anchor').replaceChildren();work(()=>options());});
  get('anchor').addEventListener('change',reset);
  get('refresh').addEventListener('click',()=>{reset();loadedSchema='';work(()=>options(true));});
  get('find').addEventListener('click',()=>work(async()=>{
    reset();await options();search=await post('evidence/search',{schema:get('schema').value,question:question(),anchor:get('anchor').value});
    get('routes').replaceChildren(new Option(t('aitest.evChoose','경로 선택'),''));
    for(const route of search.routes)get('routes').append(new Option(`${route.tables.join(' — ')} · ${t('aitest.evRelations','관계 {0}개',route.relations.length)}`,route.id));
    get('route-field').hidden=!search.routes.length;
    message(!search.routes.length?t('aitest.evNone','연결 근거를 찾지 못했습니다. 기준 테이블이나 질문을 바꿔 주세요.'):search.limited?t('aitest.evLimited','상위 경로만 표시합니다. 기준 테이블로 범위를 좁힐 수 있습니다.'):'');
  }));
  get('routes').addEventListener('change',()=>work(async()=>{
    selected=null;renderEvidence(get('selected'),null);changed();if(!get('routes').value)return;
    selected=await post('evidence/choose',{searchId:search.id,route:get('routes').value});renderEvidence(get('selected'),selected);changed();
  }));
  return {
    controls,invalidate,enabled,current:()=>selected,
    ready:()=>evidenceReady(enabled(),selected,question()),
    matches:value=>evidenceMatches(value,enabled(),selected,question()),
    request:()=>({useOntology:enabled(),evidenceHash:enabled()?evidenceKey(selected):null}),
    async restore(data,refresh){
      const schema=refresh?get('schema').value:data.evidence?.schema||get('schema').value||data.evidenceSchema;
      get('schema').replaceChildren(...data.schemas.map(name=>new Option(name,name)));get('schema').value=data.schemas.includes(schema)?schema:data.evidenceSchema;
      if(refresh){reset();loadedSchema='';}else if(data.evidence&&data.evidence.question===question()){selected=data.evidence;get('enabled').checked=true;renderEvidence(get('selected'),selected);}
      if(enabled()){try{await options();}catch(ex){return ex.message;}}
      return '';
    }
  };
}
