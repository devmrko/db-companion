import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
import {renderHistory,historyStoragePresentation} from './business-glossary-history.mjs';
import {renderTextSetup} from './business-glossary-setup.mjs';

const label=(key,fallback,...args)=>t('businessGlossary.'+key,fallback,...args);
const el=(tag,text,css)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(css)node.className=css;return node;};
const button=(text,fn)=>{const node=el('button',text,'btn app-btn app-btn-quiet');node.type='button';node.addEventListener('click',fn);return node;};
const kind=value=>value==='TERM'?label('termMatch','대표 용어 일치'):value==='ALIAS'?label('aliasMatch','등록 별칭 일치'):label('textMatch','형태소 기반 설명 검색');
export const MAX_GLOSSARY_DEFINITIONS=30;
export const automaticSelection=search=>!search||search.more||search.hits.length>MAX_GLOSSARY_DEFINITIONS?null:search.hits.map(h=>h.term.id);
export const validSelection=(search,ids,question,profile,now=Date.now())=>Boolean(search&&!search.more&&search.question===question&&search.profile===profile&&new Date(search.expires).getTime()>now&&ids.length===search.hits.length&&ids.length<=MAX_GLOSSARY_DEFINITIONS&&new Set(ids).size===ids.length&&ids.every(id=>search.hits.some(h=>h.term.id===id)));
export const selectionRequest=(enabled,search,ids)=>({glossary:enabled?{enabled:true,searchId:search?.id,termIds:[...ids]}:{enabled:false}});
export const sameSelection=(snapshot,enabled,search,ids)=>!enabled?!snapshot:Boolean(snapshot&&search&&snapshot.question===search.question&&snapshot.profile===search.profile&&snapshot.selected.length===ids.length&&snapshot.selected.every(h=>ids.includes(h.term.id)&&search.hits.some(v=>v.term.id===h.term.id&&v.term.revision===h.term.revision)));

export function renderTokenAnalysis(host,analysis){
  host.replaceChildren();if(!analysis)return;
  if(analysis.language)host.append(el('p',t('questionAnalysis.applied','적용 언어: {0} · Lexer: {1} · 정책: {2}',analysis.language,analysis.lexer||'—',analysis.policy||'—'),'app-filter-message'));
  if(analysis.mode==='ORACLE_TEXT_UNCONFIGURED'){
    host.append(el('p',t('questionAnalysis.missing','질문 분석용 Oracle Text 설정이 없습니다. 현재 테이블명·등록 명칭의 정확 일치만 확인했으며, 설명 기반 검색은 수행하지 못했습니다. 업무 용어 사전 테이블·인덱스는 필요하지 않습니다.'),'app-alert'));
    const help=el('a',t('questionAnalysis.settingsLink','질문 분석 언어·설정 확인'),'app-link');help.href='#question-analysis-settings';host.append(help);return;
  }
  if(analysis.mode!=='ORACLE_TEXT'){host.append(el('p',label('exactOnly','정확·별칭 검색만 수행했습니다. 형태소 검색은 호출하지 않았습니다.'),'app-filter-message'));return;}
  host.append(el('h3',label('tokens','Oracle 형태소 분석 토큰')));
  const tokens=el('div',undefined,'app-glossary-chips');
  for(const text of [...new Set((analysis.tokens||[]).map(v=>v.token))])tokens.append(el('span',text,'app-glossary-chip'));
  if(!tokens.childNodes.length)tokens.append(el('p',label('noTokens','추출된 검색 토큰이 없습니다.')));
  host.append(tokens);
}

