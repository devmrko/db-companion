import {t} from './i18n.mjs';

export const inspectionMatches=(value,profile,question)=>Boolean(value&&value.profile?.selection?.name===profile&&value.question===question);
export const sameProfile=(a,b)=>Boolean(a&&b&&a.selection?.owner===b.selection?.owner&&a.selection?.name===b.selection?.name&&a.version===b.version&&a.provider===b.provider&&a.model===b.model);
export const optionEnabled=value=>String(value??'').trim().replaceAll('"','').toLowerCase()==='true';

// Display-only readers for the known SHOWPROMPT format. Never infer execution authorization.
function sqlBoundary(text,start,split=false){
  let quote='',depth=0,begin=start;const parts=[];
  for(let i=start;i<text.length;i++){
    const c=text[i];
    if(quote){if(c===quote){if(text[i+1]===quote)i++;else quote='';}continue;}
    if(c==='"'||c==="'"){quote=c;continue;}
    if(c==='(')depth++;
    if(c===')'){if(depth===0)return split?[...parts,text.slice(begin,i)]:i;depth--;}
    if(split&&c===','&&depth===0){parts.push(text.slice(begin,i));begin=i+1;}
  }
  return null;
}
function jsonArray(text,start){
  let depth=0,quoted=false,escaped=false;
  for(let i=start;i<text.length;i++){
    const c=text[i];if(quoted){if(escaped)escaped=false;else if(c==='\\')escaped=true;else if(c==='"')quoted=false;continue;}
    if(c==='"')quoted=true;else if(c==='[')depth++;else if(c===']'&&--depth===0)return JSON.parse(text.slice(start,i+1));
  }
  throw new Error('Unclosed examples');
}
export function parseShowprompt(source){
  const result={tables:[],examples:[],tablesParsed:false,examplesParsed:false};
  if(typeof source!=='string'||source.length>2_000_000)return result;
  let messages;try{messages=JSON.parse(source);}catch{return result;}
  if(!Array.isArray(messages))return result;
  for(const message of messages){
    const blocks=typeof message.content==='string'?[message.content]:Array.isArray(message.content)?message.content.filter(v=>typeof v.text==='string').map(v=>v.text):[];
    for(const text of blocks){
      if(String(message.role).toUpperCase()==='SYSTEM'){
        const pattern=/#\s*CREATE\s+TABLE\s+"((?:""|[^"])*)"\."((?:""|[^"])*)"\s*\(/gi;
        let match;while((match=pattern.exec(text))){
          const start=pattern.lastIndex,end=sqlBoundary(text,start);if(end===null)continue;
          const chunks=sqlBoundary(text,start,true);const columns=[];
          for(const chunk of chunks){
            const field=/^\s*"((?:""|[^"])*)"\s+([^']+?)(?:\s+'((?:''|[^'])*)')?\s*$/.exec(chunk);
            if(field)columns.push({name:field[1].replaceAll('""','"'),dataType:field[2].trim(),comment:field[3]?.replaceAll("''","'")??'',raw:chunk});
          }
          const line=text.lastIndexOf('\n--',match.index);const prefix=text.slice(line<0?0:line+1,match.index).trim();
          const comment=prefix.startsWith("--'")&&prefix.endsWith("'")?prefix.slice(3,-1).replaceAll("''","'"):'';
          result.tables.push({owner:match[1].replaceAll('""','"'),name:match[2].replaceAll('""','"'),comment,columns,raw:text.slice(line<0?match.index:line+1,end+1),complete:columns.length===chunks.length});
          result.tablesParsed=true;pattern.lastIndex=end+1;
        }
      }
      if(String(message.role).toUpperCase()==='USER'){
        const marker=text.indexOf('Here are examples of previous successful queries');
        if(marker<0)continue;const start=text.indexOf('[',marker),boundary=text.indexOf('Additional Instructions:',marker);
        if(start<0||boundary>=0&&start>boundary)continue;
        try{const values=jsonArray(boundary<0?text:text.slice(0,boundary),start);
          if(values.every(v=>v&&typeof v.user_prompt==='string'&&typeof v.sql_query==='string')){result.examples.push(...values);result.examplesParsed=true;}
        }catch{/* Keep the original prompt; an unsupported format is not an empty result. */}
      }
    }
  }
  return result;
}

