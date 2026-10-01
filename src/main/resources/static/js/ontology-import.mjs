import {t} from './i18n.mjs';
import {profileTables} from './table-list.mjs';

const label=(key,...args)=>t('ontology.import.'+key,key,...args);
export const importable=outcome=>['NEW','CHANGED','UNCHANGED'].includes(outcome);

const oracleReasons=new Set(['ORACLE_MAINTAINED','SECONDARY','TEXT_NAME','VECTOR_NAME','FEEDBACK_NAME']);
export function importPlan(names,reasons={}, {excludeOracle=true,excludeApp=true,omitted=new Set()}={}) {
  const available=[],selected=[],excluded=[];
  for(const table of new Set(names)) {
    const reason=Object.hasOwn(reasons,table)?reasons[table]:null;
    if(excludeOracle&&oracleReasons.has(reason)||excludeApp&&reason==='APP_STORE')excluded.push({table,reason});
    else {
      available.push(table);
      if(omitted.has(table))excluded.push({table,reason:'MANUAL'});
      else selected.push(table);
    }
  }
  return {available,selected,excluded};
}

// UI queue only: authentication, revision and metadata checks live on the server.
export class ImportQueue {
  constructor(names,mode='save') {
    this.mode=mode;
    this.items=[...new Set(names)].map(table=>({table,outcome:'PENDING',revision:null,triples:null}));
    this.index=0;
    this.active=false;
    this.stopped=false;
    this.started=false;
  }
  next() {
    if(this.active||this.stopped||this.index>=this.items.length)return null;
    this.started=true;
    this.active=true;
    this.items[this.index].outcome='RUNNING';
    return this.items[this.index].table;
  }
  accept(result) {
    const item=this.items[this.index];
    if(this.mode==='preview') {
      if(!this.active||result?.table!==item.table||!['NEW','CHANGED','UNCHANGED','REVIEW'].includes(result.outcome)
        ||!Number.isInteger(result.revision)||result.revision<0||!Array.isArray(result.differences)
        ||(importable(result.outcome)&&!(typeof result.token==='string'&&result.token)))throw new Error(label('invalidResponse'));
      Object.assign(item,result);this.active=false;this.index++;return;
    }
    if(!this.active||result?.table!==item.table||!['IMPORTED','SKIPPED'].includes(result.outcome)
      ||!Number.isInteger(result.revision)||result.revision<1||!Number.isInteger(result.triples)||result.triples<1)
      throw new Error(label('invalidResponse'));
    Object.assign(item,{outcome:result.outcome,revision:result.revision,triples:result.triples});
    this.active=false;
    this.index++;
  }
  fail() {
    if(this.active)this.items[this.index].outcome='UNKNOWN';
    this.active=false;
    this.stopped=true;
  }
  stop() {this.stopped=true;}
  get complete() {return this.index===this.items.length;}
}