/** All database and question strings are text nodes. Never render dictionary content as HTML. */
export function renderSearch(host,search,ids=[],changed=null){
  host.replaceChildren();if(!search)return;
  const original=el('details');original.append(el('summary',label('original','검색한 원 질문')),el('pre',search.question,'app-preview-value'));host.append(original);
  host.append(el('h3',label('targets','검색 대상')));
  const targets=el('div',undefined,'app-glossary-chips');
  for(const target of search.targets||[])targets.append(el('span',`${target.expression} · ${kind(target.kind)}`,'app-glossary-chip'));
  if(!targets.childNodes.length)targets.append(el('p',label('noExact','원문에 일치하는 등록 용어·별칭이 없습니다.'),'app-filter-message'));
  host.append(targets);
  if(search.mode==='ORACLE_TEXT'){
    const tokens=el('div');renderTokenAnalysis(tokens,search);host.append(tokens);
    const query=el('details');query.append(el('summary',label('query','실제 Oracle Text 검색식')),el('pre',search.textQuery||'—','app-preview-value'));host.append(query);
  }else host.append(el('p',label('exactOnly','정확·별칭 검색만 수행했습니다. 형태소 검색은 호출하지 않았습니다.'),'app-filter-message'));
  host.append(el('h3',label('definitions','검색된 용어 정의')));
  if(search.more)host.append(el('p',label('more','후보는 최대 30개까지 표시합니다. 더 정확한 질문으로 범위를 좁힐 수 있습니다.'),'app-alert'));
  if(!search.hits?.length)host.append(el('p',label('noHits','검색된 정의가 없습니다. 용어를 등록하거나 표현을 바꿔 주세요.')));
  for(const hit of search.hits||[]){
    const card=el('section',undefined,'app-glossary-hit'),heading=el('label',undefined,'app-glossary-pick');
    if(changed){const check=el('input');check.type='checkbox';check.checked=ids.includes(hit.term.id);check.dataset.termId=hit.term.id;check.addEventListener('change',()=>changed(hit.term.id,check.checked));heading.append(check);}
    heading.append(el('strong',`${hit.term.term} · v${hit.term.revision}`));card.append(heading);
    card.append(el('p',[kind(hit.kind),...(hit.expressions||[])].join(' · '),'app-filter-message'));
    if(hit.term.aliases.length)card.append(el('p',label('aliases','별칭')+': '+hit.term.aliases.join(', ')));
    card.append(el('p',hit.term.definition,'app-glossary-definition'));
    if(hit.term.criteria){const detail=el('details');detail.append(el('summary',label('criteria','집계·필터·SQL 기준 (실행하지 않음)')),el('pre',hit.term.criteria,'app-preview-value'));card.append(detail);}
    host.append(card);
  }
}
export function renderGlossarySnapshot(host,snapshot){
  if(!snapshot)return;
  const details=el('details',undefined,'app-glossary-snapshot');details.append(el('summary',label('snapshot','이 요청에 전달한 업무 용어 정의')));
  const body=el('div');renderSearch(body,{...snapshot,hits:snapshot.selected});details.append(body);host.append(details);
}

/** Optional single-test integration. No table means disabled; lookup failure is not absence. */
export function mountBusinessGlossary(root,{csrf,question,profile,busy,setBusy,changed,message}){
  const host=root.querySelector('[data-business-glossary]');if(!host)return {load:async()=>{},controls:()=>{},invalidate:()=>{},ready:()=>true,request:()=>({}),matches:s=>!s,enabled:()=>false};
  let search=null,ids=[],status=null,sequence=0;
  const toggle=host.querySelector('[data-glossary-enabled]'),panel=host.querySelector('[data-glossary-panel]'),mode=host.querySelector('[data-glossary-mode]'),find=host.querySelector('[data-glossary-find]'),result=host.querySelector('[data-glossary-results]'),note=host.querySelector('[data-glossary-note]'),count=host.querySelector('[data-glossary-count]');
  const enabled=()=>toggle.checked;
  const ready=()=>!enabled()||validSelection(search,ids,question(),profile());
  function controls(){toggle.disabled=busy()||status?.table!=='READY';mode.disabled=busy();find.disabled=busy()||!question().trim()||!profile();for(const input of result.querySelectorAll('input'))input.disabled=busy();count.textContent=label('selected','자동 첨부 정의 {0} / {1}개',ids.length,MAX_GLOSSARY_DEFINITIONS);}
  function invalidate(){sequence++;search=null;ids=[];result.replaceChildren();note.textContent=enabled()?label('searchAgain','현재 질문·프로필로 다시 검색해 주세요. 검색된 정의는 자동 첨부됩니다.'):'';changed();}
  toggle.addEventListener('change',async()=>{panel.hidden=!enabled();invalidate();if(enabled()&&question().trim()&&profile())await findDefinitions();});
  mode.addEventListener('change',async()=>{invalidate();if(enabled()&&question().trim()&&profile())await findDefinitions();});
  async function load(){const current=++sequence;try{const value=await assistantApi('/business-glossary/status');if(current!==sequence)return;status=value;toggle.checked=false;panel.hidden=true;mode.querySelector('[value="TEXT"]').disabled=value.text!=='READY';mode.value=value.text==='READY'?'TEXT':'EXACT';note.textContent=value.table==='MISSING'?label('missing','업무 용어 사전이 없습니다. 사전 관리에서 생성해 주세요.'):value.table!=='READY'?label('mismatch','사전 구조 확인이 필요합니다. 기존 객체는 자동 변경하지 않습니다.'):value.text!=='READY'?label('textMissing','정확·별칭 검색 가능 · 형태소 검색은 사전 관리에서 별도 설정하세요.'):label('available','등록 용어·별칭과 Oracle 형태소 검색을 사용할 수 있습니다.');}catch(ex){status=null;toggle.checked=false;panel.hidden=true;note.textContent=ex.message;}finally{controls();}}
  async function findDefinitions(){
    if(busy())return;search=null;ids=[];result.replaceChildren();const current=++sequence,q=question(),p=profile();setBusy(true);note.textContent=label('searching','사전 검색 중… AI는 호출하지 않습니다.');
    try{const value=await assistantApi('/business-glossary/search',assistantPost(csrf,{question:q,profile:p,useText:mode.value==='TEXT'}));if(current!==sequence||q!==question()||p!==profile())return;search=value;const matched=automaticSelection(search);ids=matched??[];renderSearch(result,search);note.textContent=matched===null?label('autoLimit','검색된 정의가 전송 한도를 초과했습니다. 일부만 첨부하지 않습니다. 질문 범위를 좁혀 다시 검색해 주세요.'):ids.length?label('autoAttached','검색된 정의 {0}개를 자동 첨부합니다. 아래 내용을 확인해 주세요.',ids.length):label('autoEmpty','일치하는 정의가 없어 용어 참고정보 없이 진행합니다.');}
    catch(ex){search=null;note.textContent=ex.message;message(ex.message,true);}finally{setBusy(false);changed();controls();}
  }
  find.addEventListener('click',findDefinitions);
  return {load,controls,invalidate,ready,enabled,request:()=>selectionRequest(enabled(),search,ids),matches:s=>sameSelection(s,enabled(),search,ids)};
}

