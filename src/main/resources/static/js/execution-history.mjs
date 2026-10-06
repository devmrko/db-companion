import {t} from './i18n.mjs';
import {setupCandidates} from './sql-history-candidates.mjs';
export function detailFields(detail) {
  return [
    [t('ui.4a7849864c3b', "프로필"), detail.profile], [t('ui.01dfd369cfa7', "작업"), detail.action], [t('ui.5efd3ddd4584', "생성일"), detail.created], [t('ui.d3a98476e16a', "수정일"), detail.modified],
    [t('ui.318443459dd8', "대화명"), detail.title], [t('ui.a53af20a117e', "대화 ID"), detail.conversationId], [t('ui.b0303d3b58fa', "기록 ID"), detail.id],
    [t('ui.b367c7d58dda', "클라이언트"), detail.clientIdentifier], [t('ui.e23654401285', "클라이언트 IP"), detail.clientIp], [t('ui.6f9f4735de0c', "세션 ID"), detail.sid], [t('ui.7b38c99a949b', "세션 일련번호"), detail.serial],
  ].map(([label, value]) => [label, value == null || value === '' ? '—' : String(value)]);
}

export function mappedSqlFields(detail) {
  return [
    ['SQL ID', detail.sqlId], [t('ui.60a16dfed39e', "변환 SQL ID"), detail.mappedId], [t('ui.2f6ef2954275', "변환일시 (DB)"), detail.translated],
    [t('ui.3be8493652c4', "변환 방식"), detail.method], [t('ui.9576c95da1d3', "매핑 사용 횟수"), detail.useCount], [t('ui.7a4a10f97abd', "SQL 번역 프로필 ID"), detail.translationProfileId],
    [t('ui.301a0a20e3da', "컨테이너 ID"), detail.conId], [t('ui.7c5d2bb1ab6b', "변환 CPU 시간 (DB 원값)"), detail.cpuTime], [t('ui.67d3d08de735', "변환 경과 시간 (DB 원값)"), detail.elapsedTime],
  ].map(([label, value]) => [label, value == null || value === '' ? '—' : String(value)]);
}

export function detailRequest(mode, schema, id) {
  if (mode === 'history') return `/ai-executions/sql/sources/detail?${new URLSearchParams({id})}`;
  return mode === 'sql' ? `/ai-executions/sql/detail?${new URLSearchParams({id})}`
    : `/ai-executions/detail?${new URLSearchParams({schema, id})}`;
}

export function sourceFields(detail) {
  const labels = {
    PARSING_USER_NAME: ['sqlh.detail.parsingUser', '최초 파싱 사용자'],
    PARSING_SCHEMA_NAME: ['sqlh.actor.cache', '파싱 스키마'],
    EXECUTIONS: ['sqlh.detail.executions', '커서 누적 실행 횟수'],
    ELAPSED_SECONDS_TOTAL: ['sqlh.detail.elapsedTotal', '누적 DB 경과 시간 (초)'],
    ELAPSED_SECONDS_PER_EXECUTION: ['sqlh.detail.elapsedAverage', '누적 DB 시간 ÷ 실행 횟수 (초)'],
    CPU_SECONDS_TOTAL: ['sqlh.detail.cpu', '누적 CPU 시간 (초)'],
    PLSQL_SECONDS_TOTAL: ['sqlh.detail.plsql', '누적 PL/SQL 시간 (초)'],
    ROWS_PROCESSED: ['sqlh.detail.rows', '커서 누적 처리 행 수'],
    MODULE: ['sqlh.detail.module', '최초 파싱 모듈'],
    ACTION: ['sqlh.detail.action', '최초 파싱 작업'],
    DBUSERNAME: ['sqlh.detail.auditUser', '감사 기록의 실행 사용자'],
  };
  const priority = ['PARSING_USER_NAME', 'EXECUTIONS', 'ELAPSED_SECONDS_TOTAL', 'ELAPSED_SECONDS_PER_EXECUTION'];
  const fields = detail.fields ?? [];
  const ordered = [...priority.flatMap(name => fields.filter(field => field.name === name)), ...fields.filter(field => !priority.includes(field.name))];
  return ordered.map(field => [labels[field.name] ? t(...labels[field.name]) : String(field.name), field.value == null || field.value === '' ? '—' : String(field.value)]);
}

export function mappingStatus(mapping) {
  switch (mapping?.status) {
    case 'FOUND': return t('sqlh.detail.mappingFound', '원 SQL ID와 컨테이너가 일치하는 Oracle 변환 SQL입니다. 개별 실행별 생성 결과 목록은 아닙니다.');
    case 'NOT_FOUND': return t('sqlh.detail.mappingMissing', '이 SQL ID에 연결된 변환 SQL이 없습니다. 공유 SQL 원문만으로 GENERATE의 반환값을 복원할 수는 없습니다.');
    case 'UNAVAILABLE': return t('sqlh.detail.mappingUnavailable', '현재 계정에서 SQL 매핑을 조회할 수 없습니다. 생성 SQL이 없다는 뜻은 아닙니다.');
    default: return t('sqlh.detail.mappingUnconfirmed', 'SQL 매핑을 확인하지 못했습니다. 호출 SQL과 조회된 통계만 표시합니다.');
  }
}

