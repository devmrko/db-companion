import {t} from './i18n.mjs';
import {valueChange,compactRows} from './profile-diff.mjs';
const node=(tag,text='',className)=>{const el=document.createElement(tag);el.textContent=text;if(className)el.className=className;return el;};
export const outcomeLabel=outcome=>({VERIFIED:t('ui.5d9c284e2909', "저장 확인"),UNCERTAIN:t('ui.0f0ec196d600', "결과 확인 필요"),BEFORE_SAVED:t('ui.6ecd5601b0ca', "변경 전 보관 · 저장 결과 미확인")})[outcome]??outcome;
// Support the previously rendered template until the next normal server restart.
// Thymeleaf consumes data-th-* attributes; new markup uses data-tmh-*.
export const historyFallbackSelectors={
  close:'.app-preview-header button',csrf:'.app-history-content input[data-csrf-header]',
  refresh:'.app-history-toolbar > button:first-child',install:'.app-history-toolbar > button:last-child',
  message:'.app-history-content > [role="status"]',
  comparison:'.app-history-content > div:not([class]):nth-of-type(2)',
  entries:'.app-history-content > div:not([class]):nth-of-type(3)',
  prev:'.app-history-pagination > button:first-child',page:'.app-history-pagination > span',
  next:'.app-history-pagination > button:last-child'
};
export function teamChanges(before,after,label='Team') {
  const fields=team=>{
    const result=new Map();
    result.set('exists',{label:t('ui.03ede01cabad', "{0} 존재", label),value:typeof team.exists==='boolean'?team.exists:team.info!=null});
    for(const [key,value] of Object.entries(team.info??team.object??{}))result.set(`info:${key}`,{label:`${label} · ${key}`,value});
    const attributes=Array.isArray(team.attributes)?team.attributes:Object.entries(team.attributes??{}).map(([name,value])=>({name,value}));
    for(const attribute of attributes)result.set(`attribute:${attribute.name}`,{label:attribute.name,value:attribute.value});
    return result;
  };
  const a=fields(before),b=fields(after),changes=[];
  for(const key of new Set([...a.keys(),...b.keys()])) {
    const x=a.get(key),y=b.get(key),change=valueChange((y??x).label,x?.value,y?.value,a.has(key),b.has(key));
    if(change)changes.push(change);
  }
  return changes;
}
export function mountHistory(dialog,{objectMode=false}={}) {
  const find=name=>objectMode?dialog.querySelector(`[data-aoh-${name}]`):(dialog.querySelector(`[data-tmh-${name}]`)??dialog.querySelector(historyFallbackSelectors[name])),base=dialog.dataset.url;
  let target={schema:dialog.dataset.schema,team:dialog.dataset.team};
  const message=find('message'),comparison=find('comparison'),entries=find('entries');
  let page=1,more=false,busy=false,installing=false,pending=null,generation=0,selected=null;
  const show=(text,error=false)=>{message.textContent=text;message.hidden=!text;message.className=error?'app-alert is-error':'app-alert';};
  const controls=()=>{find('prev').disabled=busy||page===1;find('next').disabled=busy||!more;find('refresh').disabled=busy;find('install').disabled=busy;find('close').disabled=installing;entries.querySelectorAll('button').forEach(button=>button.disabled=busy);};
  async function api(path='',query={},body) {
    const url=new URL(base+path,location.origin),csrf=find('csrf');
    if(!body)for(const [key,value] of Object.entries({...target,...query}))url.searchParams.set(key,value);
    const response=await fetch(url,{method:body?'POST':'GET',credentials:'same-origin',cache:'no-store',signal:body?undefined:pending?.signal,
      headers:body?{'Content-Type':'application/json',[csrf.dataset.csrfHeader]:csrf.value}:{},body:body?JSON.stringify(body):undefined});
    if(response.redirected||response.status===401)throw new Error(t('ui.b9c067f345b1', "로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
    if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(t('ui.1930407cf479', "응답을 확인하지 못했습니다. HTTP {0}", response.status));
    const data=await response.json();if(!response.ok)throw new Error(data.error||`HTTP ${response.status}`);return data;
  }
  function drawComparison(entry) {
    comparison.replaceChildren();
    const panel=node('section','','app-history-compare-panel');panel.append(node('h3',t('ui.9b94d4838419', "#{0} · 변경 전 → 변경 후", entry.seq)));
    if(entry.afterJson===null)panel.append(node('p',t('ui.18a8f85b5367', "변경 전 값만 보관돼 있습니다. 현재 값과 저장 결과를 확인해 주세요."),'app-history-compare-error'));
    else {
      const changes=teamChanges(JSON.parse(entry.beforeJson),JSON.parse(entry.afterJson),objectMode?target.kind:'Team');
      panel.append(node('p',changes.length?t('ui.c243e5cdb8d6', "변경 {0}개 항목 · − 삭제 / + 추가", changes.length):t('ui.0ed32259c839', "보관된 변경 전·후 값이 같습니다."),'app-history-compare-result'));
      for(const change of changes) {
        const section=node('section','','app-profile-change'),diff=node('div','','app-unified-diff');section.append(node('h4',change.name));diff.setAttribute('aria-label',t('ui.efea61c83cc5', "{0} 변경분", change.name));
        if(change.beforeState!==change.afterState)section.append(node('p',`${change.beforeState} → ${change.afterState}`,'app-muted'));
        for(const row of compactRows(change.rows)) {
          if(row.kind==='gap'){diff.append(node('div',t('ui.d6b395610065', "⋯ 변경 없는 {0}줄", row.count),'app-diff-gap'));continue;}
          const line=node('div','',`app-diff-line app-diff-${row.kind}`);
          line.append(node('span',row.oldLine??'','app-diff-number'),node('span',row.newLine??'','app-diff-number'),node('span',row.kind==='removed'?'-':row.kind==='added'?'+':' ','app-diff-sign'),node('span',row.text.replace(/\n$/,''),'app-diff-text'));diff.append(line);
          if(!row.text.endsWith('\n')&&row.kind!=='equal')diff.append(node('div',t('ui.8840e83f7a84', "줄 끝 개행 없음"),'app-diff-eol'));
        }
        section.append(diff);panel.append(section);
      }
    }
    const full=node('details');full.append(node('summary',t('ui.e078b14a70bc', "전체 보기")));
    for(const [title,raw] of [[t('ui.0c263cac5947', "변경 전"),entry.beforeJson],[t('ui.cc34819ae36e', "변경 후"),entry.afterJson]])if(raw!==null) {
      full.append(node('h4',title),node('pre',JSON.stringify(JSON.parse(raw),null,2),'app-history-json'));
    }
    panel.append(full);comparison.append(panel);
  }
  async function choose(seq) {
    if(busy)return;const id=++generation;busy=true;controls();show(t('ui.8bf609c884ca', "불러오는 중…"));
    try {
      const entry=await api('/entry',{seq});if(id!==generation||!dialog.open)return;
      selected=seq;drawComparison(entry);show('');
      entries.querySelectorAll('[data-seq]').forEach(button=>{const current=button.dataset.seq===seq;button.textContent=current?t('ui.3622ad893c69', "선택됨"):t('ui.d8291eb080d2', "변경 내용");button.setAttribute('aria-pressed',String(current));});
    }catch(error){if(error.name!=='AbortError'&&id===generation)show(error.message,true);}
    finally{if(id===generation){busy=false;controls();}}
  }
  function drawRows(data) {
    more=data.more;entries.replaceChildren();find('install').hidden=data.installed;
    if(!data.installed)entries.append(node('p',t('ui.7a761d94c083', "공통 이력 보관 테이블을 준비해 주세요."),'app-empty'));
    else if(!data.entries.length)entries.append(node('p',t('ui.80168c9d1394', "보관된 이력이 없습니다."),'app-empty'));
    for(const entry of data.entries) {
      const article=node('article','','app-history-entry'),heading=node('div','','app-history-entry-heading'),button=node('button',selected===entry.seq?t('ui.3622ad893c69', "선택됨"):t('ui.d8291eb080d2', "변경 내용"),'btn app-btn app-btn-quiet');
      button.type='button';button.dataset.seq=entry.seq;button.setAttribute('aria-pressed',String(selected===entry.seq));button.setAttribute('aria-label',t('ui.5ccfb8a9e4c0', "#{0} 변경 내용", entry.seq));button.addEventListener('click',()=>choose(entry.seq));
      heading.append(node('h3',`#${entry.seq} · ${entry.eventAt} · ${entry.actor}`),button);article.append(heading,node('p',`${entry.attribute} · ${outcomeLabel(entry.outcome)}`,'app-muted'));entries.append(article);
    }
    find('page').textContent=t('ui.25be58ceeac8', "{0} 페이지", page);
  }
  async function load(nextPage=1) {
    pending?.abort();pending=new AbortController();const id=++generation;busy=true;controls();show(t('ui.8bf609c884ca', "불러오는 중…"));
    selected=null;comparison.replaceChildren();entries.replaceChildren();find('install').hidden=true;
    try{const data=await api('',{page:nextPage});if(id!==generation||!dialog.open)return;page=nextPage;drawRows(data);show('');}
    catch(error){if(error.name!=='AbortError'&&id===generation)show(error.message,true);}
    finally{if(id===generation){busy=false;controls();}}
  }
  document.querySelectorAll(objectMode?'[data-open-object-history]':'[data-open-team-history]').forEach(button=>button.addEventListener('click',()=>{
    if(objectMode){target={schema:dialog.dataset.schema,kind:button.dataset.kind,name:button.dataset.name};find('title').textContent=t('ui.10ebbf9e50d0', "{0} · {1} · 변경 이력", target.kind, target.name);}
    selected=null;more=false;page=1;comparison.replaceChildren();entries.replaceChildren();find('install').hidden=true;dialog.showModal();load(1);
  }));
  find('refresh').addEventListener('click',()=>load(page));find('prev').addEventListener('click',()=>load(page-1));find('next').addEventListener('click',()=>load(page+1));
  find('install').addEventListener('click',async()=>{
    if(busy||!confirm(t('ui.3e95726292c6', "{0}의 공통 이력 보관 테이블을 준비할까요? 기존 Profile·Team·Agent·Task·Tool 이력을 복사·검증하며 원본은 삭제하지 않습니다.", target.schema)))return;
    busy=true;installing=true;controls();show(t('ui.51ce4c5b5246', "공통 이력 준비 중…"));
    try{const data=await api('/install',{},target);page=1;selected=null;comparison.replaceChildren();drawRows(data);show(t('ui.2feef4b8e5cc', "공통 이력을 준비했습니다."));}
    catch(error){show(error.message,true);find('install').hidden=true;}
    finally{busy=false;installing=false;controls();}
  });
  find('close').addEventListener('click',()=>{if(!installing)dialog.close();});dialog.addEventListener('cancel',event=>{if(installing)event.preventDefault();});
  dialog.addEventListener('close',()=>{++generation;pending?.abort();busy=false;});
}
if(typeof document!=='undefined')document.querySelectorAll('[data-team-history]').forEach(dialog=>mountHistory(dialog));
