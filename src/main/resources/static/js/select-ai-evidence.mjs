import {t} from './i18n.mjs';

export const evidenceKey=value=>value?.hash||'';
export const evidenceReady=(enabled,value,question)=>!enabled||Boolean(value&&value.question===question&&value.hash);
export const evidenceMatches=(saved,enabled,current,question)=>evidenceReady(enabled,current,question)&&evidenceKey(saved)===(enabled?evidenceKey(current):'');
const status=value=>value==='APPROVED'?t('aitest.evApproved','승인'):value==='FK'?t('aitest.evFk','확인된 FK'):t('aitest.evMetadata','보관 메타데이터');
const kind=value=>value==='DEFINITION'?t('aitest.evDefinition','업무 정의'):value==='RELATION'?t('aitest.evRelation','관계'):t('aitest.evMetadata','보관 메타데이터');

export function renderEvidence(host,value){
  host.replaceChildren();host.hidden=!value;if(!value)return;
  const meta=document.createElement('p');meta.className='app-filter-message';
  meta.textContent=`${value.schema} · ${value.references.map(r=>`${r.table} v${r.revision}`).join(' · ')}`;host.append(meta);
  const wrapper=document.createElement('div');wrapper.className='table-responsive';
  const table=document.createElement('table');table.className='app-table';const body=document.createElement('tbody');
  for(const item of JSON.parse(value.source).evidence){
    const row=document.createElement('tr');
    const detail=item.kind==='RELATION'?`${item.source} (${item.from.join(', ')}) → ${item.target} (${item.to.join(', ')})`:item.description||item.title;
    for(const text of [item.id,kind(item.kind),detail,status(item.status)]){const cell=document.createElement('td');cell.textContent=text;row.append(cell);}body.append(row);
  }
  const head=document.createElement('thead'),line=document.createElement('tr');
  for(const text of ['ID',t('aitest.evType','구분'),t('aitest.evidence','온톨로지 근거'),t('aitest.evStatus','상태')]){const cell=document.createElement('th');cell.scope='col';cell.textContent=text;line.append(cell);}head.append(line);table.append(head,body);wrapper.append(table);host.append(wrapper);
  const details=document.createElement('details'),summary=document.createElement('summary'),pre=document.createElement('pre');
  summary.textContent=t('aitest.evSource','전송 근거 · RDF/JSON');pre.className='app-preview-value app-test-prompt';pre.textContent=value.source;details.append(summary,pre);host.append(details);
}

export {mountRdfEvidence as mountEvidence} from './select-ai-rdf-evidence.mjs';
