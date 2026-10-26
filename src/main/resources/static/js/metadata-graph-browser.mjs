import {t} from './i18n.mjs';
const text=(key,fallback,...args)=>t('ontology.mg.browser.'+key,fallback,...args);
const el=(tag,value,cls)=>{const node=document.createElement(tag);if(value!==undefined)node.textContent=value;if(cls)node.className=cls;return node;};
const display=value=>value==null||value===''?'—':String(value);
function table(rows,fields){
  const table=el('table',undefined,'table app-table align-middle'),head=el('thead'),heading=el('tr');
  for(const [key,fallback] of fields)heading.append(el('th',text(key,fallback)));head.append(heading);table.append(head);
  const body=el('tbody');for(const row of rows){const tr=el('tr');for(const [key] of fields)tr.append(el('td',display(row[key])));body.append(tr);}table.append(body);
  const wrap=el('div',undefined,'table-responsive');wrap.append(table);return wrap;
}
export function renderMetadataBrowser(host,value,{review}={}){
  const tabs=el('div',undefined,'d-flex flex-wrap gap-2 mb-3'),content=el('div');host.append(tabs,content);
  const groups=[['RELATES_TO','relations','Table relationships',value.rows],['MAPS_TO','mappings','Column mappings',value.mappings??[]],['CONTAINS','columns','Column membership',value.columns??[]]];
  const controls=[];
  function render(index){
    controls.forEach((b,i)=>{b.className='btn app-btn '+(i===index?'app-btn-primary':'app-btn-secondary');b.setAttribute('aria-pressed',String(i===index));});
    const [kind,,,rows]=groups[index],total=value.counts?.[kind]??rows.length;content.replaceChildren();
    content.append(el('p',text('count','Showing {0} of {1}',rows.length,total),'app-filter-message'));
    if(!rows.length){content.append(el('p',text(index===0?'noRelations':'empty',index===0?'No table relationships returned. Column membership alone does not define joins; review saved relationship definitions.':'No entries in this category.'),'border rounded p-3'));return;}
    if(index===2)content.append(table(rows,[['SOURCE_OBJECT','Table'],['TARGET_COLUMN','Column']]));
    else for(const row of rows){
      const card=el('article',undefined,'border rounded p-3 mb-2'),title=el('div',undefined,'d-flex flex-wrap align-items-center gap-2');
      title.append(el('strong',display(row.SOURCE_OBJECT)),el('span','→'),el('strong',display(row.TARGET_OBJECT)));card.append(title);
      card.append(el('p',row.RELATION_LABEL||text('unlabeled','Relationship label not defined'),'mb-2 mt-2'));
      card.append(el('span',t('ontology.relationships.'+row.RELATION_STATE,row.RELATION_STATE||'—'),['CANDIDATE','STALE'].includes(row.RELATION_STATE)?'badge text-bg-warning':'badge text-bg-secondary'));
      if(review&&row.RELATION_STATE!=='FK'){const edit=el('button',text('review','Review / exclude'),'btn app-btn app-btn-secondary ms-2');edit.type='button';edit.addEventListener('click',()=>review(row));card.append(edit);}
      if(index===1)card.append(el('p',`${display(row.SOURCE_COLUMN)} → ${display(row.TARGET_COLUMN)}`,'mb-2'));
      const details=el('details');details.append(el('summary',text('mappingDetails','Mapping and condition details')),table([row],[['SOURCE_COLUMN','Source column'],['TARGET_COLUMN','Target column'],['CONDITION_TEXT','Condition'],['ORIGIN','Origin'],['RELATION_STATE','Review state'],['RELATION_ID','Relationship ID'],['MAPPING_POSITION','Position'],['MAPPING_COUNT','Mapping count']]));card.append(details);content.append(card);
    }
    if(total>rows.length)content.append(el('p',text('limited','Only the first 100 entries in this category are shown.'),'app-filter-message'));
  }
  groups.forEach(([kind,key,fallback,rows],index)=>{const b=el('button',`${text(key,fallback)} (${value.counts?.[kind]??rows.length})`);b.type='button';b.addEventListener('click',()=>render(index));controls.push(b);tabs.append(b);});render(0);
}
