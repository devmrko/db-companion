import {t} from './i18n.mjs';
import {assistantApi} from './ai-assistant.mjs';

export const archiveCanSave=(state,hasPath)=>!!hasPath&&state?.owner&&state.records==='READY'&&state.rdf==='READY';
export const archiveCanSetup=state=>!!state?.owner&&state.records==='READY'&&state.rdf==='MISSING'&&state.api;
export const archiveJson=value=>JSON.stringify(value,null,2);
const a=(key,...args)=>t('ontology.archive.'+key,key,...args);
const node=(tag,text,css)=>{const el=document.createElement(tag);if(text!=null)el.textContent=text;if(css)el.className=css;return el;};

export function attachArchive(root,{work,post,selection}){
  const get=name=>root.querySelector(`[data-oa-${name}]`),base=root.dataset.base+'/archive',schema=root.dataset.schema;
  let status=null,page={rows:[],next:''},cursors=[''],position=0,mode='list',prepared=null,current=null,locked=false;
  const message=(text,error=false)=>{const el=root.querySelector('[data-oq-archive-message]');el.textContent=text;el.className=error?'app-alert is-error':'app-filter-message';};
  const api=(path,args={})=>assistantApi(base+'/'+path+'?'+new URLSearchParams({schema,...args}));
  const run=fn=>work(fn,'archive-message');
  function controls(busy,hasPath){
    locked=busy;get('open').disabled=busy;get('preview').disabled=busy||!hasPath;
    get('prev').disabled=busy||position===0;get('next').disabled=busy||!page.next;
    get('install').hidden=!status?.owner||status.records!=='MISSING'||mode!=='list';
    get('setup').hidden=!archiveCanSetup(status)||mode!=='list';
    get('save').disabled=busy||!prepared||!get('consent').checked;
  }
  function showMode(next){
    mode=next;get('list').hidden=mode!=='list';get('review').hidden=mode==='list';get('back').hidden=mode==='list';
    get('consent').checked=false;get('consent-label').hidden=mode==='detail';get('save').hidden=mode==='detail';get('read-graph').hidden=true;
    get('rdf-section').hidden=!['preview','detail'].includes(mode);
  }
  function open(){if(!get('dialog').open)get('dialog').showModal();}
  function renderRows(){
    const box=get('rows');box.replaceChildren();get('page').textContent=String(position+1);
    if(!page.rows.length){box.append(node('p',a('empty'),'app-filter-message'));return;}
    const table=node('table',null,'table app-table'),head=node('thead'),hr=node('tr');
    for(const text of [a('time'),a('question'),a('actor'),a('state')])hr.append(node('th',text));head.append(hr);table.append(head);const body=node('tbody');
    for(const item of page.rows){const row=node('tr'),question=node('td'),link=node('button',item.question||item.id,'btn app-btn app-btn-quiet app-archive-question');link.type='button';link.title=item.question;link.addEventListener('click',()=>run(()=>detail(item.id)));question.append(link);row.append(node('td',item.recordedAt),question,node('td',item.actor),node('td',a('status.'+item.state)));body.append(row);}
    table.append(body);box.append(table);
  }
  async function list(){prepared=null;current=null;showMode('list');page=status?.records==='READY'?await api('list',{before:cursors[position]}):{rows:[],next:''};renderRows();}
  async function load(refresh=false){
    status=await api('status',{refresh});get('status').textContent=`${schema} · ${a('records')} ${a('status.'+status.records)} · RDF ${a('status.'+status.rdf)}`;
    if(status.diagnostic)message(status.diagnostic,true);else message('');position=0;cursors=[''];await list();
  }
  function review(title,script,payload,rdfText=''){
    get('review-title').textContent=title;get('meta').textContent='';get('script').textContent=script;get('json').textContent=archiveJson(payload);get('rdf').textContent=rdfText;
  }
  async function detail(id){
    const data=await api('detail',{id});prepared=null;current=data;showMode('detail');const p=data.payload;
    review(data.item.question,p.sparql||'',p);get('meta').textContent=`${data.item.id} · ${data.item.actor} · ${data.item.recordedAt} · ${a('status.'+data.item.state)}`;
    get('read-graph').hidden=data.item.state!=='SUCCEEDED';message(p.error||'',!!p.error);
  }
  get('open').addEventListener('click',()=>run(async()=>{open();await load();}));
  get('refresh').addEventListener('click',()=>run(()=>load(true)));
  get('back').addEventListener('click',()=>run(async()=>{message('');await list();}));
  get('prev').addEventListener('click',()=>run(async()=>{position--;await list();message('');}));
  get('next').addEventListener('click',()=>run(async()=>{cursors[++position]=page.next;await list();message('');}));
  get('preview').addEventListener('click',()=>run(async()=>{
    open();await load();const selected=selection();if(!archiveCanSave(status,!!selected)){message(a('notReady'),true);return;}
    prepared=null;const data=await post('archive/preview',{id:selected.id,route:selected.route});prepared={mode:'preview',...data};showMode('preview');
    review(data.question,data.sparql,data.payload,data.turtle);get('meta').textContent=`${data.id} · ${a('triples',data.triples)}`;get('consent-text').textContent=a('consent');get('save').textContent=a('save');message('');
  }));
  get('install').addEventListener('click',()=>{
    if(locked)return;prepared={mode:'install'};showMode('install');review(a('install'),`${schema}.DBC_APP_RECORD`,{recordTypes:['ONTOLOGY_QUERY','RDF_STORE'],format:1});get('consent-text').textContent=a('installConsent',schema);get('save').textContent=a('install');controls(false,!!selection());
  });
  get('setup').addEventListener('click',()=>run(async()=>{
    const value=await api('setup-preview');prepared={mode:'setup',...value};showMode('setup');review(a('setup'),value.sql,value);get('consent-text').textContent=a('setupConsent');get('save').textContent=a('setup');message('');
  }));
  get('consent').addEventListener('change',()=>controls(locked,!!selection()));
  get('save').addEventListener('click',()=>run(async()=>{
    const value=prepared;if(!value||!get('consent').checked)return;prepared=null;get('consent').checked=false;
    if(value.mode==='install'){await post('archive/install',{schema,confirmed:true});await load(true);}
    else if(value.mode==='setup'){await post('archive/setup',{schema,tablespace:value.tablespace,confirmed:true});await load(true);}
    else{const result=await post('archive/save',{token:value.token,confirmed:true});await detail(result.item.id);message(a('saved'));}
  }));
  get('read-graph').addEventListener('click',()=>run(async()=>{
    const result=await api('graph',{id:current.item.id});get('rdf').textContent=result.turtle;get('rdf-section').open=true;message(a('triples',result.count));
  }));
  const close=()=>{if(locked)return;prepared=null;get('dialog').close();};
  get('close').addEventListener('click',close);get('dialog').addEventListener('cancel',event=>{event.preventDefault();close();});
  return {controls};
}
