import {t} from './i18n.mjs';
import {mountSourceViewer} from './source-viewer.mjs';
import {mountOrdsApiTest} from './ords-api-test.mjs';
const tr=key=>t('ords.'+key,key);
const el=(tag,text)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text??'—';return n;};
export const canApply=(preview,confirmation,busy,attempted)=>!!preview&&confirmation===preview.confirmation&&!busy&&!attempted;
export const editorDefaults=(kind,row={})=>({
  SCHEMA:{enabled:row.STATUS==='ENABLED'?'true':'false',mappingType:row.TYPE??'BASE_PATH',mappingPattern:row.PATTERN??'',autoRestAuth:row.AUTO_REST_AUTH==='DISABLED'?'false':'true'},
  MODULE:{basePath:row.URI_PREFIX??'',itemsPerPage:row.ITEMS_PER_PAGE??'25',status:row.STATUS??'NOT_PUBLISHED',comments:row.COMMENTS??''},
  TEMPLATE:{priority:row.PRIORITY??'0',etagType:row.ETAG_TYPE??'HASH',etagQuery:row.ETAG_QUERY??'',comments:row.COMMENTS??''},
  HANDLER:{sourceType:row.SOURCE_TYPE??'json/collection',source:row.SOURCE??'',itemsPerPage:row.ITEMS_PER_PAGE??'',mimesAllowed:row.MIMES_ALLOWED??'',comments:row.COMMENTS??''}
}[kind]);
const columns={enabled:'STATUS',mappingType:'TYPE',mappingPattern:'PATTERN',autoRestAuth:'AUTO_REST_AUTH',basePath:'URI_PREFIX',itemsPerPage:'ITEMS_PER_PAGE',status:'STATUS',comments:'COMMENTS',priority:'PRIORITY',etagType:'ETAG_TYPE',etagQuery:'ETAG_QUERY',sourceType:'SOURCE_TYPE',source:'SOURCE',mimesAllowed:'MIMES_ALLOWED'};
export const changeLines=(original,proposed)=>Object.entries(proposed).map(([key,value])=>`${key}\n- ${original[columns[key]]??'∅'}\n+ ${value??'∅'}`).join('\n\n');
// Display only: the ORDS host/context cannot be inferred from the app URL.
export function resourcePath(schema,module,template){
  if(schema?.TYPE!=='BASE_PATH'||!schema.PATTERN||!module?.URI_PREFIX||!template?.URI_TEMPLATE)return null;
  return '/'+schema.PATTERN.replace(/^\/+|\/+$/g,'')+'/'+module.URI_PREFIX.replace(/^\/+|\/+$/g,'')+'/'+template.URI_TEMPLATE.replace(/^\/+/,'');
}
// Guidance follows the requested state transition; it does not authorize or execute changes.
export function previewNotices(preview){
  const {kind,action}=preview.input,old=preview.original??{},next=preview.proposed??{};
  if(action==='DELETE')return [kind==='SCHEMA'?'schemaDelete':kind==='HANDLER'?'deleteHandler':'deleteChildren'];
  if(kind==='MODULE'&&next.status==='PUBLISHED'&&old.STATUS!=='PUBLISHED')return ['exposure'];
  if(kind==='MODULE'&&next.status==='NOT_PUBLISHED'&&old.STATUS==='PUBLISHED')return ['unpublishImpact'];
  if(kind==='SCHEMA'&&next.enabled==='true'&&old.STATUS!=='ENABLED')return ['enableImpact'];
  if(kind==='SCHEMA'&&next.enabled==='false'&&old.STATUS==='ENABLED')return ['disableImpact'];
  return [];
}
export function requestState(){let generation=0;return {invalidate(){generation++;},start(){const current=++generation;return ()=>current===generation;}};}
export async function ordsRequest(base,path,body,csrf,fetcher=fetch){
  const options={cache:'no-store',headers:{Accept:'application/json'}};
  if(body!==undefined){options.method='POST';options.headers['Content-Type']='application/json';options.headers[csrf.dataset.csrfHeader]=csrf.value;options.body=JSON.stringify(body);}
  const response=await fetcher(base+path,options);
  if(response.redirected||response.status===401)throw new Error(tr('expired'));
  if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(`HTTP ${response.status}`);
  const data=await response.json();if(!response.ok)throw new Error(data.error||`HTTP ${response.status}`);return data;
}
if(typeof document!=='undefined')document.querySelectorAll('[data-ords]').forEach(root=>{
  const get=key=>root.querySelector(`[data-ords-${key}]`),api=(path,body)=>ordsRequest(root.dataset.base,path,body,get('csrf'));
  const gate=requestState();let snapshot=null,module=null,template=null,editing=null,preview=null,busy=false,attempted=false,testBusy=false;
  const apiTest=mountOrdsApiTest(root.querySelector('[data-ords-test]'),api,active=>{testBusy=active;controls();});
  function message(text){get('message').textContent=text;}
  function controls(){
    get('fields').disabled=busy||testBusy||attempted;get('refresh').disabled=busy||testBusy;get('confirm').disabled=busy||testBusy||attempted;
    get('apply').disabled=!canApply(preview,get('confirm').value,busy||testBusy,attempted);
    get('catalog').querySelectorAll('button,select').forEach(n=>{if(!n.closest('.app-ords-source'))n.disabled=busy||testBusy;});root.setAttribute('aria-busy',String(busy||testBusy));
  }
  function invalidate(){gate.invalidate();preview=null;get('review').hidden=true;get('confirm').value='';controls();}
  function button(label,action,style='secondary'){const n=el('button',label);n.type='button';n.className='btn app-btn app-btn-'+style;n.addEventListener('click',()=>{if(!busy&&!testBusy)action();});return n;}
  function toolbar(host){const bar=el('div');bar.className='app-ords-actions';host.append(bar);return bar;}
  function actions(host,kind,row){for(const action of ['UPDATE','DELETE'])host.append(button(tr(action),()=>edit(kind,action,row),action==='DELETE'?'danger':'secondary'));}
  function metadata(host,data){const detail=el('details');detail.className='app-ords-details';const pre=el('pre',JSON.stringify(data,null,2));pre.className='app-preview-source';detail.append(el('summary',tr('original')),pre);host.append(detail);}
  function summary(host,fields){const list=el('dl');list.className='app-ords-summary';for(const [name,value] of fields){const item=el('div');item.append(el('dt',tr(name)),el('dd',value||'—'));list.append(item);}host.append(list);}
  function badge(host,value){const tag=el('span',value||'—');tag.className='app-badge app-ords-status';tag.classList.toggle('is-active',['ENABLED','PUBLISHED'].includes(value));host.append(tag);}
  function note(host,text,empty=false){const p=el('p',text);p.className=empty?'app-ords-empty':'app-ords-comment';host.append(p);}
  function select(host,rows,selected,label,choose){
    const wrap=el('label'),title=el('span',tr(label)),input=el('select');wrap.className='app-ords-select';input.className='form-select';input.append(new Option(tr('select'),''));
    rows.forEach(row=>input.append(new Option(row.NAME??row.URI_TEMPLATE,row.ID)));input.value=selected?.ID??'';
    input.addEventListener('change',()=>{if(!busy){close();choose(rows.find(row=>row.ID===input.value)??null);render();}});wrap.append(title,input);host.append(wrap);
  }
  const rows=key=>snapshot?.tables[key]??[];
  function render(){
    if(!snapshot)return;get('catalog').hidden=snapshot.status!=='AVAILABLE';if(snapshot.status!=='AVAILABLE')return;
    const schema=get('schema'),registration=rows('SCHEMAS')[0];schema.replaceChildren();
    if(registration){badge(schema,registration.STATUS);summary(schema,[['schemaName',registration.PARSING_SCHEMA],['mappingPattern',registration.PATTERN],['mappingType',registration.TYPE],['autoRestAuth',registration.AUTO_REST_AUTH]]);actions(toolbar(schema),'SCHEMA',registration);metadata(schema,rows('SCHEMAS'));}
    else{note(schema,tr('notRegistered'),true);toolbar(schema).append(button(tr('CREATE'),()=>edit('SCHEMA','CREATE',{}),'primary'));}
    const modules=get('modules');modules.replaceChildren();select(modules,rows('MODULES'),module,'selectModule',row=>{module=row;template=null;});
    const moduleActions=toolbar(modules);moduleActions.append(button(tr('createModule'),()=>edit('MODULE','CREATE',{}),'primary'));
    if(module){actions(moduleActions,'MODULE',module);moduleActions.append(button(tr('PUBLISH'),()=>edit('MODULE','PUBLISH',module)));badge(modules,module.STATUS);summary(modules,[['basePath',module.URI_PREFIX],['itemsPerPage',module.ITEMS_PER_PAGE]]);if(module.COMMENTS)note(modules,module.COMMENTS);metadata(modules,module);}
    else note(modules,tr('selectModuleHint'),true);
    const templates=get('templates');templates.replaceChildren();
    if(module){select(templates,rows('TEMPLATES').filter(row=>row.MODULE_ID===module.ID),template,'selectTemplate',row=>{template=row;});const templateActions=toolbar(templates);templateActions.append(button(tr('createTemplate'),()=>edit('TEMPLATE','CREATE',{}),'primary'));if(template){actions(templateActions,'TEMPLATE',template);summary(templates,[['priority',template.PRIORITY],['etagType',template.ETAG_TYPE]]);if(template.COMMENTS)note(templates,template.COMMENTS);metadata(templates,template);}else note(templates,tr('selectTemplateHint'),true);}
    else note(templates,tr('selectModuleHint'),true);
    const handlers=get('handlers');handlers.replaceChildren();
    if(template){const path=resourcePath(registration,module,template);if(path){const route=el('div');route.className='app-ords-route';route.append(el('span',tr('routePath')),el('code',path));handlers.append(route);note(handlers,tr('routeHelp'));}
      toolbar(handlers).append(button(tr('createHandler'),()=>edit('HANDLER','CREATE',{}),'primary'));
      const selectedHandlers=rows('HANDLERS').filter(row=>row.TEMPLATE_ID===template.ID);if(!selectedHandlers.length)note(handlers,tr('handlersEmpty'),true);
      for(const row of selectedHandlers){
        const section=el('section');section.className='app-ords-handler';const header=el('div');header.className='app-ords-heading';const title=el('div');badge(title,row.METHOD);title.append(el('strong',row.SOURCE_TYPE));header.append(title);actions(toolbar(header),'HANDLER',row);section.append(header);
        if(row.COMMENTS)note(section,row.COMMENTS);section.append(el('h3',tr('handlerSource')));const source=el('div');source.className='app-ords-source';section.append(source);if(row.SOURCE)mountSourceViewer(source,[{type:'SQL / PL/SQL',text:row.SOURCE}],null);
        toolbar(section).append(button(tr('test.title'),()=>{close();apiTest.open({schema:root.dataset.schema,module:module.NAME,pattern:template.URI_TEMPLATE,method:row.METHOD,revision:snapshot.revision});},'primary'));
        metadata(section,{handler:row,parameters:rows('PARAMETERS').filter(p=>p.HANDLER_ID===row.ID)});handlers.append(section);
      }
    }else note(handlers,tr('selectTemplateHint'),true);controls();
  }
  const choices={enabled:['true','false'],autoRestAuth:['true','false'],mappingType:['BASE_PATH','BASE_URL'],status:['NOT_PUBLISHED','PUBLISHED'],etagType:['HASH','NONE','QUERY'],sourceType:['json/collection','json/item','plsql/block','resource/lob','json/query','json/query;type=single','csv/query','json/feed'],method:['GET','POST','PUT','DELETE']};
  const fieldHelp={status:'statusHelp',autoRestAuth:'authHelp',source:'sourceNotice'};
  function field(host,name,value){
    const wrap=el('div'),label=el('label');wrap.className='app-ords-field';label.append(el('span',tr(name)));let input;
    if(choices[name]){input=el('select');for(const choice of new Set([...choices[name],value]))input.append(new Option(choice,choice));}
    else{input=el(['source','etagQuery','comments'].includes(name)?'textarea':'input');if(input.tagName==='TEXTAREA')input.rows=name==='source'?12:3;}
    input.name=name;input.value=value;input.className='form-control';if(['source','etagQuery','comments'].includes(name))wrap.classList.add('app-ords-wide');input.addEventListener('input',invalidate);label.append(input);wrap.append(label);
    if(fieldHelp[name]){const help=el('details'),title=el('summary','? '+tr(name));help.className='app-context-help';title.setAttribute('aria-label',tr(name)+' · '+tr('fieldHelp'));help.append(title,el('p',tr(fieldHelp[name])));wrap.append(help);}
    host.append(wrap);return input;
  }
  function close(){invalidate();editing=null;get('editor').hidden=true;apiTest.close();}
  function edit(kind,action,row){
    apiTest.close();
    attempted=false;invalidate();editing={kind,action,module:kind==='MODULE'?row.NAME??'':module?.NAME??'',pattern:kind==='TEMPLATE'?row.URI_TEMPLATE??'':template?.URI_TEMPLATE??'',method:row.METHOD??'GET',row};
    const host=get('inputs');host.replaceChildren();
    if(action==='CREATE'&&kind==='MODULE')field(host,'module','');
    if(action==='CREATE'&&kind==='TEMPLATE')field(host,'pattern','');
    if(action==='CREATE'&&kind==='HANDLER')field(host,'method','GET');
    if(action!=='DELETE')for(const [name,value]of Object.entries(editorDefaults(kind,row))){
      if(action==='PUBLISH'&&name!=='status')continue;field(host,name,value);
    }
    get('target').textContent=[tr(action),tr(kind),editing.module,editing.pattern,kind==='HANDLER'?editing.method:''].filter(Boolean).join(' / ');
    get('editor').hidden=false;controls();get('editor').scrollIntoView({block:'nearest',behavior:'smooth'});
  }
  async function load(){
    close();attempted=false;busy=true;snapshot=null;get('catalog').hidden=true;controls();message(tr('loading'));
    try{snapshot=await api('/list?'+new URLSearchParams({schema:root.dataset.schema}));module=null;template=null;message(snapshot.status==='AVAILABLE'?(snapshot.tables.MODULES.length?'':tr('empty')):tr(snapshot.status));render();}
    catch(ex){message(ex.message);}finally{busy=false;controls();}
  }
  get('form').addEventListener('submit',async event=>{
    event.preventDefault();if(busy||attempted||!editing)return;invalidate();const current=gate.start();const values=Object.fromEntries(new FormData(get('form'))),input={schema:root.dataset.schema,kind:editing.kind,action:editing.action,module:values.module??editing.module,pattern:values.pattern??editing.pattern,method:values.method??editing.method,revision:snapshot.revision,values};
    delete values.module;delete values.pattern;delete values.method;busy=true;controls();message(tr('loading'));
    try{const data=await api('/preview',input);if(!current())return;preview=data;get('review').hidden=false;
      get('original').textContent=JSON.stringify(data.original,null,2);get('changes').textContent=data.destructive?tr('DELETE'):changeLines(data.original,data.proposed);
      get('sql').textContent=data.statements.map(s=>s.sql+'\n'+tr('bindings')+'\n'+JSON.stringify(s.bindings,null,2)).join('\n\n');
      get('warnings').replaceChildren();for(const key of previewNotices(data)){const warning=el('p',tr(key));warning.className='app-alert is-warning';get('warnings').append(warning);}
      get('impact').textContent=Object.entries(data.affected).map(([key,value])=>tr(key)+': '+value).join(' · ');get('phrase').textContent=data.confirmation;message(tr('reviewReady'));
    }catch(ex){if(current())message(ex.message);}finally{busy=false;controls();}
  });
  get('apply').addEventListener('click',async()=>{
    if(!canApply(preview,get('confirm').value,busy,attempted))return;
    const body={schema:root.dataset.schema,token:preview.token,confirmation:get('confirm').value};attempted=true;busy=true;controls();
    try{await api('/apply',body);busy=false;await load();message(tr('APPLIED')+(snapshot?.status==='AVAILABLE'?'':' '+tr('noRetry')));}
    catch(ex){message(ex.message+' '+tr('noRetry'));}finally{busy=false;preview=null;controls();}
  });
  get('confirm').addEventListener('input',controls);get('cancel').addEventListener('click',()=>{if(!busy)close();});get('refresh').addEventListener('click',()=>{if(!busy)load();});load();
});