const node=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;};
const pre=text=>node('pre',text??'—','app-preview-value');
function details(title){const e=node('details');e.append(node('summary',title));return e;}
function link(path,params,label){const e=node('a',label);e.href=path+'?'+new URLSearchParams(params);e.target='_blank';e.rel='noopener';return e;}
function button(label,callback){const e=node('button',label,'btn app-btn app-btn-quiet');e.type='button';e.addEventListener('click',callback);return e;}
const referenceLabel=state=>state==='sql'?t('inspect.sqlUsed','생성 SQL 참조'):state==='definition'?t('inspect.highlightDefinition','프롬프트 정의 포함'):state==='feedback'?t('inspect.highlightFeedback','프롬프트 예제와 질문·응답 일치'):state==='question'?t('inspect.highlightQuestion','예제 질문 일치 · 응답 미확인'):t('inspect.highlightDifferent','예제 질문 일치 · 응답 다름');
function badge(state,label=referenceLabel(state)){return node('span',label,`app-ref-badge app-ref-${state}`);}
function highlight(element,state){element.className=(element.className?element.className+' ':'')+'app-ref-'+state;element.setAttribute('data-ref-state',state);return element;}
function highlightLegend(host,sql=false){
  const legend=node('div',undefined,'app-ref-legend');if(sql)legend.append(badge('sql'));legend.append(badge('definition'),badge('feedback'),badge('question',t('inspect.highlightPending','추가 확인 필요')));host.append(legend);
  host.append(node('p',sql?t('inspect.sqlLegendHelp','초록은 생성 SQL의 테이블 참조, 파랑·보라는 프롬프트 포함 비교입니다. 서로 다른 기준이며 SQL 실행 성공이나 LLM 내부 활용을 뜻하지 않습니다. 컬럼·Annotation·Feedback을 SQL이 활용했다고 추정하지 않습니다.'):t('inspect.highlightHelp','색상은 현재 재구성 SHOWPROMPT와의 일치를 뜻합니다. 과거 실제 전송이나 LLM 내부 활용 여부를 증명하지 않습니다. 색상 없음은 미참고가 아니라 미확인입니다. Annotation은 개별 포함 여부를 대조하지 않습니다.'),'app-filter-message'));
}
export function promptTable(parsed,table){return parsed?.tables.find(v=>v.owner===table.owner&&v.name===table.name)??null;}
export function sqlTable(analysis,table){return analysis?.status==='PARSED'?analysis.tables.find(v=>v.owner!==null&&v.owner===table.owner&&v.name===table.name)??null:null;}
export function renderSqlReferences(host,outcome){
  host.replaceChildren();const refs=outcome?.action==='SQL'?outcome.sqlReferences:null;
  host.append(node('h3',t('inspect.sqlTables','생성 SQL에서 참조한 테이블')));
  if(!outcome||outcome.action!=='SQL'){host.append(node('p',t('inspect.sqlPending','같은 질문·프로필·근거의 SQL 생성 결과가 필요합니다. 프롬프트 보기는 필요하지 않습니다.'),'app-filter-message'));return;}
  host.append(node('p',`${outcome.id} · ${new Date(outcome.requestedAt).toLocaleString()}`,'app-filter-message'));
  host.append(node('p',t('inspect.sqlHelp','생성 SQL의 구문 분석 결과입니다. SQL 실행·성공 또는 DB 객체 존재를 확인한 것은 아닙니다. CTE·주석·문자열은 참조 테이블로 세지 않습니다.'),'app-filter-message'));
  if(refs?.source==='REJECTED_RESPONSE')host.append(node('p',t('inspect.sqlRejected','Oracle 거절 응답 안에 남아 있는 SQL을 분석했습니다. 원래 오류와 실행 차단은 그대로 유지됩니다.'),'app-alert is-warning'));
  else if(outcome.error)host.append(node('p',t('inspect.sqlError','오류가 있는 생성 응답입니다. 아래 참조 표시는 오류 해결이나 실행 승인을 뜻하지 않습니다.'),'app-alert is-warning'));
  if(refs?.status!=='PARSED'){host.append(node('p',t('inspect.sqlUnknown','참조 테이블을 분석하지 못했습니다. SQL 미수신·길이 제한·미지원 형식일 수 있으며, 테이블을 사용하지 않았다는 뜻은 아닙니다.'),'app-filter-message'));return;}
  if(!refs.tables.length){host.append(node('p',t('inspect.sqlEmpty','분석한 SQL에는 테이블 참조가 없습니다.')));return;}
  const list=node('ul');for(const table of refs.tables){const item=node('li',table.sqlName);
    if(table.owner!==null){highlight(item,'sql');item.append(badge('sql'));}
    else item.append(badge('question',t('inspect.sqlUnqualified','소유자 미지정 · DB 객체 매칭 미확정')));
    list.append(item);}
  host.append(list);
}
export function feedbackMatch(parsed,row,loaded){
  if(!parsed?.examplesParsed)return null;
  const candidates=parsed.examples.filter(example=>example.user_prompt===row.question);if(!candidates.length)return null;
  if(!loaded||loaded.detail.question!==row.question||typeof loaded.detail.response!=='string')return 'question';
  return candidates.some(example=>example.sql_query===loaded.detail.response)?'feedback':'different';
}
function comment(text,included){const element=pre(text);if(text&&included===text)highlight(element,'definition');return element;}
function columns(host,rows,definition){
  const wrap=node('div',undefined,'table-responsive'),table=node('table',undefined,'app-table app-inspection-columns'),head=node('thead'),tr=node('tr'),body=node('tbody');
  for(const label of [t('inspect.column','컬럼'),t('inspect.type','타입'),t('inspect.comment','코멘트')])tr.append(node('th',label));head.append(tr);
  for(const row of rows){const tr=node('tr'),name=node('td',row.name),description=node('td',row.comment||'—'),included=definition?.columns.find(c=>c.name===row.name);
    if(included){highlight(name,'definition');name.append(badge('definition'));if(row.comment&&row.comment===included.comment)highlight(description,'definition');}
    tr.append(name,node('td',row.dataType),description);body.append(tr);}
  table.append(head,body);wrap.append(table);host.append(wrap);
}
export function renderPromptInspection(host,outcome){
  host.replaceChildren();if(!outcome?.text||outcome.error)return;
  const parsed=parseShowprompt(outcome.text);
  host.append(node('p',t('inspect.promptHelp','SHOWPROMPT에 포함된 정의와 예제입니다. 과거 SQL 생성 시 실제 전송된 자료라는 의미는 아닙니다.'),'app-filter-message'));
  highlightLegend(host);
  const tables=details(t('inspect.promptTables','프롬프트에 포함된 테이블·컬럼')+(parsed.tablesParsed?` · ${parsed.tables.length}`:''));
  if(!parsed.tablesParsed)tables.append(node('p',t('inspect.parseUnknown','이 형식은 구조화하지 못했습니다. 프롬프트 원문을 확인해 주세요.')));
  for(const table of parsed.tables){const item=highlight(details(`${table.owner}.${table.name} · ${table.columns.length}`),'definition');item.children[0].append(badge('definition'));item.append(comment(table.comment,table.comment));columns(item,table.columns,table);
    if(!table.complete)item.append(node('p',t('inspect.parseUnknown','이 형식은 구조화하지 못했습니다. 프롬프트 원문을 확인해 주세요.')));
    const raw=details(t('inspect.definition','정의 원문'));raw.append(pre(table.raw));item.append(raw);tables.append(item);}
  const examples=details(t('inspect.promptFeedback','프롬프트에 선택된 Feedback 예제')+(parsed.examplesParsed?` · ${parsed.examples.length}`:''));examples.open=true;
  if(!parsed.examplesParsed)examples.append(node('p',t('inspect.parseUnknown','이 형식은 구조화하지 못했습니다. 프롬프트 원문을 확인해 주세요.')));
  for(const example of parsed.examples){const item=highlight(details(example.user_prompt),'feedback');item.children[0].append(badge('feedback',t('inspect.highlightSelected','프롬프트 예제 포함')));if(example.user_prompt===outcome.question)item.append(node('p',t('inspect.exact','질문 완전 일치')));item.append(pre(example.sql_query));examples.append(item);}
  host.append(tables,examples);
}

