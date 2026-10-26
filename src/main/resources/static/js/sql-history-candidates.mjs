import {t} from './i18n.mjs';

export function candidateEvidence(kind) {
  if (kind === 'SESSION') return t('sqlh.candidates.session', 'ASH: 인스턴스·세션·일련번호 일치');
  if (kind === 'COORDINATOR') return t('sqlh.candidates.coordinator', 'ASH: 병렬 조정 인스턴스·세션·일련번호 일치');
  return t('sqlh.candidates.timeOnly', '같은 스키마·가까운 활성 시각만 일치 · 세션 연결 미확인');
}
export function candidateAccess(status) {
  if (status === 'NOT_CHECKED') return t('sqlh.candidates.notChecked', '표시할 후보가 없어 세션 근거 조회는 생략했습니다.');
  if (status === 'AVAILABLE') return t('sqlh.candidates.available', '일부 후보에 ASH 세션 근거가 있습니다. 동일 세션도 다른 요청에 재사용되므로 생성 결과를 증명하지 않습니다.');
  if (status === 'UNAVAILABLE') return t('sqlh.candidates.unavailable', '현재 계정은 ASH 세션 근거를 조회할 수 없습니다. 스키마·시간 기반 후보만 표시합니다.');
  if (status === 'UNCONFIRMED') return t('sqlh.candidates.unconfirmed', 'ASH 조회를 완료하지 못했습니다. 스키마·시간 기반 후보만 표시합니다.');
  return t('sqlh.candidates.noMatch', '연결되는 세션 표본을 확인하지 못했습니다. 표본이 없다는 것이 무관함을 뜻하지는 않습니다.');
}
export function candidateOffset(value) {
  return t('sqlh.candidates.offset', '기준 커서의 마지막 활성 시각 대비 {0}초 (실행 간격·소요시간 아님)', `${value > 0 ? '+' : ''}${value}`);
}
export function renderCandidates(container, data) {
  container.replaceChildren();
  const paragraph = (text, parent = container) => { const p = document.createElement('p'); p.textContent = text; parent.append(p); };
  paragraph(`${data.schema} · ${data.anchorTime} (DB) · ±60s`);
  paragraph(candidateAccess(data.sessionEvidence));
  if (!data.items?.length) paragraph(t('sqlh.candidates.empty', '이 범위에서 표시할 후보를 찾지 못했습니다. 생성 SQL이 없었다는 뜻은 아닙니다.'));
  for (const item of data.items ?? []) {
    const card = document.createElement('article'); card.className = 'app-sql-candidate';
    const title = document.createElement('h4'); title.textContent = item.sqlId; card.append(title);
    paragraph((item.objects ?? []).join(' · '), card);
    paragraph(candidateEvidence(item.evidence), card);
    paragraph(candidateOffset(item.offsetSeconds), card);
    paragraph(t('sqlh.candidates.metrics', '마지막 활성(DB): {0} · 선택 커서 누적 실행: {1}회 · 누적 DB 시간: {2}초', item.lastActive, item.executions ?? '—', item.elapsedSeconds ?? '—'), card);
    const details = document.createElement('details'), heading = document.createElement('summary'), sql = document.createElement('pre');
    heading.textContent = t('sqlh.candidates.sql', 'SQL 원문 보기 (실행하지 않음)'); sql.textContent = item.sql ?? '—';
    details.append(heading, sql); card.append(details); container.append(card);
  }
  if (data.limited) paragraph(t('sqlh.candidates.limited', '검색·분석 또는 표시 한도를 적용했습니다. 전체 후보 목록이 아닙니다.'));
  paragraph(t('sqlh.candidates.omitted', '중복 SQL ID는 합치고, 시스템·앱 관리 조회 또는 분석할 수 없는 SQL은 표시 대상에서 제외합니다. 제외 {0}개. 관련 없다는 판정은 아닙니다.', data.omitted ?? 0));
}
export function setupCandidates(root, dialog) {
  const section = root.querySelector('[data-sql-candidates]');
  if (!section) return {reset() {}};
  const button = section.querySelector('button'), status = section.querySelector('[role="status"]'), results = section.querySelector('[data-sql-candidate-results]');
  let selection = '', version = 0, controller;
  function reset(id = '', enabled = false) {
    version++; controller?.abort(); selection = id; section.hidden = !enabled;
    status.textContent = ''; results.replaceChildren(); button.disabled = false;
  }
  dialog.addEventListener('close', () => reset());
  button.addEventListener('click', async () => {
    if (!selection || button.disabled) return;
    const current = ++version; controller?.abort(); controller = new AbortController();
    button.disabled = true; results.replaceChildren(); status.textContent = t('ui.8bf609c884ca', '불러오는 중…');
    try {
      const response = await fetch(`/ai-executions/sql/sources/candidates?${new URLSearchParams({id: selection})}`, {signal: controller.signal, cache: 'no-store', headers: {Accept: 'application/json'}});
      if (response.redirected || !response.headers.get('content-type')?.includes('application/json')) throw new Error(t('ui.9f0bb0f1f663', '다시 로그인해 주세요.'));
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || t('sqlh.candidates.failed', '후보 조회를 완료하지 못했습니다. 기존 상세 정보는 그대로 확인할 수 있습니다.'));
      if (current !== version || !dialog.open) return;
      renderCandidates(results, data); status.textContent = '';
    } catch (error) {
      if (error.name !== 'AbortError' && current === version && dialog.open) status.textContent = error.message;
    } finally { if (current === version) button.disabled = false; }
  });
  return {reset};
}
