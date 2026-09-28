import {t} from './i18n.mjs';
const label=(key,fallback)=>t('businessGlossary.textState.'+key,fallback);

/** Configuration is optional and explicit; never infer readiness from table existence. */
export function textSetupPresentation(status){
  if(!status)return {text:label('checking','Oracle Text 구성 상태 확인 중…'),showSetup:false};
  if(status.table==='MISSING')return {text:label('tableRequired','Oracle Text 미구성 · 사전 테이블 생성 후 선택적으로 설정할 수 있습니다.'),showSetup:false};
  if(status.table!=='READY')return {text:label('unavailable','Oracle Text 구성 상태 확인 필요 · 새로고침하여 확인해 주세요.'),showSetup:false};
  if(status.text==='READY')return {text:label('ready','Oracle Text 구성 완료 · 형태소 검색 사용 가능'),showSetup:false};
  if(status.text==='MISSING')return {text:label('missing','Oracle Text 미구성 · 정확·별칭 검색은 사용 가능하며, 형태소 검색은 별도 설정이 필요합니다.'),showSetup:true};
  return {text:label('unavailable','Oracle Text 구성 상태 확인 필요 · 새로고침하여 확인해 주세요.'),showSetup:false};
}

export function renderTextSetup(root,status){
  const view=textSetupPresentation(status);
  root.querySelector('[data-glossary-text-status]').textContent=view.text;
  root.querySelector('[data-glossary-text-setup]').hidden=!view.showSetup;
  return view;
}
