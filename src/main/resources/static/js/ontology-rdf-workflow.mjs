import {t} from './i18n.mjs';
import {attachQueryGraph} from './ontology-query-graph.mjs';
const label=key=>t('ontology.query.rdfFlow.'+key,key);
const node=(tag,text)=>{const e=document.createElement(tag);if(text!=null)e.textContent=text;return e;};
export function renderRdfWorkflow(host,summary,generate,edited=()=>{},submitLabel=label('generate')){
  host.replaceChildren();
  host.append(node('h3',label('terms')));
  for(const rule of summary.rules){const box=node('p');box.append(node('strong',rule.term+' · '),node('span',rule.definition.match(/^[\s\S]*?[.!?。](?:\s|$)/)?.[0]?.trim()||rule.definition));host.append(box);}
  if(!summary.rules.length)host.append(node('p',label('noTerms')));
  host.append(node('h3',label('relationship')));
  const relationSummary=node('p'),selectedSources=node('p');host.append(relationSummary,selectedSources);
  const graph=attachQueryGraph(host,summary);
  const evidence=node('details');evidence.append(node('summary',label('evidence')),node('p',label('policy')));
  const sourceDetails=node('details');sourceDetails.append(node('summary',label('sources')));evidence.append(sourceDetails);
  const checks=[],links=[];
  const recommended=summary.sources.some(s=>s.recommended);
  for(const [index,source] of summary.sources.entries()){
    const card=node('article');card.className='border rounded p-3 mb-2';const check=node('input');check.type='checkbox';check.value=source.name;check.checked=recommended?source.recommended===true:index===0;
    const name=node('label');name.append(check,node('strong',` ${source.concept||source.name} · ${source.name}`));
    card.append(name,node('p',`${source.state} · v${source.revision} · ${label(source.recommended?'dictionarySource':'rdfSource')}`),node('p',source.description));
    const columns=node('details');columns.append(node('summary',label('columns')));
    const table=node('table');table.className='table app-table';
    for(const c of source.columns){const row=node('tr');row.append(node('td',c.name),node('td',c.type),node('td',c.description));table.append(row);}columns.append(table);card.append(columns);sourceDetails.append(card);checks.push(check);
  }
  evidence.append(node('h4',label('rules')));
  for(const rule of summary.rules){const box=node('article');box.append(node('strong',rule.term),node('p',rule.definition));const sql=node('details');sql.append(node('summary',label('criteria')),node('pre',rule.criteria));box.append(sql);evidence.append(box);}
  const relevant=node('section');relevant.append(node('h4',label('relations')));evidence.append(relevant);
  const others=node('details');others.append(node('summary',label('otherRelations')));evidence.append(others);
  if(!summary.relations.length)relevant.append(node('p',label('noRelations')));
  for(const r of summary.relations){const row=node('label'),check=node('input');check.type='checkbox';check.value=r.id;check.checked=false;
    if(!r.usable){check.disabled=true;check.dataset.localDisabled='';}
    const card=node('article');row.append(check,node('span',`${r.source} (${r.from.join(', ')}) → ${r.target} (${r.to.join(', ')}) · ${r.label} · ${r.status} · ${label(r.usable?'usable':'referenceOnly')}`));card.append(row,node('p',r.condition));links.push({check,relation:r,card});
  }
  const mode=node('select');mode.className='form-select';mode.setAttribute('aria-label',label('mode'));
  for(const value of ['INDEPENDENT','JOIN']){const option=node('option',label(value));option.value=value;mode.append(option);}mode.value='INDEPENDENT';evidence.append(mode);host.append(evidence);
  const submit=node('button',submitLabel);submit.type='button';submit.className='btn app-btn app-btn-primary mt-3';
  const update=()=>{
    const names=checks.filter(c=>c.checked).map(c=>c.value);
    const selectedLinks=links.filter(v=>v.relation.usable&&v.check.checked&&names.includes(v.relation.source)&&names.includes(v.relation.target));
    relationSummary.textContent=mode.value==='INDEPENDENT'&&names.length>1?t('ontology.query.graph.businessSummary','businessSummary'):label(!names.length?'chooseSources':mode.value==='INDEPENDENT'?'singleSummary':selectedLinks.length?'joinSummary':'chooseRelations');
    selectedSources.textContent=summary.sources.filter(s=>names.includes(s.name)).map(s=>s.concept||s.name).join(' · ');
    if(mode.value==='JOIN'&&selectedLinks.length)relationSummary.textContent+=' '+selectedLinks.map(v=>`${v.relation.source} → ${v.relation.target} · ${v.relation.label}`).join('; ');
    for(const v of links){const active=names.includes(v.relation.source)&&names.includes(v.relation.target);if(!active)v.check.checked=false;(active?relevant:others).append(v.card);}
    graph.update({tables:names,mode:mode.value,relations:selectedLinks.map(v=>v.relation.id)});
    submit.disabled=!names.length||(mode.value==='JOIN'&&(names.length<2||!selectedLinks.length));if(submit.disabled)submit.dataset.localDisabled='';else delete submit.dataset.localDisabled;
  };
  submit.addEventListener('click',()=>{if(submit.disabled)return;return generate({mode:mode.value,tables:checks.filter(c=>c.checked).map(c=>c.value),relations:mode.value==='JOIN'?links.filter(v=>v.relation.usable&&v.check.checked).map(v=>v.check.value):[]});});
  for(const input of [mode,...checks,...links.map(v=>v.check)])input.addEventListener('change',()=>{update();edited();});
  update();host.append(submit);return evidence;
}
