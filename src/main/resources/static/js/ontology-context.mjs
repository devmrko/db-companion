import {t} from './i18n.mjs';
const label=key=>t('ontology.context.'+key,key);
const el=(tag,text,cls)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(cls)node.className=cls;return node;};
export function responseDiagnostic(error){
  const d=error?.diagnostic;
  return d&&['EMPTY','SIZE','JSON','ROOT','COUNT','ITEM','TYPE','SCOPE','DUPLICATE','VALUE','STYLE'].includes(d.code)&&typeof d.path==='string'&&d.path.length<=160&&typeof d.rawResponse==='string'&&d.rawResponse.length<=200000?{code:d.code,path:d.path,rawResponse:d.rawResponse}:null;
}
export function prettyPayload(source){try{return JSON.stringify(JSON.parse(source),null,2);}catch{return source;}}
export function selectedEdits(rows){return rows.filter(r=>r.selected).map(r=>({field:r.field,name:r.name,value:r.value}));}
export function reviewRows(changes,recommendations){return changes.map(c=>{
  const r=recommendations.find(r=>r.field===c.field&&r.name===(c.name??''));
  return r?{...r,before:c.before,selected:false}:null;
}).filter(Boolean);}
export function contextSummary(host,context){
  host.replaceChildren();if(!context)return;
  const target=context.sources[0];
  if(target)host.append(el('p',t('ontology.context.scope','{0} · {1}',target.schema+'.'+target.table,context.relations.length),'app-filter-message'));
  const details=el('details'),summary=el('summary',label('sources'));
  details.append(summary);
  for(const ref of context.sources)details.append(el('p',`${ref.schema}.${ref.table} · v${ref.revision} · ${t('ontology.'+ref.state,ref.state)}`));
  if(context.omitted.length)details.append(el('p',label('omitted')+': '+context.omitted.join(', '),'app-filter-message'));
  host.append(details);
}
export function reviewContext(host,rows,onChange){
  host.replaceChildren();
  for(const row of rows){
    const card=el('section',undefined,'app-ontology-context-card'),title=row.field==='relation'?row.name:t('ontology.'+row.field,row.field);
    const check=el('input');check.type='checkbox';check.checked=false;check.addEventListener('change',()=>{row.selected=check.checked;onChange();});
    const pick=el('label',undefined,'app-ontology-consent');pick.append(check,el('span',title+' · '+label('adopt')));
    const value=el('textarea',undefined,'form-control');value.value=row.value;value.rows=2;value.maxLength=160;value.setAttribute('aria-label',title+' · '+label('proposed'));
    value.addEventListener('input',()=>{row.value=value.value;onChange();});
    card.append(pick,el('p',t('ontology.rdf.before','Saved meaning')+': '+(row.before||'—'),'app-filter-message'),value);
    if(row.reason)card.append(el('p',label('reason')+': '+row.reason));
    if(row.uncertainty)card.append(el('p',label('uncertainty')+': '+row.uncertainty));
    host.append(card);
  }
}
export function evidenceView(value){
  const details=el('details',undefined,'app-disclosure');details.append(el('summary',label('evidence')));
  details.append(el('p',value.generatedAt));
  for(const ref of value.sources)details.append(el('p',`${ref.schema}.${ref.table} · v${ref.revision} · ${t('ontology.'+ref.state,ref.state)}`));
  for(const r of value.accepted)details.append(el('p',`${r.name||t('ontology.'+r.field,r.field)}: ${r.value}`),el('p',label('reason')+': '+r.reason),el('p',label('uncertainty')+': '+(r.uncertainty||'—')));
  details.append(el('p',label('historical'),'app-filter-message'));return details;
}