export function metadataImporter(dialog,{schema,post,completed,profiles}) {
  const get=key=>dialog.querySelector(`[data-import-${key}]`);
  let queue=null,running=false,cells=new Map(),names=[],scopedNames=[],reasons={},total=0,entries=new Map(),bulk=true,omitted=new Set();
  let previews=new Map(),loading=false,generation=0,saving=false,finished=false;
  function render() {
    for(const [table,values] of cells) {
      const preview=previews.get(table),item=queue.items.find(i=>i.table===table)??preview;
      values[0].textContent=label(item?.outcome??'omitted');
      const revision=item?.revision??entries.get(table)?.revision;
      values[1].textContent=revision?`v${revision}`:'—';values[2].textContent=item?.triples??'—';
      if(preview?.reason)values[0].textContent+=' · '+label('review.'+preview.reason);
    }
    const imported=queue.items.filter(i=>i.outcome==='IMPORTED').length;
    const unchanged=[...previews.values()].filter(i=>i.outcome==='UNCHANGED').length;
    const state=loading?'loading':!queue.items.length?'empty':queue.stopped?(running?'stopping':'stopped'):running?(saving?'running':'checking'):finished?'finished':previews.size?'checked':'ready';
    get('status').textContent=label(state)+' · '+label('captureCounts',queue.index,queue.items.length,imported,unchanged);
    get('progress').max=Math.max(queue.items.length,1);
    get('progress').value=queue.index;
    const writable=[...previews.values()].filter(i=>importable(i.outcome));
    get('start').disabled=running||loading||finished||!writable.length||queue.stopped;
    get('check').disabled=running||loading||!queue.items.length||queue.items.length>100;
    if(queue.items.length>100)get('status').textContent+=' · '+label('limit');
    get('stop').hidden=!running;
    get('stop').disabled=queue.stopped;
    get('close').disabled=running;
    get('filters').disabled=running;
    get('profile').disabled=running||loading;
    dialog.querySelectorAll('[data-import-select]').forEach(input=>input.disabled=running||loading);
  }
  function plan() {
    return importPlan(scopedNames,reasons,{excludeOracle:bulk&&get('exclude-oracle').checked,excludeApp:bulk&&get('exclude-app').checked,omitted});
  }
  function refreshSelection(rebuild=true) {
    if(running)return;
    previews=new Map();finished=false;get('changes').replaceChildren();
    const selection=plan();queue=new ImportQueue(selection.selected,'preview');
    get('scope').textContent=schema+' · '+label('refreshScope',total,scopedNames.length,selection.selected.filter(n=>entries.has(n)).length,queue.items.length)+' · '+label('excludedCount',selection.excluded.length);
    get('excluded-count').textContent=label('excludedDetails',selection.excluded.length);
    get('excluded').hidden=!selection.excluded.length;
    get('excluded-list').replaceChildren(...selection.excluded.map(item=>{
      const li=document.createElement('li');li.textContent=item.table+' · '+label('reason.'+item.reason);return li;
    }));
    if(rebuild) {
      cells=new Map();const fragment=document.createDocumentFragment();
      for(const table of selection.available) {
        const row=document.createElement('tr'),name=document.createElement('td'),choice=document.createElement('label'),input=document.createElement('input'),text=document.createElement('span');
        choice.className='app-ontology-import-table';input.type='checkbox';input.dataset.importSelect='';input.value=table;input.checked=!omitted.has(table);
        text.textContent=table;choice.append(input,text);name.append(choice);
        const state=document.createElement('small');state.className='app-filter-message';state.textContent=label(entries.has(table)?'existing':'missing');name.append(state);row.append(name);
        input.addEventListener('change',()=>{if(running)return;input.checked?omitted.delete(table):omitted.add(table);refreshSelection(false);});
        const values=Array.from({length:3},()=>document.createElement('td'));row.append(...values);cells.set(table,values);fragment.append(row);
      }
      get('rows').replaceChildren(fragment);
    }
    for(const [table,values] of cells) {values[0].textContent=label(omitted.has(table)?'omitted':'PENDING');values[1].textContent='—';values[2].textContent='—';}
    render();
  }
  function showError(value) {get('error').textContent=value;get('error').hidden=!value;}
  function showChanges() {
    get('changes').replaceChildren(...[...previews.values()].filter(p=>p.differences.length).map(p=>{
      const details=document.createElement('details'),summary=document.createElement('summary'),list=document.createElement('ul');
      summary.textContent=p.table+' · '+label('differences',p.differences.length);details.append(summary,list);
      for(const diff of p.differences){const li=document.createElement('li');li.textContent=[diff.kind,diff.name,`${diff.baseline||'—'} → ${diff.current||'—'}`].join(' · ');list.append(li);}
      return details;
    }));
  }
  async function execute(save) {
    if(running||loading)return;
    const targets=save?[...previews.values()].filter(p=>importable(p.outcome)).map(p=>p.table):plan().selected;
    if(!targets.length||targets.length>100||save&&(finished||queue.stopped))return;
    queue=new ImportQueue(targets,save?'save':'preview');saving=save;running=true;showError('');
    if(!save){finished=false;previews.clear();showChanges();}
    let table;
    while((table=queue.next())!==null) {
      render();
      try {
        const result=await post(save?'/import/apply':'/import/preview',save?{schema,table,token:previews.get(table).token,confirmed:true}:{schema,table});
        queue.accept(result);
        if(!save){previews.set(table,result);showChanges();}
      }catch(ex) {
        queue.fail();
        showError(`${schema}.${table} · ${ex.message}\n${label(save?'saveUncertain':'checkError')}`);
      }
    }
    if(save){finished=true;try{await completed();}catch(ex){showError((get('error').textContent+'\n'+label('refreshError')+' · '+ex.message).trim());}}
    running=false;
    render();
  }
  get('check').addEventListener('click',()=>execute(false));
  get('start').addEventListener('click',async()=>execute(true));
  get('profile').addEventListener('change',async()=>{
    const version=++generation,profile=get('profile').value;
    loading=!!profile;scopedNames=profile?[]:names;showError('');refreshSelection();
    if(!profile)return;
    try{const data=await profiles(profile);if(version!==generation)return;scopedNames=profileTables(names.map(name=>({name})),schema,data.objectList).map(i=>i.name);}
    catch(ex){if(version!==generation)return;scopedNames=[];showError(ex.message);}
    loading=false;refreshSelection();
  });
  get('stop').addEventListener('click',()=>{queue.stop();render();});
  get('close').addEventListener('click',()=>{if(!running){generation++;dialog.close();}});
  dialog.addEventListener('cancel',e=>{if(running)e.preventDefault();});
  for(const key of ['exclude-oracle','exclude-app'])get(key).addEventListener('change',()=>refreshSelection());
  return {
    async open(candidateNames,totalCount,savedEntries,exclusions={},isBulk=true) {
      if(running)return;
      const version=++generation;
      names=[...new Set(candidateNames)];scopedNames=names;total=totalCount;entries=new Map(savedEntries.map(e=>[e.name,e]));reasons={...exclusions};bulk=isBulk;omitted=new Set();queue=null;loading=false;
      get('filters').hidden=!bulk;get('filters').disabled=false;get('excluded').open=false;
      get('exclude-oracle').checked=true;get('exclude-app').checked=true;
      get('profile').replaceChildren(new Option(label('allProfiles'),''));
      showError('');
      refreshSelection();
      dialog.showModal();
      if(!bulk||!profiles)return;
      loading=true;render();
      try{const options=await profiles();if(version!==generation)return;get('profile').append(...options.map(p=>new Option(p.name,p.name)));}
      catch(ex){if(version!==generation)return;showError(ex.message);}
      loading=false;render();
    }
  };
}
