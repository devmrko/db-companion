import {t} from './i18n.mjs';
import {assistantApi} from './ai-assistant.mjs';
import {confirmAction} from './confirm-action.mjs';
const label=k=>t('ontology.native.'+k,k);
const node=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;};
const button=(text,fn)=>{const e=node('button',text,'btn app-btn app-btn-secondary');e.type='button';e.addEventListener('click',fn);return e;};
export function nativeSelection(names){return [...new Set(names)].sort();}
export function nativeCanPreview(names,snapshots){return names.length>=2&&names.length<=50&&names.every(n=>snapshots.some(s=>s.table===n));}
export const nativeRuntimeReady=status=>status?.runtime?.state==='READY';
export const nativeCapturesVerified=(captures,snapshots)=>captures.length>0&&captures.every(c=>snapshots.some(s=>s.table===c.table&&s.iri===c.iri));
export function ontologyNative(dialog,{schema,base,post,entries,canOpen,saved}){
  const get=k=>dialog.querySelector('[data-native-'+k+']');
  let busy=false,status=null,preview=null,selected=new Set(),chosen=new Set();
  const request=(path,params={})=>assistantApi(base+'/native/'+path+'?'+new URLSearchParams({schema,...params}));
  const message=(text,error=false)=>{get('status').textContent=text;get('status').className=error?'app-alert is-error':'app-filter-message';};
  function controls(){
    dialog.querySelectorAll('button,input').forEach(e=>e.disabled=busy);
    const ready=nativeRuntimeReady(status);
    get('install').hidden=status?.storage!=='MISSING';get('install').disabled=busy||!status?.api||!ready;
    get('java-setup').hidden=!status?.runtime?.canEnable;
    get('java-enable').disabled=busy||!status?.runtime?.canEnable||!get('java-consent').checked;
    get('capture').disabled=busy||!ready||status?.storage!=='READY'||selected.size===0||selected.size>50||!get('consent').checked;
    get('diagnose').disabled=busy||!ready||status?.storage!=='READY';
    get('preview').disabled=busy||!ready||!nativeCanPreview([...selected],status?.snapshots||[]);
    get('save').disabled=busy||!preview||chosen.size===0||!get('save-consent').checked;
    get('result').hidden=!preview;dialog.setAttribute('aria-busy',String(busy));get('progress').hidden=!busy;
    get('count').textContent=t('ontology.native.selected','Selected {0} / 50',selected.size);
  }
  async function run(fn){if(busy)return;busy=true;controls();message(label('working'));try{await fn();}catch(e){message(e.message,true);}finally{busy=false;controls();}}
  function invalidate(){preview=null;chosen.clear();get('save-consent').checked=false;get('candidates').replaceChildren();controls();}
  function list(){
    const wrap=get('objects');wrap.replaceChildren();
    for(const entry of entries()){
      const name=entry.name, snapshot=status?.snapshots?.find(s=>s.table===name),row=node('div',undefined,'app-native-row'),check=node('input');check.type='checkbox';check.checked=selected.has(name);
      check.addEventListener('change',()=>{check.checked?selected.add(name):selected.delete(name);invalidate();});
      const text=node('label',undefined,'app-native-object');text.append(check,node('span',name));row.append(text);
      row.append(node('span',snapshot?label('captured')+' · '+snapshot.capturedAt:label('notCaptured'),'app-filter-message'));
      if(snapshot)row.append(button(label('columns'),()=>run(async()=>{const values=await request('columns',{table:name});get('columns').textContent=JSON.stringify(values,null,2);get('column-details').open=true;message(label('readDone'));})));
      wrap.append(row);
    }
  }
  async function refresh(){
    status=null;get('storage').textContent=schema+' · '+label('working');get('java-state').textContent='';get('java-sql').textContent='';
    try{status=await request('status');}catch(e){get('storage').textContent=schema+' · '+label('javaState.UNKNOWN');get('java-state').textContent=label('javaState.UNKNOWN');throw e;}get('storage').textContent=schema+' · '+status.storage;
    get('java-state').textContent=label('javaState.'+status.runtime.state);get('java-sql').textContent=status.runtime.sql;list();
  }
  function results(){
    const wrap=get('candidates');wrap.replaceChildren();
    for(const r of preview.candidates){const row=node('label',undefined,'app-native-row'),check=node('input');check.type='checkbox';check.checked=chosen.has(r.id);check.addEventListener('change',()=>{check.checked?chosen.add(r.id):chosen.delete(r.id);controls();});row.append(check,node('span',r.source+'.'+r.sourceColumns.join(', ')+' ↔ '+r.target+'.'+r.targetColumns.join(', ')));wrap.append(row);}
    get('candidate-count').textContent=t('ontology.native.candidateCount','Candidates {0}; already stored {1}',preview.candidates.length,preview.existing);
  }
  get('close').addEventListener('click',()=>{if(!busy)dialog.close();});dialog.addEventListener('cancel',e=>{if(busy)e.preventDefault();});
  get('consent').addEventListener('change',controls);get('save-consent').addEventListener('change',controls);
  get('java-consent').addEventListener('change',controls);
  get('refresh').addEventListener('click',()=>run(async()=>{invalidate();await refresh();message(label('ready'));}));
  get('diagnose').addEventListener('click',()=>run(async()=>{const rows=await request('diagnostics');get('diagnostics').textContent=JSON.stringify(rows,null,2);get('diagnostic-details').open=true;message(label('diagnosticRows'));}));
  get('java-enable').addEventListener('click',()=>{
    if(!status?.runtime?.canEnable||!get('java-consent').checked)return;
    run(async()=>{get('java-consent').checked=false;status=await post('/native/enable-java',{schema,confirmed:true});await refresh();message(label('javaRequested'));});
  });
  get('all').addEventListener('click',()=>{selected=new Set(entries().map(e=>e.name));invalidate();list();controls();});
  get('none').addEventListener('click',()=>{selected.clear();invalidate();list();controls();});
  get('install').addEventListener('click',()=>run(async()=>{if(!await confirmAction(label('installConfirm')+'\n'+schema)){message(label('ready'));return;}await post('/native/install',{schema,confirmed:true});await refresh();message(label('installed'));}));
  get('capture').addEventListener('click',()=>run(async()=>{
    invalidate();const names=nativeSelection([...selected]),captures=[];let count=0;
    for(const table of names){message(t('ontology.native.capturing','Capturing {0}/{1}: {2}',count+1,names.length,table));captures.push(await post('/native/capture',{schema,table,confirmed:true}));count++;}
    get('consent').checked=false;await refresh();
    if(!nativeCapturesVerified(captures,status.snapshots))throw new Error(label('captureUnverified'));
    message(t('ontology.native.captureDone','Captured {0} objects',count));
  }));
  get('preview').addEventListener('click',()=>run(async()=>{invalidate();preview=await post('/native/preview',{schema,tables:nativeSelection([...selected])});results();message(label('previewDone'));}));
  get('choose-all').addEventListener('click',()=>{chosen=new Set(preview.candidates.map(r=>r.id));results();controls();});
  get('choose-none').addEventListener('click',()=>{chosen.clear();results();controls();});
  get('save').addEventListener('click',()=>run(async()=>{const result=await post('/native/save',{schema,token:preview.token,ids:[...chosen],confirmed:true});invalidate();await saved();message(t('ontology.native.saved','Saved {0} unapproved candidates',result.saved));}));
  return {open:async()=>{if(!canOpen())return;selected.clear();invalidate();get('consent').checked=false;get('java-consent').checked=false;get('columns').textContent='';get('column-details').open=false;dialog.showModal();await run(async()=>{await refresh();message(label('ready'));});}};
}
