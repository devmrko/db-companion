import {t} from './i18n.mjs';
const text=k=>t('ontology.wizard.'+k,k);
const node=(tag,value,cls)=>{const n=document.createElement(tag);if(value!==undefined)n.textContent=value??'—';if(cls)n.className=cls;return n;};
export const sampleRequest=(schema,entry,columns,count,confirmed)=>({schema,table:entry.document.source.table,revision:entry.revision,columns,count:Number(count),confirmed});
export function editsOf(rows){return rows.filter(r=>r.accepted).map(r=>({column:r.column,description:r.description,label:r.label,aliases:r.aliases.split('\n').map(v=>v.trim()).filter(Boolean),unit:r.unit,role:r.role,valueMeaning:r.valueMeaning??'',labelColumn:r.labelColumn??'',usageGuidance:r.usageGuidance??''}));}
export function recommendationRow(rec){const d=rec.definition;return {column:rec.column,description:rec.description,label:d.label,aliases:d.aliases.join('\n'),unit:d.unit,role:d.role,valueMeaning:d.valueMeaning??'',labelColumn:d.labelColumn??'',usageGuidance:d.usageGuidance??'',accepted:false};}
export function labelColumnChoices(columns,column){return columns.filter(name=>name!==column);}
export function canSample(columns,confirmed,busy){return !busy&&confirmed&&columns.length>0&&columns.length<=20;}
export function definitionDetails(d){return d?[['label',d.label],['aliases',d.aliases.join(', ')],['unit',d.unit],['role',d.role],['valueMeaning',d.valueMeaning??''],['labelColumn',d.labelColumn??''],['usageGuidance',d.usageGuidance??''],...(d.profile?[['assessment',text(d.assessment)],['reason',d.reason],['uncertainty',d.uncertainty],['profile',d.profile],['sampledAt',d.sampledAt],['sampleRows',String(d.sampleRows)]]:[])]:[];}
export function definitionSummary(d){const box=node('details');box.append(node('summary',text('details')));for(const [key,value] of definitionDetails(d)){const p=node('p');p.append(node('strong',text(key)+': '),node('span',value||'—'));box.append(p);}return box;}
export function editDefinition(previous,key,value){
  if(!['label','aliases','unit','role','valueMeaning','labelColumn','usageGuidance'].includes(key))throw new Error('Invalid definition field');
  const d=previous?structuredClone(previous):{label:'',aliases:[],unit:'',role:'',assessment:'UNKNOWN',reason:'',uncertainty:'',profile:'',sampledAt:'',sampleRows:0};
  d[key]=key==='aliases'?value.split('\n').map(v=>v.trim()).filter(Boolean):value;return d;
}
export function definitionEditor({column,definition,columns,editable,changed}){
  const box=node('details',undefined,'app-column-definition');box.append(node('summary',text('editDefinition')));
  let current=definition;
  for(const key of ['label','aliases','role','unit','valueMeaning','labelColumn','usageGuidance']){
    const wrap=node('label',undefined,'app-ontology-field'),control=node(key==='labelColumn'?'select':key==='aliases'||key==='usageGuidance'?'textarea':'input',undefined,key==='labelColumn'?'form-select app-select':'form-control');
    if(key==='labelColumn'){control.add(new Option(text('labelNone'),''));for(const name of labelColumnChoices(columns,column))control.add(new Option(name,name));}
    else{control.maxLength=key==='aliases'?2570:key==='unit'?128:['valueMeaning','usageGuidance'].includes(key)?500:256;if(control.tagName==='TEXTAREA')control.rows=2;}
    control.value=key==='aliases'?(definition?.aliases??[]).join('\n'):definition?.[key]??'';control.disabled=!editable;control.setAttribute('aria-label',column+' '+text(key));
    control.addEventListener(key==='labelColumn'?'change':'input',()=>{current=editDefinition(current,key,control.value);changed(current);});
    wrap.append(node('span',text(key)),control);box.append(wrap);
  }
  if(definition?.profile)box.append(definitionSummary(definition));
  return box;
}
export function ontologyWizard(dialog,{schema,getOptions,post,saved}){
  const get=k=>dialog.querySelector(`[data-w-${k}]`);
  let entry=null,preview=null,proposal=null,selected=[],rows=[],busy=false,step=0,spent=false,closeInProgress=false;
  function message(value,error=false){get('message').textContent=value;get('message').className=error?'app-alert is-error':'app-filter-message';}
  function show(index){step=index;['select','preview','review'].forEach((k,i)=>get(k).hidden=i!==index);get('steps').querySelectorAll('li').forEach((li,i)=>{if(i===index)li.setAttribute('aria-current','step');else li.removeAttribute('aria-current');});lock();}
  function lock(){
    dialog.querySelectorAll('input,select,textarea,button').forEach(e=>e.disabled=busy||e.dataset.blocked==='true');
    get('sample').disabled=!canSample(selected,get('local-consent').checked,busy);
    get('generate').disabled=busy||spent||!preview||!get('consent').checked;
    get('save').disabled=busy||!proposal||!rows.some(r=>r.accepted);
  }
  async function work(action){if(busy)return;busy=true;lock();message(text('working'));try{await action();message('');}catch(e){message(e.message,true);}finally{busy=false;lock();}}
  async function discard(){const tokens=[preview?.token,proposal?.token].filter(Boolean);for(const token of new Set(tokens))await post('/wizard/cancel',{token});preview=null;proposal=null;get('payload').textContent='';get('samples').replaceChildren();get('results').replaceChildren();rows=[];}
  function sampleView(){
    const sample=preview.sample,p=preview.request.profile;
    get('profile').textContent=`${p.selection.owner}.${p.selection.name} · ${p.provider} / ${p.model??''}`;
    get('summary').textContent=text('limited')+' · '+sample.rows+' / '+get('count').value;
    get('excluded').textContent=sample.excluded.length?text('excluded')+': '+sample.excluded.join(', '):'';
    const host=get('samples');host.replaceChildren();
    for(const c of sample.columns){const details=node('details');details.append(node('summary',c.name+' · '+c.dataType));const pre=node('pre',JSON.stringify(c.values,null,2),'app-preview-source');details.append(pre,node('p',`${text('nulls')}: ${c.nulls} · ${text('truncated')}: ${c.truncated}`));host.append(details);}
    get('payload').textContent=preview.request.source;get('consent').checked=false;show(1);
  }
  function field(card,label,value,onInput,multiline=false){const wrap=node('label',undefined,'app-ontology-field'),input=node(multiline?'textarea':'input',undefined,'form-control');input.value=value??'';input.maxLength=label==='description'?8000:label==='aliases'?2570:label==='unit'?128:['valueMeaning','usageGuidance'].includes(label)?500:256;if(multiline)input.rows=3;input.addEventListener('input',()=>onInput(input.value));wrap.append(node('span',text(label)),input);card.append(wrap);}
  function results(){
    get('results').replaceChildren();rows=[];
    for(const rec of proposal.columns){
      const d=rec.definition,row=recommendationRow(rec);rows.push(row);
      const card=node('article',undefined,'app-wizard-result'),head=node('label',undefined,'app-ontology-consent'),check=node('input');check.type='checkbox';check.addEventListener('change',()=>{row.accepted=check.checked;lock();});head.append(check,node('strong',rec.column),node('span',text('adopt')));card.append(head);
      card.append(node('p',text(d.assessment),'app-wizard-assessment'),node('p',text('current')+': '+(entry.document.meaning.columns[rec.column].description||'—')));
      for(const key of ['description','label','aliases','unit','role','valueMeaning','usageGuidance'])field(card,key,row[key],value=>row[key]=value,['description','aliases','usageGuidance'].includes(key));
      const wrap=node('label',undefined,'app-ontology-field'),select=node('select',undefined,'form-select app-select');
      select.add(new Option(text('labelNone'),''));for(const name of labelColumnChoices(proposal.columns.map(c=>c.column),rec.column))select.add(new Option(name,name));
      select.value=row.labelColumn;select.addEventListener('change',()=>row.labelColumn=select.value);wrap.append(node('span',text('labelColumn')),select);card.append(wrap);
      card.append(node('p',text('reason')+': '+d.reason),node('p',text('uncertainty')+': '+(d.uncertainty||'—')));get('results').append(card);
    }
    show(2);
  }
  get('local-consent').addEventListener('change',lock);get('consent').addEventListener('change',lock);
  get('sample').addEventListener('click',()=>work(async()=>{await discard();preview=await post('/wizard/sample',sampleRequest(schema,entry,selected,get('count').value,get('local-consent').checked));spent=false;sampleView();}));
  get('back').addEventListener('click',()=>work(async()=>{await discard();show(0);}));
  get('generate').addEventListener('click',()=>work(async()=>{
    if(spent||!preview||!get('consent').checked)return;spent=true;
    try{proposal=await post('/wizard/generate',{token:preview.token,consent:true});results();}
    finally{get('payload').textContent='';get('samples').replaceChildren();preview=null;}
  }));
  get('save').addEventListener('click',()=>work(async()=>{
    if(!proposal)return;const current=proposal;proposal=null;
    await post('/wizard/apply',{schema,table:current.table,revision:current.revision,token:current.token,edits:editsOf(rows)});
    rows=[];get('results').replaceChildren();dialog.close();await saved(current.table);
  }));
  async function close(){if(busy||closeInProgress)return;closeInProgress=true;await work(async()=>{await discard();dialog.close();});closeInProgress=false;}
  get('close').addEventListener('click',close);dialog.addEventListener('cancel',e=>{e.preventDefault();close();});
  dialog.addEventListener('close',()=>{entry=null;rows=[];selected=[];get('columns').replaceChildren();});
  return {async open(value){
    if(busy)return;entry=value;selected=[];preview=null;proposal=null;rows=[];spent=false;get('local-consent').checked=false;get('consent').checked=false;get('count').value='10';
    get('target').textContent=schema+'.'+value.document.source.table+' · v'+value.revision;get('columns').replaceChildren();show(0);dialog.showModal();
    await work(async()=>{
      const options=await getOptions(value);
      for(const c of options){const wrap=node('label',undefined,'app-wizard-column'),check=node('input');check.type='checkbox';check.disabled=!!c.blocked;check.dataset.blocked=String(!!c.blocked);check.addEventListener('change',()=>{selected=check.checked?[...selected,c.name]:selected.filter(n=>n!==c.name);lock();});wrap.append(check,node('span',c.name),node('small',c.type+(c.blocked?' · '+text(c.blocked):'')));get('columns').append(wrap);}
    });
  }};
}
