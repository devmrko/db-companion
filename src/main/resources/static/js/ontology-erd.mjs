import {t} from './i18n.mjs';
import {assistantApi} from './ai-assistant.mjs';
import {profileTables} from './table-list.mjs';

const label=(key,...args)=>t('ontology.erd.'+key,key,...args);
export const tableId=(schema,name)=>JSON.stringify([schema,name]);
export const readableZoom=value=>Math.max(0.85,Math.min(1,Number.isFinite(value)?value:1));
export function columnDetails(entry){
  const source=entry.document.source,keys=new Map(keyColumns(source.keys).map(c=>[c.name,c.kinds]));
  return source.columns.map(c=>({name:c.name,key:keys.get(c.name)||'',type:c.dataType,
    description:entry.document.meaning.columns[c.name]?.description||c.comment||'—'}));
}

// Only recorded FK constraints become edges. Names/comments never imply a relationship.
export function relationshipGraph(data,allowed=null,expanded=[]) {
  const tables=new Map(data.tables.map(row=>[tableId(data.schema,row.name),row]));
  const visible=new Set(data.tables.filter(row=>allowed===null||allowed.has(row.name)).map(row=>tableId(data.schema,row.name)));
  for(const id of expanded)if(tables.has(id))visible.add(id);
  const nodes=new Map(),edges=[];
  const add=(schema,name)=>{
    const id=tableId(schema,name),saved=tables.get(id);
    if(!nodes.has(id))nodes.set(id,{id,schema,name,table:saved??null,
      boundary:visible.has(id)?null:schema!==data.schema?'OTHER_SCHEMA':saved?'FILTERED':'MISSING'});
    return id;
  };
  for(const id of visible)add(data.schema,tables.get(id).name);
  for(const table of data.tables)for(const key of table.keys) {
    if(key.type!=='R'||!key.targetOwner||!key.targetTable)continue;
    const source=tableId(data.schema,table.name),target=tableId(key.targetOwner,key.targetTable);
    if(!visible.has(source)&&!visible.has(target))continue;
    add(data.schema,table.name);add(key.targetOwner,key.targetTable);
    edges.push({id:'fk:'+JSON.stringify([data.schema,table.name,key.name]),source,target,key});
  }
  return {nodes:[...nodes.values()],edges};
}

export function keyColumns(keys) {
  const columns=new Map();
  for(const key of keys)for(const name of key.columns){
    if(!columns.has(name))columns.set(name,new Set());
    columns.get(name).add(({P:'PK',U:'UK',R:'FK'})[key.type]);
  }
  return [...columns].map(([name,kinds])=>({name,kinds:[...kinds].filter(Boolean).join('/')}));
}

export function graphElements(graph) {
  const shorten=value=>value.length>32?value.slice(0,31)+'…':value;
  return [
    ...graph.nodes.map(node=>{
      const columns=node.boundary?[]:keyColumns(node.table.keys),lines=columns.slice(0,4).map(c=>`${c.kinds}  ${shorten(c.name)}`);
      if(columns.length>4)lines.push(`+${columns.length-4}`);
      const subtitle=node.boundary?label(node.boundary):`v${node.table.revision} · ${t('ontology.'+node.table.state,node.table.state)}`;
      return {data:{id:node.id,label:[shorten(node.name),subtitle,...lines].join('\n'),height:64+lines.length*20},classes:node.boundary?'boundary':''};
    }),
    ...graph.edges.map(edge=>({data:{id:edge.id,source:edge.source,target:edge.target,label:edge.key.name},
      classes:edge.key.status!=='ENABLED'||edge.key.validated!=='VALIDATED'?'unchecked':''}))
  ];
}

const el=(tag,text,cls)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text??'—';if(cls)node.className=cls;return node;};
function action(text,run){const node=el('button',text,'btn app-btn app-btn-quiet');node.type='button';node.addEventListener('click',run);return node;}
function columns(entry){
  const list=el('ul',undefined,'app-erd-columns');list.setAttribute('aria-label',label('column'));
  for(const column of columnDetails(entry)){
    const item=el('li'),head=el('div',undefined,'app-erd-column-heading');
    head.append(el('code',column.name));if(column.key)head.append(el('span',column.key,'app-erd-key'));
    item.append(head,el('code',column.type,'app-erd-column-type'),el('p',column.description));list.append(item);
  }
  return list;
}
function mapping(key){
  const list=el('ol',undefined,'app-erd-mapping');
  key.columns.forEach((name,i)=>{const item=el('li'),pair=el('dl');
    pair.append(el('dt',label('sourceColumn')),el('dd',name),el('dt',label('targetColumn')),el('dd',key.targetColumns[i]??'—'));
    item.append(pair);list.append(item);
  });return list;
}

