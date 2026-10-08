import {t} from './i18n.mjs';

const text=key=>t('ontology.query.graph.'+key,key);
const flow=key=>t('ontology.query.rdfFlow.'+key,key);
const nodeId=name=>'source:'+name;
const element=(tag,value)=>{const e=document.createElement(tag);if(value!=null)e.textContent=value;return e;};
// Bound canvas labels, including identifiers without whitespace. Full text stays in evidence.
export function graphLabel(value,limit=84){
  const chars=Array.from(String(value||''));
  const short=chars.length>limit?chars.slice(0,limit-1).join('')+'…':chars.join('');
  return short.split('\n').map(line=>Array.from(line).reduce((out,c,i)=>out+(i&&i%22===0?'\n':'')+c,'')).join('\n');
}

/** Presentation only: no inference, SQL parsing, approval, fetching or execution here. */
export function queryGraph(summary,selection,showReferences=false){
  const selected=new Set(selection.tables),chosen=new Set(selection.relations);
  const sources=summary.sources.filter(s=>selected.has(s.name));
  const names=new Set(sources.map(s=>s.name)),nodes=[],edges=[];
  sources.forEach((s,i)=>nodes.push({data:{id:nodeId(s.name),kind:'source',label:graphLabel(s.concept||s.name,48),record:s},position:{x:370,y:110+i*210}}));
  const outputs=new Map(sources.map(s=>[s.name,[]]));
  const references=summary.termReferences||[];
  (summary.rules||[]).forEach((rule,i)=>{
    const ref=references[i];
    // Never guess a connection from a term, description or similarly named column.
    const mapped=ref?.term===rule.term?(ref.sources||[]).filter(s=>names.has(s)):[];
    const id='term:'+i;
    nodes.push({data:{id,kind:'term',label:graphLabel(rule.term,36),record:rule,reference:ref,mapped},position:{x:90,y:110+i*210}});
    mapped.forEach(name=>edges.push({data:{id:'mapping:'+i+':'+name,source:id,target:nodeId(name),kind:'mapping',label:text('dictionaryMapping'),record:rule,reference:ref}}));
    // A multi-source dictionary rule must not be depicted as independent per-source aggregates.
    if(selection.mode==='INDEPENDENT'&&mapped.length===1){
      const ruleId='rule:'+i;
      nodes.push({data:{id:ruleId,kind:'rule',label:graphLabel(rule.term,36)+'\n'+text('ruleBasis')+'\n'+graphLabel(rule.definition,110),record:rule,reference:ref},position:{x:690,y:110+i*210}});
      outputs.get(mapped[0]).push(ruleId);
      edges.push({data:{id:'criteria:'+i,source:nodeId(mapped[0]),target:ruleId,kind:'criteria',label:text('ruleBasis'),record:rule,reference:ref}});
    }
  });
  const relevant=summary.relations.filter(r=>names.has(r.source)&&names.has(r.target));
  const used=relevant.filter(r=>selection.mode==='JOIN'&&r.usable&&chosen.has(r.id));
  const usedIds=new Set(used.map(r=>r.id));
  const available=relevant.filter(r=>!usedIds.has(r.id));
  const extras=showReferences?available.slice(0,40):[];
  for(const r of [...used,...extras])edges.push({data:{id:'relation:'+r.id,source:nodeId(r.source),target:nodeId(r.target),kind:usedIds.has(r.id)?'join':'reference',label:graphLabel(r.label||text('relation'),36)+' · '+r.status,record:r}});
  if(selection.mode==='INDEPENDENT'&&sources.length){
    const id='operation:independent';
    nodes.push({data:{id,kind:'operation',label:text(sources.length>1?'compare':'single'),record:{description:flow(sources.length>1?'independentSummary':'singleSummary')}},position:{x:1020,y:110+(Math.max(sources.length,references.length)-1)*105}});
    for(const s of sources)for(const output of outputs.get(s.name).length?outputs.get(s.name):[nodeId(s.name)])edges.push({data:{id:'output:'+output,source:output,target:id,kind:'operation',label:'',record:{description:flow(sources.length>1?'independentSummary':'singleSummary')}}});
  }
  return {nodes,edges,referenceCount:available.length,omitted:showReferences?Math.max(0,available.length-extras.length):available.length};
}

