import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
const text=(key,...args)=>t('archive.'+key,key,...args);
const el=(tag,value,css)=>{const n=document.createElement(tag);if(value!==undefined)n.textContent=value;if(css)n.className=css;return n;};
export function archiveControls(status,busy=false){
  const ready=status?.table==='READY'&&status?.job==='READY',source=status?.sourceAccess==='READY',conflict=status?.table==='CONFLICT'||status?.job==='CONFLICT';
  return {INSTALL:!busy&&Boolean(status)&&!conflict&&status.job==='MISSING'&&source,
    CONFIGURE:!busy&&ready&&source,PAUSE:!busy&&ready&&status.enabled,RESUME:!busy&&ready&&!status.enabled&&source,
    RUN:!busy&&ready&&source&&status.state!=='RUNNING',search:!busy&&status?.table==='READY'};
}
export const validInterval=value=>Number.isInteger(Number(value))&&Number(value)>=10&&Number(value)<=3600;
export function archiveAccessControls(access,username,busy=false){
  const ready=Boolean(access&&(access.read||access.select)&&access.createTable&&access.createJob);
  return {check:!busy&&Boolean(username),grant:!busy&&Boolean(access)&&access.username===username&&!ready};
}
export function mountArchive(root){
  const get=name=>root.querySelector(`[data-ar-${name}]`),api=path=>assistantApi('/ai-executions/sql/archive/'+path),
    post=(path,data)=>assistantApi('/ai-executions/sql/archive/'+path,assistantPost(get('csrf'),data));
  let status=null,busy=false,preview=null,previewKind='capture',access=null,page=1,more=false,filters=null;
  // Disabled controls are omitted by FormData, including during the initial status load.
  const readFilters=()=>Object.fromEntries(['from','to','match','sqlId','text'].map(name=>[name,get('filters').elements.namedItem(name).value]));
  function message(value,error=false){get('message').textContent=value;get('message').className=error?'app-alert is-error':'app-filter-message';}
  function lock(value){busy=value;root.setAttribute('aria-busy',String(value));for(const n of root.querySelectorAll('button,input,select'))n.disabled=value;
    const controls=archiveControls(status,value);for(const n of root.querySelectorAll('[data-ar-op]')){
      const op=n.dataset.arOp;n.disabled=!controls[op];
      n.hidden=Boolean(status)&&(op==='INSTALL'?status.table==='READY'&&status.job==='READY':status.job==='MISSING'||op==='PAUSE'&&!status.enabled||op==='RESUME'&&status.enabled);
    }
    get('search').disabled=!controls.search;get('prev').disabled=value||page<=1||!filters;get('next').disabled=value||!more||page>=1000;
    get('apply').disabled=value||!preview||!get('consent').checked;
    if(get('access')){const a=archiveAccessControls(access,get('access-user').value,value);get('access-check').disabled=!a.check;get('access-grant').disabled=!a.grant;}
  }
  function renderAccess(data){access=data;get('access-status').textContent=data.username+' · '+
    [['V$SQL READ/SELECT',data.read||data.select],['CREATE TABLE',data.createTable],['CREATE JOB',data.createJob]]
      .map(([name,granted])=>name+': '+text(granted?'access.present':'access.missing')).join(' · ');}
  async function loadAccess(){if(!get('access'))return;access=null;const selected=get('access-user').value,users=await api('access/users');
    get('access-user').replaceChildren(el('option',text('access.choose')));get('access-user').firstChild.value='';
    for(const name of users){const option=el('option',name);option.value=name;get('access-user').append(option);}
    get('access-user').value=users.includes(selected)?selected:'';
    if(get('access-user').value)renderAccess(await api('access?'+new URLSearchParams({username:selected})));
    else get('access-status').textContent=text('access.choose');
  }
  function renderStatus(s){get('status').replaceChildren();
    for(const [key,value] of [['owner',s.owner],['table','DBC_SQL_CACHE_ARCHIVE · '+s.table],['job','DBC_SQL_CACHE_CAPTURE · '+s.job],['state',s.job==='READY'?(s.enabled?text('enabled'):text('disabled'))+' · '+s.state:'—'],['currentInterval',s.interval||'—'],['lastRun',[s.lastRun,s.lastStatus,s.lastError&&s.lastError!=='0'?'ORA-'+s.lastError:''].filter(Boolean).join(' · ')||'—'],['nextRun',s.nextRun||'—'],['sourceAccess',s.sourceAccess]]){
      const box=el('div');box.append(el('dt',text(key)),el('dd',value));get('status').append(box);
    }
    const seconds=/^FREQ=SECONDLY;INTERVAL=(\d+)$/.exec(s.interval);if(seconds)get('seconds').value=seconds[1];
  }
  function clearRows(note){get('rows').replaceChildren();get('empty').textContent=note;get('page').textContent='';more=false;}
  async function list(){clearRows(text('loading'));const result=await api('list?'+new URLSearchParams({...filters,page}));more=result.more;
    for(const row of result.rows){const tr=el('tr');tr.append(el('td',row.LAST_SEEN),el('td',row.SQL_ID),el('td',`${row.INSTANCE_ID} / ${row.CHILD_NUMBER}`),el('td',row.EXECUTIONS));
      const td=el('td'),b=el('button',row.SQL_PREVIEW||'SQL','app-inline-link');b.type='button';b.addEventListener('click',()=>detail(row.CURSOR_KEY));td.append(b);tr.append(td);get('rows').append(tr);}
    get('empty').textContent=result.rows.length?'':text('empty');get('page').textContent=text('page',page,result.rows.length);
  }
  async function load(){if(busy)return;status=null;lock(true);message(text('loading'));clearRows('');try{status=await api('status');renderStatus(status);await loadAccess();
    if(status.table==='READY'){page=1;filters=readFilters();await list();}else clearRows(text('notInstalled'));
    message(status.table==='CONFLICT'||status.job==='CONFLICT'?text('conflict'):text('ready'));return true;
  }catch(e){get('status').replaceChildren();clearRows(text('queryFailed'));message(e.message,true);return false;}finally{lock(false);}}
  async function prepare(operation){if(busy)return;if(!validInterval(get('seconds').value)){message(text('invalid'),true);return;}lock(true);message(text('loading'));preview=null;previewKind='capture';
    try{preview=await post('preview',{operation,seconds:Number(get('seconds').value)});get('title').textContent=text('preview');get('note').textContent=preview.owner+' · '+text('scope');
      get('sql').textContent=preview.statements.map(s=>s+(s.trim().endsWith('END;')?'\n/':';')).join('\n\n');get('consent').checked=false;get('confirm').hidden=false;get('dialog').showModal();message('');
    }catch(e){message(e.message,true);}finally{lock(false);}}
  async function detail(key){if(busy)return;lock(true);message(text('loading'));preview=null;try{const data=await api('detail?'+new URLSearchParams({key}));
    get('title').textContent=text('detail');get('note').textContent=Object.entries(data).filter(([k])=>k!=='SQL_FULLTEXT').map(([k,v])=>`${k}: ${v??'—'}`).join(' · ');
    get('sql').textContent=data.SQL_FULLTEXT||text('empty');get('confirm').hidden=true;get('dialog').showModal();message('');
  }catch(e){message(e.message,true);}finally{lock(false);}}
  get('help').addEventListener('click',async()=>{if(busy)return;lock(true);preview=null;message(text('loading'));try{const data=await api('collector');get('title').textContent='SQL / PLSQL';get('note').textContent=text('helpNote');get('sql').textContent=data.sql;get('confirm').hidden=true;get('dialog').showModal();message('');}catch(e){message(e.message,true);}finally{lock(false);}});
  get('close').addEventListener('click',()=>{if(!busy){preview=null;get('dialog').close();}});
  get('dialog').addEventListener('cancel',e=>{if(busy)e.preventDefault();else preview=null;});
  get('consent').addEventListener('change',()=>lock(busy));get('refresh').addEventListener('click',load);
  for(const b of root.querySelectorAll('[data-ar-op]'))b.addEventListener('click',()=>prepare(b.dataset.arOp));
  if(get('access')){
    get('access-user').addEventListener('change',()=>{access=null;get('access-status').textContent=text('access.check');lock(busy);});
    get('access-check').addEventListener('click',async()=>{if(busy)return;access=null;lock(true);message(text('loading'));try{renderAccess(await api('access?'+new URLSearchParams({username:get('access-user').value})));message('');}catch(e){get('access-status').textContent=e.message;message(e.message,true);}finally{lock(false);}});
    get('access-grant').addEventListener('click',async()=>{if(busy)return;lock(true);message(text('loading'));preview=null;previewKind='access';try{
      preview=await post('access/preview',{username:get('access-user').value});get('title').textContent=text('access.grant');
      get('note').textContent=preview.database+' · '+preview.before.username+' · '+text('access.scope');get('sql').textContent=preview.statements.map(s=>s+';').join('\n');
      get('consent').checked=false;get('confirm').hidden=false;get('dialog').showModal();message('');
    }catch(e){access=null;message(e.message,true);}finally{lock(false);}});
  }
  get('apply').addEventListener('click',async()=>{if(busy||!preview||!get('consent').checked)return;const token=preview.token,grant=previewKind==='access';preview=null;lock(true);message(text('applying'));
    let applied=false;try{await post(grant?'access/apply':'apply',{token,consent:true});applied=true;get('dialog').close();}catch(e){status=null;access=null;get('dialog').close();message(e.message,true);}finally{lock(false);}
    if(applied&&await load())message(text(grant?'access.applied':'applied'));
  });
  get('filters').addEventListener('submit',async e=>{e.preventDefault();if(busy)return;filters=readFilters();page=1;lock(true);message(text('loading'));try{await list();message('');}catch(ex){clearRows(text('queryFailed'));message(ex.message,true);}finally{lock(false);}});
  for(const [name,delta] of [['prev',-1],['next',1]])get(name).addEventListener('click',async()=>{if(busy)return;page+=delta;lock(true);message(text('loading'));try{await list();message('');}catch(e){clearRows(text('queryFailed'));message(e.message,true);}finally{lock(false);}});
  load();
}
if(typeof document!=='undefined')document.querySelectorAll('[data-sql-archive]').forEach(mountArchive);