if (typeof document !== 'undefined') {
  document.querySelectorAll('[data-execution-help]').forEach(button => {
    const id = button.getAttribute('popovertarget'), tip = document.getElementById(id);
    tip.addEventListener('toggle', event => {
      const open = event.newState === 'open'; button.setAttribute('aria-expanded', String(open));
      if (!open) { button.removeAttribute('aria-describedby'); return; }
      button.setAttribute('aria-describedby', id);
      const rect = button.getBoundingClientRect();
      tip.style.left = `${Math.max(10, Math.min(rect.left, window.innerWidth - tip.offsetWidth - 10))}px`;
      tip.style.top = `${Math.max(10, Math.min(rect.bottom + 8, window.innerHeight - tip.offsetHeight - 10))}px`;
    });
  });
  document.querySelectorAll('[data-execution-history]').forEach((root) => {
    const sqlMode = root.dataset.executionMode === 'sql';
    const sourceMode = root.dataset.executionMode === 'history';
    const dialog = root.querySelector('[data-execution-dialog]');
    const message = root.querySelector('[data-execution-message]');
    const content = root.querySelector('[data-execution-detail]');
    const metadata = root.querySelector('[data-execution-metadata]');
    const summary = root.querySelector('[data-execution-summary]');
    const prompt = root.querySelector('[data-execution-prompt]');
    const response = root.querySelector('[data-execution-response]');
    const sourceNote = root.querySelector('[data-execution-source-note]');
    const mappings = root.querySelector('[data-execution-mappings]');
    const mappingItems = root.querySelector('[data-execution-mapping-items]');
    const candidates = setupCandidates(root, dialog);
    let controller, version = 0;
    const clear = () => { content.hidden = true; metadata.replaceChildren(); summary.replaceChildren(); prompt.textContent = ''; response.textContent = ''; if(sourceNote){sourceNote.hidden=true;sourceNote.textContent='';}if(mappings)mappings.hidden=true;mappingItems?.replaceChildren(); };
    dialog.addEventListener('close', () => { version++; controller?.abort(); clear(); });
    root.querySelector('[data-execution-close]').addEventListener('click', () => dialog.close());
    root.querySelectorAll('[data-execution-id]').forEach((button) => button.addEventListener('click', async () => {
      controller?.abort(); controller = new AbortController(); const current = ++version;
      clear(); candidates.reset(); message.textContent = t('ui.8bf609c884ca', "불러오는 중…"); dialog.showModal();
      try {
        const result = await fetch(detailRequest(root.dataset.executionMode, root.dataset.schema, button.dataset.executionId), {signal: controller.signal, cache: 'no-store', headers: {Accept: 'application/json'}});
        if (result.redirected || !result.headers.get('content-type')?.includes('application/json')) throw new Error(t('ui.9f0bb0f1f663', "다시 로그인해 주세요."));
        const data = await result.json();
        if (!result.ok) throw new Error(data.error || t('ui.e4596b202ad1', "기록을 조회하지 못했습니다."));
        if (current !== version || !dialog.open) return;
        (sourceMode ? sourceFields(data) : sqlMode ? mappedSqlFields(data) : detailFields(data)).forEach(([label, value], index) => {
          const row = document.createElement('div'); const term = document.createElement('dt'); const text = document.createElement('dd');
          term.textContent = label; text.textContent = value; row.append(term, text); (index < (sourceMode && data.source==='cache' ? 4 : 3) ? summary : metadata).append(row);
        });
        if (sourceMode) {
          prompt.textContent = data.sql ?? '—';
          candidates.reset(button.dataset.executionId, data.source === 'cache');
          if(sourceNote && data.source==='cache'){
            sourceNote.textContent=t('sqlh.detail.cacheNote','공유 커서의 누적 통계입니다. 검색 기간이나 마지막 1회에 걸린 시간이 아닙니다. 파싱 사용자는 최초 커서 작성자이며 매 실행의 사용자 목록이 아닙니다. 시간에는 파싱·실행·인출이 포함되며 병렬 작업은 합산될 수 있습니다. GENERATE 호출 시간과 생성된 SQL의 조회 시간은 서로 다릅니다.');sourceNote.hidden=false;
          }
          if(mappings && data.mapping){
            mappings.hidden=false;root.querySelector('[data-execution-mapping-status]').textContent=mappingStatus(data.mapping);
            for(const item of data.mapping.items ?? []){
              const entry=document.createElement('details'),heading=document.createElement('summary'),sql=document.createElement('pre');
              heading.textContent=`${item.sqlId ?? '—'} · ${item.translated ?? '—'} · ${item.method ?? '—'}`;
              sql.textContent=item.sql ?? t('sqlh.detail.noSql','변환 SQL 원문이 없습니다.');entry.append(heading,sql);mappingItems.append(entry);
            }
            if(data.mapping.more){const note=document.createElement('p');note.textContent=t('sqlh.detail.mappingMore','최대 10개 매핑을 표시합니다. SQL 매핑 탭에서 범위를 좁혀 확인하세요.');mappingItems.append(note);}
          }
        }
        else if (sqlMode) { prompt.textContent = data.originalSql ?? '—'; response.textContent = data.mappedSql ?? '—'; }
        else { prompt.textContent = data.prompt ?? '—'; response.textContent = data.response ?? '—'; }
        content.hidden = false; message.textContent = '';
      } catch (error) {
        if (error.name !== 'AbortError' && current === version && dialog.open) message.textContent = error.message;
      }
    }));
  });
}
