import {t} from './i18n.mjs';
import {editGuard} from './edit-guard.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {pageOf} from './table-list.mjs';
import {rdfViewer,meaningChanges} from './ontology-rdf.mjs';
import {metadataImporter} from './ontology-import.mjs';
import {ontologyNative} from './ontology-native.mjs';
import {ontologyErd} from './ontology-erd.mjs';
import {ontologyRelationships} from './ontology-relationships.mjs';
import {ontologyPipeline} from './ontology-pipeline.mjs';
import {ontologyWizard,definitionEditor} from './ontology-wizard.mjs';
import {valuesEditor} from './ontology-values.mjs';
import {contextSummary,reviewContext,reviewRows,selectedEdits,evidenceView,responseDiagnostic,prettyPayload} from './ontology-context.mjs';
const label=key=>t('ontology.'+key,key);
export const entriesPage=(entries,query,page)=>pageOf(entries.map(e=>({...e,description:[e.state,e.actor].join(' ')})),query,page);
export const editable=(entry,latest)=>entry?.revision===latest;
export const readOnlyPanel=(entry,latest,tab)=>!editable(entry,latest)&&['definition','columns','relations','values'].includes(tab);
export function captureChoices(tables){return [...tables];}
export function savePayload(schema,entry,meaning,state){return {schema,table:entry.document.source.table,revision:entry.revision,meaning,state};}
const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text??'—';if(cls)e.className=cls;return e;};
const button=(text,fn,cls='app-credential-link')=>{const b=el('button',text,cls);b.type='button';b.addEventListener('click',fn);return b;};
function grid(labels,rows){const wrap=el('div',undefined,'table-responsive'),table=el('table',undefined,'table app-table'),head=el('thead'),hr=el('tr'),body=el('tbody');labels.forEach(v=>{const cell=el('th',v);cell.scope='col';hr.append(cell);});head.append(hr);for(const values of rows){const row=el('tr');for(const value of values){const cell=el('td');cell.append(value instanceof Node?value:el('span',value??'—'));row.append(cell);}body.append(row);}table.append(head,body);wrap.append(table);return wrap;}
if(typeof document!=='undefined')document.querySelectorAll('[data-ontology]').forEach(root=>{
  const get=key=>root.querySelector(`[data-on-${key}]`),tabs=[...root.querySelectorAll('[data-on-tab]')],schema=root.dataset.schema,dialog=get('dialog');
  let catalog=null,entry=null,meaning=null,page=1,tab='definition',latest=0,busy=false,dirty=false,historyBefore='',historyStack=[],preview=null,proposal=null,aiRunning=false,aiSpent=false,rdfData=null;
  let view='list';
  let contextRows=[];
  const erd=ontologyErd(get('erd'),{schema,base:root.dataset.base});
  const relationships=ontologyRelationships(get('relationships'),{schema,base:root.dataset.base,post:(path,data)=>post(path,data),run:fn=>{if(dirty){message(label('import.saveFirst'),true);return;}return work(fn);},
    coverage:()=>({saved:catalog?.entries.length||0,total:catalog?.tables.length||0}),
    saved:async table=>{erd.invalidate();relationships.invalidate();await load();if(entry?.document.source.table===table)await open(table);applyView();},
    openDefinition:table=>work(async()=>{if(!leave())return;view='list';await open(table);applyView();})});
  editGuard.register(()=>dirty||relationships.dirty());
  const pipeline=ontologyPipeline(get('pipeline-dialog'),{schema,post:(path,data)=>post(path,data),profiles:()=>assistantApi(get('erd').dataset.profilesUrl+'?'+new URLSearchParams({schema})),canOpen:()=>{if(busy||dirty||relationships.dirty()){message(label('import.saveFirst'),true);return false;}return true;},updated:()=>work(async()=>{relationships.invalidate();await relationships.show();})});
  root.querySelector('[data-rel-discover]').addEventListener('click',()=>pipeline.discover());root.querySelector('[data-rel-graph]').addEventListener('click',()=>pipeline.graph());
  function applyView(){const ready=catalog?.status==='READY';get('views').hidden=!ready;get('ready').hidden=!ready||view!=='list';get('erd').hidden=!ready||view!=='erd';get('relationships').hidden=!ready||view!=='relationships';get('detail').hidden=view!=='list'||!entry;root.querySelectorAll('[data-on-view]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.onView===view)));}
  const loading=()=>t('ui.8bf609c884ca','불러오는 중…');
  function message(text,error=false){get('message').textContent=text;get('message').className=error?'app-alert is-error':'app-filter-message';}
  function aiMessage(text,error=false){get('ai-message').textContent=text;get('ai-message').className=error?'app-alert is-error':'app-filter-message';}
  function aiDiagnostic(error=null){const d=responseDiagnostic(error);get('ai-diagnostic').hidden=!d;get('ai-diagnostic').open=false;get('ai-diagnostic-path').textContent=d?`${d.code} · ${d.path}`:'';get('ai-raw').textContent=d?.rawResponse??'';}
  function url(path,params={}){return root.dataset.base+path+'?'+new URLSearchParams({schema,...params});}
  const post=(path,data)=>assistantApi(root.dataset.base+path,assistantPost(get('csrf'),data));
  const native=ontologyNative(get('native-dialog'),{schema,base:root.dataset.base,post,entries:()=>catalog?.entries||[],canOpen:()=>{if(busy||dirty||relationships.dirty()){message(label('import.saveFirst'),true);return false;}return true;},saved:reloadSaved});
  get('native').addEventListener('click',()=>native.open());
  function lock(value){busy=value;root.querySelectorAll('button,input,textarea,select').forEach(e=>{if(!dialog.contains(e)&&!get('import-dialog').contains(e)&&!get('wizard-dialog').contains(e)&&!get('pipeline-dialog').contains(e)&&!get('native-dialog').contains(e))e.disabled=value||e.dataset.boundDisabled==='true';});get('prev').disabled=value||page<=1;get('next').disabled=value||page>=entriesPage(catalog?.entries??[],get('filter').value,page).pages;get('capture').disabled=value||!get('source').value;const edit=editable(entry,latest);['save','approve','ai','wizard'].forEach(k=>get(k).disabled=value||!edit);get('ai').disabled=value||!edit||dirty;get('wizard').disabled=value||!edit||dirty;if(!value){if(entry&&readOnlyPanel(entry,latest,tab))get('panel').querySelectorAll('input,textarea,select').forEach(e=>e.disabled=true);}}
  async function work(fn){if(busy)return;lock(true);message(loading());try{await fn();message('');}catch(ex){message(ex.message,true);}finally{lock(false);}}
  function leave(){return !dirty&&!relationships.dirty()||window.confirm(label('discard'));}
  function list(){const result=entriesPage(catalog?.entries??[],get('filter').value,page);page=result.page;get('list').replaceChildren(grid(['table','version','state','actor','recordedAt'].map(label),result.items.map(e=>[button(e.name,()=>{if(leave())work(()=>open(e.name));}),String(e.revision),label(e.state),e.actor,e.recordedAt])));get('count').textContent=t('ui.f32c9f13d498','{0}–{1} / {2}개',result.from,result.to,result.total);get('page').textContent=`${result.pages?page:0} / ${result.pages}`;lock(busy);}
  async function load(refresh=false){if(refresh){erd.invalidate();relationships.invalidate();}get('install').hidden=true;catalog=null;applyView();const data=await assistantApi(url('/catalog',{refresh}));catalog=data;get('status').textContent=label(data.status);get('install').hidden=!data.canInstall;get('checked').textContent=data.checkedAt?new Date(data.checkedAt).toLocaleString(document.documentElement.lang):'';const options=captureChoices(data.tables,data.entries);get('source').replaceChildren(new Option(label('sourceTable'),''),...options.map(i=>new Option(i.name,i.name)));list();applyView();}
  async function open(table,revision=0){const data=await assistantApi(url('/detail',{table,revision}));entry=data;rdfData=null;if(!revision)latest=data.revision;meaning=structuredClone(data.document.meaning);dirty=false;historyBefore='';historyStack=[];get('detail').hidden=view!=='list';get('target').textContent=schema+'.'+table;get('version').textContent=`v${data.revision} · ${label(data.state)} · ${data.actor} · ${data.recordedAt}`;await render();}
  function input(value,change,large=false){const e=el(large?'textarea':'input',undefined,'form-control');e.value=value??'';if(large)e.rows=2;e.maxLength=large?8000:256;e.disabled=!editable(entry,latest);e.addEventListener('input',()=>{change(e.value);dirty=true;get('ai').disabled=true;get('wizard').disabled=true;});return e;}
  function field(host,title,control){const group=el('label',undefined,'app-ontology-field');group.append(el('span',title),control);host.append(group);}
  async function render(){
    if(!entry)return;tabs.forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.onTab===tab)));const host=get('panel'),source=entry.document.source;host.replaceChildren();get('actions').hidden=!editable(entry,latest);
    if(tab==='definition'){
      host.append(grid([label('field'),label('value')],[[label('sourceComment'),source.comment],[label('capturedAt'),source.capturedAt],[label('origin'),entry.document.origin],[label('profile'),entry.document.profile]]));
      field(host,label('concept'),input(meaning.concept,v=>meaning.concept=v));field(host,label('description'),input(meaning.description,v=>meaning.description=v,true));
      if(entry.document.analysis)host.append(evidenceView(entry.document.analysis));
    }else if(tab==='columns'){
      const help=el('details',undefined,'app-disclosure');help.append(el('summary',t('ontology.wizard.semanticsTitle','?')),el('p',t('ontology.wizard.genericHelp','')),el('p',t('ontology.wizard.semanticsHelp','')));host.append(help);
      host.append(grid(['column','type','sourceComment','description','sensitivity'].map(label),source.columns.map(c=>{
        const value=meaning.columns[c.name],select=el('select',undefined,'form-select app-select');select.setAttribute('aria-label',c.name+' '+label('sensitivity'));for(const v of ['UNKNOWN','SENSITIVE','NON_SENSITIVE'])select.add(new Option(label(v),v));select.value=value.sensitivity;select.disabled=!editable(entry,latest);select.addEventListener('change',()=>{value.sensitivity=select.value;dirty=true;get('ai').disabled=true;get('wizard').disabled=true;});const description=input(value.description,v=>value.description=v,true);description.setAttribute('aria-label',c.name+' '+label('description'));const content=el('div');content.append(description,definitionEditor({column:c.name,definition:value.definition,columns:source.columns.map(v=>v.name),editable:editable(entry,latest),changed:d=>{value.definition=d;dirty=true;get('ai').disabled=true;get('wizard').disabled=true;}}));return [c.name,c.dataType,c.comment,content,select];
      })));
    }else if(tab==='relations'){
      host.append(grid(['key','type','sourceColumns','target','state','description'].map(label),source.keys.map(k=>[k.name,k.type,k.columns.join(' + '),[k.targetOwner,k.targetTable].filter(Boolean).join('.')+(k.targetColumns.length?' ('+k.targetColumns.join(' + ')+')':''),[k.status,k.validated].join(' / '),k.type==='R'?input(meaning.relations[k.name],v=>meaning.relations[k.name]=v,true):'—'])));
      if(!source.keys.length)host.append(el('p',label('empty'),'app-empty'));
    }else if(tab==='values'){
      valuesEditor(host,{entry,meaning,editable:editable(entry,latest),lookup:value=>post('/values/lookup',value),run:work,changed:()=>{dirty=true;get('ai').disabled=true;get('wizard').disabled=true;}});
    }else if(tab==='drift'){
      const drift=await assistantApi(url('/drift',{table:source.table}));host.append(el('p',`${drift.status} · ${drift.limitation||''}`,'app-filter-message'));
      host.append(grid(['kind','name','baseline','current','status'],drift.differences.map(d=>[d.kind,d.name,d.baseline,d.current,d.status])));
      if((drift.status==='REVIEW_REQUIRED'||drift.status==='ANNOTATION_BASELINE_REQUIRED')&&editable(entry,latest)){const initializing=drift.status==='ANNOTATION_BASELINE_REQUIRED',accept=button(initializing?'현재 Annotation을 초기 기준으로 저장':'현재 메타데이터를 새 기준으로 수락',()=>{const message=initializing?'과거 기준에는 확인된 Annotation 조회값이 없습니다. 현재 성공적으로 조회한 Annotation을 별도 초기 기준 버전으로 저장할까요? 기존 기준은 보존됩니다.':'현재 메타데이터를 새 기준 버전으로 저장할까요? 기존 기준은 보존됩니다.';if(window.confirm(message))work(async()=>{await post('/drift/accept',{schema,table:source.table,revision:entry.revision,token:drift.checkedAt,confirmed:true});await reloadSaved();await open(source.table);});},'btn app-btn app-btn-primary');host.append(accept);}
    }else if(tab==='rdf'){
      rdfData??=await assistantApi(url('/rdf',{table:source.table,revision:entry.revision}));host.append(rdfViewer(rdfData,dirty));
    }else{
      const data=await assistantApi(url('/history',{table:source.table,before:historyBefore}));host.append(grid(['version','state','actor','recordedAt'].map(label),data.items.map(v=>[button('v'+v.revision,()=>{if(leave())work(async()=>{tab='definition';await open(source.table,v.revision);});}),label(v.state),v.actor,v.recordedAt])));
      const controls=el('div',undefined,'app-dds-pages'),prev=button(t('ui.da7e61c67cc5','이전'),()=>work(async()=>{historyBefore=historyStack.pop()??'';await render();}),'btn app-btn app-btn-quiet'),next=button(t('ui.aef613c6612d','다음'),()=>work(async()=>{historyStack.push(historyBefore);historyBefore=data.next;await render();}),'btn app-btn app-btn-quiet');prev.disabled=!historyStack.length;next.disabled=!data.next;prev.dataset.boundDisabled=String(prev.disabled);next.dataset.boundDisabled=String(next.disabled);controls.append(prev,next);host.append(controls);
    }
  }
  async function refresh(){if(!leave())return;const selected=entry?.document.source.table;entry=null;get('detail').hidden=true;await load(true);if(selected&&catalog.entries.some(e=>e.name===selected))await open(selected);if(view==='erd'&&catalog.status==='READY')await erd.show();if(view==='relationships'&&catalog.status==='READY')await relationships.show();}
  root.querySelectorAll('[data-on-view]').forEach(button=>button.addEventListener('click',()=>work(async()=>{view=button.dataset.onView;applyView();if(view==='erd')await erd.show();if(view==='relationships')await relationships.show();})));
  get('analyze').addEventListener('click',()=>work(async()=>{view='relationships';applyView();await relationships.all();}));
  async function reloadSaved(){erd.invalidate();relationships.invalidate();await load();}
  get('install').addEventListener('click',()=>{if(window.confirm(label('installConfirm')+'\n'+schema+'.DBC_ONTOLOGY_CATALOG'))work(async()=>{await post('/install',{schema,confirmed:true});await reloadSaved();});});
  const importer=metadataImporter(get('import-dialog'),{schema,post,completed:reloadSaved,profiles:profile=>assistantApi(get('erd').dataset.profilesUrl+'?'+new URLSearchParams(profile===undefined?{schema}:{schema,profile}))});
  const wizard=ontologyWizard(get('wizard-dialog'),{schema,post,getOptions:value=>assistantApi(url('/wizard/options',{table:value.document.source.table,revision:value.revision})),saved:async table=>{await reloadSaved();await open(table);}});
  get('wizard').addEventListener('click',()=>{if(!busy&&!dirty&&editable(entry,latest))wizard.open(entry);});
  function importPreview(table=null){
    if(dirty){message(label('import.saveFirst'),true);return;}
    work(async()=>{
      await load(true);
      if(catalog.status!=='READY')throw new Error(label('notReady'));
      const pending=captureChoices(catalog.tables,catalog.entries),names=pending.filter(i=>table===null||i.name===table).map(i=>i.name);
      await importer.open(names,catalog.tables.length,catalog.entries,catalog.importExclusions??{},table===null);
    });
  }
  get('capture').addEventListener('click',()=>{const table=get('source').value;if(table)importPreview(table);});
  get('import-all').addEventListener('click',()=>importPreview());
  get('refresh').addEventListener('click',()=>work(refresh));get('latest').addEventListener('click',()=>{if(leave())work(()=>open(entry.document.source.table));});
  get('filter').addEventListener('input',()=>{page=1;list();});get('prev').addEventListener('click',()=>{page--;list();});get('next').addEventListener('click',()=>{page++;list();});get('source').addEventListener('change',()=>lock(false));
  for(const key of ['save','approve'])get(key).addEventListener('click',()=>work(async()=>{const table=entry.document.source.table;await post('/save',savePayload(schema,entry,meaning,key==='approve'?'APPROVED':'DRAFT'));await reloadSaved();await open(table);}));
  tabs.forEach(b=>b.addEventListener('click',()=>work(async()=>{tab=b.dataset.onTab;await render();})));
  function aiLock(){get('generate').disabled=!preview||aiRunning||aiSpent||!get('consent').checked;get('close').disabled=aiRunning;get('consent').disabled=aiRunning||aiSpent;get('apply').disabled=aiRunning||!proposal||!selectedEdits(contextRows).length;get('ai-result').querySelectorAll('input,textarea').forEach(n=>n.disabled=aiRunning);}
  get('ai').addEventListener('click',()=>work(async()=>{preview=null;proposal=null;contextRows=[];aiSpent=false;aiRunning=true;aiDiagnostic();get('consent').checked=false;get('ai-result').hidden=true;get('apply').hidden=true;get('ai-source').textContent='';get('ai-profile').textContent='';get('ai-context').replaceChildren();get('ai-payload').open=false;aiLock();dialog.showModal();aiMessage(loading());try{preview=await post('/ai/preview',{schema,table:entry.document.source.table,revision:entry.revision});const p=preview.request.profile;get('ai-profile').textContent=`${p.selection.owner}.${p.selection.name} · ${p.provider} / ${p.model??''}`;get('ai-source').textContent=prettyPayload(preview.request.source);contextSummary(get('ai-context'),preview.context);aiMessage(label('context.noSampling'));}catch(ex){aiMessage(ex.message,true);}finally{aiRunning=false;aiLock();}}));
  get('consent').addEventListener('change',aiLock);
  get('generate').addEventListener('click',async()=>{if(get('generate').disabled)return;aiRunning=true;aiSpent=true;aiLock();aiMessage(loading());try{
    proposal=await post('/ai/generate',{token:preview.token,consent:get('consent').checked});
    const changes=meaningChanges(entry.document.meaning,proposal.meaning);
    contextRows=reviewRows(changes,proposal.recommendations);reviewContext(get('ai-result'),contextRows,aiLock);
    get('ai-result').hidden=false;get('apply').hidden=!changes.length;aiMessage(changes.length?label('reviewAi'):label('rdf.noChanges'));
    if(!changes.length)proposal=null;
  }catch(ex){proposal=null;contextRows=[];get('apply').hidden=true;get('ai-result').hidden=true;aiDiagnostic(ex);aiMessage(ex.message,true);}finally{aiRunning=false;aiLock();}});
  get('apply').addEventListener('click',async()=>{if(get('apply').disabled)return;aiRunning=true;aiLock();try{const table=proposal.table;await post('/ai/apply',{schema,table,revision:proposal.revision,token:proposal.token,edits:selectedEdits(contextRows)});proposal=null;dialog.close();await work(async()=>{await reloadSaved();await open(table);});}catch(ex){proposal=null;aiMessage(ex.message,true);}finally{aiRunning=false;aiLock();}});
  get('close').addEventListener('click',()=>{if(!aiRunning)dialog.close();});dialog.addEventListener('cancel',e=>{if(aiRunning)e.preventDefault();});dialog.addEventListener('close',()=>{const tokens=[preview?.token,proposal?.token].filter(Boolean);preview=null;proposal=null;contextRows=[];aiDiagnostic();get('ai-source').textContent='';get('ai-result').textContent='';get('ai-context').replaceChildren();for(const token of tokens)post('/ai/cancel',{token}).catch(ex=>message(ex.message,true));});
  work(()=>load());
});