export function ontologyErd(root,{schema,base}) {
  const get=name=>root.querySelector(`[data-erd-${name}]`);
  let data=null,cy=null,view=null,profiles=null,allowed=null,expanded=new Set(),selected=null,selection=0,filterVersion=0,epoch=0;
  const objects=new Map(),details=new Map();
  const url=(path,params={})=>base+path+'?'+new URLSearchParams({schema,...params});
  const profileUrl=profile=>root.dataset.profilesUrl+'?'+new URLSearchParams({schema,...(profile===undefined?{}:{profile})});
  const message=(text,error=false)=>{get('message').textContent=text;get('message').className=error?'app-alert is-error':'app-filter-message';};
  function destroy(){cy?.destroy();cy=null;view=null;get('zoom').textContent='—';get('select').replaceChildren(new Option(label('select'),''));}
  function resetDetail(){selection++;selected=null;get('detail').replaceChildren(el('p',label('choose')));}
  function draw(){
    destroy();resetDetail();
    view=relationshipGraph(data,allowed,expanded);
    const unresolved=data.tables.flatMap(table=>table.keys).filter(key=>key.type==='R'&&(!key.targetOwner||!key.targetTable)).length;
    message(label('counts',view.nodes.filter(n=>!n.boundary).length,view.edges.length,view.nodes.filter(n=>n.boundary).length)+(unresolved?' · '+label('unresolved',unresolved):''));
    if(!view.nodes.length){message(label('empty'));return;}
    if(typeof globalThis.cytoscape!=='function')throw new Error(label('libraryError'));
    const theme=getComputedStyle(root),color=(name,fallback)=>theme.getPropertyValue(name).trim()||fallback;
    cy=globalThis.cytoscape({container:get('canvas'),elements:graphElements(view),minZoom:0.1,maxZoom:2.5,
      boxSelectionEnabled:false,autounselectify:false,wheelSensitivity:0.2,
      layout:{name:'grid',padding:35,avoidOverlap:true},
      style:[
        {selector:'node',style:{shape:'roundrectangle',width:280,height:'data(height)',label:'data(label)',
          'background-color':color('--app-surface','#fff'),'border-color':color('--app-border','#ddd'),'border-width':1.5,
          color:color('--app-text','#302c28'),'font-size':14,'text-wrap':'wrap','text-valign':'center','text-halign':'center','line-height':1.4}},
        {selector:'node.boundary',style:{'border-style':'dashed','background-color':color('--app-accent-soft','#f1efed'),color:color('--app-muted','#777')}},
        {selector:'edge',style:{width:1.5,'curve-style':'bezier','target-arrow-shape':'triangle','line-color':color('--app-muted','#928b80'),'target-arrow-color':color('--app-muted','#928b80')}},
        {selector:'edge.unchecked',style:{'line-style':'dashed'}},
        {selector:':selected',style:{'border-color':color('--app-accent','#403c38'),'border-width':3,'line-color':color('--app-accent','#403c38'),'target-arrow-color':color('--app-accent','#403c38')}}
      ]});
    cy.on('tap','node',event=>selectNode(event.target.id()));
    cy.on('tap','edge',event=>showEdge(view.edges.find(edge=>edge.id===event.target.id())));
    cy.on('zoom',()=>{get('zoom').textContent=Math.round(cy.zoom()*100)+'%';});
    const options=view.nodes.map(node=>new Option(`${node.schema}.${node.name}${node.boundary?' · '+label(node.boundary):''}`,node.id));
    get('select').append(...options);
    layout();
  }
  function anchor(){
    const choice=cy?.$(':selected');if(choice?.length)return choice;
    return cy?.nodes().sort((a,b)=>b.degree()-a.degree()).first();
  }
  function focus(zoom){if(cy){cy.resize();cy.zoom(zoom);cy.center(anchor());get('zoom').textContent=Math.round(cy.zoom()*100)+'%';}}
  function layout(){if(cy){cy.resize();cy.layout({name:view.nodes.length>120?'grid':'cose',animate:false,randomize:false,padding:30,nodeRepulsion:()=>12000,idealEdgeLength:()=>80,numIter:500,componentSpacing:60}).run();focus(readableZoom(cy.zoom()));}}
  function relations(id){
    const host=el('div',undefined,'app-erd-relations');host.append(el('h3',label('relations')));
    const edges=view.edges.filter(e=>e.source===id||e.target===id);
    if(!edges.length)host.append(el('p',label('noRelations')));
    for(const edge of edges){const source=view.nodes.find(n=>n.id===edge.source),target=view.nodes.find(n=>n.id===edge.target);
      host.append(action(`${edge.key.name} · ${source.name} → ${target.name}`,()=>showEdge(edge)));}
    return host;
  }
  function showEdge(edge){
    if(!edge)return;selection++;
    selected=null;get('select').value='';cy?.elements().unselect();cy?.getElementById(edge.id).select();
    const source=view.nodes.find(n=>n.id===edge.source),target=view.nodes.find(n=>n.id===edge.target),key=edge.key;
    const host=get('detail');host.replaceChildren(el('h3',key.name),el('p',`${source.schema}.${source.name} → ${target.schema}.${target.name}`),el('p',[key.status,key.validated].join(' · ')),
      mapping(key),
      action(label('sourceTable'),()=>selectNode(source.id)),action(label('targetTable'),()=>selectNode(target.id)));
  }
  async function selectNode(id){
    const node=view?.nodes.find(n=>n.id===id);if(!node)return;
    const token=++selection;selected=id;get('select').value=id;
    cy?.elements().unselect();cy?.getElementById(id).select();
    const host=get('detail');host.replaceChildren(el('h3',`${node.schema}.${node.name}`));
    if(node.boundary){
      host.append(el('p',label(node.boundary)));
      if(node.boundary==='FILTERED')host.append(action(label('expand'),()=>{expanded.add(id);draw();selectNode(id);}));
      host.append(relations(id));return;
    }
    host.append(el('p',label('loading')));
    const key=JSON.stringify([node.name,node.table.revision]);
    try{
      if(!details.has(key))details.set(key,assistantApi(url('/detail',{table:node.name,revision:node.table.revision})).catch(ex=>{details.delete(key);throw ex;}));
      const entry=await details.get(key);if(token!==selection||selected!==id)return;
      const source=entry.document.source;
      host.replaceChildren(el('h3',`${node.schema}.${node.name}`),el('p',`v${entry.revision} · ${t('ontology.'+entry.state,entry.state)}`),
        el('p',label('captured',source.capturedAt),'app-filter-message'),el('p',entry.document.meaning.description||source.comment||'—'),
        columns(entry),relations(id));
    }catch(ex){if(token===selection)host.append(el('p',ex.message,'app-alert is-error'));}
  }
  async function loadProfiles(){
    if(profiles!==null)return;
    const version=epoch;
    try{const result=await assistantApi(profileUrl());if(version!==epoch)return;profiles=result;
      get('profile').replaceChildren(new Option(label('all'),''),...profiles.map(p=>new Option(p.name,p.name)));
    }catch(ex){if(version!==epoch)return;profiles=[];get('profile-message').textContent=ex.message;}
  }
  get('profile').addEventListener('change',async()=>{
    const version=++filterVersion,name=get('profile').value;expanded.clear();resetDetail();get('profile-message').textContent='';
    if(!name){allowed=null;draw();return;}
    allowed=new Set();draw();message(label('loading'));
    try{if(!objects.has(name))objects.set(name,assistantApi(profileUrl(name)).catch(ex=>{objects.delete(name);throw ex;}));
      const value=await objects.get(name);if(version!==filterVersion)return;
      allowed=new Set(profileTables(data.tables,schema,value.objectList).map(row=>row.name));draw();
    }catch(ex){if(version!==filterVersion)return;allowed=new Set();draw();message(ex.message,true);}
  });
  get('select').addEventListener('change',()=>selectNode(get('select').value));
  get('fit').addEventListener('click',()=>cy?.fit(undefined,35));get('layout').addEventListener('click',layout);
  get('actual').addEventListener('click',()=>focus(1));get('focus').addEventListener('click',()=>focus(1));
  const zoomBy=factor=>{if(cy)cy.zoom({level:cy.zoom()*factor,renderedPosition:{x:cy.width()/2,y:cy.height()/2}});};
  get('in').addEventListener('click',()=>zoomBy(1.2));get('out').addEventListener('click',()=>zoomBy(1/1.2));
  const observer=new ResizeObserver(()=>{if(!root.hidden)cy?.resize();});observer.observe(get('canvas'));
  return {
    invalidate(){epoch++;filterVersion++;data=null;profiles=null;allowed=null;expanded.clear();objects.clear();details.clear();destroy();resetDetail();get('profile').replaceChildren(new Option(label('all'),''));get('profile-message').textContent='';},
    async show(){
      if(data){cy?.resize();return;}
      const version=epoch;message(label('loading'));
      try{const result=await assistantApi(url('/graph'));if(version!==epoch)return;data=result;draw();await loadProfiles();}
      catch(ex){if(version===epoch){data=null;destroy();message(ex.message,true);}}
    }
  };
}
