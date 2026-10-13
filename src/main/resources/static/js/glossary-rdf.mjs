import {t} from './i18n.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';

export function mappingLines(text){return text.split(/\r?\n/).map(v=>v.trim()).filter(Boolean).map(line=>{
  const parts=line.split('|').map(v=>v.trim()),names=(parts[0]||'').split('.');
  if(names.length!==3||parts.length<2||parts.length>3||!names.every(n=>/^[A-Z][A-Z0-9_$#]*$/.test(n))||!['VALUE','DATE','IDENTIFIER','FILTER','JOIN'].includes(parts[1])||!['','SUM','COUNT','COUNT_DISTINCT','MAX','MIN'].includes(parts[2]||''))throw new Error(t('auditFixes.rdf.invalid','매핑 형식을 확인해 주세요: 스키마.테이블.컬럼 | 역할 | 집계'));
  return {schema:names[0],table:names[1],column:names[2],role:parts[1],aggregation:parts[2]||''};
});}
export function mountGlossaryRdf(host,csrf){
  const panel=document.createElement('details'),title=document.createElement('summary');title.textContent=t('auditFixes.rdf.title','RDF · 업무 의미와 원천 매핑 (선택)');panel.append(title);
  const note=document.createElement('p');note.textContent=t('auditFixes.rdf.note','사용자가 확인한 매핑만 저장합니다. 업무 의미와 원천 매핑을 구분하며, 조인 승인이나 SQL 정확성 보증이 아닙니다.');panel.append(note);
  const fields={};for(const [key,limit,lines] of [['domain',256,1],['unit',128,1],['scope',1000,2],['grain',1000,2],['rules',20000,3],['citations',20000,2],['mappings',20000,4]]){
    const fallback={domain:'업무 영역',unit:'단위',scope:'적용 범위',grain:'집계·식별 단위',rules:'필터·제외·업무 규칙 (한 줄에 하나)',citations:'출처·근거 (한 줄에 하나)',mappings:'테이블·컬럼 매핑 (한 줄에 하나)'};
    const label=document.createElement('label'),caption=document.createElement('span'),input=document.createElement('textarea');caption.textContent=t(`auditFixes.rdf.${key}`,fallback[key]);input.className='form-control';input.maxLength=limit;input.rows=lines;label.append(caption,input);panel.append(label);fields[key]=input;
  }
  const help=document.createElement('p');help.textContent=t('auditFixes.rdf.mappingHelp','한 줄에 하나: SCHEMA.TABLE.COLUMN | VALUE / DATE / IDENTIFIER / FILTER / JOIN | SUM / COUNT_DISTINCT 등 (선택). 같은 이름도 원천별로 구분합니다.');panel.append(help);
  const exportButton=document.createElement('button');exportButton.type='button';exportButton.className='btn app-btn app-btn-quiet';exportButton.textContent=t('auditFixes.rdf.export','저장된 RDF 확인·내보내기');panel.append(exportButton);
  const output=document.createElement('div');panel.append(output);host.after(panel);let term=null;
  const analysis=document.createElement('details'),analysisTitle=document.createElement('summary'),sql=document.createElement('textarea'),check=document.createElement('button'),result=document.createElement('div'),warning=document.createElement('p');
  analysisTitle.textContent=t('auditFixes.rdf.analyze','저장된 매핑과 SQL 대조 · 실행 없음');sql.className='form-control';sql.rows=5;sql.maxLength=20000;sql.setAttribute('aria-label','SQL');check.type='button';check.className='btn app-btn app-btn-quiet';check.textContent=t('auditFixes.rdf.check','SQL 참조 확인');warning.textContent=t('auditFixes.rdf.analysisNote','원천·컬럼 참조를 SQL 구문으로 대조합니다. 집계·기간·필터·중복 조인의 정확성은 자동 검증하지 않습니다. 아래 업무 규칙과 SQL을 함께 확인하세요.');analysis.append(analysisTitle,warning,sql,check,result);panel.append(analysis);
  check.addEventListener('click',async()=>{if(!term||!csrf)return;check.disabled=true;try{const value=await assistantApi('/business-glossary/rdf/explain',assistantPost(csrf,{id:term.id,sql:sql.value}));result.replaceChildren();if(value.status!=='PARSED')result.textContent=t('auditFixes.rdf.unsupported','SQL 구문을 분석하지 못했습니다. 미사용으로 판단하지 않습니다.');else{for(const ref of value.references){const p=document.createElement('p');p.textContent=`${ref.mapping.schema}.${ref.mapping.table}.${ref.mapping.column} · ${t('auditFixes.rdf.ref.'+ref.status,ref.status)} · ${ref.mapping.role} ${ref.mapping.aggregation}`;result.append(p);}for(const rule of value.rules){const p=document.createElement('p');p.textContent=rule;result.append(p);}}}catch(ex){result.textContent=ex.message;}finally{check.disabled=false;}});
  exportButton.addEventListener('click',async()=>{if(!term)return;exportButton.disabled=true;output.replaceChildren();try{
    const data=await assistantApi('/business-glossary/rdf?'+new URLSearchParams({id:term.id}));
    const summary=document.createElement('p');summary.textContent=data.sources.join('\n')||t('auditFixes.rdf.noMapping','등록된 원천 매핑이 없습니다. 업무 정의 RDF만 표시합니다.');output.append(summary);
    for(const [key,text] of [['meaning',data.meaning],['mapping',data.mapping]]){
      const detail=document.createElement('details'),caption=document.createElement('summary'),pre=document.createElement('pre'),download=document.createElement('button');caption.textContent=t(`auditFixes.rdf.${key}`,key==='meaning'?'업무 정의 RDF':'테이블·컬럼 매핑 RDF');pre.className='app-preview-value';pre.textContent=text;download.type='button';download.className='btn app-btn app-btn-quiet';download.textContent=t('auditFixes.rdf.download','Turtle 다운로드');download.addEventListener('click',()=>{const url=URL.createObjectURL(new Blob([text],{type:'text/turtle;charset=utf-8'})),link=document.createElement('a');link.href=url;link.download=`glossary-${term.id}-v${term.revision}-${key}.ttl`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});detail.append(caption,pre,download);output.append(detail);
    }
  }catch(ex){output.textContent=ex.message;}finally{exportButton.disabled=false;}});
  return {edit(value){term=value;output.replaceChildren();result.replaceChildren();sql.value='';check.disabled=!term||!csrf;exportButton.disabled=!term;for(const key of ['domain','unit','scope','grain'])fields[key].value=value?.rdf?.[key]||'';for(const key of ['rules','citations'])fields[key].value=(value?.rdf?.[key]||[]).join('\n');fields.mappings.value=(value?.rdf?.mappings||[]).map(m=>`${m.schema}.${m.table}.${m.column} | ${m.role} | ${m.aggregation}`).join('\n');},value(){
    if(!Object.values(fields).some(f=>f.value.trim())&&!term?.rdf)return null;
    const rdf={};for(const key of ['domain','unit','scope','grain'])rdf[key]=fields[key].value.trim();for(const key of ['rules','citations'])rdf[key]=fields[key].value.split(/\r?\n/).map(v=>v.trim()).filter(Boolean);rdf.mappings=mappingLines(fields.mappings.value);return rdf;
  }};
}
