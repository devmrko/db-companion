import {t} from './i18n.mjs';
export function sameHistoryItem(a, b) {
  return ['schema', 'table', 'column', 'kind', 'annotationName'].every(key => (a[key] ?? null) === (b[key] ?? null));
}

export function selectHistoryVersion(current, row, side) {
  if (!['left', 'right'].includes(side)) throw new Error('Invalid comparison side');
  if (!current || (side === 'left' && !sameHistoryItem(current.left.row, row)))
    return {left: {row, phase: 'before'}, right: {row, phase: 'after'}};
  if (!sameHistoryItem(current.left.row, row))
    throw new Error(t('ui.af709ad4fb04', "같은 항목끼리 비교할 수 있습니다. 다른 항목은 기준부터 선택해 주세요."));
  return {...current, [side]: {row, phase: current[side].phase}};
}

export function readHistorySnapshot(raw) {
  try {
    const value = JSON.parse(raw);
    if (!value || typeof value.exists !== 'boolean' || !Object.hasOwn(value, 'value')
        || (value.value !== null && typeof value.value !== 'string') || (!value.exists && value.value !== null))
      throw new Error('Invalid history snapshot');
    return {...value, valid: true};
  } catch { return {valid: false, raw: raw ?? ''}; }
}

// Bounded grapheme diff: common ends first, then LCS only for a small middle.
const segmenter = typeof Intl.Segmenter === 'function' ? new Intl.Segmenter('ko', {granularity: 'grapheme'}) : null;
const characters = value => segmenter ? Array.from(segmenter.segment(value), part => part.segment) : Array.from(value);
export function diffHistoryText(before, after) {
  const a = characters(before), b = characters(after), parts = [];
  const append = (kind, text) => {
    if (!text) return;
    if (parts.at(-1)?.kind === kind) parts.at(-1).text += text;
    else parts.push({kind, text});
  };
  let start = 0, aEnd = a.length, bEnd = b.length;
  while (start < aEnd && start < bEnd && a[start] === b[start]) start++;
  while (aEnd > start && bEnd > start && a[aEnd - 1] === b[bEnd - 1]) { aEnd--; bEnd--; }
  append('equal', a.slice(0, start).join(''));
  const n = aEnd - start, m = bEnd - start;
  const coarse = n * m > 250_000;
  if (!n || !m || coarse) {
    append('removed', a.slice(start, aEnd).join(''));
    append('added', b.slice(start, bEnd).join(''));
  } else {
    const width = m + 1, scores = new Uint32Array((n + 1) * width);
    for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--)
      scores[i * width + j] = a[start + i] === b[start + j]
        ? scores[(i + 1) * width + j + 1] + 1
        : Math.max(scores[(i + 1) * width + j], scores[i * width + j + 1]);
    let i = 0, j = 0;
    while (i < n || j < m) {
      if (i < n && j < m && a[start + i] === b[start + j]) { append('equal', a[start + i]); i++; j++; }
      else if (i < n && (j === m || scores[(i + 1) * width + j] >= scores[i * width + j + 1]))
        append('removed', a[start + i++]);
      else append('added', b[start + j++]);
    }
  }
  append('equal', a.slice(aEnd).join(''));
  return {parts, coarse};
}

export function compareHistorySnapshots(leftRaw, rightRaw) {
  const left = readHistorySnapshot(leftRaw), right = readHistorySnapshot(rightRaw);
  if (!left.valid || !right.valid) return {left, right, kind: 'invalid', parts: [], coarse: false};
  const kind = !left.exists && right.exists ? 'added' : left.exists && !right.exists ? 'removed'
    : left.exists === right.exists && left.value === right.value ? 'equal' : 'changed';
  return {left, right, kind, ...diffHistoryText(left.value ?? '', right.value ?? '')};
}

const node = (tag, text = '', className) => {
  const result = document.createElement(tag);
  result.textContent = text;
  if (className) result.className = className;
  return result;
};
const itemLabel = row => `${row.column ?? t('ui.3d721f9ac601', "테이블")} · ${row.kind === 'COMMENT' ? t('ui.eed74758dafd', "코멘트") : 'Annotation'}${row.annotationName ? ' / ' + row.annotationName : ''}`;

