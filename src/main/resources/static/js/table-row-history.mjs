import {t} from './i18n.mjs';
import {valueChange,compactRows} from './profile-diff.mjs';

export function rowHistoryControls(state,busy=false) {
  return {prepare:!!state?.canPrepare,install:!!state?.canInstall,
    toggleVisible:!!state&&['ON','OFF','INVALID'].includes(state.code),
    checked:state?.code==='ON'||state?.canDisable===true,
    disabled:busy||!(state?.canEnable||state?.canDisable)};
}
export function rowHistoryRequest(schema,table,state,action) {
  const flag={prepare:'canPrepare',install:'canInstall',enable:'canEnable',disable:'canDisable'}[action];
  if(!flag||state?.[flag]!==true)throw new Error(t('ui.76684d51aa12','상태를 다시 확인해 주세요.'));
  return {schema,table,tableId:state.tableId,signature:state.signature,action};
}
export function rowChanges(before,after){
  const a=before===null?{}:JSON.parse(before),b=after===null?{}:JSON.parse(after);
  return [...new Set([...Object.keys(a),...Object.keys(b)])].map(key=>valueChange(key,a[key],b[key],Object.hasOwn(a,key),Object.hasOwn(b,key))).filter(Boolean);
}
const labels={ON:['ui.f4054ea64dad','트리거 켜짐'],OFF:['ui.a9fce6f80008','트리거 꺼짐'],NOT_INSTALLED:['ui.c9b6b3d43ae9','트리거 미설치'],
  INVALID:['ui.0862914f278e','추적 비정상'],CONFLICT:['ui.34f15d3f98ec','트리거 확인 필요'],UNSUPPORTED:['rowHistory.unsupported','이력 설치를 지원하지 않는 컬럼이 있습니다.']};
const reasons={OWNER:['rowHistory.owner','관리하려면 {0} 계정으로 로그인해 주세요.'],ARCHIVE:['rowHistory.archive','공통 이력 준비가 필요합니다.'],
  PRIVILEGE:['ui.b30dfffbec92','CREATE TRIGGER 권한이 필요합니다. 관리자에게 요청해 주세요.'],
  CODE:['rowHistory.code','테이블 구조 또는 트리거 코드가 다릅니다. 자동 교체하지 않습니다.'],INVALID:['ui.d0aee68bf15a','트리거 컴파일 또는 공통 이력 상태를 확인해 주세요.']};
