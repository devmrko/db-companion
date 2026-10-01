import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
const label=key=>t('callable.'+key,key);
const node=(tag,text)=>{const n=document.createElement(tag);n.textContent=text;return n;};
export function requestOf(values){return {question:values.question,profile:values.profile,glossary:Boolean(values.glossary),ontology:Boolean(values.ontology),tables:values.ontology?values.tables:[],mode:values.mode,maxRows:Number(values.maxRows)};}
export function canInstall(status){return ['MISSING','INCOMPLETE'].includes(status?.state);}
export function mountCallable(root){
  const get=name=>root.querySelector(`[data-ca-${name}]`),api=path=>assistantApi('/db/functions/ai-query/'+path),post=(path,value)=>assistantApi('/db/functions/ai-query/'+path,assistantPost(get('csrf'),value));
  let status=null,busy=false,preview=null,kind=null,timer=null,access=null;
  function message(value){get('message').textContent=value;}
  function lock(value){busy=value;root.setAttribute('aria-busy',String(value));for(const n of root.querySelectorAll('button,input,select,textarea'))n.disabled=value;
    get('install').disabled=value||!canInstall(status);get('test').disabled=value||status?.state!=='READY';get('apply').disabled=value||!preview||!get('consent').checked;
    get('ontology-panel').hidden=!get('ontology').checked;get('spinner').hidden=!value;
    if(get('access')){get('access-check').disabled=value||!get('access-user').value;get('access-grant').disabled=value||!access||access.granted||access.username!==get('access-user').value;}
    if(timer)clearInterval(timer);timer=null;
    if(value){const start=Date.now();get('elapsed').textContent='0.0 s';timer=setInterval(()=>{get('elapsed').textContent=((Date.now()-start)/1000).toFixed(1)+' s';},100);}
  }
  function renderStatus(s){status=s;get('status').textContent=s.owner+' · '+label('state.'+s.state);get('errors').textContent=s.errors.join('\n');get('errors').hidden=!s.errors.length;
    const current=get('profile').value;get('profile').replaceChildren();for(const profile of s.profiles){const o=node('option',profile);o.value=profile;get('profile').append(o);}if(s.profiles.includes(current))get('profile').value=current;
    get('tables').replaceChildren();for(const table of s.tables){const l=node('label',''),c=document.createElement('input');c.type='checkbox';c.value=table;l.append(c,node('span',table));get('tables').append(l);}if(!s.tables.length)get('tables').append(node('p',label('noTables')));
  }
  async function load(){if(busy)return;lock(true);try{renderStatus(await api('status'));if(get('access')){const users=await api('access/users');get('access-user').replaceChildren(node('option',''));for(const user of users){const o=node('option',user);o.value=user;get('access-user').append(o);}access=null;get('access-status').textContent='';}message('');}catch(e){status=null;message(e.message);}finally{lock(false);}}
  function show(title,note,text,confirm){get('dialog-title').textContent=title;get('dialog-note').textContent=note;get('preview').textContent=text;get('confirm').hidden=!confirm;get('consent').checked=false;get('consent-label').textContent=note;get('dialog').showModal();}
  function render(data){get('result').hidden=false;get('payload').textContent=JSON.stringify(data,null,2);get('sql-result').hidden=!data.sql;get('sql-result').textContent=data.sql||'';
    get('timing').textContent=Object.entries(data.timing||{}).map(([k,v])=>`${k}: ${(v/1000).toFixed(2)} s`).join(' · ');
    get('summary').replaceChildren();for(const term of data.glossary?.definitions||[])get('summary').append(node('p',`${term.term} · v${term.revision} — ${term.definition}`));
    for(const table of data.ontology||[])get('summary').append(node('p',`${table.table} · v${table.revision} · ${table.concept||''} · ${table.relations.length} relations`));
    get('grid').replaceChildren();if(data.result){const table=node('table','');table.className='app-table';const head=node('thead',''),tr=node('tr','');for(const c of data.result.columns)tr.append(node('th',c));head.append(tr);table.append(head);const body=node('tbody','');for(const row of data.result.rows){const r=node('tr','');for(const cell of row)r.append(node('td',cell===null?'NULL':String(cell)));body.append(r);}table.append(body);get('grid').append(table);if(data.result.more)get('grid').append(node('p',label('more')));}
  }
  async function execute(){const p=preview,operation=kind;preview=null;get('dialog').close();lock(true);message(label('running'));
    try{if(operation==='grant'){access=await post('access/grant',{token:p.token,consent:true});get('access-status').textContent=access.username+' · CREATE PROCEDURE: '+label(access.granted?'granted':'missing');message(label('done'));}
      else if(operation==='install'){renderStatus(await post('install',{token:p.token,consent:true}));message(label('installed'));}
      else{const data=await post('run',{token:p.token,consent:true});render(data);message(label('done'));}
    }catch(e){message(e.message+' · '+label('noRetry'));}finally{lock(false);}}
  get('refresh').addEventListener('click',load);
  if(get('access')){
    get('access-user').addEventListener('change',()=>{access=null;get('access-status').textContent='';lock(false);});
    get('access-check').addEventListener('click',async()=>{if(busy)return;lock(true);try{access=await api('access?'+new URLSearchParams({username:get('access-user').value}));get('access-status').textContent=access.username+' · CREATE PROCEDURE: '+label(access.granted?'granted':'missing');}catch(e){access=null;message(e.message);}finally{lock(false);}});
    get('access-grant').addEventListener('click',async()=>{if(busy)return;lock(true);try{preview=await post('access/preview',{username:get('access-user').value});kind='grant';show(label('accessGrant'),label('accessHelp'),preview.sql,true);}catch(e){message(e.message);}finally{lock(false);}});
  }
  get('script').addEventListener('click',async()=>{if(busy)return;preview=null;lock(true);try{show(label('script'),label('installNote'),(await api('script')).sql,false);}catch(e){message(e.message);}finally{lock(false);}});
  get('install').addEventListener('click',async()=>{if(busy)return;lock(true);try{preview=await post('install-preview',{});kind='install';show(label('install'),label('installNote'),preview.sql,true);}catch(e){message(e.message);}finally{lock(false);}});
  get('test').addEventListener('click',async()=>{if(busy)return;get('result').hidden=true;preview=null;message(label('running'));
    const request=requestOf({question:get('question').value,profile:get('profile').value,glossary:get('glossary').checked,ontology:get('ontology').checked,tables:[...get('tables').querySelectorAll('input:checked')].map(n=>n.value),mode:get('mode').value,maxRows:get('limit').value});
    lock(true);try{preview=await post('preview',request);kind='run';if(request.mode==='CONTEXT'){await execute();return;}
      show(label('test'),label(request.mode==='SQL'?'sqlConsent':'queryConsent'),JSON.stringify(request,null,2),true);
    }catch(e){message(e.message);}finally{lock(false);}});
  get('ontology').addEventListener('change',()=>lock(false));get('consent').addEventListener('change',()=>{get('apply').disabled=busy||!preview||!get('consent').checked;});get('apply').addEventListener('click',()=>{if(!busy&&preview&&get('consent').checked)execute();});
  get('close').addEventListener('click',()=>{preview=null;get('dialog').close();});get('dialog').addEventListener('cancel',()=>{preview=null;});
  load();return ()=>{if(timer)clearInterval(timer);};
}
if(typeof document!=='undefined'){const root=document.querySelector('[data-callable]');if(root)mountCallable(root);}
