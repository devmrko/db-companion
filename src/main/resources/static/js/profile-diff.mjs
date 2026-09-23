import {t} from './i18n.mjs';
// Presentation only. Stored snapshots and audit requests are never rewritten.
const state = (present, value) => !present ? t('ui.f6a454f6c46d', "항목 없음") : value === null ? 'NULL'
  : value === '' ? t('ui.a747ef5ec74d', "빈 문자열") : typeof value === 'string' ? t('ui.6ca8363c8567', "문자열") : 'JSON';
const textOf = value => typeof value === 'string' ? value : JSON.stringify(value, null, 2);
function fields(data) {
  const result = new Map();
  for (const [key, value] of Object.entries(data)) {
    if (['profile', 'attributes'].includes(key) && value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length) {
      for (const [name, item] of Object.entries(value))
        result.set(JSON.stringify([key, name]), {name: key === 'attributes' ? name : t('ui.9c8719fe0606', "프로필 · {0}", name), value: item});
    } else result.set(JSON.stringify([key]), {name: key, value});
  }
  return result;
}
export function valueChange(name, left, right, leftPresent = true, rightPresent = true) {
  if (leftPresent === rightPresent && JSON.stringify(left) === JSON.stringify(right)) return null;
  const before = leftPresent ? textOf(left) : '', after = rightPresent ? textOf(right) : '';
  const beforeState = state(leftPresent, left), afterState = state(rightPresent, right);
  const result = lineDiff(before, after);
  // Different types/presence may have the same display text (NULL vs literal "null", absent vs empty).
  if (before === after) result.rows = [
    {kind: 'removed', text: before, oldLine: 1, newLine: null},
    {kind: 'added', text: after, oldLine: null, newLine: 1}
  ];
  return {name, before, after, beforeState, afterState, ...result};
}
export function snapshotChanges(left, right) {
  const a = fields(left), b = fields(right), changes = [];
  for (const key of new Set([...a.keys(), ...b.keys()])) {
    const x = a.get(key), y = b.get(key);
    const change = valueChange((y ?? x).name, x?.value, y?.value, a.has(key), b.has(key));
    if (change) changes.push(change);
  }
  return changes;
}

// Keep line terminators in tokens, so trailing newline and CRLF changes are not lost.
const linesOf = text => text.match(/[^\n]*\n|[^\n]+$/g) ?? [];
export function lineDiff(before, after) {
  const a = linesOf(before), b = linesOf(after), edits = [];
  let start = 0, endA = a.length, endB = b.length;
  while (start < endA && start < endB && a[start] === b[start]) start++;
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) { endA--; endB--; }
  const add = (kind, text) => edits.push({kind, text});
  for (let i = 0; i < start; i++) add('equal', a[i]);
  const n = endA - start, m = endB - start, coarse = n * m > 250_000;
  if (!n || !m || coarse) {
    for (let i = start; i < endA; i++) add('removed', a[i]);
    for (let j = start; j < endB; j++) add('added', b[j]);
  } else {
    const width = m + 1, scores = new Uint32Array((n + 1) * width);
    for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--)
      scores[i * width + j] = a[start + i] === b[start + j]
        ? scores[(i + 1) * width + j + 1] + 1
        : Math.max(scores[(i + 1) * width + j], scores[i * width + j + 1]);
    let i = 0, j = 0;
    while (i < n || j < m) {
      if (i < n && j < m && a[start + i] === b[start + j]) { add('equal', a[start + i]); i++; j++; }
      else if (i < n && (j === m || scores[(i + 1) * width + j] >= scores[i * width + j + 1])) add('removed', a[start + i++]);
      else add('added', b[start + j++]);
    }
  }
  for (let i = endA; i < a.length; i++) add('equal', a[i]);
  let oldLine = 0, newLine = 0;
  return {coarse, rows: edits.map(row => ({...row,
    oldLine: row.kind === 'added' ? null : ++oldLine,
    newLine: row.kind === 'removed' ? null : ++newLine}))};
}

export function compactRows(rows, context = 2) {
  if (!Number.isInteger(context) || context < 0 || context > 10) throw new Error('Invalid diff context');
  const visible = new Set();
  rows.forEach((row, i) => {
    if (row.kind !== 'equal') for (let j = Math.max(0, i - context); j <= Math.min(rows.length - 1, i + context); j++) visible.add(j);
  });
  const result = []; let skipped = 0;
  const gap = () => { if (skipped) { result.push({kind: 'gap', count: skipped}); skipped = 0; } };
  rows.forEach((row, i) => { if (visible.has(i)) { gap(); result.push(row); } else skipped++; });
  gap(); return result;
}
