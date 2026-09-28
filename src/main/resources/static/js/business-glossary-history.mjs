import {t} from './i18n.mjs';
const label=(key,fallback,...args)=>t('businessGlossary.history.'+key,fallback,...args);
const el=(tag,text,css)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(css)node.className=css;return node;};
export function historyStoragePresentation(storage,table){
  let text;
  if(storage==='READY')text=table==='READY'
    ?label('recording','앱 변경 이력 기록 중 · 저장소 준비 완료 (DBC_APP_RECORD)')
    :label('ready','이력 저장소 준비 완료 · 용어 사전 준비 후 앱 변경을 기록합니다. (DBC_APP_RECORD)');
  else if(storage==='MISSING')text=label('missing','앱 변경 이력을 기록하려면 이력 저장 테이블을 생성해 주세요. (DBC_APP_RECORD)');
  else if(storage===null)text=label('checking','이력 저장소 확인 중…');
  else text=label('unavailable','이력 저장소 상태 확인이 필요합니다. 새로고침해 주세요. (DBC_APP_RECORD)');
  return {text,showSetup:storage==='MISSING',showGuide:storage==='READY'};
}
export function snapshotFields(term){
  return term?[
    [t('businessGlossary.term','대표 용어'),term.term],
    [t('businessGlossary.aliases','별칭'),term.aliases.join('\n')],
    [t('businessGlossary.definition','업무 정의'),term.definition],
    [t('businessGlossary.criteria','집계·필터·SQL 기준 (실행하지 않음)'),term.criteria],
    [t('businessGlossary.state','상태'),term.enabled?t('businessGlossary.active','사용'):t('businessGlossary.inactive','비활성')]
  ]:[];
}
function snapshot(title,term){
  const section=el('section',undefined,'app-glossary-history-snapshot');section.append(el('h3',title));
  if(!term){section.append(el('p',label('absent','등록 전 내용 없음')));return section;}
  section.append(el('p',`v${term.revision}`,'app-filter-message'));
  const list=el('dl');for(const [name,value] of snapshotFields(term)){list.append(el('dt',name));const dd=el('dd');dd.append(el('pre',value||'—','app-preview-value'));list.append(dd);}section.append(list);return section;
}
export function renderHistory(host,entries){
  for(const entry of entries){
    const c=entry.change,details=el('details',undefined,'app-disclosure');
    details.open=host.childElementCount===0;
    const action=c.operation==='CREATE'?label('created','등록'):label('updated','수정');
    details.append(el('summary',`${action} · ${c.before?'v'+c.before.revision+' → ':''}v${c.after.revision} · ${entry.recordedAt} · ${entry.actor}`));
    const grid=el('div',undefined,'app-glossary-history-grid');
    grid.append(snapshot(label('before','변경 전'),c.before),snapshot(label('after','변경 후'),c.after));
    details.append(grid);host.append(details);
  }
}