if(typeof document!=='undefined')document.querySelectorAll('[data-glossary-manager]').forEach(root=>{
  const get=name=>root.querySelector(`[data-glossary-${name}]`),csrf=get('csrf'),post=(path,data)=>assistantApi('/business-glossary/'+path,assistantPost(csrf,data));
  let status=null,offset=0,more=false,editing=null,setup=null,busy=false,unknownWrite=false,historyState=null,historyTerm=null,historyNext='';
  const show=(text,error=false)=>{get('message').textContent=text;get('message').className=error?'app-alert is-error':'app-filter-message';};
  const lock=value=>{busy=value;for(const node of root.querySelectorAll('button,input,textarea,select'))node.disabled=value;get('create').disabled=value||status?.table!=='MISSING'||unknownWrite;get('text-setup').disabled=value||status?.table!=='READY'||status?.text!=='MISSING'||unknownWrite;get('new').disabled=value||status?.table!=='READY'||historyState!=='READY'||unknownWrite;get('previous').disabled=value||offset===0;get('next').disabled=value||!more;get('save').disabled=value||!get('save-consent').checked||historyState!=='READY'||unknownWrite;get('apply').disabled=value||!setup||!get('setup-consent').checked||unknownWrite;get('search').disabled=value||status?.table!=='READY';get('mode').querySelector('[value="TEXT"]').disabled=status?.text!=='READY';get('history-setup').disabled=value||historyState!=='MISSING'||unknownWrite;get('history-more').disabled=value||!historyNext;};
  async function list(){if(status?.table!=='READY'){get('rows').replaceChildren();return;}const page=await assistantApi('/business-glossary/list?'+new URLSearchParams({filter:get('filter').value,offset}));more=page.more;get('page').textContent=`${offset+1}–${offset+page.rows.length}`;get('rows').replaceChildren();for(const term of page.rows){const row=el('tr');row.append(el('td',term.term),el('td',term.aliases.join(', ')),el('td',`v${term.revision}`),el('td',term.enabled?label('active','사용'):label('inactive','비활성')));const action=el('td');action.append(button(label('edit','조회·편집'),()=>edit(term)),button(label('history.title','변경 이력'),()=>openHistory(term)));row.append(action);get('rows').append(row);}}
  function historyStatus(){const view=historyStoragePresentation(historyState,status?.table);get('history-status').textContent=view.text;get('history-setup').hidden=!view.showSetup;get('history-guide').hidden=!view.showGuide;}
  async function load(){status=null;renderTextSetup(root,status);lock(true);historyState=null;historyStatus();try{status=await assistantApi('/business-glossary/status');get('status').textContent=`${status.owner} · DBC_BUSINESS_TERM: ${status.table}`;renderTextSetup(root,status);unknownWrite=false;await list();show(status.message);historyState=(await assistantApi('/business-glossary/history/status')).storage;}catch(ex){if(!status)renderTextSetup(root,{table:'UNAVAILABLE',text:'UNAVAILABLE'});historyState='UNAVAILABLE';show(ex.message,true);}finally{historyStatus();lock(false);}}
  async function historyPage(){lock(true);get('history-error').textContent='';try{const page=await assistantApi('/business-glossary/history?'+new URLSearchParams({id:historyTerm.id,before:historyNext}));renderHistory(get('history-entries'),page.entries);historyNext=page.next;get('history-empty').hidden=get('history-entries').childElementCount>0;}catch(ex){get('history-error').textContent=ex.message;}finally{lock(false);}}
  async function openHistory(term){historyTerm=term;historyNext='';get('history-entries').replaceChildren();get('history-empty').hidden=true;get('history-title').textContent=`${term.term} · ${label('history.title','변경 이력')}`;get('history-dialog').showModal();await historyPage();}
  get('history-close').addEventListener('click',()=>get('history-dialog').close());get('history-dialog').addEventListener('cancel',event=>{if(busy)event.preventDefault();});get('history-more').addEventListener('click',historyPage);
  function edit(term=null){editing=term;get('term').value=term?.term||'';get('aliases').value=(term?.aliases||[]).join('\n');get('definition').value=term?.definition||'';get('criteria').value=term?.criteria||'';get('enabled').checked=term?.enabled??true;get('save-consent').checked=false;get('edit-error').textContent='';get('edit-title').textContent=term?`${term.term} · v${term.revision}`:label('new','용어 등록');get('editor').showModal();lock(false);}
  get('new').addEventListener('click',()=>edit());get('edit-close').addEventListener('click',()=>get('editor').close());get('editor').addEventListener('cancel',event=>{if(busy)event.preventDefault();});get('save-consent').addEventListener('change',()=>lock(busy));
  get('save').addEventListener('click',async()=>{if(busy||!get('save-consent').checked)return;lock(true);try{await post('save',{id:editing?.id,revision:editing?.revision||0,value:{term:get('term').value,aliases:get('aliases').value.split(/\r?\n/).map(v=>v.trim()).filter(Boolean),definition:get('definition').value,criteria:get('criteria').value,enabled:get('enabled').checked},consent:true});get('editor').close();await list();get('search-result').replaceChildren();show(label('saved','용어를 저장했습니다. 이전 검색 결과는 다시 조회해 주세요.'));}catch(ex){unknownWrite=true;get('edit-error').textContent=ex.message;get('save-consent').checked=false;}finally{lock(false);}});
  async function prepare(operation){lock(true);try{setup=await post('setup-preview',{operation});get('setup-sql').textContent=setup.statements.map(sql=>sql.trim()+(sql.trim().endsWith('END;')?'\n/':';')).join('\n\n');get('setup-consent').checked=false;get('setup-dialog').showModal();}catch(ex){show(ex.message,true);}finally{lock(false);}}
  get('create').addEventListener('click',()=>prepare('TABLE'));get('text-setup').addEventListener('click',()=>prepare('TEXT'));get('history-setup').addEventListener('click',()=>prepare('HISTORY'));get('setup-close').addEventListener('click',()=>get('setup-dialog').close());get('setup-dialog').addEventListener('cancel',event=>{if(busy)event.preventDefault();});get('setup-consent').addEventListener('change',()=>lock(busy));
  get('apply').addEventListener('click',async()=>{if(busy||!setup||!get('setup-consent').checked)return;const token=setup.token;setup=null;lock(true);get('setup-dialog').close();try{status=await post('setup',{token,consent:true});await load();}catch(ex){unknownWrite=true;show(ex.message,true);}finally{lock(false);}});
  get('refresh').addEventListener('click',()=>load());get('filter-run').addEventListener('click',async()=>{offset=0;lock(true);try{await list();}catch(ex){show(ex.message,true);}finally{lock(false);}});
  for(const [key,delta] of [['previous',-20],['next',20]])get(key).addEventListener('click',async()=>{offset=Math.max(0,offset+delta);lock(true);try{await list();}catch(ex){show(ex.message,true);}finally{lock(false);}});
  get('search').addEventListener('click',async()=>{lock(true);get('search-result').replaceChildren();try{const result=await post('search',{question:get('question').value,profile:'',useText:get('mode').value==='TEXT'});renderSearch(get('search-result'),result);show(label('searched','검색 완료 · AI 호출 없음'));}catch(ex){show(ex.message,true);}finally{lock(false);}});
  load();
});
