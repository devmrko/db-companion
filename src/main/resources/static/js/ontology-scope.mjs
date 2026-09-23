import {t} from './i18n.mjs';
const label=(key,...args)=>t('ontology.scope.'+key,key,...args);
const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;e.className=cls||'';return e;};
export function reconcileScope(requested,available){const known=new Set(available);return {tables:[...new Set(requested)].filter(n=>known.has(n)).sort(),missing:[...new Set(requested)].filter(n=>!known.has(n)).sort()};}
export function scopeMatches(tables,query,selected,onlySelected=false){const term=query.trim().toLocaleLowerCase();return tables.filter(t=>(!onlySelected||selected.has(t.name))&&t.name.toLocaleLowerCase().includes(term));}
export function validScope(selected){return selected.size>=2&&selected.size<=500;}
export async function scopePicker(host,{schema,post,profiles,preview,changed,status,lock,isBusy,initialTables=[]}){
  let options=await post('/pipeline/scope/options',{schema}),selected=new Set(initialTables),next='',saved=[],warnings=false,page=0;
  const action=(key,run)=>{const b=el('button',label(key),'btn app-btn app-btn-secondary');b.type='button';b.addEventListener('click',run);return b;};
  const disable=(e,value)=>{e.dataset.boundDisabled=String(value);e.disabled=value;};
  const field=(key,input)=>{input.setAttribute('aria-label',label(key));const row=el('label',undefined,'app-ontology-field');row.append(el('span',label(key)),input);return row;};
  const work=async fn=>{if(isBusy())return;lock(true);try{await fn();}catch(ex){status(ex.message,true);}finally{lock(false);}};
  host.append(el('h3',label('title')),el('p',label('help'),'app-filter-message'),el('p',label('replaceWarning'),'app-filter-message'));
  const imports=el('div',undefined,'app-scope-tools'),profile=el('select',undefined,'form-select');profile.append(new Option(label('chooseProfile'),''));
  const importProfile=action('importProfile',()=>work(async()=>{
    const v=await post('/pipeline/scope/profile',{schema,profile:profile.value});apply(v.tables,v.missing,v.outside,v.wholeSchema);status(label('profileImported'));
  }));disable(importProfile,true);profile.addEventListener('change',()=>disable(importProfile,!profile.value));imports.append(field('sourceProfile',profile),importProfile);host.append(imports);
  // Profile availability must not prevent manual selection.
  try{for(const p of await profiles())profile.append(new Option(p.name,p.name));}catch(ex){host.append(el('p',ex.message,'app-alert'));}
  const savedHost=el('details'),savedBody=el('div');savedHost.append(el('summary',label('saved')),savedBody);host.append(savedHost);
  async function loadSaved(reset=false){
    const p=await post('/pipeline/scope/saved',{schema,before:reset?'':next});saved=reset?p.rows:[...saved,...p.rows];next=p.next;drawSaved();
  }
  function drawSaved(){
    savedBody.replaceChildren();if(!saved.length)savedBody.append(el('p',label('noSaved')));
    for(const item of saved){const row=el('div',undefined,'app-scope-saved'),b=action('load',()=>{
      const v=reconcileScope(item.tables,options.tables.map(t=>t.name));apply(v.tables,v.missing,[],false);name.value=item.name;status(label('loaded'));
    });row.append(el('span',`${item.name} · ${item.tables.length} · ${item.recordedAt}`),b);savedBody.append(row);}
    if(next)savedBody.append(action('more',()=>work(()=>loadSaved())));
  }
  const warning=el('div',undefined,'app-alert'),ack=el('input');ack.type='checkbox';const ackRow=field('ack',ack);warning.hidden=true;ackRow.hidden=true;host.append(warning,ackRow);
  const filter=el('input',undefined,'form-control');filter.type='search';const only=el('input');only.type='checkbox';
  const count=el('p',undefined,'app-filter-message'),list=el('div',undefined,'app-scope-tables'),selectedList=el('details'),selectedNames=el('p');selectedList.append(el('summary',label('selectedList')),selectedNames);
  const tools=el('div',undefined,'app-scope-tools');tools.append(field('search',filter),field('onlySelected',only),action('addMatches',()=>{
    const matches=scopeMatches(options.tables,filter.value,selected);const merged=new Set([...selected,...matches.map(t=>t.name)]);
    if(merged.size>500){status(label('max'),true);return;}selected=merged;update();
  }),action('selectAll',()=>{
    if(options.tables.length>500){status(label('max'),true);return;}apply(options.tables.map(t=>t.name),[],[],false);status(label('allSelected',options.tables.length));
  }),action('clear',()=>{selected.clear();update();}));
  const pager=el('div',undefined,'app-scope-tools'),previous=action('previous',()=>{page--;render();}),following=action('next',()=>{page++;render();}),pageNumber=el('output');pager.append(previous,pageNumber,following);host.append(tools,count,list,pager,selectedList);
  const saveArea=el('div',undefined,'app-scope-tools'),name=el('input',undefined,'form-control'),saveStatus=el('p',undefined,'app-filter-message');name.maxLength=100;
  const save=action('save',()=>work(async()=>{
    await post('/pipeline/scope/save',{schema,name:name.value,tables:[...selected].sort()});await loadSaved(true);status(label('savedOk'));
  }));saveArea.append(field('name',name),save);host.append(saveArea,saveStatus,el('p',label('saveHelp'),'app-filter-message'));
  const prepare=action('preview',()=>work(async()=>{if(!validScope(selected)||warnings&&!ack.checked)return;await preview([...selected].sort());}));host.append(prepare);
  name.addEventListener('input',render);ack.addEventListener('change',render);filter.addEventListener('input',()=>{page=0;render();});only.addEventListener('change',()=>{page=0;render();});
  function update(){changed();render();}
  function apply(tables,missing,outside,whole){
    selected=new Set(tables);warnings=missing.length>0||outside.length>0||whole;warning.replaceChildren();
    if(missing.length)warning.append(el('p',label('missing',missing.join(', '))));
    if(outside.length)warning.append(el('p',label('outside',outside.join(', '))));
    if(whole)warning.append(el('p',label('wholeSchema')));
    warning.hidden=!warnings;ackRow.hidden=!warnings;ack.checked=false;filter.value='';only.checked=false;page=0;update();
  }
  function render(){
    const matches=scopeMatches(options.tables,filter.value,selected,only.checked),pages=Math.ceil(matches.length/60);page=Math.max(0,Math.min(page,pages-1));const visible=matches.slice(page*60,(page+1)*60);list.replaceChildren();
    count.textContent=label('counts',selected.size,options.tables.length,options.schemaTables,visible.length,matches.length);pageNumber.textContent=`${pages?page+1:0} / ${pages}`;disable(previous,page===0);disable(following,page+1>=pages);
    for(const table of visible){const row=el('label',undefined,'app-scope-table'),input=el('input');input.type='checkbox';input.checked=selected.has(table.name);
      input.addEventListener('change',()=>{if(input.checked){if(selected.size>=500){input.checked=false;status(label('max'),true);return;}selected.add(table.name);}else selected.delete(table.name);update();});
      row.append(input,el('span',table.name),el('small',`v${table.revision} · ${table.state}`));list.append(row);}
    selectedNames.textContent=[...selected].sort().join(', ')||label('empty');
    saveStatus.hidden=options.canSave;saveStatus.textContent=options.storage==='READY'?t('ontology.ownerRequired','Owner required'):label('storage',options.storage);
    disable(save,!options.canSave||!name.value.trim()||selected.size===0||selected.size>500);
    disable(prepare,!validScope(selected)||warnings&&!ack.checked);
  }
  if(options.storage==='READY'){try{await loadSaved(true);}catch(ex){savedBody.append(el('p',ex.message,'app-alert'));}}
  else{
    savedBody.append(el('p',label('storage',options.storage)));
    if(options.installSql){const code=el('pre',options.installSql,'app-pipeline-code'),consent=el('input');consent.type='checkbox';
      const install=action('install',()=>work(async()=>{
        if(!consent.checked)return;options=await post('/pipeline/scope/install',{schema,confirmed:true});await loadSaved(true);render();status(label('installed'));
      }));disable(install,true);consent.addEventListener('change',()=>disable(install,!consent.checked));savedBody.append(code,field('installConsent',consent),install);
    }
  }
  render();return {selection:()=>[...selected].sort()};
}
