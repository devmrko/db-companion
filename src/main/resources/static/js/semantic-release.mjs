import {t} from './i18n.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {changeHistorySummary} from './change-history-summary.mjs';
const label=(key,...args)=>t('semantic.'+key,key,...args);
const node=(tag,text)=>{const e=document.createElement(tag);e.textContent=text;return e;};
export function selectionItems(versions){return [['DEFAULT',label('default')],['LIVE',label('live')],...versions.map(v=>[v.id,`${v.name} · ${v.id.slice(0,8)}`])];}
export function editableMembers(parent,catalog){
  const terms=new Map(catalog.terms.map(t=>[t.id,{kind:'term',id:t.id,label:t.term,revision:t.revision,included:false}]));
  for(const t of parent.terms)terms.set(t.id,{kind:'term',id:t.id,label:t.term,revision:t.revision,included:true});
  const sources=new Map(catalog.sources.map(s=>[s.table,{kind:'source',id:s.table,label:s.table,revision:s.revision,included:false}]));
  for(const e of parent.entries){const table=e.document.source.table;sources.set(table,{kind:'source',id:table,label:table,revision:e.revision,included:true});}
  return [...terms.values(),...sources.values()];
}
if(typeof document!=='undefined')document.querySelectorAll('[data-semantic-release]').forEach(root=>{
  const get=name=>root.querySelector(`[data-sr-${name}]`);let state,busy=false,editing='',members=[];
  const controls=()=>{for(const key of ['create','deploy','target','initialize','parent','load','revise'])get(key).disabled=busy||state?.storage!=='READY';get('deploy').disabled||=!state?.versions.length;get('load').disabled||=!state?.versions.length;get('revise').disabled||=!editing;for(const button of get('contents').querySelectorAll('button'))button.disabled=busy;};
  const options=(host,items)=>host.replaceChildren(...items.map(([value,text])=>{const e=node('option',text);e.value=value;return e;}));
  const reveal=element=>{for(let ancestor=element.parentElement;ancestor&&ancestor!==root;ancestor=ancestor.parentElement)if(ancestor.tagName==='DETAILS')ancestor.open=true;element.scrollIntoView({block:'nearest'});element.focus();};
  function showContents(snapshot){
    const table=node('table','');table.className='app-table';const head=node('thead',''),header=node('tr','');
    for(const key of ['member','revision'])header.append(node('th',label(key)));head.append(header);table.append(head);const body=node('tbody','');
    const append=(name,revision)=>{const row=node('tr','');row.append(node('td',name),node('td',String(revision)));body.append(row);};
    for(const term of snapshot.content.terms)append(`${label('term')} · ${term.term}`,term.revision);
    for(const entry of snapshot.content.entries)append(`${label('source')} · ${entry.document.source.table}`,entry.revision);
    table.append(body);get('summary').replaceChildren(node('h3',snapshot.content.name),table);
    get('json').textContent=JSON.stringify(snapshot,null,2);get('detail').hidden=false;get('detail').open=true;get('detail').scrollIntoView({block:'nearest'});
  }
  function versionList(data){
    const table=node('table','');table.className='app-table';const head=node('thead',''),header=node('tr','');
    for(const key of ['versionName','status','createdAt','creator','actions'])header.append(node('th',label(key)));head.append(header);table.append(head);
    const body=node('tbody','');
    for(const version of data.versions){
      const row=node('tr',''),actions=node('td',''),toolbar=node('div','');toolbar.className='app-editor-actions';
      const action=(key,work)=>{const button=node('button',label(key));button.type='button';button.className='btn app-btn app-btn-quiet';button.setAttribute('aria-label',`${version.name} · ${label(key)}`);button.addEventListener('click',work);toolbar.append(button);};
      action('view',()=>run(async()=>showContents(await assistantApi(root.dataset.base+'/'+encodeURIComponent(version.id)))));
      action('editCopy',()=>{get('parent').value=version.id;reveal(get('load'));get('load').click();});
      if(version.id!==data.head.releaseId)action('promote',()=>{get('target').value=version.id;get('reason').value='';reveal(get('reason'));});
      actions.append(toolbar);row.append(node('td',version.name),node('td',label(version.id===data.head.releaseId?'operational':'saved')),node('td',version.recordedAt),node('td',version.actor),actions);body.append(row);
    }
    table.append(body);get('contents').replaceChildren(data.versions.length?table:node('p',label('empty')));
  }
  function render(data){state=data;
    editing='';members=[];get('members').replaceChildren();
    options(get('target'),data.versions.map(v=>[v.id,`${v.name} · ${v.id.slice(0,8)}`]));
    options(get('parent'),data.versions.map(v=>[v.id,`${v.name} · ${v.recordedAt}`]));
    get('initialize').hidden=data.storage!=='READY'||!!data.head.releaseId;
    get('current').textContent=label('operationalCurrent',data.versions.find(v=>v.id===data.head.releaseId)?.name||data.head.releaseId||'—');
    versionList(data);
    get('history').replaceChildren(...data.history.map(row=>{const event=JSON.parse(row.payload);const p=node('p',changeHistorySummary({actor:row.actor,at:row.recordedAt,content:`${event.previous||'—'} → ${event.target} · ${event.reason}`}));p.className='app-change-history-summary';return p;}));
    get('message').textContent=data.storage==='READY'?'':label('storage');controls();
  }
  async function run(work){if(busy)return;busy=true;controls();get('message').textContent='';try{await work();}catch(e){get('message').textContent=e.message;}finally{busy=false;controls();}}
  const post=(path,data)=>assistantApi(root.dataset.base+path,assistantPost(get('csrf'),data));
  get('initialize').addEventListener('click',()=>run(async()=>{if(!confirm(label('confirmInitialize')))return;await post('/initialize',{confirmed:true});render(await assistantApi(root.dataset.base));}));
  get('load').addEventListener('click',()=>run(async()=>{
    editing='';members=[];get('members').replaceChildren();
    const parent=await assistantApi(root.dataset.base+'/'+encodeURIComponent(get('parent').value));
    const catalog=await assistantApi(root.dataset.base+'/catalog');editing=parent.id;members=editableMembers(parent.content,catalog);
    const table=node('table','');table.className='table app-table';const header=node('tr','');
    for(const key of ['include','member','revision'])header.append(node('th',label(key)));const thead=node('thead','');thead.append(header);table.append(thead);const body=node('tbody','');
    for(const member of members){const row=node('tr',''),check=document.createElement('input'),revision=document.createElement('input');
      check.type='checkbox';check.checked=member.included;check.setAttribute('aria-label',label('include')+' '+member.label);check.addEventListener('change',()=>member.included=check.checked);
      revision.type='number';revision.min='1';revision.step='1';revision.value=member.revision;revision.className='form-control';revision.setAttribute('aria-label',label('revision')+' '+member.label);revision.addEventListener('input',()=>member.revision=Number(revision.value));
      const first=node('td',''),last=node('td','');first.append(check);last.append(revision);row.append(first,node('td',`${label(member.kind)} · ${member.label}`),last);body.append(row);
    }table.append(body);get('members').replaceChildren(table);get('name').value=parent.content.name+' (copy)';
  }));
  get('parent').addEventListener('change',()=>{editing='';members=[];get('members').replaceChildren();controls();});
  get('revise').addEventListener('click',()=>run(async()=>{
    const chosen=members.filter(m=>m.included);if(!get('name').value.trim()||chosen.some(m=>!Number.isSafeInteger(m.revision)||m.revision<1))throw new Error(label('invalidRevision'));
    if(!confirm(label('confirmRevise')))return;
    await post('/revise',{parentId:editing,name:get('name').value,terms:chosen.filter(m=>m.kind==='term').map(m=>({id:m.id,revision:m.revision})),sources:chosen.filter(m=>m.kind==='source').map(m=>({table:m.id,revision:m.revision})),confirmed:true});
    editing='';members=[];get('members').replaceChildren();render(await assistantApi(root.dataset.base));
  }));
  get('create').addEventListener('click',()=>run(async()=>{if(!get('name').value.trim()||!confirm(label('confirmCreate')))return;await post('/create',{name:get('name').value,confirmed:true});render(await assistantApi(root.dataset.base));}));
  get('deploy').addEventListener('click',()=>run(async()=>{if(!get('reason').value.trim()||!confirm(label('confirmDeploy')))return;render(await post('/deploy',{id:get('target').value,expected:state.head,reason:get('reason').value,confirmed:true}));}));
  run(async()=>render(await assistantApi(root.dataset.base)));
});