const node=(tag,text='',className)=>{const e=document.createElement(tag);e.textContent=text;if(className)e.className=className;return e;};
if(typeof document!=='undefined')document.querySelectorAll('[data-row-history]').forEach(root=>{
  const find=name=>root.querySelector(`[data-rh-${name}]`),dialog=find('dialog');
  // Keep the heading visible before installation, including cached server templates.
  const toolbar=root.querySelector('.app-history-toolbar'),title=node('span',t('rowHistory.title','데이터 이력'),'app-row-history-title');
  const actions=node('div','','app-row-history-actions');
  toolbar.prepend(title);title.after(find('help'));
  find('toggle-label').querySelector('span').classList.add('visually-hidden');
  find('state').classList.add('app-row-history-state');
  for(const button of root.querySelectorAll('[data-rh-action],[data-rh-refresh],[data-rh-history]'))actions.append(button);
  toolbar.append(actions);
  let state=null,busy=false,page=1,more=false,generation=0,pending=null;
  const error=text=>{find('error').textContent=text;find('error').hidden=!text;};
  const status=(text,code='')=>{find('state').textContent=text;find('state').dataset.state=code;};
  function controls(){
    const c=rowHistoryControls(state,busy);find('toggle-label').hidden=!c.toggleVisible;find('toggle').checked=c.checked;find('toggle').disabled=c.disabled;
    root.querySelectorAll('[data-rh-action]').forEach(button=>{button.hidden=!c[button.dataset.rhAction];button.disabled=busy;});
    find('refresh').disabled=busy;find('history').disabled=busy;
  }
  async function api(path='',params={},body,signal){
    const url=new URL(root.dataset.url+path,location.origin);
    if(!body)for(const [k,v] of Object.entries({schema:root.dataset.schema,table:root.dataset.table,...params}))url.searchParams.set(k,v);
    const csrf=find('csrf');
    const response=await fetch(url,{method:body?'POST':'GET',cache:'no-store',credentials:'same-origin',signal,
      headers:body?{'Content-Type':'application/json',[csrf.dataset.csrfHeader]:csrf.value}:{Accept:'application/json'},body:body?JSON.stringify(body):undefined});
    if(response.redirected||response.status===401)throw new Error(t('ui.b9c067f345b1','로그인 세션이 만료되었습니다. 다시 로그인해 주세요.'));
    if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(t('ui.1930407cf479','응답을 확인하지 못했습니다. HTTP {0}',response.status));
    const data=await response.json();if(!response.ok)throw new Error(data.error||`HTTP ${response.status}`);return data;
  }
  function draw(data){
    state=data;status(t(...(labels[data.code]??['ui.c6bdc20546de','상태 확인 불가'])),data.code);find('state').title=data.triggerName??'';
    const reason=reasons[data.reason];find('detail').textContent=[reason?t(...reason,root.dataset.schema):'',data.detail].filter(Boolean).join(' · ');find('detail').hidden=!find('detail').textContent;
    find('columns').textContent=t('rowHistory.columns','보관 컬럼: {0}',data.columns.join(', '));find('excluded').textContent=t('rowHistory.excluded','제외: {0}',data.excluded.join(', '));controls();
  }
  async function refresh(force=false){
    if(busy)return;busy=true;state=null;controls();error('');status(t('ui.145294b1737c','상태 확인 중…'));
    try{draw(await api('/state',{refresh:force}));}catch(ex){status(t('ui.c6bdc20546de','상태 확인 불가'));error(ex.message);}finally{busy=false;controls();}
  }
  async function change(action){
    if(busy)return;
    const prompt=action==='prepare'?t('rowHistory.prepareConfirm','공통 이력 저장소를 준비할까요? 기존 이력은 유지합니다.')
      :action==='disable'?t('ui.2b3d5b2c7b11','트리거를 끌까요? 꺼진 동안의 변경은 기록되지 않습니다. 기존 이력은 유지됩니다.')
      :t('rowHistory.enableConfirm','{0}.{1}의 데이터 이력을 켤까요? 이후 이력 저장이 실패하면 데이터 변경도 중단됩니다. VECTOR 원문은 보관하지 않습니다.',root.dataset.schema,root.dataset.table);
    if(!confirm(prompt)){controls();return;}
    try{const body=rowHistoryRequest(root.dataset.schema,root.dataset.table,state,action);busy=true;controls();error('');draw(await api('',{},body));}
    catch(ex){state=null;status(t('ui.a8d53a72e8a4','작업 결과 확인 필요'));error(ex.message);}finally{busy=false;controls();}
  }
  root.querySelectorAll('[data-rh-action]').forEach(button=>button.addEventListener('click',()=>change(button.dataset.rhAction)));
  find('toggle').addEventListener('change',()=>change(find('toggle').checked?'enable':'disable'));
  const pager=loading=>{find('prev').disabled=loading||page<=1;find('next').disabled=loading||!more;};
  async function history(number){
    pending?.abort();pending=new AbortController();const version=++generation;pager(true);find('entries').replaceChildren();find('comparison').replaceChildren();find('message').textContent=t('ui.8bf609c884ca','불러오는 중…');
    try{
      const data=await api('',{page:number},null,pending.signal);if(version!==generation||!dialog.open)return;
      page=data.page;more=data.more;find('page').textContent=t('ui.25be58ceeac8','{0} 페이지',page);
      find('message').textContent=data.entries.length?'':t('ui.80168c9d1394','보관된 이력이 없습니다.');
      for(const entry of data.entries){
        const article=node('article','','app-history-entry'),button=node('button',t('ui.d8291eb080d2','변경 내용'),'btn app-btn app-btn-quiet');
        const operation={I:t('ui.bb216f104fde','등록'),U:t('ui.3537f0cc3ec9','수정'),D:t('ui.6139b6c3ed73','삭제')}[entry.operation]??entry.operation;
        article.append(node('h3',`#${entry.seq} · ${entry.eventAt} · ${entry.actor}`),node('p',`${operation} · ${t('rowHistory.tableId','테이블 ID {0}',entry.tableId)}`,'app-muted'));
        button.type='button';button.addEventListener('click',()=>choose(entry.seq));article.append(button);find('entries').append(article);
      }
    }catch(ex){if(ex.name!=='AbortError'&&version===generation)find('message').textContent=ex.message;}finally{if(version===generation)pager(false);}
  }
  async function choose(seq){
    pending?.abort();pending=new AbortController();const version=++generation;pager(true);find('comparison').replaceChildren();find('message').textContent=t('ui.8bf609c884ca','불러오는 중…');
    try{
      const entry=await api('/entry',{seq},null,pending.signal);if(version!==generation||!dialog.open)return;
      const panel=node('section','','app-history-compare-panel');panel.append(node('h3',t('ui.9b94d4838419','#{0} · 변경 전 → 변경 후',seq)));
      const changes=rowChanges(entry.beforeJson,entry.afterJson);panel.append(node('p',changes.length?t('ui.c243e5cdb8d6','변경 {0}개 항목 · − 삭제 / + 추가',changes.length):t('ui.a8299983b847','보관된 값의 차이가 없습니다.')));
      for(const change of changes){
        const section=node('section','','app-profile-change'),diff=node('div','','app-unified-diff');section.append(node('h4',change.name));
        for(const row of compactRows(change.rows)){
          if(row.kind==='gap'){diff.append(node('div',t('ui.d6b395610065','⋯ 변경 없는 {0}줄',row.count),'app-diff-gap'));continue;}
          const line=node('div','',`app-diff-line app-diff-${row.kind}`);line.append(node('span',row.oldLine??'','app-diff-number'),node('span',row.newLine??'','app-diff-number'),node('span',row.kind==='added'?'+':row.kind==='removed'?'-':' ','app-diff-sign'),node('span',row.text.replace(/\n$/,''),'app-diff-text'));diff.append(line);
        }
        section.append(diff);panel.append(section);
      }
      const full=node('details');full.append(node('summary',t('ui.e078b14a70bc','전체 보기')));
      for(const [label,raw] of [[t('ui.0c263cac5947','변경 전'),entry.beforeJson],[t('ui.cc34819ae36e','변경 후'),entry.afterJson]])full.append(node('h4',label),node('pre',raw??t('ui.f6a454f6c46d','항목 없음'),'app-history-json'));
      panel.append(full);find('comparison').replaceChildren(panel);find('message').textContent='';
    }catch(ex){if(ex.name!=='AbortError'&&version===generation)find('message').textContent=ex.message;}finally{if(version===generation)pager(false);}
  }
  find('refresh').addEventListener('click',()=>refresh(true));find('history').addEventListener('click',()=>{dialog.showModal();history(1);});
  find('prev').addEventListener('click',()=>history(page-1));find('next').addEventListener('click',()=>history(page+1));find('close').addEventListener('click',()=>dialog.close());
  dialog.addEventListener('close',()=>{++generation;pending?.abort();pending=null;});
  const tip=root.querySelector('#row-history-help');tip.addEventListener('toggle',event=>{
    const open=event.newState==='open';find('help').setAttribute('aria-expanded',String(open));if(!open)return;
    const rect=find('help').getBoundingClientRect();tip.style.left=`${Math.max(10,Math.min(rect.left,window.innerWidth-tip.offsetWidth-10))}px`;tip.style.top=`${Math.max(10,Math.min(rect.bottom+8,window.innerHeight-tip.offsetHeight-10))}px`;
  });
  refresh();
});
