import {t} from './i18n.mjs';
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
  return (detail.fields ?? []).map(field => [String(field.name), field.value == null || field.value === '' ? '—' : String(field.value)]);
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
    let controller, version = 0;
    const clear = () => { content.hidden = true; metadata.replaceChildren(); summary.replaceChildren(); prompt.textContent = ''; response.textContent = ''; };
    dialog.addEventListener('close', () => { version++; controller?.abort(); clear(); });
    root.querySelector('[data-execution-close]').addEventListener('click', () => dialog.close());
    root.querySelectorAll('[data-execution-id]').forEach((button) => button.addEventListener('click', async () => {
      controller?.abort(); controller = new AbortController(); const current = ++version;
      clear(); message.textContent = t('ui.8bf609c884ca', "불러오는 중…"); dialog.showModal();
      try {
        const result = await fetch(detailRequest(root.dataset.executionMode, root.dataset.schema, button.dataset.executionId), {signal: controller.signal, cache: 'no-store', headers: {Accept: 'application/json'}});
        if (result.redirected || !result.headers.get('content-type')?.includes('application/json')) throw new Error(t('ui.9f0bb0f1f663', "다시 로그인해 주세요."));
        const data = await result.json();
        if (!result.ok) throw new Error(data.error || t('ui.e4596b202ad1', "기록을 조회하지 못했습니다."));
        if (current !== version || !dialog.open) return;
        (sourceMode ? sourceFields(data) : sqlMode ? mappedSqlFields(data) : detailFields(data)).forEach(([label, value], index) => {
          const row = document.createElement('div'); const term = document.createElement('dt'); const text = document.createElement('dd');
          term.textContent = label; text.textContent = value; row.append(term, text); (index < 3 ? summary : metadata).append(row);
        });
        if (sourceMode) { prompt.textContent = data.sql ?? '—'; }
        else if (sqlMode) { prompt.textContent = data.originalSql ?? '—'; response.textContent = data.mappedSql ?? '—'; }
        else { prompt.textContent = data.prompt ?? '—'; response.textContent = data.response ?? '—'; }
        content.hidden = false; message.textContent = '';
      } catch (error) {
        if (error.name !== 'AbortError' && current === version && dialog.open) message.textContent = error.message;
      }
    }));
  });
}