export function mountHistoryComparison(entries) {
  let selected = null, activator;
  const panel = node('section', '', 'app-history-compare-panel'); panel.hidden = true;
  panel.setAttribute('aria-label', t('ui.a236a7078035', "항목별 버전 비교"));
  const header = node('div', '', 'app-history-compare-header');
  const heading = node('h3', t('ui.e884c7c67060', "버전 비교")); heading.tabIndex = -1;
  const close = node('button', t('ui.2f9e84e0d68e', "비교 닫기"), 'btn app-btn app-btn-quiet'); close.type = 'button';
  header.append(heading, close);
  const target = node('p', '', 'app-history-compare-target');
  const notice = node('p', t('ui.50580640f12e', "항목별 기록을 비교합니다. 이력 OFF 기간의 변경은 포함되지 않습니다."), 'app-muted');
  const selectionError = node('p', '', 'app-history-compare-error'); selectionError.setAttribute('role', 'alert');
  const result = node('p', '', 'app-history-compare-result'); result.setAttribute('role', 'status');
  const columns = node('div', '', 'app-history-comparison');
  const controls = {};
  for (const [side, title] of [['left', t('ui.d4191a97067b', "기준 버전")], ['right', t('ui.e766337009ea', "비교 버전")]]) {
    const column = node('div', '', 'app-history-version');
    const select = node('select', '', 'form-select app-select'); select.id = `history-compare-${side}`;
    const label = node('label', title); label.htmlFor = select.id;
    const metadata = node('p', '', 'app-muted');
    const presence = node('p', '', 'app-history-presence');
    const text = node('pre', '', 'app-history-diff');
    column.append(label, select, metadata, presence, text); columns.append(column);
    controls[side] = {select, metadata, presence, text};
    select.addEventListener('change', () => {
      selected = {...selected, [side]: {...selected[side], phase: select.value}};
      selectionError.textContent = ''; render();
    });
  }
  const footnote = node('p', '', 'app-muted app-history-diff-note');
  panel.append(header, target, notice, selectionError, result, columns, footnote);
  entries.before(panel);

  function render() {
    if (!selected) return;
    target.textContent = itemLabel(selected.left.row);
    const raw = side => selected[side].row[`${selected[side].phase}Json`];
    const comparison = compareHistorySnapshots(raw('left'), raw('right'));
    const labels = {equal: t('ui.30c0c8d63c16', "동일"), added: t('ui.b73accca8a4a', "추가"), removed: t('ui.6139b6c3ed73', "삭제"), changed: t('ui.3537f0cc3ec9', "수정"), invalid: t('ui.c3056ea6bec7', "비교 불가")};
    result.textContent = t('ui.45bfb873bdc3', "{0} · 기준 → 비교", labels[comparison.kind]);
    result.dataset.change = comparison.kind;
    for (const side of ['left', 'right']) {
      const {row, phase} = selected[side], view = controls[side], snapshot = comparison[side];
      view.select.replaceChildren(...['before', 'after'].map(value => {
        const option = node('option', t('ui.7e4c9e871e55', "#{0} · 변경 {1}", row.seq, value === 'before' ? t('ui.d9f427c3257e', "전") : t('ui.084a4bd8972f', "후")));
        option.value = value; return option;
      }));
      view.select.value = phase;
      view.metadata.textContent = t('ui.fe9b3082f3de', "이력 기록: {0} · {1}", row.changedAt, row.changedBy);
      view.text.replaceChildren();
      view.presence.textContent = !snapshot.valid ? t('ui.9070bdcbed63', "JSON 형식을 확인해 주세요.") : !snapshot.exists ? t('ui.f6a454f6c46d', "항목 없음")
        : snapshot.value === null ? t('ui.b127908bee20', "값 없음 (NULL)") : snapshot.value === '' ? t('ui.a747ef5ec74d', "빈 문자열") : t('ui.4bbf8f68f323', "값 있음");
      if (!snapshot.valid) view.text.textContent = snapshot.raw;
      else if (comparison.kind === 'invalid') view.text.textContent = snapshot.value ?? '';
      else for (const part of comparison.parts) {
        if ((side === 'left' && part.kind === 'added') || (side === 'right' && part.kind === 'removed')) continue;
        view.text.append(node(part.kind === 'removed' ? 'del' : part.kind === 'added' ? 'ins' : 'span', part.text));
      }
    }
    footnote.textContent = comparison.kind === 'invalid' ? t('ui.e11cad50b02d', "저장된 JSON 원문을 표시합니다.")
      : comparison.coarse ? t('ui.f1e55ed3a6ad', "긴 텍스트는 변경 구간 전체를 강조합니다.")
      : comparison.kind === 'equal' ? t('ui.41f4733b737a', "선택한 두 상태의 값과 존재 여부가 같습니다.")
      : t('ui.0a18357fab9d', "삭제된 문구는 취소선, 추가된 문구는 밑줄로 표시합니다.");
  }
  const reset = () => {
    selected = null; panel.hidden = true; selectionError.textContent = '';
    for (const view of Object.values(controls)) {
      view.select.replaceChildren(); view.metadata.textContent = ''; view.presence.textContent = ''; view.text.replaceChildren();
    }
    activator = null;
  };
  close.addEventListener('click', () => { const previous = activator; reset(); if (previous?.isConnected) previous.focus(); });
  return {
    reset,
    select(row, side, button) {
      try { selected = selectHistoryVersion(selected, row, side); }
      catch (error) { selectionError.textContent = error.message; panel.scrollIntoView({block: 'nearest'}); return; }
      activator = button; selectionError.textContent = ''; panel.hidden = false; render();
      heading.focus({preventScroll: true}); panel.scrollIntoView({block: 'nearest'});
    }
  };
}
