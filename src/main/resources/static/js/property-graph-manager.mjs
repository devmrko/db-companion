import {t} from './i18n.mjs';
import {renderMetadataBrowser} from './metadata-graph-browser.mjs';
const label=(key,...args)=>t('ontology.mg.'+key,key,...args);
const el=(tag,value,cls)=>{const node=document.createElement(tag);if(value!==undefined)node.textContent=value;if(cls)node.className=cls;return node;};
const displayValue=value=>value==null?'—':typeof value==='object'?JSON.stringify(value):String(value);

export function propertyGraphManager(host,{schema,post,create,review,canCreate=()=>true}){
  const toolbar=el('div',undefined,'app-ontology-actions'),refresh=el('button',label('refreshGraphs'),'btn app-btn app-btn-secondary'),newGraph=el('button',label('newGraph'),'btn app-btn app-btn-primary');
  refresh.type=newGraph.type='button';newGraph.addEventListener('click',create);toolbar.append(refresh,newGraph);
  const notice=el('p',label('existingHelp'),'app-filter-message'),status=el('p',undefined,'app-filter-message'),list=el('div'),results=el('div');
  const panel=el('div',undefined,'p-4'),header=el('div',undefined,'d-flex flex-wrap justify-content-between align-items-center gap-3 mb-3');
  header.append(el('h2',label('graphsTitle'),'h5 mb-0'),toolbar);panel.append(header,notice,status,list,results);host.append(panel);
  let loaded=false,busy=false,request=0;
  async function show(force=false){
    newGraph.dataset.boundDisabled=String(!canCreate());newGraph.disabled=!canCreate();if(busy||loaded&&!force)return;busy=true;++request;status.className='app-filter-message';status.textContent=label('loadingGraphs');results.replaceChildren();
    try{
      const graphs=await post('/metadata-graph/list',{schema});list.replaceChildren();
      if(!graphs.length)list.append(el('p',label('noGraphs'),'app-filter-message'));
      for(const graph of graphs.slice(0,100)){
        const row=el('div',undefined,'border rounded p-3 mb-2 d-flex flex-wrap justify-content-between align-items-center gap-3'),name=el('strong',graph.name),state=el('div'),read=el('button',label('queryGraph'),'btn app-btn app-btn-secondary');
        const title=el('div',undefined,'d-flex flex-wrap gap-2 align-items-center');title.append(name,el('span',graph.status,graph.status==='VALID'?'badge text-bg-success':'badge text-bg-secondary'));
        const details=el('details',undefined,'small mt-2');details.append(el('summary',label('technicalDetails')),el('p',`${graph.name}_NODES: ${graph.nodesStatus??'—'} · ${graph.name}_EDGES: ${graph.edgesStatus??'—'}`));
        state.append(title,el('p',label(graph.metadataCandidate?'metadataResult':'genericResult'),'small mb-0 mt-1'),details);
        read.type='button';read.disabled=graph.status!=='VALID'||!headerName(graph.name);read.dataset.boundDisabled=String(read.disabled);
        read.addEventListener('click',()=>query(graph.name));row.append(state,read);list.append(row);
      }
      loaded=true;status.textContent=label('listedGraphs',Math.min(graphs.length,100))+(graphs.length>100?' · '+label('graphListLimit'):'');
    }catch(error){status.textContent=error.message;status.className='app-alert is-error';}
    finally{busy=false;}
  }
  function headerName(name){return /^[A-Z][A-Z0-9_]{0,59}$/.test(name);}
  async function query(name){
    const ticket=++request;
    results.replaceChildren();status.className='app-filter-message';status.textContent=label('queryingGraph',name);
    try{
      const value=await post('/metadata-graph/query',{schema,name}),rows=value.rows;if(ticket!==request)return;results.append(el('h3',name,'h5 mt-4'));
      if(value.mode==='METADATA'){renderMetadataBrowser(results,value,{review});status.textContent=label('queryComplete');return;}
      results.append(el('p',label(value.mode==='METADATA'?'metadataResult':'genericResult'),'app-filter-message'));
      if(!rows.length){results.append(el('p',label('noGraphRows'),'app-filter-message'));status.textContent=label('queriedGraph',0);return;}
      const columns=value.mode==='METADATA'?['SOURCE_OBJECT','SOURCE_COLUMN','EDGE_KIND','RELATION_ID','RELATION_LABEL','MAPPING_POSITION','MAPPING_COUNT','CONDITION_TEXT','TARGET_OBJECT','TARGET_COLUMN']:['SOURCE_ID','EDGE_ID','TARGET_ID'];
      const table=el('table',undefined,'table app-table'),head=el('thead'),heading=el('tr');columns.forEach(key=>heading.append(el('th',key)));head.append(heading);table.append(head);
      const body=el('tbody');for(const row of rows){const tr=el('tr');for(const key of columns)tr.append(el('td',displayValue(row[key])));body.append(tr);}table.append(body);
      const wrap=el('div',undefined,'table-responsive');wrap.append(table);results.append(wrap,el('p',label('queryLimit'),'app-filter-message'));
      status.textContent=label('queriedGraph',rows.length);
    }catch(error){if(ticket!==request)return;status.textContent=error.message;status.className='app-alert is-error';}
  }
  refresh.addEventListener('click',()=>show(true));
  return {show,refresh:()=>show(true)};
}