const styles=[
  {selector:'node',style:{label:'data(label)',shape:'round-rectangle',width:210,height:96,'background-color':'#e8f1f5','border-color':'#39738b','border-width':1.5,color:'#203a48','text-valign':'center','text-halign':'center','text-wrap':'wrap','text-max-width':194,'font-size':14}},
  {selector:'node[kind="term"]',style:{'background-color':'#eef0fb','border-color':'#737daf',width:180,height:76}},
  {selector:'node[kind="operation"]',style:{'background-color':'#f4f2ee','border-color':'#8a8175','border-style':'dashed'}},
  {selector:'node[kind="rule"]',style:{'background-color':'#f0f6f1','border-color':'#698775',width:252,height:194,'text-max-width':232,'font-size':14}},
  {selector:'edge',style:{label:'data(label)',width:2,'curve-style':'bezier','target-arrow-shape':'triangle','line-color':'#39738b','target-arrow-color':'#39738b','font-size':11,color:'#334955','text-background-color':'#fff','text-background-opacity':.95,'text-background-padding':3,'text-wrap':'wrap','text-max-width':160}},
  {selector:'edge[kind="mapping"]',style:{'line-color':'#737daf','target-arrow-color':'#737daf','line-style':'dotted'}},
  {selector:'edge[kind="reference"]',style:{'line-color':'#a78748','target-arrow-color':'#a78748','line-style':'dashed','control-point-step-size':65}},
  {selector:'edge[kind="operation"]',style:{'line-color':'#8a8175','target-arrow-color':'#8a8175','line-style':'dashed'}},
  {selector:'edge[kind="criteria"]',style:{'line-color':'#698775','target-arrow-color':'#698775','line-style':'dotted'}},
  {selector:':selected',style:{'border-width':3,'border-color':'#164966','line-color':'#164966','target-arrow-color':'#164966'}}
];

