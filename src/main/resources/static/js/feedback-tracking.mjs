import {t} from './i18n.mjs';
import {snapshotChanges,compactRows} from './profile-diff.mjs';

export function trackingActions(state={}) {
  state ??= {};
  return {
    prepare:{visible:state.archive!==undefined&&state.archive!=='READY',enabled:state.canPrepare===true},
    install:{visible:state.code==='NOT_INSTALLED',enabled:state.canInstall===true},
    enable:{visible:state.code==='OFF',enabled:state.canEnable===true},
    disable:{visible:state.code==='ON'||state.canDisable===true,enabled:state.canDisable===true}
  };
}
export function feedbackChanges(beforeJson,afterJson) {
  return snapshotChanges(beforeJson===null?{}:JSON.parse(beforeJson),afterJson===null?{}:JSON.parse(afterJson));
}
export function trackingRequest(schema,profile,state,action) {
  if(!state||!trackingActions(state)[action]?.enabled)throw new Error(t('ui.76684d51aa12', "상태를 다시 확인해 주세요."));
  return {schema,profile,profileId:state.profileId,tableId:state.tableId,action};
}
const node=(tag,text='',className)=>{const element=document.createElement(tag);element.textContent=text;if(className)element.className=className;return element;};
if(typeof document!=='undefined')document.querySelectorAll('[data-feedback-tracking]').forEach(root=>{
  const find=name=>root.querySelector(`[data-ft-${name}]`),dialog=find('dialog');
  let state=null,busy=false,page=1,more=false,generation=0,pending=null;
  const message=(text,error=false)=>{find('error').textContent=error?text:'';find('error').hidden=!error;};
  function controls() {
    const actions=trackingActions(state);
    root.querySelectorAll('[data-ft-action]').forEach(button=>{const action=actions[button.dataset.ftAction];button.hidden=!action.visible;button.disabled=busy||!action.enabled;});
    find('refresh').disabled=busy;find('history').disabled=busy;
  }
  async function api(path,params={},body) {
    const url=new URL(root.dataset.url+path,location.origin);
    if(!body)for(const [key,value] of Object.entries({schema:root.dataset.schema,profile:root.dataset.profile,...params}))url.searchParams.set(key,value);
    const csrf=find('csrf');
    const response=await fetch(url,{method:body?'POST':'GET',cache:'no-store',credentials:'same-origin',
      headers:body?{'Content-Type':'application/json',[csrf.dataset.csrfHeader]:csrf.value}:{},body:body?JSON.stringify(body):undefined,
      signal:body?undefined:pending?.signal});
    if(response.redirected||response.status===401)throw new Error(t('ui.b9c067f345b1', "로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
    if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(t('ui.1930407cf479', "응답을 확인하지 못했습니다. HTTP {0}", response.status));
    const data=await response.json();if(!response.ok)throw new Error(data.error||`HTTP ${response.status}`);return data;
  }
  function drawState(data) {
    state=data;find('state').textContent=data.label;find('state').title=data.triggerName||'';
    find('detail').textContent=data.detail;find('detail').hidden=!data.detail;
    find('state').className=['INVALID','CONFLICT'].includes(data.code)?'app-alert is-error':'app-muted';controls();
  }
  async function refresh() {
    if(busy)return;busy=true;state=null;controls();message('');find('state').textContent=t('ui.145294b1737c', "상태 확인 중…");
    try{drawState(await api('/state'));}catch(error){find('state').textContent=t('ui.c6bdc20546de', "상태 확인 불가");message(error.message,true);}
    finally{busy=false;controls();}
  }
  root.querySelectorAll('[data-ft-action]').forEach(button=>button.addEventListener('click',async()=>{
    if(busy)return;const action=button.dataset.ftAction;
    const prompt=action==='prepare'?t('ui.f158fed8c60b', "{0}의 공통 이력을 준비할까요? 기존 이력은 유지하며 Feedback 저장을 위한 제약을 확장합니다.", root.dataset.schema)
      :action==='disable'?t('ui.2b3d5b2c7b11', "트리거를 끌까요? 꺼진 동안의 변경은 기록되지 않습니다. 기존 이력은 유지됩니다.")
      :t('ui.8ced65e914b7', "{0}.{1}의 트리거를 {2}켤까요? 이후 이력 저장이 실패하면 Feedback 변경도 중단됩니다.", root.dataset.schema, root.dataset.profile, action==='install'?t('ui.cf05d19409e1', "설치하고 "):'');
    if(!confirm(prompt))return;
    const body=trackingRequest(root.dataset.schema,root.dataset.profile,state,action);busy=true;controls();message('');
    try{drawState(await api('',{},body));}
    catch(error){state=null;find('state').textContent=t('ui.a8d53a72e8a4', "작업 결과 확인 필요");message(error.message,true);}
    finally{busy=false;controls();}
  }));
  const historyControls=loading=>{find('prev').disabled=loading||page<=1;find('next').disabled=loading||!more;};
  async function loadHistory(number) {
    pending?.abort();pending=new AbortController();const version=++generation;
    historyControls(true);find('entries').replaceChildren();find('comparison').replaceChildren();find('message').textContent=t('ui.8bf609c884ca', "불러오는 중…");
    try {
      const data=await api('/history',{page:number});if(version!==generation||!dialog.open)return;
      page=data.page;more=data.more;find('page').textContent=t('ui.25be58ceeac8', "{0} 페이지", page);
      find('message').textContent=data.entries.length?'':t('ui.80168c9d1394', "보관된 이력이 없습니다.");
      for(const entry of data.entries) {
        const article=node('article','','app-history-entry'),button=node('button',t('ui.d8291eb080d2', "변경 내용"),'btn app-btn app-btn-quiet');
        article.append(node('h3',`#${entry.seq} · ${entry.eventAt} · ${entry.actor}`),node('p',t('ui.3a119bfbba90', "{0} · 프로필 ID {1}", ({I:t('ui.bb216f104fde', "등록"),U:t('ui.3537f0cc3ec9', "수정"),D:t('ui.6139b6c3ed73', "삭제")})[entry.operation]??entry.operation, entry.profileId),'app-muted'));
        button.type='button';button.addEventListener('click',()=>choose(entry.seq));article.append(button);find('entries').append(article);
      }
    }catch(error){if(error.name!=='AbortError'&&version===generation)find('message').textContent=error.message;}
    finally{if(version===generation)historyControls(false);}
  }
  async function choose(seq) {
    pending?.abort();pending=new AbortController();const version=++generation;
    find('comparison').replaceChildren();find('message').textContent=t('ui.8bf609c884ca', "불러오는 중…");historyControls(true);
    try {
      const entry=await api('/entry',{seq});if(version!==generation||!dialog.open)return;
      const panel=node('section','','app-history-compare-panel');panel.append(node('h3',t('ui.9b94d4838419', "#{0} · 변경 전 → 변경 후", seq)));
      const changes=feedbackChanges(entry.beforeJson,entry.afterJson);
      panel.append(node('p',changes.length?t('ui.c243e5cdb8d6', "변경 {0}개 항목 · − 삭제 / + 추가", changes.length):t('ui.a8299983b847', "보관된 값의 차이가 없습니다.")));
      for(const change of changes) {
        const section=node('section','','app-profile-change'),diff=node('div','','app-unified-diff');section.append(node('h4',change.name));
        for(const row of compactRows(change.rows)) {
          if(row.kind==='gap'){diff.append(node('div',t('ui.d6b395610065', "⋯ 변경 없는 {0}줄", row.count),'app-diff-gap'));continue;}
          const line=node('div','',`app-diff-line app-diff-${row.kind}`);
          line.append(node('span',row.oldLine??'','app-diff-number'),node('span',row.newLine??'','app-diff-number'),node('span',row.kind==='added'?'+':row.kind==='removed'?'-':' ','app-diff-sign'),node('span',row.text.replace(/\n$/,''),'app-diff-text'));diff.append(line);
          if(!row.text.endsWith('\n')&&row.kind!=='equal')diff.append(node('div',t('ui.8840e83f7a84', "줄 끝 개행 없음"),'app-diff-eol'));
        }
        section.append(diff);panel.append(section);
      }
      const full=node('details');full.append(node('summary',t('ui.e078b14a70bc', "전체 보기")));
      for(const [title,raw] of [[t('ui.0c263cac5947', "변경 전"),entry.beforeJson],[t('ui.cc34819ae36e', "변경 후"),entry.afterJson]])full.append(node('h4',title),node('pre',raw===null?t('ui.f6a454f6c46d', "항목 없음"):JSON.stringify(JSON.parse(raw),null,2),'app-history-json'));
      panel.append(full);find('comparison').replaceChildren(panel);find('message').textContent='';
    }catch(error){if(error.name!=='AbortError'&&version===generation)find('message').textContent=error.message;}
    finally{if(version===generation)historyControls(false);}
  }
  find('refresh').addEventListener('click',refresh);
  find('history').addEventListener('click',()=>{dialog.showModal();loadHistory(1);});
  find('prev').addEventListener('click',()=>loadHistory(page-1));find('next').addEventListener('click',()=>loadHistory(page+1));
  find('close').addEventListener('click',()=>dialog.close());dialog.addEventListener('close',()=>{++generation;pending?.abort();pending=null;});
  refresh();
});
