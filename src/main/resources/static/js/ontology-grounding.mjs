import {t} from './i18n.mjs';
const label=(key,fallback)=>t('ontology.query.grounding.'+key,fallback);
const node=(tag,text,cls)=>{const e=document.createElement(tag);if(text!=null)e.textContent=text;if(cls)e.className=cls;return e;};
export function automaticTermIds(value){
  if(value.more||value.targets.some(t=>t.termIds.length!==1))return null;
  const ids=[...new Set(value.targets.flatMap(t=>t.termIds))];
  return ids.every(id=>value.hits.some(h=>h.term.id===id))?ids:null;
}
export function renderDictionaryChoices(host,value,continueSearch){
  host.replaceChildren();host.hidden=false;
  host.append(node('h3',label('title','1. 용어 정의 확인'),'h5'),node('p',value.question));
  const selects=[];
  for(const target of value.targets){
    const field=node('fieldset',null,'border rounded p-3 mb-2');field.append(node('legend',target.expression,'h6'));
    const select=node('select',null,'form-select');select.setAttribute('aria-label',target.expression);
    if(target.termIds.length>1){const empty=node('option',label('choose','의미를 선택하세요'));empty.value='';select.append(empty);}
    for(const id of target.termIds){const hit=value.hits.find(h=>h.term.id===id);if(!hit)continue;const o=node('option',hit.term.term);o.value=id;select.append(o);}
    const detail=node('p',null,'mt-2 mb-0');const show=()=>{const term=value.hits.find(h=>h.term.id===select.value)?.term;detail.textContent=term?term.definition+(term.criteria?'\n'+term.criteria:''):label('ambiguous','같은 표현에 여러 정의가 있습니다. 임의로 선택하지 않습니다.');};
    select.addEventListener('change',show);field.append(select,detail);selects.push(select);host.append(field);show();
  }
  if(!value.hits.length)host.append(node('p',label('none','일치하는 활성 용어가 없습니다. 원문으로 관계를 탐색합니다.')));
  if(value.more){host.append(node('p',label('limit','용어 검색 한도를 넘었습니다. 질문 범위를 줄여 주세요.')));return;}
  const run=node('button',label('search','정의 적용 · 관계 탐색'),'btn app-btn app-btn-primary');run.type='button';
  run.addEventListener('click',()=>{if(selects.some(s=>!s.value)){selects.find(s=>!s.value).focus();return;}return continueSearch([...new Set(selects.map(s=>s.value))]);});host.append(run);
}
export function renderGroundedResult(host,value){
  host.replaceChildren();host.hidden=false;
  if(value.rdf){
    if(value.summary){const raw=node('details');raw.append(node('summary',label('rdfTriples','RDF 원문 근거')));host.append(raw);host=raw;}
    host.append(node('h3',label('rdfTitle','RDF 온톨로지 검색 결과'),'h5'),node('p',label('rdfSource','현재 저장된 정의를 RDF 트리플로 조회했습니다. 업무 데이터나 실행용 조인 승인 결과가 아닙니다.')),
      node('p',value.rdf.terms.join(' · ')));
    if(!value.rdf.hits.length)host.append(node('p',label('rdfEmpty','일치하는 RDF 근거가 없습니다. 검색 질문과 용어 정의를 확인하세요.')));
    for(const hit of value.rdf.hits){
      const card=node('article',null,'border rounded p-3 mb-2');card.append(node('h4',`${hit.table} · ${hit.state} · v${hit.revision}`,'h6'),node('p',hit.terms.join(' · ')),node('code',hit.versionIri));
      const details=node('details');details.append(node('summary',label('rdfTriples','RDF 근거 · 주어 / 술어 / 목적어')));
      for(const triple of hit.triples)details.append(node('pre',`${triple.subject}\n${triple.predicate}\n${triple.object.value}`,'text-wrap border-bottom py-2'));
      card.append(details);host.append(card);
    }
    if(value.rdf.limited)host.append(node('p',label('rdfLimited','표시 한도에 따라 일부 RDF 근거를 제외했습니다. 전체 결과가 아닙니다.')));
    return;
  }
  host.append(node('h3',label('interpreted','해석된 질문'),'h5'),node('pre',value.interpretation.interpreted,'text-wrap'),node('p',label('policy','원문에 사전 정의를 덧붙였습니다. 날짜·수치·필터를 대체하지 않습니다. 제안 관계는 실행 SQL의 근거로 자동 채택하지 않습니다.'),'app-filter-message'));
  const details=node('details');details.append(node('summary',label('proposals','관련 제안 관계 (최대 100개)')));
  for(const r of value.proposals){const card=node('article',null,'border rounded p-3 mb-2');card.append(node('strong',`${r.source} → ${r.target}`),node('p',`${r.status} · ${r.origin} · ${r.label}`),node('p',`${r.sourceColumns.join(', ')} → ${r.targetColumns.join(', ')}`),node('p',r.condition),node('p',r.evidence.filter(s=>!s.includes('_DEFINITION:')).join(' · ')));details.append(card);}
  host.append(details);
  if(value.graph){
    host.append(node('h3',value.graph.name,'h5 mt-3'),node('p',label('graphNote','선택 그래프의 관련 관계를 최대 100행 조회했습니다. 현재 정의와 생성 시점이 다를 수 있으며 실행용 조인 승인으로 사용하지 않습니다.')));
    for(const row of value.graph.rows)host.append(node('p',`${row.SOURCE_OBJECT}${row.SOURCE_COLUMN?'.'+row.SOURCE_COLUMN:''} → ${row.TARGET_OBJECT}${row.TARGET_COLUMN?'.'+row.TARGET_COLUMN:''} · ${row.RELATION_STATE} · ${row.RELATION_LABEL||'—'}`));
    if(!value.graph.rows.length)host.append(node('p',label('graphEmpty','선택 그래프에 일치하는 관계가 없습니다.')));
    const sql=node('details');sql.append(node('summary','GRAPH_TABLE SQL'),node('pre',value.graph.sql),node('pre',JSON.stringify(value.graph.parameters,null,2)));host.append(sql);
  }
}
