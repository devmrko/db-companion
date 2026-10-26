import {t} from './i18n.mjs';
import {scopePicker} from './ontology-scope.mjs';
import {metadataGraphPanel} from './metadata-graph.mjs';
const label=(key,...args)=>t('ontology.'+key,key,...args);
const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;e.className=cls||'';return e;};
export function pipelineSummary(definition){return {included:definition.items.filter(i=>i.status==='INCLUDED').length,excluded:definition.items.filter(i=>i.status==='EXCLUDED').length};}
export function discoveryDiagnostic(error){
  const d=error?.diagnostic;
  return d&&['EMPTY','SIZE','JSON','ROOT','COUNT','ITEM','TYPE','TEXT','TABLE','COLUMNS','COLUMN','SENSITIVE','DATATYPE','DUPLICATE'].includes(d.code)&&typeof d.path==='string'&&d.path.length<=160&&typeof d.rawResponse==='string'&&d.rawResponse.length<=200000?{code:d.code,path:d.path,rawResponse:d.rawResponse}:null;
}
export async function runDiscovery(plan,{call,stop,progress}){
  let completed=0;for(let index=plan.progress.nextIndex;index<plan.progress.authorizedUntil&&!stop();index++){
    const result=await call(index);completed++;progress(result);if(result.paused||result.done||result.resultLimit)break;
  }return completed;
}
export function ontologyPipeline(dialog,{schema,post,profiles,updated,canOpen,graphCreated=()=>{}}){
  const get=k=>dialog.querySelector(`[data-pipeline-${k}]`);let busy=false,stopping=false,plan=null,changed=false;
  const status=(text,error=false)=>{get('status').textContent=text;get('status').className=error?'app-alert is-error':'app-filter-message';};
  function diagnostic(error=null){const d=discoveryDiagnostic(error);get('diagnostic').hidden=!d;get('diagnostic').open=false;get('diagnostic-path').textContent=d?`${d.code} · ${d.path}`:'';get('raw').textContent=d?.rawResponse??'';}
  const button=(title,fn)=>{const b=el('button',title,'btn app-btn app-btn-secondary');b.type='button';b.addEventListener('click',fn);return b;};
  const check=(title)=>{const row=el('label',undefined,'app-pipeline-consent'),input=el('input');input.type='checkbox';row.append(input,document.createTextNode(title));return {row,input};};
  function lock(value){busy=value;get('close').disabled=value;dialog.querySelectorAll('input,button,select').forEach(e=>{if(e!==get('stop')&&e!==get('close'))e.disabled=value||e.dataset.boundDisabled==='true';});}
  async function close(){if(busy)return;if(plan?.token)await post('/pipeline/stop',{token:plan.token}).catch(ex=>status(ex.message,true));diagnostic();dialog.close();if(changed)await updated();}
  get('close').addEventListener('click',close);dialog.addEventListener('cancel',event=>{event.preventDefault();close();});get('stop').addEventListener('click',()=>{
    stopping=true;get('stop').disabled=true;if(plan?.token)post('/pipeline/stop',{token:plan.token}).catch(ex=>status(ex.message,true));
  });
  function open(title){if(!canOpen())return false;diagnostic();plan=null;changed=false;stopping=false;get('title').textContent=title;get('body').replaceChildren();get('stop').hidden=true;get('stop').disabled=false;status('');dialog.showModal();return true;}
  async function discover(){
    if(!open(label('discovery.title')))return;lock(true);status(label('discovery.preparing'));
    try{
      const archive=el('section'),selection=el('div'),results=el('section');get('body').append(archive,selection,results);
      plan=(await post('/pipeline/status',{schema})).plan;
      await savedRuns(archive);
      const initialTables=plan?.budget?.sizes.map(s=>s.table)||[];
      const invalidate=()=>{diagnostic();if(plan?.token)post('/pipeline/stop',{token:plan.token}).catch(ex=>status(ex.message,true));plan=null;stopping=false;get('stop').disabled=false;results.replaceChildren();};
      await scopePicker(selection,{schema,post,profiles,status,lock,initialTables,isBusy:()=>busy,changed:invalidate,preview:async tables=>{
        invalidate();status(label('discovery.preparing'));plan=await post('/pipeline/preview',{schema,tables});discoveryPreview(results);results.scrollIntoView({block:'nearest',behavior:'smooth'});
      }});
      if(plan){discoveryPreview(results);changed=plan.progress?.nextIndex>0;}else status(label('scope.help'));
    }catch(ex){status(ex.message,true);}finally{lock(false);}
  }
  async function savedRuns(host){
    const select=el('select',undefined,'form-select'),list=el('div');select.setAttribute('aria-label',label('discovery.savedRuns'));
    let before='';const more=button(label('discovery.olderRuns'),async()=>{lock(true);try{await load();}catch(ex){status(ex.message,true);}finally{lock(false);}});
    async function load(){const page=await post('/pipeline/saved',{schema,before});for(const row of page.rows){const option=el('option',`${row.recordedAt} · ${row.profile} · ${row.tables} / ${row.calls}`);option.value=row.id;select.append(option);}before=page.next;more.hidden=!before;}
    const restore=button(label('discovery.restore'),async()=>{if(busy||!select.value)return;lock(true);try{if(plan?.token)await post('/pipeline/stop',{token:plan.token});await post('/pipeline/restore',{schema,token:select.value});dialog.close();await discover();}catch(ex){status(ex.message,true);}finally{lock(false);}});
    const inspect=button(label('discovery.savedCalls'),async()=>{if(busy||!select.value)return;lock(true);try{await savedCalls(list,select.value);}catch(ex){status(ex.message,true);}finally{lock(false);}});
    host.append(el('h3',label('discovery.savedRuns')),el('p',label('discovery.durableHelp'),'app-filter-message'),select,restore,inspect,more,list);
    try{await load();if(!select.options.length){select.hidden=true;restore.hidden=true;inspect.hidden=true;host.append(el('p',label('discovery.noSavedRuns')));}}catch(ex){select.hidden=true;restore.hidden=true;inspect.hidden=true;more.hidden=true;host.append(el('p',ex.message,'app-filter-message'));}
  }
  async function savedCalls(host,token){
    host.replaceChildren();const calls=await post('/pipeline/calls',{schema,token});
    for(const call of calls){const details=el('details'),summary=el('summary',label('discovery.callReceipt',call.index+1,call.state,call.stage,call.saved)),body=el('div');details.append(summary,body);host.append(details);let loaded=false;
      details.addEventListener('toggle',async()=>{if(!details.open||loaded)return;loaded=true;try{
        const value=await post('/pipeline/call',{schema,token,index:call.index});const r=value.receipt;
        if(r.diagnostic)body.append(el('p',r.diagnostic,'app-alert'));
        for(const issue of r.issues)body.append(el('p',`${issue.code} · ${issue.path} · ${label('discovery.response.'+issue.code)}`));
        body.append(el('pre',r.raw||label('discovery.noResponse'),'app-pipeline-code'));
        if(value.state==='CHECK_REQUIRED'&&r.stage==='SAVE'&&r.raw){const consent=check(label('discovery.recoverConsent')),save=button(label('discovery.recover'),async()=>{if(busy||!consent.input.checked)return;lock(true);try{plan=await post('/pipeline/recover',{schema,token,index:call.index,confirmed:true});changed=true;await savedCalls(host,token);status(label('discovery.recovered'));}catch(ex){status(ex.message,true);}finally{lock(false);}});body.append(consent.row,save);}
      }catch(ex){loaded=false;body.replaceChildren(el('p',ex.message,'app-alert is-error'));}});
    }
  }
  function discoveryPreview(host){
      const budget=plan.budget;
      const planTitle=el('h3',label(plan.progress?.nextIndex?'discovery.existingPlan':'scope.plan'));host.append(planTitle,el('p',label('scope.characters',budget.originalCharacters,budget.compactCharacters)),el('p',label('scope.calls',budget.blocks,budget.calls)),el('p',label('scope.characterHelp'),'app-filter-message'));
      const sizes=el('details');sizes.append(el('summary',label('scope.sizes')));const sizeTable=el('table',undefined,'table app-table'),thead=el('thead'),heading=el('tr'),tbody=el('tbody');
      for(const key of ['table','scope.original','scope.compact'])heading.append(el('th',label(key)));thead.append(heading);sizeTable.append(thead,tbody);
      for(const row of budget.sizes){const tr=el('tr',undefined,row.compactCharacters>28000?'app-alert is-error':'');tr.append(el('td',row.table),el('td',String(row.originalCharacters)),el('td',String(row.compactCharacters)));tbody.append(tr);}sizes.append(sizeTable);host.append(sizes);
      if(budget.reason){host.append(el('p',label('scope.limit.'+budget.reason),'app-alert is-error'));status(label('scope.reduce'),true);return;}
      host.append(el('p',label('scope.transmission',budget.transmissionCharacters,budget.maxBatchCharacters)));
      host.append(el('p',label('discovery.scope',plan.tables,budget.calls)),el('p',label('discovery.groups',Math.ceil(budget.calls/plan.groupSize),plan.groupSize)),el('p',`${plan.profile.selection.owner}.${plan.profile.selection.name} · ${plan.profile.provider} / ${plan.profile.model||''}`));
      const details=el('details'),summary=el('summary',label('discovery.payload'));details.append(summary);
      const payloadIndex=el('input',undefined,'form-control');payloadIndex.type='number';payloadIndex.min=1;payloadIndex.max=budget.calls;payloadIndex.value=Math.min(plan.progress.nextIndex+1,budget.calls);payloadIndex.setAttribute('aria-label',label('discovery.payloadIndex'));
      const payload=el('div'),load=button(label('discovery.loadPayload'),async()=>{
        const index=Number(payloadIndex.value)-1;if(busy||!Number.isInteger(index)||index<0||index>=budget.calls)return;lock(true);
        try{const b=await post('/pipeline/payload',{token:plan.token,index});payload.replaceChildren(el('p',b.tables.join(', ')),el('pre',b.source,'app-pipeline-code'));}catch(ex){status(ex.message,true);}finally{lock(false);}
      });details.append(payloadIndex,load,payload);host.append(details);
      const progress=el('p'),failures=el('p',undefined,'app-alert'),next=el('p'),all=check(label('discovery.all',budget.calls-plan.progress.nextIndex)),consent=check(label('discovery.consent'));let uncertain=false;
      const refresh=button(label('discovery.checkProgress'),async()=>{if(busy)return;lock(true);try{await sync();paint();}catch(ex){uncertain=true;paint();status(ex.message,true);}finally{lock(false);}});
      async function sync(){const saved=(await post('/pipeline/status',{schema})).plan;if(!saved||saved.token!==plan.token)throw new Error(label('stale'));plan=saved;uncertain=false;}
      const start=button(label('discovery.start'),async()=>{
        if(busy||uncertain||plan.progress.running||plan.progress.done||plan.progress.resultLimit||!consent.input.checked)return;diagnostic();changed=true;stopping=false;lock(true);get('stop').hidden=false;get('stop').disabled=false;
        try{
          plan=await post('/pipeline/resume',{token:plan.token,index:plan.progress.nextIndex,consent:true,all:all.input.checked});
          await runDiscovery(plan,{stop:()=>stopping,call:index=>{status(label('discovery.call',index+1,budget.calls));return post('/pipeline/generate',{token:plan.token,index,consent:true});},progress:r=>{plan={...plan,progress:r};paint();}});
          await post('/pipeline/stop',{token:plan.token});await sync();status(label(plan.progress.done?(plan.progress.failed.length?'discovery.incomplete':'discovery.finished'):'discovery.paused'));
        }catch(ex){
          uncertain=true;await post('/pipeline/stop',{token:plan.token}).catch(()=>{});await sync().catch(()=>{});diagnostic(ex);status(ex.message+' · '+label('discovery.noRetry'),true);
        }finally{consent.input.checked=false;get('stop').hidden=true;paint();lock(false);}
      });
      function paint(){
        planTitle.textContent=label(plan.progress.nextIndex?'discovery.existingPlan':'scope.plan');
        const p=plan.progress;progress.textContent=label('discovery.progress',p.completed,p.total,p.candidates);
        failures.hidden=!p.failed.length;failures.textContent=label('discovery.failed',p.failed.join(', '));
        next.textContent=uncertain?label('discovery.unknown'):p.resultLimit?label('discovery.resultLimit'):p.running?label('discovery.busy'):p.done?label(p.failed.length?'discovery.incomplete':'discovery.finished'):label('discovery.nextGroup',p.nextIndex+1,all.input.checked?p.total:Math.min(p.nextIndex+plan.groupSize,p.total),p.total);
        start.textContent=label(p.nextIndex?'discovery.continue':'discovery.start');const disabled=uncertain||p.running||p.done||p.resultLimit||!consent.input.checked;
        start.dataset.boundDisabled=String(disabled);start.disabled=busy||disabled;
      }
      const receipts=el('div'),inspect=button(label('discovery.savedCalls'),async()=>{if(busy)return;lock(true);try{await savedCalls(receipts,plan.token);}catch(ex){status(ex.message,true);}finally{lock(false);}});
      all.input.addEventListener('change',()=>{consent.input.checked=false;paint();});consent.input.addEventListener('change',paint);host.append(progress,failures,next,all.row,consent.row,start,refresh,inspect,receipts,el('p',label('discovery.help'),'app-filter-message'));paint();status(label('discovery.help'));
  }
  async function graph(){
    if(!open(label('pg.title')))return;const host=get('body'),mode=el('select',undefined,'form-select'),field=el('label',undefined,'app-ontology-field'),panel=el('div');
    field.append(el('span',label('mg.mode')),mode);mode.setAttribute('aria-label',label('mg.mode'));
    for(const value of ['metadata','business']){const option=el('option',label('mg.'+value));option.value=value;mode.append(option);}
    const render=()=>{panel.replaceChildren();status('');if(mode.value==='metadata')metadataGraphPanel(panel,{schema,post,lock,status,isBusy:()=>busy,created:graphCreated});else businessGraph(panel);};
    mode.addEventListener('change',render);host.append(field,panel);render();
  }
  function businessGraph(host){
    host.append(el('p',label('mg.businessHelp'),'app-filter-message'));
    const name=el('input',undefined,'form-control');name.value='DBC_RELATION_GRAPH';name.maxLength=60;name.setAttribute('aria-label',label('pg.name'));
    const row=el('label',undefined,'app-ontology-field');row.append(el('span',label('pg.name')),name);const result=el('div');let preview=null,selected=null;
    name.addEventListener('input',()=>{preview=null;result.replaceChildren();});
    const prepare=button(label('pg.preview'),async()=>{
      if(busy)return;lock(true);result.replaceChildren();status(label('discovery.preparing'));
      try{
        preview=await post('/pipeline/graph/preview',{schema,name:name.value,relations:selected});const d=preview.definition,a=preview.access,counts=pipelineSummary(d);
        result.append(el('p',label('pg.summary',d.vertices,d.edges,counts.excluded)),el('p',label('pg.help'),'app-filter-message'));
        const states=[['supported',a.supported],['owner',a.owner],['privilege',a.privilege],['nameFree',!a.exists]];
        result.append(el('p',states.map(([key,ok])=>label('pg.'+key)+': '+label(ok?'pg.yes':'pg.no')).join(' · ')));
        if(a.exists)result.append(el('p',label('mg.nameCollision')+' '+a.existingStatus,'app-alert is-error'));
        if(d.available.length){
          const choices=el('fieldset'),mode=el('select',undefined,'form-select'),list=el('div',undefined,'app-pipeline-items'),boxes=[];
          choices.append(el('legend',label('mg.relationScope')));mode.setAttribute('aria-label',label('mg.relationScope'));
          for(const [value,title] of [['all',label('mg.allRelations')],['selected',label('mg.selectedRelations')]]){const option=el('option',title);option.value=value;mode.append(option);}mode.value=selected===null?'all':'selected';choices.append(mode);
          for(const choice of d.available){const row=el('label',undefined,'app-pipeline-consent'),box=el('input');box.type='checkbox';box.value=choice.id;box.checked=selected===null||selected.includes(choice.id);box.disabled=selected===null;box.dataset.boundDisabled=String(box.disabled);boxes.push(box);row.append(box,el('span',choice.name));list.append(row);}
          const invalidate=()=>{preview=null;status(label('mg.selectionChanged'));result.querySelectorAll('button').forEach(b=>{b.disabled=true;b.dataset.boundDisabled='true';});};
          mode.addEventListener('change',()=>{selected=mode.value==='all'?null:boxes.filter(b=>b.checked).map(b=>b.value);boxes.forEach(b=>{b.disabled=selected===null;b.dataset.boundDisabled=String(b.disabled);});invalidate();});
          boxes.forEach(b=>b.addEventListener('change',()=>{selected=boxes.filter(v=>v.checked).map(v=>v.value);invalidate();}));
          choices.append(list,el('p',label('mg.selectionHelp'),'app-filter-message'));result.append(choices);
        }
        const table=el('table',undefined,'table app-table'),head=el('thead'),hr=el('tr');['pg.target','pg.alias','pg.state'].forEach(k=>hr.append(el('th',label(k))));head.append(hr);table.append(head);const body=el('tbody');
        d.items.forEach(item=>{const r=el('tr');r.append(el('td',item.name),el('td',item.alias||'—'),el('td',item.status==='INCLUDED'?label('pg.included'):label('pg.reason.'+item.reason)));body.append(r);});table.append(body);const scroll=el('div',undefined,'app-pipeline-items');scroll.append(table);result.append(scroll,el('pre',d.sql||label('pg.noVertices'),'app-pipeline-code'));
        const consent=check(label('pg.consent')),create=button(label('pg.create'),async()=>{
          if(busy||!preview?.canCreate||!consent.input.checked)return;lock(true);status(label('pg.creating'));const token=preview.token;preview=null;
          try{const created=await post('/pipeline/graph/create',{token,confirmed:true});status(label('pg.created',created.schema+'.'+created.name,created.status));result.append(el('pre',created.query,'app-pipeline-code'));graphCreated(created);}
          catch(ex){status(ex.message+' · '+label('pg.noRetry'),true);}finally{create.dataset.boundDisabled='true';consent.input.dataset.boundDisabled='true';lock(false);}
        });
        create.dataset.boundDisabled='true';consent.input.disabled=!preview.canCreate;consent.input.dataset.boundDisabled=String(!preview.canCreate);
        consent.input.addEventListener('change',()=>{create.dataset.boundDisabled=String(!preview?.canCreate||!consent.input.checked);create.disabled=create.dataset.boundDisabled==='true';});result.append(consent.row,create);status('');
      }catch(ex){preview=null;status(ex.message,true);}finally{lock(false);}
    });host.append(row,prepare,result);
  }
  return {discover,graph};
}
