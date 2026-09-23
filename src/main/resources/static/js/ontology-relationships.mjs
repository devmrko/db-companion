import {t} from './i18n.mjs';
import {assistantApi} from './ai-assistant.mjs';
import {tableId,readableZoom} from './ontology-erd.mjs';
const label=(key,...args)=>t('ontology.relationships.'+key,key,...args);
const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text??'';if(cls)e.className=cls;return e;};
const normalize=s=>String(s??'').normalize('NFC').toLocaleLowerCase();
export function relationPage(data,query='',state='',page=1){
  const rows=data.relations.filter(r=>(!state||r.status===state)&&normalize([r.source,r.targetSchema,r.target,r.label,...r.sourceColumns,...r.targetColumns].join(' ')).includes(normalize(query)));
  const pages=Math.ceil(rows.length/10),current=Math.max(1,Math.min(page,pages||1));
  return {items:rows.slice((current-1)*10,current*10),total:rows.length,page:current,pages};
}
export function unconnectedTables(data,schema){
  const connected=new Set(data.relations.filter(r=>['FK','APPROVED'].includes(r.status)).flatMap(r=>[r.source,r.targetSchema===schema?r.target:'']));
  return data.tables.filter(table=>!connected.has(table.name)).map(table=>table.name);
}
export function relationElements(data){
  const nodes=new Map(data.tables.map(table=>[tableId(data.schema,table.name),{data:{id:tableId(data.schema,table.name),name:table.name,
    label:[table.concept||table.name,table.concept?table.name:'',`v${table.revision} · ${t('ontology.'+table.state,table.state)}`].filter(Boolean).join('\n')}}]));
  const edges=[];
  for(const r of data.relations){
    if(r.status==='REJECTED'||!r.targetSchema||!r.target)continue;
    const target=tableId(r.targetSchema,r.target);if(!nodes.has(target))nodes.set(target,{data:{id:target,name:r.target,label:r.targetSchema+'.'+r.target},classes:'boundary'});
    edges.push({data:{id:r.id,source:tableId(data.schema,r.source),target,label:r.label||r.key?.name||label('CANDIDATE')},
      classes:r.status==='FK'?(r.key.status==='ENABLED'&&r.key.validated==='VALIDATED'?'fk':'fk unchecked'):r.status.toLowerCase()});
  }
  return [...nodes.values(),...edges];
}
export function reviewPayload(data,relation,values){
  const source=data.tables.find(t=>t.name===relation.source),target=data.tables.find(t=>t.name===relation.target);
  if(!source||!target||relation.targetSchema!==data.schema)throw new Error(label('mapping'));
  return {schema:data.schema,source:source.name,sourceDocumentId:source.documentId,sourceRevision:source.revision,
    target:target.name,targetDocumentId:target.documentId,targetRevision:target.revision,sourceColumns:relation.sourceColumns,targetColumns:relation.targetColumns,
    candidateId:relation.id||'',label:values.label,condition:values.condition,status:values.status};
}
// Reopening a result is intentionally not a database refresh, and must not discard an editor.
export async function revealAnalysis({show,resetFilters,focus,announce,hasData}){
  const reused=hasData();resetFilters();await show();
  if(hasData()){focus();announce(reused);}
}
export function ontologyRelationships(root,{schema,base,post,run,saved,openDefinition,coverage=()=>({saved:0,total:0})}){
  const get=key=>root.querySelector(`[data-rel-${key}]`);
  let data=null,cy=null,epoch=0,page=1,dirty=false,active=null;
  const status=(value,error=false)=>{get('message').textContent=value;get('message').className=error?'app-alert is-error':'app-filter-message';};
  const button=(text,fn)=>{const e=el('button',text,'btn app-btn app-btn-quiet');e.type='button';e.addEventListener('click',fn);return e;};
  const safeLeave=()=>!dirty||window.confirm(t('ontology.discard','Discard unsaved changes?'));
  function field(host,title,input){const row=el('label',undefined,'app-ontology-field');row.append(el('span',title),input);host.append(row);return input;}
  function input(value,max,area=false){const e=el(area?'textarea':'input',undefined,'form-control');e.value=value??'';e.maxLength=max;if(area)e.rows=2;e.addEventListener('input',()=>dirty=true);return e;}
  function selector(host,title,items,value,change){const e=el('select',undefined,'form-select app-select');e.setAttribute('aria-label',title);e.append(...items.map(v=>new Option(v.label??v.name,v.name)));e.value=value;e.addEventListener('change',()=>{dirty=true;change(e.value);});field(host,title,e);return e;}
  function draw(){
    cy?.destroy();cy=null;get('canvas').replaceChildren();if(!data.tables.length){status(label('empty'));return;}
    if(typeof globalThis.cytoscape!=='function')throw new Error(t('ontology.erd.libraryError','Diagram library unavailable'));
    const theme=getComputedStyle(root),color=(key,fallback)=>theme.getPropertyValue(key).trim()||fallback;
    cy=globalThis.cytoscape({container:get('canvas'),elements:relationElements(data),minZoom:.08,maxZoom:2.5,userZoomingEnabled:false,
      layout:{name:'grid'},style:[
        {selector:'node',style:{shape:'roundrectangle',width:250,height:90,label:'data(label)','text-wrap':'wrap','text-max-width':225,'font-size':13,'text-valign':'center',color:color('--app-text','#302c28'),'background-color':color('--app-surface','#fff'),'border-width':1.5,'border-color':color('--app-border','#bbb')}},
        {selector:'node.boundary',style:{'border-style':'dashed'}},
        {selector:'edge',style:{width:1.5,'curve-style':'bezier','target-arrow-shape':'triangle',label:'data(label)','font-size':10,'text-background-opacity':.9,'text-background-color':color('--app-surface','#fff'),'text-wrap':'ellipsis','text-max-width':140,'line-color':color('--app-muted','#888'),'target-arrow-color':color('--app-muted','#888')}},
        {selector:'edge.candidate, edge.stale, edge.unchecked',style:{'line-style':'dashed','line-color':'#af752f','target-arrow-color':'#af752f'}},
        {selector:'edge.approved',style:{width:2.5,'line-color':'#497867','target-arrow-color':'#497867'}},
        {selector:':selected',style:{'border-width':3,'border-color':color('--app-accent','#845e4d'),'overlay-opacity':.12}}
      ]});
    cy.on('tap','edge',event=>{if(data&&safeLeave())inspect(data.relations.find(r=>r.id===event.target.id()));});
    cy.on('tap','node',event=>{if(data&&safeLeave())inspectTable(event.target.id());});
    cy.on('zoom',()=>get('zoom').textContent=Math.round(cy.zoom()*100)+'%');layout();
  }
  function focus(){if(cy){cy.resize();cy.zoom(1);cy.center(cy.$(':selected').length?cy.$(':selected'):cy.nodes().sort((a,b)=>b.degree()-a.degree()).first());}}
  function layout(){if(cy&&data){cy.resize();cy.layout({name:data.tables.length>120?'grid':'cose',animate:false,randomize:false,padding:30,nodeRepulsion:()=>14000,idealEdgeLength:()=>95,numIter:500}).run();const zoom=readableZoom(cy.zoom());focus();cy.zoom(zoom);get('zoom').textContent=Math.round(zoom*100)+'%';}}
  function counts(){status(label('counts',data.tables.length,...['FK','CANDIDATE','APPROVED','REJECTED','STALE'].map(s=>data.relations.filter(r=>r.status===s).length)));
    const scope=coverage();get('coverage').textContent=t('ontology.discovery.coverage','Saved {0} / {1}',scope.saved,scope.total);
    const isolated=unconnectedTables(data,schema);
    get('isolated-title').textContent=t('ontology.discovery.isolated','No confirmed relations: {0}',isolated.length);get('isolated').textContent=isolated.join(', ')||'—';
  }
  function list(){
    const result=relationPage(data,get('query').value,get('state').value,page);page=result.page;
    const body=get('rows');body.replaceChildren();
    for(const r of result.items){const row=el('tr');
      for(const value of [label(r.status),`${r.source} → ${r.targetSchema}.${r.target}`,r.label||r.key?.name||'—']){const cell=el('td',value);cell.title=value;row.append(cell);}
      const cell=el('td');cell.append(button(label('review'),()=>{if(safeLeave())inspect(r);}));row.append(cell);body.append(row);
    }
    get('page').textContent=label('page',result.total,result.pages?page:0,result.pages);
    get('prev').disabled=page<=1;get('next').disabled=page>=result.pages;
    get('prev').dataset.boundDisabled=String(get('prev').disabled);get('next').dataset.boundDisabled=String(get('next').disabled);
  }
  function columnInfo(table,name){const c=table?.columns.find(c=>c.name===name);return c?[c.name,c.type,c.label,c.description,...c.aliases].filter(Boolean).join(' · '):name;}
  function context(host,r){
    for(const [title,name] of [['source',r.source],['target',r.target]]){
      const table=data.tables.find(t=>t.name===name&&(title==='source'||r.targetSchema===schema));host.append(el('h3',label(title)),el('code',(title==='source'?schema:r.targetSchema)+'.'+name));
      if(table){host.append(el('p',table.concept||table.description||'—'),el('p',`v${table.revision} · ${t('ontology.'+table.state,table.state)}`,'app-filter-message'));
        host.append(button(label('definition'),()=>{if(safeLeave())openDefinition(name);}));}
    }
    const pairs=el('ol',undefined,'app-erd-mapping');r.sourceColumns.forEach((name,i)=>{const pair=el('li');pair.append(el('p',columnInfo(data.tables.find(t=>t.name===r.source),name)),el('p','→ '+columnInfo(r.targetSchema===schema?data.tables.find(t=>t.name===r.target):null,r.targetColumns[i]??'—')));pairs.append(pair);});host.append(pairs);
  }
  function inspectTable(id){
    dirty=false;active=null;const host=get('detail');host.replaceChildren();cy?.elements().unselect();cy?.getElementById(id).select();
    const table=data.tables.find(t=>tableId(schema,t.name)===id);if(table){host.append(el('h3',table.concept||table.name),el('code',schema+'.'+table.name),el('p',table.description||'—'),button(label('definition'),()=>openDefinition(table.name)));}
    for(const r of data.relations.filter(r=>tableId(schema,r.source)===id||tableId(r.targetSchema,r.target)===id))host.append(button(`${label(r.status)} · ${r.source} → ${r.target}`,()=>inspect(r)));
  }
  function inspect(r){
    if(!r)return;dirty=false;active=r;cy?.elements().unselect();cy?.getElementById(r.id).select();const host=get('detail');host.replaceChildren(el('h3',label(r.status)));
    context(host,r);host.append(el('h3',label('evidence')),el('p',r.evidence.filter(value=>!value.startsWith('SOURCE_DEFINITION:')&&!value.startsWith('TARGET_DEFINITION:')).map(value=>value.startsWith('AI_')?t('ontology.discovery.evidence.'+value.split(':')[0],value.split(':')[0])+(value.includes(':')?' · '+value.slice(value.indexOf(':')+1):''):label('evidence.'+value)).join(' · ')));
    if(r.review)host.append(el('p',`${r.review.actor} · ${r.review.reviewedAt}`),el('p',label('versions',r.review.sourceRevision,r.review.targetRevision)),el('p',label(r.review.status)));
    if(r.status==='STALE')host.append(el('p',label('stale'),'app-alert'));
    if(r.origin==='FK'){host.append(el('p',r.label||'—'),el('p',`${r.key.name} · ${r.key.status} · ${r.key.validated}`));return;}
    const name=field(host,label('name'),input(r.label,80)),condition=field(host,label('condition'),input(r.condition,1000,true));
    if(r.targetSchema===schema&&data.tables.some(t=>t.name===r.target)){
      host.append(button(label('approve'),()=>save(r,name.value,condition.value,'APPROVED')),button(label('reject'),()=>save(r,name.value,condition.value,'REJECTED')));
    }
  }
  async function save(r,name,condition,state){
    if(state==='APPROVED'&&!name.trim()){status(label('nameRequired'),true);return;}
    if(!r.sourceColumns.length||r.sourceColumns.some(c=>!c)||r.targetColumns.some(c=>!c)){status(label('mapping'),true);return;}
    const payload=reviewPayload(data,r,{label:name,condition,status:state});
    await run(async()=>{
      try{await post('/relationships/review',payload);}catch(ex){
        // Keep the user's edited notes visible, but require refresh before another write.
        epoch++;data=null;dirty=true;get('detail').querySelectorAll('button,input,textarea,select').forEach(e=>{e.disabled=true;e.dataset.boundDisabled='true';});
        status(ex.message+' · '+label('refreshRequired'),true);throw ex;
      }
      dirty=false;await saved(r.source);await show();status(label('saved'));
    });
  }
  function manual(){
    if(!data?.tables.length||!safeLeave())return;dirty=false;active=null;const host=get('detail');host.replaceChildren(el('h3',label('manual')));
    const draft={id:'',source:data.tables[0].name,targetSchema:schema,target:data.tables[Math.min(1,data.tables.length-1)].name,sourceColumns:[],targetColumns:[]};
    const pairs=el('div',undefined,'app-relationship-pairs');
    const options=data.tables.map(t=>({name:t.name,label:t.name+(t.concept?' · '+t.concept:'')}));
    const reset=()=>{draft.sourceColumns=[];draft.targetColumns=[];pairs.replaceChildren();addPair();};
    selector(host,label('source'),options,draft.source,value=>{draft.source=value;reset();});selector(host,label('target'),options,draft.target,value=>{draft.target=value;reset();});
    host.append(pairs);
    function addPair(){
      if(draft.sourceColumns.length>=32)return;const position=draft.sourceColumns.length;draft.sourceColumns.push('');draft.targetColumns.push('');
      const row=el('div',undefined,'app-relationship-pair');
      for(const side of ['source','target']){const items=[{name:'',label:label('chooseColumn')},...data.tables.find(t=>t.name===draft[side]).columns.map(c=>({name:c.name,label:c.name+' · '+c.type+(c.label?' · '+c.label:'')}))];selector(row,label(side+'Column')+' '+(position+1),items,'',value=>draft[side+'Columns'][position]=value);}
      pairs.append(row);
    }
    addPair();host.append(button(label('addPair'),()=>{dirty=true;addPair();}),button(label('removePair'),()=>{if(draft.sourceColumns.length>1){dirty=true;draft.sourceColumns.pop();draft.targetColumns.pop();pairs.lastElementChild.remove();}}));
    const name=field(host,label('name'),input('',80)),condition=field(host,label('condition'),input('',1000,true));
    host.append(button(label('approve'),()=>save(draft,name.value,condition.value,'APPROVED')));
  }
  function invalidate(){epoch++;data=null;active=null;dirty=false;cy?.destroy();cy=null;get('canvas').replaceChildren();get('rows').replaceChildren();get('detail').replaceChildren();get('zoom').textContent='—';get('page').textContent='';get('manual').disabled=true;get('result').hidden=true;get('result').textContent='';}
  async function show(){
    if(data){cy?.resize();return;}const version=epoch;status(label('loading'));
    try{const result=await assistantApi(base+'/relationships?'+new URLSearchParams({schema}));if(version!==epoch)return;data=result;counts();draw();list();get('manual').disabled=!data.tables.length;get('detail').replaceChildren(el('p',label('choose')));}
    catch(ex){if(version===epoch){invalidate();status(ex.message,true);}throw ex;}
  }
  get('query').addEventListener('input',()=>{if(data){page=1;list();}});get('state').addEventListener('change',()=>{if(data){page=1;list();}});
  get('prev').addEventListener('click',()=>{if(data){page--;list();}});get('next').addEventListener('click',()=>{if(data){page++;list();}});
  get('manual').addEventListener('click',manual);get('fit').addEventListener('click',()=>cy?.fit(undefined,35));get('focus').addEventListener('click',focus);get('layout').addEventListener('click',layout);
  const zoomBy=factor=>{if(cy)cy.zoom({level:cy.zoom()*factor,renderedPosition:{x:cy.width()/2,y:cy.height()/2}});};
  get('in').addEventListener('click',()=>zoomBy(1.2));get('out').addEventListener('click',()=>zoomBy(1/1.2));
  new ResizeObserver(()=>{if(!root.hidden)cy?.resize();}).observe(get('canvas'));
  async function all(){
    root.scrollIntoView({block:'start',behavior:'instant'});root.focus({preventScroll:true});
    get('result').hidden=false;get('result').textContent=label('loading');
    try{await revealAnalysis({show,hasData:()=>data!==null,
      resetFilters:()=>{get('query').value='';get('state').value='';page=1;},focus,
      announce:reused=>{list();counts();const time=new Date(data.checkedAt);const checked=Number.isNaN(time.getTime())?data.checkedAt:time.toLocaleString(document.documentElement.lang);
        get('result').textContent=label(reused?'reused':'completed',checked);get('result').hidden=false;
      }});
    }catch(ex){get('result').hidden=true;throw ex;}
  }
  return {show,invalidate,dirty:()=>dirty,all};
}
