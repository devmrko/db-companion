import {t} from './i18n.mjs';
import {observedTime} from './observed-time.mjs';

// Preserve provenance: a snapshot recorder is not necessarily the person who changed it.
export function changeHistoryFields({actor,at,content,role='change'}) {
  const keys=role==='record'?['recorder','recordedAt','recordedContent']
    :role==='request'?['requester','requestedAt','requestedContent']:['actor','at','content'];
  const fallback={actor:'변경자',at:'변경 시각',content:'변경 내용',recorder:'기록자',recordedAt:'기록 시각',recordedContent:'기록 내용',requester:'요청자',requestedAt:'요청 시각',requestedContent:'요청 내용'};
  const unknown=t('changeHistory.unknown','미확인');
  const values=[actor||unknown,at?observedTime(at):unknown,content||unknown];
  return keys.map((key,index)=>({label:t('changeHistory.'+key,fallback[key]),value:values[index]}));
}

// Render via textContent at call sites; never interpret stored actors/content as HTML.
export function changeHistorySummary(entry) {
  return changeHistoryFields(entry).map(field=>`${field.label}: ${field.value}`).join(' · ');
}