export function mountInspection(root,{post,isBusy,setBusy,selected,question,prompt,promptCompatible=()=>true,generated=()=>null,generatedCompatible=()=>true,message}){
  const get=name=>root.querySelector(`[data-inspect-${name}]`);let value=null,comparedPrompt=null,comparedSql=null,filterInput=null;
  const matching=()=>inspectionMatches(value,selected(),question());
  const comparable=()=>{const current=prompt();return matching()&&promptCompatible()&&current&&!current.error&&sameProfile(value.profile,current.profile)&&value.question===current.question?current:null;};
  const comparableSql=()=>{const current=generated();return matching()&&generatedCompatible()&&current?.action==='SQL'&&sameProfile(value.profile,current.profile)&&value.question===current.question?current:null;};
  function controls(){if(value&&(comparable()!==comparedPrompt||comparableSql()!==comparedSql)){render(true);return;}get('load').disabled=isBusy()||!selected()||!question().trim();
    for(const e of get('body').querySelectorAll('button,input'))e.disabled=isBusy()||!matching();
    get('stale').textContent=value&&!matching()?t('inspect.stale','질문 또는 프로필이 바뀌었습니다. 참고정보를 다시 조회해 주세요.'):'';
  }
  async function request(path,data){if(isBusy())return;setBusy(true);message('');try{value=await post('inspection'+path,data);render();}catch(ex){message(ex.message,true);}finally{setBusy(false);controls();}}
  function render(preserveFilter=false){
    const draft=preserveFilter?filterInput?.value:null;filterInput=null;comparedPrompt=comparable();comparedSql=comparableSql();
    const host=get('body');host.replaceChildren();get('body').hidden=!value;if(!value){controls();return;}
    const p=value.profile.selection;
    host.append(node('p',`${p.owner}.${p.name} · ${new Date(value.checkedAt).toLocaleString()}`,'app-filter-message'),pre(value.question));
    const settings=details(t('inspect.options','참고정보 옵션 / 허용 객체'));settings.open=true;
    for(const key of ['comments','annotations','constraints','object_list_mode','enforce_object_list'])settings.append(node('p',`${key}: ${value.settings[key]??t('inspect.unset','미설정 (기본값 확인 필요)')}`));
    const raw=details('object_list');raw.append(pre(value.settings.object_list));settings.append(raw);host.append(settings);
    const sqlReferences=node('div');renderSqlReferences(sqlReferences,comparedSql);host.append(sqlReferences);
    highlightLegend(host,true);
    if(!comparedPrompt)host.append(node('p',t('inspect.promptOptional','프롬프트 포함 여부는 별도 비교 정보입니다. 프롬프트를 조회하지 않아도 생성 SQL의 테이블 참조는 초록색으로 표시합니다.'),'app-filter-message'));
    const objects=details(t('inspect.currentTables','현재 DB 테이블·컬럼'));objects.open=true;
    objects.append(node('p',t('inspect.metadataHelp','허용 목록은 실제 프롬프트 포함 목록과 다릅니다. 객체를 열어 현재 DB 설명을 조회하세요. 코멘트는 옵션이 꺼져 있어도 비교용으로 표시하며, Annotation은 옵션이 true일 때만 조회합니다.'),'app-filter-message'));
    if(!value.objects.length)objects.append(node('p',t('inspect.noObjects','명시된 객체 목록이 없습니다. 자동 선택 모드는 프롬프트 원문에서 확인해 주세요.')));
    const compare=comparedPrompt?parseShowprompt(comparedPrompt.text):null;
    for(const object of value.objects){
      const item=details(`${object.owner}.${object.name??'*'}`);
      const loaded=value.tables.filter(v=>v.owner===object.owner&&(object.name===null||v.name===object.name));
      const referenced=sqlTable(comparedSql?.sqlReferences,object);
      if(referenced){highlight(item,'sql');item.children[0].append(badge('sql'));}
      if(promptTable(compare,object)){if(!referenced)highlight(item,'definition');item.children[0].append(badge('definition'));}
      if(compare?.tablesParsed&&object.name!==null){const found=compare.tables.some(v=>v.owner===object.owner&&v.name===object.name);item.append(node('p',found?t('inspect.inPrompt','현재 SHOWPROMPT 정의에서 확인됨'):t('inspect.notInPrompt','현재 SHOWPROMPT 정의에서 확인되지 않음 (원문 확인 필요)'),'app-filter-message'));}
      if(object.name!==null)item.append(button(t('inspect.loadTable','테이블·컬럼 조회'),()=>request('/table',{id:value.id,...object})));
      else{const input=node('input');input.className='form-control';input.maxLength=128;input.placeholder=t('inspect.tableName','테이블 이름');input.setAttribute('aria-label',input.placeholder);item.append(input,button(t('inspect.loadTable','테이블·컬럼 조회'),()=>request('/table',{id:value.id,owner:object.owner,name:input.value})));}
      for(const table of loaded){
        const definition=promptTable(compare,table),heading=node('p',`${table.owner}.${table.name} · ${table.type} · ${new Date(table.checkedAt).toLocaleString()}`,'app-filter-message');
        if(sqlTable(comparedSql?.sqlReferences,table))heading.append(badge('sql'));if(definition)heading.append(badge('definition'));item.open=true;item.append(heading);
        item.append(link('/tables/detail',{schema:table.owner,table:table.name,tab:'comments'},t('inspect.openTable','기존 테이블 상세 열기')));
        if(table.error){item.append(node('p',t('inspect.lookupError','조회 불가 — 존재하지 않는다는 뜻은 아닙니다.')+' · '+table.error,'app-alert is-error'));continue;}
        item.append(comment(table.comment,definition?.comment));columns(item,table.columns,definition);
        const annotations=details('Annotation · '+table.annotationStatus);
        for(const a of table.annotations){annotations.append(node('h4',`${a.column||t('inspect.table','테이블')} · ${a.name}`),pre(a.value));if(a.domainName)annotations.append(node('p',`${a.domainOwner}.${a.domainName}`));}
        if(table.annotationStatus==='LOADED'&&!table.annotations.length)annotations.append(node('p',t('inspect.noAnnotations','조회된 Annotation이 없습니다.')));
        item.append(annotations);
      }
      objects.append(item);
    }
    host.append(objects);
    const feedback=details(t('inspect.registered','현재 등록 Feedback (검색 결과)'));feedback.open=true;
    feedback.append(node('p',t('inspect.feedbackHelp','현재 등록 자료의 문자 검색입니다. Oracle이 프롬프트에 선택한 예제와 다르며, 검색 결과 0건이 전체 Feedback 0건을 의미하지는 않습니다. AI 검토에는 현재 페이지와 열어 본 상세만 포함합니다.'),'app-filter-message'));
    const filter=node('input');filter.className='form-control';filter.maxLength=500;filter.value=value.feedback?.search??value.question;filter.setAttribute('aria-label',t('inspect.search','Feedback 검색어'));
    if(draft!==null&&draft!==undefined)filter.value=draft;filterInput=filter;
    feedback.append(filter,button(t('inspect.searchButton','검색'),()=>request('/feedback',{id:value.id,search:filter.value,page:1})));
    const f=value.feedback;if(f){
      feedback.append(node('p',`${new Date(f.checkedAt).toLocaleString()} · ${f.page}`,'app-filter-message'));
      if(f.search.length<=500)feedback.append(link('/ai-feedback',{schema:p.owner,profile:p.name,search:f.search,page:f.page},t('inspect.openFeedback','기존 Feedback 조회 열기')));
      if(f.error)feedback.append(node('p',f.error==='SEARCH_TOO_LONG'?t('inspect.searchLong','질문이 500자를 초과합니다. 검색어를 직접 지정해 주세요. 질문을 자동으로 자르거나 지우지 않았습니다.'):t('inspect.lookupError','조회 불가 — 존재하지 않는다는 뜻은 아닙니다.')+' · '+f.error,'app-alert is-error'));
      else if(f.missingTable)feedback.append(node('p',t('inspect.noFeedbackTable','프로필의 Feedback 저장 테이블이 조회되지 않습니다.')));
      else if(f.rows){
        if(!f.rows.items.length)feedback.append(node('p',t('inspect.noFeedback','이 검색 조건에 맞는 Feedback이 없습니다.')));
        for(const row of f.rows.items){const item=details(`${row.question} · ${row.type||'—'}`);item.append(pre(row.feedback),button(t('inspect.loadFeedback','Feedback 원문 조회'),()=>request('/feedback/detail',{id:value.id,rowId:row.id})));
          const loaded=value.feedbackDetails.find(d=>d.id===row.id),state=feedbackMatch(compare,row,loaded);if(state){highlight(item,state);item.children[0].append(badge(state));}
          if(loaded){item.open=true;const d=loaded.detail;item.append(node('p',new Date(loaded.checkedAt).toLocaleString(),'app-filter-message'));if(d.question===value.question)item.append(node('p',t('inspect.exact','질문 완전 일치')));
            item.append(node('p','SQL ID: '+(d.sqlId||'—')));
            for(const [label,text,open] of [[t('aitest.question','질문'),d.question,false],['Feedback',d.feedback,false],[t('ui.53818ff46ad2','응답 SQL')+' (response)',d.response,true],[t('ui.b3cee53ed773','Select AI 원문')+' (sql_text)',d.sqlText,false],[t('ui.2e54bc3fafd1','원문 JSON'),d.attributes,false]]){const part=details(label);part.append(pre(text));part.open=open;item.append(part);}}
          feedback.append(item);
        }
        const paging=node('div',undefined,'app-assistant-actions');if(f.page>1)paging.append(button(t('ui.da7e61c67cc5','이전'),()=>request('/feedback',{id:value.id,search:f.search,page:f.page-1})));
        if(f.rows.hasNext&&f.page<1000)paging.append(button(t('ui.aef613c6612d','다음'),()=>request('/feedback',{id:value.id,search:f.search,page:f.page+1})));feedback.append(paging);
      }
    }
    host.append(feedback);controls();
  }
  get('load').addEventListener('click',()=>request('',{profile:selected(),question:question()}));
  return {controls,restore:data=>{value=data??null;render();},invalidate:()=>{value=null;render();},refreshPrompt:()=>{if(value)render(true);}};
}
