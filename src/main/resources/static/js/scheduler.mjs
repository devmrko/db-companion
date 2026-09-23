import {t} from './i18n.mjs';
import {pageOf} from './table-list.mjs';
import {mountSourceViewer} from './source-viewer.mjs';
import {requestGate} from './external-sources.mjs';

const label=key=>t('scheduler.'+key,key);
export function schedulerPage(items,term,state,page){return pageOf(items.filter(item=>!state||item.STATE===state).map(item=>({...item,name:item.JOB_NAME,description:[item.COMMENTS,item.JOB_TYPE,item.STATE,item.ENABLED].filter(Boolean).join(' ')})),term,page);}
export const jobStates=items=>[...new Set(items.map(item=>item.STATE).filter(Boolean))].sort();
export function schedulerMessage(data){return [data.status==='AVAILABLE'?'':label(data.status),data.source,data.error].filter(Boolean).join(' · ');}
export const codeSections=data=>data.action.type==='PLSQL_BLOCK'?[{type:'PLSQL_BLOCK',text:data.action.text??''}]:[];
export function historyState(){let cursors=[''],position=0;return {current:()=>cursors[position],number:()=>position+1,canBack:()=>position>0,back(){if(position)position--;},next(cursor){if(!cursor)return;position++;cursors=cursors.slice(0,position);cursors.push(cursor);}};}
const node=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text??'—';if(cls)e.className=cls;return e;};
function table(labels,rows){
  const wrap=node('div',undefined,'table-responsive'),grid=node('table',undefined,'table app-table'),head=node('thead'),tr=node('tr'),body=node('tbody');
  labels.forEach(text=>{const th=node('th',text);th.scope='col';tr.append(th);});head.append(tr);grid.append(head);
  rows.forEach(values=>{const row=node('tr');values.forEach(value=>{const td=node('td');td.append(value instanceof Node?value:node('span',value==null||value===''?'—':String(value)));row.append(td);});body.append(row);});grid.append(body);wrap.append(grid);return wrap;
}
function button(text,action,cls='app-credential-link'){const b=node('button',text,cls);b.type='button';b.addEventListener('click',action);return b;}
const loading=()=>node('p',t('ui.8bf609c884ca','불러오는 중…'),'app-empty');
if(typeof document!=='undefined')document.querySelectorAll('[data-scheduler]').forEach(root=>{
  const get=key=>root.querySelector(`[data-scheduler-${key}]`),tabs=[...root.querySelectorAll('[data-scheduler-tab]')];
  const listGate=requestGate(),tabGate=requestGate(),logGate=requestGate();
  let items=[],page=1,current='',tab='settings',busy=false,nextCursor='',history=historyState();
  function checked(host,data){const time=new Date(data.checkedAt);host.textContent=[data.source,data.checkedAt?`${label('checkedAt')} ${Number.isNaN(time.getTime())?data.checkedAt:time.toLocaleString(document.documentElement.lang)}`:''].filter(Boolean).join(' · ');}
  async function api(path,params,signal){
    const url=new URL(root.dataset.base+path,location.origin);url.searchParams.set('schema',root.dataset.schema);
    Object.entries(params).forEach(([key,value])=>url.searchParams.set(key,value));
    const response=await fetch(url,{cache:'no-store',headers:{Accept:'application/json'},signal});
    if(response.redirected||response.status===401)throw new Error(t('ui.b9c067f345b1','로그인 세션이 만료되었습니다. 다시 로그인해 주세요.'));
    if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(`HTTP ${response.status}`);
    const data=await response.json();if(!response.ok)throw new Error(data.error||`HTTP ${response.status}`);return data;
  }
  function render(){
    const result=schedulerPage(items,get('filter').value,get('state').value,page);page=result.page;
    get('list').replaceChildren(table(['name','comments','enabled','state','lastStart','nextRun'].map(label),result.items.map(item=>{
      const link=button(item.JOB_NAME,()=>select(item.JOB_NAME));link.disabled=busy;link.setAttribute('aria-pressed',String(current===item.JOB_NAME));
      const comment=node('span',item.COMMENTS||'—','app-scheduler-comment');comment.title=item.COMMENTS??'';
      return [link,comment,item.ENABLED,item.STATE,item.LAST_START_DATE,item.NEXT_RUN_DATE];
    })));
    get('count').textContent=t('ui.f32c9f13d498','{0}–{1} / {2}개',result.from,result.to,result.total);
    get('page').textContent=`${result.pages?page:0} / ${result.pages}`;get('prev').disabled=page<=1;get('next').disabled=page>=result.pages;get('refresh').disabled=busy;
  }
  function properties(host,title,data){
    if(data.status==='NOT_APPLICABLE')return;
    const section=node('section',undefined,'app-dds-section');section.append(node('h3',label(title)));
    if(data.status!=='AVAILABLE')section.append(node('p',schedulerMessage(data),'app-filter-message'));
    else if(!data.items.length)section.append(node('p',label('empty'),'app-filter-message'));
    else for(const row of data.items)section.append(table([label('field'),label('value')],Object.entries(row).map(([key,value])=>[key,node('span',value??'—','app-scheduler-value')])));
    host.append(section);
  }
  function settings(data){
    const host=get('panel');host.replaceChildren();const stamp=node('p',undefined,'app-filter-message');checked(stamp,data.job);host.append(stamp);
    properties(host,'settings',data.job);properties(host,'program',data.program);properties(host,'schedule',data.schedule);
    properties(host,'arguments',data.arguments);properties(host,'programArguments',data.programArguments);
  }
  function showCode(data){
    const host=get('panel');host.replaceChildren();const heading=node('div',undefined,'app-card-heading');heading.append(node('span',data.action.type||'—','app-badge'),node('span',label('currentDefinition'),'app-filter-message'));host.append(heading);
    if(data.status!=='AVAILABLE')host.append(node('p',schedulerMessage(data),'app-filter-message'));
    const sections=codeSections(data);
    if(sections.length){mountSourceViewer(host,sections,null);return;}
    if(data.action.text)host.append(node('pre',data.action.text,'app-preview-source'));
    for(const definition of data.definitions){
      const section=node('section',undefined,'app-function-definition');section.append(node('h3',[definition.owner,definition.object,definition.member].filter(Boolean).join('.'),'app-object-title'));host.append(section);
      if(definition.sections.length)mountSourceViewer(section,definition.sections,definition.member);
      else section.append(node('p',label('UNRESOLVED'),'app-empty'));
    }
    if(data.status==='AVAILABLE'&&!['PLSQL_BLOCK','STORED_PROCEDURE'].includes(data.action.type))host.append(node('p',label('actionOnly'),'app-filter-message'));
  }
  async function showLog(id){
    const request=logGate.start(),host=get('log');host.hidden=false;host.replaceChildren(loading());
    try{const data=await api('/run',{name:current,id},request.signal);if(!request.current())return;host.replaceChildren();properties(host,'runDetail',data);}
    catch(ex){if(request.current()&&ex.name!=='AbortError')host.replaceChildren(node('p',ex.message,'app-alert is-error'));}
  }
  function runs(data){
    nextCursor=data.next;const host=get('panel');host.replaceChildren();const stamp=node('p',undefined,'app-filter-message');checked(stamp,data.rows);host.append(stamp);
    if(data.rows.status!=='AVAILABLE'){host.append(node('p',schedulerMessage(data.rows),'app-alert is-warning'));return;}
    if(!data.rows.items.length)host.append(node('p',label('noRuns'),'app-empty'));
    else host.append(table(['logId','step','started','state','duration','errorCode'].map(label),data.rows.items.map(row=>[
      button(row.LOG_ID,()=>showLog(row.LOG_ID)),row.JOB_SUBNAME,row.ACTUAL_START_DATE,row.STATUS,row.RUN_DURATION,row['ERROR#']
    ])));
    const controls=node('div',undefined,'app-dds-pages'),group=node('div');
    const previous=button(t('ui.da7e61c67cc5','이전'),()=>{history.back();loadTab();},'btn app-btn app-btn-quiet');previous.disabled=!history.canBack();
    const next=button(t('ui.aef613c6612d','다음'),()=>{history.next(nextCursor);loadTab();},'btn app-btn app-btn-quiet');next.disabled=!nextCursor;
    group.append(previous,node('span',label('page')+' '+history.number()),next);controls.append(node('span',String(data.rows.items.length)),group);host.append(controls);
  }
  async function loadTab(){
    if(!current||busy)return;const request=tabGate.start();logGate.cancel();get('log').hidden=true;get('log').replaceChildren();get('panel').replaceChildren(loading());
    get('detail').hidden=false;tabs.forEach(item=>item.setAttribute('aria-pressed',String(item.dataset.schedulerTab===tab)));
    try{
      const path=tab==='settings'?'/detail':tab==='code'?'/code':'/runs';
      const data=await api(path,{name:current,...(tab==='history'?{before:history.current()}:{})},request.signal);if(!request.current())return;
      if(tab==='settings')settings(data);else if(tab==='code')showCode(data);else runs(data);
    }catch(ex){if(request.current()&&ex.name!=='AbortError')get('panel').replaceChildren(node('p',ex.message,'app-alert is-error'));}
  }
  function select(name){if(busy)return;current=name;tab='settings';history=historyState();get('target').textContent=root.dataset.schema+'.'+name;render();loadTab();}
  async function list(refresh=false){
    const request=listGate.start(),selected=current;busy=true;tabGate.cancel();logGate.cancel();get('detail').hidden=true;get('panel').replaceChildren();get('log').replaceChildren();items=[];render();get('message').textContent=t('ui.8bf609c884ca','불러오는 중…');
    try{
      const data=await api('/list',{refresh},request.signal);if(!request.current())return;
      items=data.items;checked(get('checked'),data);get('message').textContent=data.status==='AVAILABLE'?(items.length?'':label('empty')):schedulerMessage(data);
      const oldState=get('state').value;get('state').replaceChildren(new Option(label('allStates'),''),...jobStates(items).map(state=>new Option(state,state)));get('state').value=jobStates(items).includes(oldState)?oldState:'';
      page=1;busy=false;render();history=historyState();
      if(data.status==='AVAILABLE'&&items.some(item=>item.JOB_NAME===selected)){current=selected;await loadTab();}else current='';
    }catch(ex){if(request.current()&&ex.name!=='AbortError')get('message').textContent=ex.message;}
    finally{if(request.current()){busy=false;render();}}
  }
  tabs.forEach(item=>item.addEventListener('click',()=>{if(busy||tab===item.dataset.schedulerTab)return;tab=item.dataset.schedulerTab;loadTab();}));
  get('refresh').addEventListener('click',()=>{if(!busy)list(true);});
  get('filter').addEventListener('input',()=>{page=1;render();});get('state').addEventListener('change',()=>{page=1;render();});
  get('prev').addEventListener('click',()=>{page--;render();});get('next').addEventListener('click',()=>{page++;render();});
  list();
});