export function graphPanel(host,summary){
  let cy=null,observer=null,disposed=false,showReferences=false,selection={tables:[],relations:[],mode:'INDEPENDENT'},graph=null,active='';
  host.className='app-query-graph';
  const header=element('div'),title=element('h4',text('title')),toolbar=element('div');header.className='app-query-graph-header';toolbar.className='app-query-graph-tools';header.append(title,toolbar);
  const hint=element('p',text('readingHint')),legend=element('p',text('legend'));hint.className=legend.className='app-query-graph-caption';
  const body=element('div'),canvas=element('div'),detail=element('aside');body.className='app-query-graph-body';canvas.className='app-query-graph-canvas';detail.className='app-query-graph-detail';
  canvas.setAttribute('role','img');canvas.setAttribute('aria-label',text('title'));detail.setAttribute('aria-label',text('detail'));
  const picker=element('select'),pickerLabel=element('label',text('inspect'));picker.className='form-select';pickerLabel.append(picker);
  const counts=element('p'),warning=element('p');counts.className=warning.className='app-query-graph-caption';warning.setAttribute('role','status');
  function button(key,action){const b=element('button',text(key));b.type='button';b.className='btn app-btn app-btn-quiet';b.addEventListener('click',action);toolbar.append(b);return b;}
  const fit=()=>{if(cy){cy.resize();cy.fit(undefined,24);}};
  button('fit',fit);button('zoomIn',()=>cy?.zoom(Math.min(2,cy.zoom()*1.2)));button('zoomOut',()=>cy?.zoom(Math.max(.2,cy.zoom()/1.2)));
  const references=button('references',()=>{showReferences=!showReferences;update(selection);});references.setAttribute('aria-pressed','false');
  const help=element('details');help.className='app-query-graph-help';help.append(element('summary',text('guide')),legend,counts,pickerLabel,element('p',text('hint')));
  body.append(canvas,detail);host.append(header,hint,help,warning,body);
  function paragraph(key,value){if(value!=null&&value!==''){const p=element('p');p.append(element('strong',text(key)+' · '),element('span',String(value)));detail.append(p);}}
  function inspect(id){
    active=id;picker.value=id;const item=[...graph.nodes,...graph.edges].find(v=>v.data.id===id)?.data;
    detail.replaceChildren();detail.hidden=!item;body.className='app-query-graph-body'+(item?' has-detail':'');
    cy?.elements().unselect();
    if(!item){fit();return;}
    const close=element('button',text('closeDetail'));close.type='button';close.className='btn app-btn app-btn-quiet';close.addEventListener('click',()=>inspect(''));detail.append(close);
    const r=item.record;
    cy?.getElementById(id).select();detail.append(element('h5',item.kind==='source'?(r.concept||r.name):['rule','criteria'].includes(item.kind)?r.term+' · '+text('ruleBasis'):item.label));
    if(item.kind==='source'){
      paragraph('source',(r.schema?r.schema+'.':'')+r.name);paragraph('state',r.state+' · v'+r.revision);paragraph('description',r.description);
      const technical=element('details');technical.append(element('summary',text('technical')),element('p',r.versionIri||'—'));detail.append(technical);
      const columns=element('details');columns.append(element('summary',flow('columns')));
      for(const c of r.columns||[])columns.append(element('p',c.name+' · '+c.type+' · '+(c.description||'')));detail.append(columns);
    }else if(['term','mapping','rule','criteria'].includes(item.kind)){
      paragraph('dictionary',r.term);paragraph('version',item.reference?.revision);paragraph('definition',r.definition);
      if(item.kind==='term'&&!item.mapped.length)detail.append(element('p',text('unmapped')));
      if(item.kind==='mapping')detail.append(element('p',text('mappingHelp')));
      if(item.kind==='rule'||item.kind==='criteria')detail.append(element('p',text('ruleHelp')));
      if(r.criteria){const criteria=element('details');criteria.append(element('summary',flow('criteria')),element('pre',r.criteria));detail.append(criteria);}
      paragraph('id',item.reference?.id);
    }else if(item.kind==='join'||item.kind==='reference'){
      paragraph('state',r.status+' · '+(item.kind==='join'?text('selectedJoin'):r.usable?text('notSelected'):flow('referenceOnly')));
      paragraph('source',r.source+' → '+r.target);
      for(let i=0;i<r.from.length;i++)paragraph('pair',(i+1)+'. '+r.from[i]+' → '+(r.to[i]||'—'));
      paragraph('condition',r.condition||'—');paragraph('origin',r.origin);paragraph('id',r.id);paragraph('rdf',r.resourceIri);
      for(const value of r.evidence||[])paragraph('evidence',value);
    }else{const technical=element('details');technical.append(element('summary',text('sqlMethod')),element('p',r.description));detail.append(element('p',text('operationHelp')),technical);}
    fit();
  }
  picker.addEventListener('change',()=>inspect(picker.value));
  function update(value){
    if(disposed)return;selection=value;graph=queryGraph(summary,selection,showReferences);
    references.setAttribute('aria-pressed',String(showReferences));
    references.textContent=text(showReferences?'hideReferences':'references')+' ('+graph.referenceCount+')';
    counts.textContent=text('nodes')+' '+graph.nodes.length+' · '+text('edges')+' '+graph.edges.length+(graph.omitted?' · '+text('omitted')+' '+graph.omitted:'');
    picker.replaceChildren();const empty=element('option',text('choose'));empty.value='';picker.append(empty);
    for(const v of [...graph.nodes,...graph.edges]){const option=element('option',v.data.kind==='source'?(v.data.record.concept||'')+' · '+v.data.record.name:v.data.label.replaceAll('\n',' · ')||text('separate'));option.value=v.data.id;picker.append(option);}
    const height=Math.min(600,Math.max(380,Math.max(summary.rules.length,selection.tables.length)*170+60));canvas.style.height=height+'px';
    cy?.destroy();cy=null;canvas.replaceChildren();warning.textContent='';
    if(!selection.tables.length)warning.textContent=flow('chooseSources');
    else if(selection.mode==='JOIN'&&!graph.edges.some(v=>v.data.kind==='join'))warning.textContent=flow('chooseRelations');
    try{
      if(typeof globalThis.cytoscape!=='function')throw new Error('unavailable');
      cy=globalThis.cytoscape({container:canvas,elements:[...graph.nodes,...graph.edges],layout:{name:'preset',fit:true,padding:30},style:styles,minZoom:.2,maxZoom:2,userZoomingEnabled:false,autoungrabify:true});
      cy.on('tap','node, edge',event=>inspect(event.target.id()));fit();
    }catch{cy?.destroy();cy=null;warning.textContent=text('fallback');}
    inspect([...graph.nodes,...graph.edges].some(v=>v.data.id===active)?active:'');
  }
  if(typeof ResizeObserver!=='undefined'){observer=new ResizeObserver(()=>{if(!disposed)fit();});observer.observe(canvas);}
  return {update,destroy(){disposed=true;observer?.disconnect();cy?.destroy();cy=null;}};
}

// Disconnecting the workflow on a new search also destroys its canvas and observer.
export function attachQueryGraph(host,summary){
  if(typeof globalThis.HTMLElement==='undefined'||!globalThis.customElements)return {update(){}};
  const name='dbc-query-evidence-graph';
  if(!customElements.get(name))customElements.define(name,class extends HTMLElement{
    connectedCallback(){this.replaceChildren();this.view=graphPanel(this,this.summary);if(this.selection)this.view.update(this.selection);}
    disconnectedCallback(){this.view?.destroy();this.view=null;}
    update(value){this.selection=value;this.view?.update(value);}
  });
  const view=document.createElement(name);view.summary=summary;host.append(view);return view;
}
