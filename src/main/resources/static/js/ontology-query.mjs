import {t} from './i18n.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {pageOf} from './table-list.mjs';
import {attachArchive} from './ontology-archive.mjs';
import {renderTokenAnalysis} from './business-glossary.mjs';
import {mountQuestionAnalysis,analysisEndpoint} from './question-analysis.mjs';
import {routeCoverage,coverageLabel,unmatchedLabel} from './ontology-route-coverage.mjs';
import {renderDictionaryChoices,renderGroundedResult,automaticTermIds} from './ontology-grounding.mjs';
import {renderPlan,blockedSources} from './ontology-plan.mjs';
import {renderRdfWorkflow} from './ontology-rdf-workflow.mjs';

export const selectedRoute=(search,id)=>search?.routes?.find(r=>r.id===id)??null;
export const groupedTables=(search,route)=>(route?.tables??[]).map(name=>search.tables.find(t=>t.name===name)).filter(Boolean);
export const columnPage=(items,filter,page)=>pageOf(items.map(c=>({...c,description:`${c.description} ${c.label} ${(c.aliases??[]).join(' ')}`})),filter,page);
export const routeArrow=(search,route,index)=>search.evidence.find(e=>e.id===route.relations[index])?.source===route.tables[index]?'→':'←';
export const resultPage=(rows,page)=>pageOf(rows.map((cells,i)=>({name:String(i),cells})),'',page);
export const analysisMissing=search=>search?.analysis?.mode==='ORACLE_TEXT_UNCONFIGURED';
const q=(key,...args)=>t('ontology.query.'+key,key,...args);
const node=(tag,text,css)=>{const el=document.createElement(tag);if(text!=null)el.textContent=text;if(css)el.className=css;return el;};
const button=(text,fn)=>{const el=node('button',text,'btn app-btn app-btn-quiet');el.type='button';el.addEventListener('click',fn);return el;};
const timestamp=value=>{const d=new Date(value);return Number.isNaN(d.valueOf())?value:d.toLocaleString(document.documentElement.lang);};

