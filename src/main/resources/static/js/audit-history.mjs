import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
const text=(key,...args)=>t('audit.'+key,key,...args);
const el=(tag,value,css)=>{const n=document.createElement(tag);if(value!==undefined)n.textContent=value;if(css)n.className=css;return n;};
export function auditControls(s,busy=false){
 const ready=s?.store==='READY'&&s?.packageState==='READY'&&s?.jobState==='READY',running=s?.job?.STATE==='RUNNING',source=s?.sourceState==='READY';
 return {INSTALL:!busy&&s?.canInstall,CONFIGURE:!busy&&ready&&source&&!running,RUN:!busy&&ready&&source&&!running,PAUSE:!busy&&ready&&s.enabled&&!running,RESUME:!busy&&ready&&source&&!s.enabled&&!running};
}
// Display only, never an authorization decision. Unknown/truncated format falls back to original.
export function rlsFields(raw){
 if(!raw)return [];
 const out=[];let pos=0;const encoder=new TextEncoder();
 while(pos<raw.length){
  pos+=/^[\s(),;]*/.exec(raw.slice(pos))[0].length;if(pos===raw.length)break;
  const m=/^([A-Z_]+)=\[(\d+)\]'/.exec(raw.slice(pos));if(!m)return [];
  pos+=m[0].length;const count=Number(m[2]);let used=0,value='';
  if(count>0)for(const ch of raw.slice(pos)){const bytes=encoder.encode(ch).length;if(used+bytes>count)break;used+=bytes;value+=ch;if(used===count)break;}
  if(used!==count||raw[pos+value.length]!=="'")return [];
  pos+=value.length+1;out.push({name:m[1],value});
 }return out;
}
export const validAuditSettings=(minutes,days)=>Number.isInteger(Number(minutes))&&Number(minutes)>=5&&Number(minutes)<=1440&&Number.isInteger(Number(days))&&Number(days)>=0&&Number(days)<=3650;
export const auditSucceeded=code=>code!=null&&String(code).trim()!==''&&Number(code)===0;
export function mountAudit(root){
 const get=k=>root.querySelector('[data-au-'+k+']'),api=p=>assistantApi('/db/audit/'+p),post=(p,v)=>assistantApi('/db/audit/'+p,assistantPost(get('csrf'),v));
 let busy=false,state=null,preview=null,filters=null,page=1,more=false;
 const readFilters=()=>Object.fromEntries(['source','from','to','scope','dbUser','endUser','objectOwner','objectName','outcome','context'].map(k=>[k,get('filters').elements.namedItem(k).value]));
 function message(v,error=false){get('message').textContent=v;get('message').className=error?'app-alert is-error':'app-filter-message';}
 function lock(v){busy=v;root.setAttribute('aria-busy',String(v));for(const n of root.querySelectorAll('button,input,select'))n.disabled=v;
  const controls=auditControls(state,v);for(const b of root.querySelectorAll('[data-au-op]')){b.disabled=!controls[b.dataset.auOp];b.hidden=b.dataset.auOp==='INSTALL'&&state?.store==='READY'&&state?.jobState==='READY';}
  get('prev').disabled=v||page<=1||!filters;get('next').disabled=v||!more||page>=1000;get('apply').disabled=v||!preview||!get('consent').checked;
  const archived=get('filters').elements.namedItem('source').value==='archive';get('filters').elements.namedItem('scope').disabled=v||archived;if(archived)get('filters').elements.namedItem('scope').value='dds';
 }
 function renderStatus(s){state=s;const rows=[
  ['owner',s.database+' · '+s.owner],['sourceState',s.source+' · '+s.sourceState],['store',s.store+' / '+s.packageState],['job',s.jobState+' · '+(s.enabled?text('enabled'):text('disabled'))],
  ['lastSuccess',s.config.LAST_SUCCESS||'—'],['checkpoint',s.config.LAST_SCAN_TO||'—'],['added',s.config.LAST_ADDED??'—'],['seen',s.config.LAST_SEEN??'—'],['deleted',s.config.LAST_DELETED??'—'],
  ['next',s.job.NEXT_RUN||'—'],['result',[s.job.STATUS,s.job['ERROR#']].filter(v=>v!=null).join(' · ')||'—'],['permissions',s.missingPrivileges.join(', ')||text('ready')]];
  get('status').replaceChildren();for(const [key,value]of rows){const box=el('div');box.append(el('dt',text(key)),el('dd',String(value)));get('status').append(box);}
  if(s.config.INTERVAL_MINUTES!=null)get('minutes').value=s.config.INTERVAL_MINUTES;if(s.config.RETENTION_DAYS!=null)get('retention').value=s.config.RETENTION_DAYS;
  if(s.error)get('status').append(el('p',s.error,'app-alert is-error'));
 }
 function clearRows(){get('rows').replaceChildren();get('page').textContent='';get('empty').textContent='';more=false;}
 async function list(){clearRows();const result=await api('list?'+new URLSearchParams({...filters,page}));more=result.more;
  for(const row of result.rows){const tr=el('tr');for(const value of [row.EVENT_TIMESTAMP_UTC,row.END_USER_NAME||'—',row.DBUSERNAME,[row.OBJECT_SCHEMA,row.OBJECT_NAME].filter(Boolean).join('.'),row.ACTION_NAME,auditSucceeded(row.RETURN_CODE)?text('success'):String(row.RETURN_CODE??'—')])tr.append(el('td',value));
   const td=el('td'),b=el('button',row.SQL_PREVIEW||text('detail'),'app-inline-link');b.type='button';b.addEventListener('click',()=>detail(row.RECORD_KEY));td.append(b);tr.append(td);get('rows').append(tr);}
  get('empty').textContent=result.rows.length?'':text('empty');get('page').textContent=text('page',page,result.rows.length);
 }
 async function search(){if(busy)return;filters=readFilters();page=1;lock(true);message(text('loading'));try{await list();message('');}catch(e){message(e.message,true);}finally{lock(false);}}
 async function load(){if(busy)return;lock(true);message(text('loading'));try{renderStatus(await api('status'));message(text('statusReady'));}catch(e){state=null;message(e.message,true);}finally{lock(false);}}
 function dialog(title){preview=null;get('title').textContent=title;get('detail').replaceChildren();get('confirm').hidden=true;get('consent').checked=false;get('dialog').showModal();}
 async function detail(key){if(busy)return;lock(true);message(text('loading'));try{const data=await api('detail?'+new URLSearchParams({key}));dialog(text('detail'));
   const dl=el('dl');for(const [k,v]of Object.entries(data).filter(([k])=>!['SQL_TEXT','RLS_INFO','SQL_PREVIEW','RECORD_KEY'].includes(k)&&!k.endsWith('_TRUNCATED'))){dl.append(el('dt',k),el('dd',String(v??'—')));}
   get('detail').append(el('p',[data.END_USER_NAME||data.DBUSERNAME,[data.OBJECT_SCHEMA,data.OBJECT_NAME].filter(Boolean).join('.'),data.EVENT_TIMESTAMP_UTC+' UTC'].join(' · '),'app-muted'));
   if(data.SECURITY_CONTEXT_ID){const b=el('button',text('related'),'btn app-btn app-btn-secondary');b.type='button';b.addEventListener('click',()=>{get('filters').elements.namedItem('context').value=data.SECURITY_CONTEXT_ID;get('dialog').close();search();});get('detail').append(b,el('p',text('relatedNote'),'app-muted'));}
   get('detail').append(el('h3','SQL'),el('pre',data.SQL_TEXT||'—'));if(data.SQL_TEXT_TRUNCATED)get('detail').append(el('p',text('clipped'),'app-alert'));
   const fields=data.RLS_INFO_TRUNCATED?[]:rlsFields(data.RLS_INFO);
   get('detail').append(el('h3',text('conditions')));for(const field of fields)get('detail').append(el('strong',field.name),el('pre',field.value));
   if(!fields.length)get('detail').append(el('p',text(data.RLS_INFO?'rawOnly':'noRls'),'app-muted'));
   const raw=el('details');raw.append(el('summary','RLS_INFO · '+text('raw')),el('pre',data.RLS_INFO||'—'));get('detail').append(raw);if(data.RLS_INFO_TRUNCATED)get('detail').append(el('p',text('clipped'),'app-alert'));
   const metadata=el('details');metadata.append(el('summary',text('detail')),dl);get('detail').append(metadata);message('');
  }catch(e){message(e.message,true);}finally{lock(false);}}
 async function prepare(operation){if(busy)return;if(!validAuditSettings(get('minutes').value,get('retention').value)){message(text('invalid'),true);return;}lock(true);message(text('loading'));try{
   const p=await post('preview',{operation,minutes:Number(get('minutes').value),retentionDays:Number(get('retention').value)});dialog(text('preview'));preview=p;
   get('detail').append(el('pre',p.statements.map(s=>s+(s.trim().endsWith('END;')||s.includes('CREATE PACKAGE')?'\n/':';')).join('\n\n')));
   get('warning').textContent=text('changeNote')+(p.settings.retentionDays>0&&['INSTALL','CONFIGURE'].includes(operation)?' '+text('deleteWarning',p.settings.retentionDays):'');
   get('confirm').hidden=false;message('');
  }catch(e){message(e.message,true);}finally{lock(false);}}
 get('filters').addEventListener('submit',e=>{e.preventDefault();search();});get('filters').elements.namedItem('source').addEventListener('change',()=>lock(busy));
 get('refresh').addEventListener('click',load);
 for(const b of root.querySelectorAll('[data-au-op]'))b.addEventListener('click',()=>prepare(b.dataset.auOp));
 get('help').addEventListener('click',async()=>{if(busy)return;lock(true);message(text('loading'));try{const data=await api('help');dialog('SQL / PLSQL');get('detail').append(el('p',text('helpNote')),el('pre',data.sql));message('');}catch(e){message(e.message,true);}finally{lock(false);}});
 get('close').addEventListener('click',()=>{if(!busy){preview=null;get('dialog').close();}});get('dialog').addEventListener('cancel',e=>{if(busy)e.preventDefault();else preview=null;});
 get('consent').addEventListener('change',()=>lock(busy));
 get('apply').addEventListener('click',async()=>{if(busy||!preview||!get('consent').checked)return;const token=preview.token;preview=null;lock(true);message(text('applying'));try{renderStatus(await post('apply',{token,consent:true}));get('dialog').close();message(text('applied'));}catch(e){state=null;get('dialog').close();message(e.message,true);}finally{lock(false);}});
 for(const [name,delta]of [['prev',-1],['next',1]])get(name).addEventListener('click',async()=>{if(busy)return;page+=delta;lock(true);message(text('loading'));try{await list();message('');}catch(e){message(e.message,true);}finally{lock(false);}});
 load();
}
if(typeof document!=='undefined')document.querySelectorAll('[data-audit]').forEach(mountAudit);
