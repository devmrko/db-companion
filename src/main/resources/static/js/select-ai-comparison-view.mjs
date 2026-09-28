import {t} from './i18n.mjs';
/** Display-only comparison of observed values. Missing/partial evidence is never equality. */
const observation=(value,status,checkedAt=null,known=false)=>({value,status,checkedAt,known});
const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'
  ?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value;
const stable=value=>JSON.stringify(canonical(value));
const withoutTimes=value=>Array.isArray(value)?value.map(withoutTimes):value&&typeof value==='object'
  ?Object.fromEntries(Object.entries(value).filter(([key])=>key!=='checkedAt').map(([key,item])=>[key,withoutTimes(item)])):value;
function side(result,name){
  const sql=result[name],inspection=result[`${name}Inspection`],prompt=result[`${name}Prompt`];
  const profile=sql?.profile||inspection?.profile;
  const rows={
    profile:observation(profile||null,profile?'OBSERVED':'NOT_OBSERVED',sql?.requestedAt||inspection?.checkedAt,Boolean(profile)),
    sql:observation(sql?.text??null,sql?.error?'ERROR':sql?.text?'GENERATED_NOT_EXECUTED':'NOT_OBSERVED',sql?.requestedAt,Boolean(sql?.text)&&!sql?.error),
    error:observation(sql?{message:sql.error||null,code:sql.code||null,phase:sql.phase||null}:null,sql?'OBSERVED':'NOT_OBSERVED',sql?.requestedAt,Boolean(sql)),
    time:observation(sql?.elapsedMillis??null,sql?'OBSERVED_MS':'NOT_OBSERVED',sql?.requestedAt,Boolean(sql)),
    options:observation(inspection?.settings??null,inspection?'EXPLICIT_READ':'NOT_QUERIED',inspection?.checkedAt,Boolean(inspection)),
    metadata:observation(null,'NOT_QUERIED'),
    feedback:observation(null,'NOT_QUERIED'),
    prompt:observation(prompt?.text??null,prompt?.error?'ERROR':prompt?.text?'RECONSTRUCTED_SHOWPROMPT':'NOT_REQUESTED',prompt?.requestedAt,Boolean(prompt?.text)&&!prompt?.error)
  };
  if(inspection?.tables?.length){
    const tables=inspection.tables,objects=inspection.objects||[];
    const partial=tables.some(table=>table.error||String(table.annotationStatus||'').startsWith('ERROR'))
      ||objects.some(object=>!object.name||!tables.some(table=>table.owner===object.owner&&table.name===object.name));
    rows.metadata=observation(tables,partial?'PARTIAL_OR_ERROR':'EXPLICIT_READ',inspection.checkedAt,!partial);
  }
  if(inspection?.feedback){
    const feedback=inspection.feedback,details=inspection.feedbackDetails||[],items=feedback.rows?.items||[];
    const partial=feedback.rows?.hasNext||items.some(item=>!details.some(detail=>detail.id===item.id));
    const status=feedback.error?'ERROR':feedback.missingTable?'NOT_VISIBLE':!feedback.rows?'UNCONFIRMED':partial?'PARTIAL':'EXPLICIT_READ';
    rows.feedback=observation({page:feedback,details},status,feedback.checkedAt,status==='EXPLICIT_READ');
  }
  return rows;
}
export function comparisonRows(result={}){
  const left=side(result,'left'),right=side(result,'right');
  return Object.keys(left).map(key=>({key,left:left[key],right:right[key],
    same:left[key].known&&right[key].known&&left[key].status===right[key].status
      &&stable(withoutTimes(left[key].value))===stable(withoutTimes(right[key].value))}));
}
const labels=()=>({profile:t('aitest.comparison.profile','프로필·모델'),sql:t('aitest.comparison.sql','생성 SQL'),error:t('aitest.comparison.error','오류'),time:t('aitest.comparison.time','소요 시간 (ms)'),options:t('aitest.comparison.options','프로필 옵션'),metadata:t('aitest.comparison.metadata','테이블·컬럼·Annotation'),feedback:t('aitest.comparison.feedback','Feedback 질문·SQL·설명'),prompt:t('aitest.comparison.prompt','재구성 SHOWPROMPT')});
export function renderComparisonRows(host,result,differencesOnly=false){
  host.replaceChildren();
  const note=document.createElement('p');note.className='app-filter-message';
  note.textContent=t('aitest.comparison.note','표시는 조회한 자료의 차이입니다. SQL 정답 여부나 실제 LLM 참고 여부를 판정하지 않습니다. SHOWPROMPT는 후속 재구성이며 과거 전송 원문이 아닙니다. 미조회·부분 조회는 같음으로 판정하지 않습니다.');
  host.append(note);
  const rows=comparisonRows(result).filter(row=>!differencesOnly||!row.same);
  if(!rows.length){const empty=document.createElement('p');empty.textContent=t('aitest.comparison.empty','확보된 비교 자료에 차이가 없습니다. 결과의 정합성을 보증하지 않습니다.');host.append(empty);}
  for(const row of rows){
    const section=document.createElement('section'),title=document.createElement('h3'),grid=document.createElement('div');
    section.className=`app-comparison-field ${row.same?'is-equal':'is-different'}`;
    title.textContent=`${labels()[row.key]} · ${row.same?t('aitest.comparison.same','조회값 같음'):t('aitest.comparison.different','차이 또는 미확인')}`;
    grid.className='app-comparison-grid';
    for(const [name,value] of [['A',row.left],['B',row.right]]){
      const cell=document.createElement('div'),heading=document.createElement('h4'),status=document.createElement('p'),body=document.createElement('pre');
      heading.textContent=name;status.className='app-filter-message';
      status.textContent=`${value.status} · ${t('aitest.comparison.observed','조회/요청')}: ${value.checkedAt||t('aitest.comparison.unconfirmed','미확인')}`;
      body.className='app-preview-value';body.textContent=value.value==null?t('aitest.comparison.noData','자료 없음'):typeof value.value==='string'?value.value:JSON.stringify(value.value,null,2);
      cell.append(heading,status,body);grid.append(cell);
    }
    section.append(title,grid);host.append(section);
  }
}