if(typeof document!=='undefined')document.querySelectorAll('[data-ontology-query]').forEach(root=>{
  const get=name=>root.querySelector(`[data-oq-${name}]`),post=(path,data={})=>assistantApi(root.dataset.base+'/'+path,assistantPost(get('csrf'),data));
  let search=null,routeId='',draft=null,preview=null,result=null,busy=false,rp=1,optionsReady=false;
  let invalidation=Promise.resolve();
  let archiveUi=null;
  let groundedResult=null,availableSources=[];
  const grounding=node('section',null,'border rounded p-3 mt-3');grounding.hidden=true;get('form').after(grounding);
  const planPanel=node('section',null,'border rounded p-3 mt-3');planPanel.hidden=true;grounding.after(planPanel);
  const automatic=node('input');automatic.type='checkbox';automatic.checked=true;
  const automaticLabel=node('label');automaticLabel.append(automatic,node('span',q('plan.auto')));get('form').append(automaticLabel);
  const graphSelect=node('select',null,'form-select app-select'),graphLabel=node('label',q('grounding.graph'));
  graphSelect.setAttribute('aria-label',q('grounding.graph'));graphLabel.append(graphSelect);get('form').append(graphLabel);graphSelect.addEventListener('change',()=>invalidate(true));
  graphLabel.hidden=automatic.checked;automatic.addEventListener('change',()=>{graphLabel.hidden=automatic.checked;invalidate(true);});
  const analysis=mountQuestionAnalysis(root,{changed:()=>invalidate(true),isBusy:()=>busy});
  const message=(value,error=false,target='message')=>{get(target).textContent=value;get(target).className=error?'app-alert is-error':'app-filter-message';};
  function controls(){
    for(const el of root.querySelectorAll('button,input:not([type="hidden"]),select,textarea'))el.disabled=busy;
    get('find').disabled=busy||!optionsReady;
    const route=selectedRoute(search,routeId);get('selected').textContent=route?route.id==='INDEPENDENT'?q('plan.INDEPENDENT'):q('paths.selected',route.tables.length,route.relations.length):search?.routes.length?q('paths.choose'):'';
    get('route-guidance').hidden=!search?.routes.length||route?.id==='INDEPENDENT';
    get('answer').disabled=busy||!route;get('sql').disabled=busy||!route;
    get('generate').disabled=busy||!preview||!get('consent').checked;
    get('reviewed').disabled=busy||!draft?.executable;get('execute').disabled=busy||!draft?.executable||!get('reviewed').checked;
    root.querySelectorAll('[data-local-disabled]').forEach(el=>el.disabled=true);
    const pr=resultPage(result?.rows??[],rp);get('result-prev').disabled=busy||pr.page<=1;get('result-next').disabled=busy||pr.page>=pr.pages;
    root.setAttribute('aria-busy',String(busy));
    archiveUi?.controls(busy,!!route);
    analysis.controls(busy);
  }
  function clearOutput(){draft=null;result=null;preview=null;get('reviewed').checked=false;get('consent').checked=false;for(const name of ['answer-panel','sql-panel','result-panel'])get(name).hidden=true;}
  function invalidate(clearSearch=false){
    const needsServer=!!search||!!draft||!!preview;
    clearOutput();if(clearSearch){search=null;routeId='';groundedResult=null;planPanel.hidden=true;planPanel.replaceChildren();get('evidence').hidden=true;grounding.hidden=true;grounding.replaceChildren();}
    if(needsServer){invalidation=invalidation.catch(()=>{}).then(()=>post('invalidate'));invalidation.catch(ex=>message(ex.message,true));}controls();
  }
  async function work(fn,target='message'){
    if(busy)return;busy=true;controls();message(t('ui.8bf609c884ca','불러오는 중…'),false,target);
    try{await invalidation;await fn();}catch(ex){message(ex.message,true,target);}finally{busy=false;controls();}
  }
  async function load(refresh=false){await work(async()=>{
    optionsReady=false;clearOutput();search=null;routeId='';get('evidence').hidden=true;grounding.hidden=true;grounding.replaceChildren();
    const data=await assistantApi(root.dataset.base+'/options?'+new URLSearchParams({schema:root.dataset.schema,refresh}));
    availableSources=data.tables;planPanel.hidden=true;groundedResult=null;
    get('anchor').replaceChildren();const empty=node('option',q('automatic'));empty.value='';get('anchor').append(empty);
    for(const item of data.tables){const o=node('option',item.concept&&item.concept!==item.name?`${item.name} · ${item.concept}`:item.name);o.value=item.name;get('anchor').append(o);}
    optionsReady=data.tables.length>0;get('checked').textContent=q('checked',timestamp(data.checkedAt));message(optionsReady?'':q('noTables'));await analysis.load();
    graphSelect.replaceChildren(new Option(q('grounding.catalog'),''));
    try{const graphs=await assistantApi(root.dataset.base+'/graphs?'+new URLSearchParams({schema:root.dataset.schema}));for(const graph of graphs)graphSelect.append(new Option(graph.name,graph.name));}catch(ex){message(ex.message,true);}
  });}
  function tableDetails(info){
    const section=node('details',null,'app-query-table-details');section.append(node('summary',`${info.name} · v${info.revision} · ${q('status.'+info.state)}`));
    if(info.concept)section.append(node('strong',info.concept));if(info.description)section.append(node('p',info.description));
    const evidence=search.evidence.find(e=>e.source===info.name&&e.kind==='DEFINITION');if(evidence)section.append(button(q('rdf'),()=>showEvidence(evidence.id)));
    const filter=node('input',null,'form-control');filter.type='search';filter.placeholder=q('paths.columnFilter');filter.setAttribute('aria-label',`${info.name} · ${q('paths.columnFilter')}`);
    const scroll=node('div',null,'app-query-scroll'),pages=node('div',null,'app-dds-pages'),count=node('span');let cp=1;
    const previous=button(t('ui.da7e61c67cc5','이전'),()=>{cp--;render();}),next=button(t('ui.aef613c6612d','다음'),()=>{cp++;render();});pages.append(count,previous,next);section.append(filter,scroll,pages);
    function render(){const page=columnPage(info.columns,filter.value,cp);cp=page.page;const table=node('table',null,'table app-table'),head=node('thead'),hr=node('tr');
      for(const label of ['column','type','definition'])hr.append(node('th',q('paths.'+label)));head.append(hr);table.append(head);const body=node('tbody');
      for(const c of page.items){const row=node('tr');row.append(node('td',c.name),node('td',c.type),node('td',c.description));body.append(row);}table.append(body);scroll.replaceChildren(table);
      count.textContent=t('ui.f32c9f13d498','{0}–{1} / {2}개',page.from,page.to,page.total);
      for(const [el,off]of [[previous,page.page<=1],[next,page.page>=page.pages]]){el.disabled=busy||off;if(off)el.dataset.localDisabled='';else delete el.dataset.localDisabled;}
    }
    filter.addEventListener('input',()=>{cp=1;render();});render();return section;
  }
  function routeCard(route,index){
    const card=node('article',null,'app-query-route');card.dataset.routeId=route.id;
    const label=node('label',null,'app-query-route-select'),radio=node('input');radio.type='radio';radio.name='ontology-route';radio.value=route.id;radio.checked=routeId===route.id;
    radio.addEventListener('change',()=>{routeId=route.id;invalidate();root.querySelectorAll('[data-route-id]').forEach(el=>el.classList.toggle('is-selected',el.dataset.routeId===routeId));});
    label.append(radio,node('strong',route.id==='INDEPENDENT'?q('plan.INDEPENDENT'):q(route.relations.length?'paths.route':'paths.table',index+1)),node('span',q('paths.count',route.tables.length,route.relations.length),'app-filter-message'));card.append(label);
    const coverage=routeCoverage(search,route);
    if(coverage.total)card.append(node('p',coverageLabel(coverage),'app-filter-message'));
    if(coverage.unmatched.length)card.append(node('p',unmatchedLabel(coverage),'app-filter-message'));
    const chain=node('div',null,'app-query-route-chain');groupedTables(search,route).forEach((item,i)=>{
      if(i)chain.append(node('span',route.id==='INDEPENDENT'?'+':routeArrow(search,route,i-1),'app-query-arrow'));
      const box=node('div',null,'app-query-route-node');box.append(node('strong',item.name));if(item.concept&&item.concept!==item.name)box.append(node('span',item.concept));chain.append(box);
    });card.append(chain);
    const matches=search.concepts.flatMap(c=>c.targets.filter(t=>route.tables.includes(t.table)).map(t=>`${c.term} → ${t.table} (${q('paths.basis.'+t.basis)})`));card.append(node('p',matches.join(' · '),'app-filter-message'));
    const detail=node('details',null,'app-query-route-details');detail.append(node('summary',q('paths.details')));
    for(const id of route.relations){const e=search.evidence.find(v=>v.id===id);if(!e)continue;const row=node('div',null,'app-query-mapping');row.append(node('code',`${e.source}.${e.from.join(', ')} → ${e.target}.${e.to.join(', ')}`),button(`${q('kind.RELATION')} · ${e.title}`,()=>showEvidence(id)));detail.append(row);}
    groupedTables(search,route).forEach(info=>detail.append(tableDetails(info)));card.append(detail);return card;
  }
  function renderRoutes(){
    renderTokenAnalysis(get('tokens'),search.analysis);
    get('routes').replaceChildren();get('other-routes').replaceChildren();get('alternatives').open=false;get('alternatives').hidden=search.routes.length<2;
    search.routes.forEach((route,index)=>(index?get('other-routes'):get('routes')).append(routeCard(route,index)));
    get('path-note').textContent=search.limited?q('paths.limited'):search.routes.length||analysisMissing(search)?'':q('paths.noRoute');
    get('matches').textContent=search.concepts.length?q('paths.terms',search.concepts.map(c=>c.term).join(' · ')):get('anchor').value;
    controls();
  }
  async function showEvidence(id){
    const e=search?.evidence.find(v=>v.id===id);if(!e)return;const body=get('detail-body');body.replaceChildren();body.append(node('h3',`${e.id} · ${e.title||e.source}`),node('p',e.description));
    if(e.kind==='RELATION')body.append(node('pre',`${e.source} (${e.from.join(', ')}) → ${e.target} (${e.to.join(', ')})`,'app-query-code'));
    body.append(node('p',`${q('status.'+e.status)} · ${e.basis.map(v=>q('basis.'+v)).join(' · ')}`),node('p',q('captured',timestamp(e.capturedAt)),'app-filter-message'));
    for(const ref of e.references){const block=node('section',null,'app-query-reference');block.append(node('p',`${ref.schema}.${ref.table} · v${ref.revision} · ${q('status.'+ref.state)}`));
      const show=button(q('rdf'),async()=>{show.disabled=true;const output=node('pre',t('ui.8bf609c884ca','불러오는 중…'),'app-preview-source');block.append(output);
        try{const data=await assistantApi(root.dataset.rdf+'?'+new URLSearchParams({schema:ref.schema,table:ref.table,revision:ref.revision}));output.textContent=data.text;}catch(ex){output.textContent=ex.message;show.disabled=false;}
      });block.append(show);body.append(block);
    }
    get('detail').showModal();
  }
  function renderAnswer(value){
    const body=get('answer-body');body.replaceChildren();for(const sentence of value.sentences){const row=node('p',sentence.text);for(const id of sentence.evidence)row.append(button(`[${id}]`,()=>showEvidence(id)));body.append(row);}
    if(value.limitation)body.append(node('p',value.limitation,'app-filter-message'));
    get('answer-meta').textContent=`${value.profile} · ${timestamp(value.generatedAt)}`;get('answer-panel').hidden=false;
  }
  function renderSql(value){
    draft=value;get('reviewed').checked=false;get('sql-text').textContent=value.sql;
    get('sql-meta').textContent=`${value.profile} · ${timestamp(value.generatedAt)} · SHA-256 ${value.hash}`;
    message(value.executable?q('sqlReady'):value.reason,!value.executable,'sql-status');get('sql-panel').hidden=false;
  }
  async function prepare(mode){await work(async()=>{
    clearOutput();const data=await post('preview',{id:search.id,mode,route:routeId});showPreview(data);
  });}
  function showPreview(data){preview=data;const mode=data.mode;
    const p=data.request.profile;get('profile').textContent=`${p.selection.owner}.${p.selection.name} · ${p.provider} / ${p.model||t('assistant.defaultModel','제공자 기본 모델')}`;
    get('payload').textContent=JSON.stringify(JSON.parse(data.request.source),null,2);get('transmission').textContent=q(['INTERPRET','RECOMMEND','PLAN'].includes(mode)?'grounding.aiTransmission':mode==='SQL'?'sqlTransmission':'answerTransmission');
    get('consent').checked=false;message('',false,'preview-status');get('preview').showModal();message('');
  }
  async function showAiOutcome(value){
    if(value.mode==='PLAN'){
      routeId='';
      renderRoutes();
      renderPlan(planPanel,value.plan,search,groundedResult?.proposals??[],availableSources,selection=>work(async()=>{
        clearOutput();search=await post('plan-selection',{id:search.id,...selection});routeId=selection.mode==='INDEPENDENT'?'INDEPENDENT':selection.route;
        renderRoutes();message(q('plan.applied'));
      }),()=>{routeId='';invalidate();});
      // Render the recommendation first, including blocked sources. Never discard it on selection failure.
      if(value.plan.mode!=='REVIEW'&&!value.plan.questions.length&&!blockedSources(value.plan,availableSources).length){
        search=await post('plan-selection',{id:search.id,mode:value.plan.mode,route:value.plan.routeId,tables:value.plan.tables,candidates:value.plan.candidates.filter(c=>c.selected).map(c=>c.id)});
        routeId=value.plan.mode==='INDEPENDENT'?'INDEPENDENT':value.plan.routeId;renderRoutes();
      }return;
    }
    if(value.mode==='RECOMMEND'){
      const r=value.recommendation;grounding.append(node('h3',q('grounding.recommendation'),'h5'),node('p',`${r.routeId} · ${r.reason}`),node('p',r.evidence.join(', ')));
      r.questions.forEach(v=>grounding.append(node('p',v)));return;
    }
    if(value.result){groundedResult=value.result;search=value.result.search;routeId='';renderGroundedResult(grounding,value.result);get('evidence').hidden=!!value.result.rdf;if(!value.result.rdf)renderRoutes();}
    else {grounding.replaceChildren();grounding.hidden=false;}
    if(value.result?.summary){const interpreted=node('details');interpreted.append(node('summary',q('grounding.aiInterpretation')),node('p',value.interpretation.summary));grounding.append(interpreted);}
    else grounding.prepend(node('h3',q('grounding.aiInterpretation'),'h5'),node('p',value.interpretation.summary));
    if(value.result?.summary){
      const raw=Array.from(grounding.children);grounding.replaceChildren();const summary=node('section');grounding.append(summary);
      const evidence=renderRdfWorkflow(summary,value.result.summary,selection=>work(async()=>{
        clearOutput();search=await post('rdf-selection',{id:search.id,...selection});routeId=selection.mode==='JOIN'?'RDF_JOIN':'INDEPENDENT';
        showPreview(await post('preview',{id:search.id,mode:'SQL',route:routeId}));
      }),()=>{routeId='';invalidate();});
      evidence.append(...raw);
    }
    value.interpretation.questions.forEach(v=>grounding.append(node('p',v)));
    if(value.interpretation.questions.length)grounding.append(node('p',q('grounding.clarify')));
  }
  async function closePreview(){if(busy)return;await work(async()=>{if(preview)await post('cancel',{token:preview.request.token});preview=null;get('preview').close();message('');},'preview-status');}
  function renderRows(){
    const page=resultPage(result.rows,rp);rp=page.page;const table=node('table',null,'table app-table'),head=node('thead'),header=node('tr');for(const title of result.columns)header.append(node('th',title));head.append(header);table.append(head);const body=node('tbody');
    for(const item of page.items){const row=node('tr');for(const cell of item.cells){const td=node('td',cell.value??'—');if(cell.truncated){td.append(node('span',q('clipped'),'app-query-clipped'));}row.append(td);}body.append(row);}table.append(body);get('result-table').replaceChildren(table);
    get('result-count').textContent=t('ui.f32c9f13d498','{0}–{1} / {2}개',page.from,page.to,page.total);get('result-page').textContent=`${page.pages?page.page:0} / ${page.pages}`;controls();
  }
  get('form').addEventListener('submit',event=>{event.preventDefault();work(async()=>{
    clearOutput();search=null;routeId='';planPanel.hidden=true;planPanel.replaceChildren();groundedResult=null;get('evidence').hidden=true;
    const question=get('question').value,anchor=get('anchor').value;
    const dictionary=await post('interpret',{schema:root.dataset.schema,question,anchor});
    const findRelations=async termIds=>{
      const request={schema:root.dataset.schema,question,anchor,dictionaryId:dictionary.id,termIds,graph:graphSelect.value};
      if(automatic.checked){showPreview(await post(analysisEndpoint('ai-interpret-preview',analysis.language()),request));return;}
      const value=await post(analysisEndpoint('grounded-search',analysis.language()),request);
      groundedResult=value;search=value.search;routeId='';renderGroundedResult(grounding,value);get('evidence').hidden=false;renderRoutes();
      grounding.append(button(q('grounding.continue'),()=>work(async()=>{
        clearOutput();showPreview(await post(analysisEndpoint('ai-interpret-preview',analysis.language()),request));
      })));
      message('');
    };
    const ids=automaticTermIds(dictionary);
    if(ids!==null)await findRelations(ids);
    else renderDictionaryChoices(grounding,dictionary,termIds=>work(()=>findRelations(termIds)));
    message('');
  });});
  get('question').addEventListener('input',()=>invalidate(true));get('anchor').addEventListener('change',()=>invalidate(true));
  get('refresh').addEventListener('click',()=>load(true));
  get('answer').addEventListener('click',()=>prepare('ANSWER'));get('sql').addEventListener('click',()=>prepare('SQL'));get('consent').addEventListener('change',controls);get('reviewed').addEventListener('change',controls);
  get('close-preview').addEventListener('click',closePreview);get('preview').addEventListener('cancel',event=>{event.preventDefault();closePreview();});get('close-detail').addEventListener('click',()=>get('detail').close());
  get('generate').addEventListener('click',()=>work(async()=>{
    if(['INTERPRET','RECOMMEND','PLAN'].includes(preview?.mode)){const token=preview.request.token;preview=null;const value=await post('assistant-generate',{token,consent:get('consent').checked});get('preview').close();await showAiOutcome(value);message('');return;}
    const token=preview.request.token;preview=null;const value=await post('generate',{token,consent:get('consent').checked});get('preview').close();value.mode==='SQL'?renderSql(value.sql):renderAnswer(value.answer);message('');
  },'preview-status'));
  get('execute').addEventListener('click',()=>work(async()=>{
    const token=draft.token;draft={...draft,executable:false};get('reviewed').checked=false;result=null;get('result-panel').hidden=true;
    try{result=await post('execute',{token,confirmed:true});rp=1;get('result-meta').textContent=`${result.actor} · ${timestamp(result.executedAt)} · SHA-256 ${result.hash}`;
      get('result-note').textContent=result.truncated?q('rowLimit'):result.rows.length?'':q('empty');renderRows();get('result-panel').hidden=false;message(q('executed'),false,'sql-status');message('');
    }catch(ex){message(q('oneUse'),false,'sql-status');throw ex;}
  }));
  get('result-prev').addEventListener('click',()=>{rp--;renderRows();});get('result-next').addEventListener('click',()=>{rp++;renderRows();});
  archiveUi=attachArchive(root,{work,post,selection:()=>search&&selectedRoute(search,routeId)?{id:search.id,route:routeId}:null});
  load();
});
