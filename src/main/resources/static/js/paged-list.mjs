import {t} from './i18n.mjs';
import { pageOf } from './table-list.mjs';

document.querySelectorAll('[data-paged-list]').forEach(root => {
  const items = Array.from(root.querySelectorAll('[data-list-row]'), row => ({row}));
  const previous = root.querySelector('[data-page-prev]');
  const next = root.querySelector('[data-page-next]');
  let currentPage = 1;
  const render = () => {
    const result = pageOf(items, '', currentPage);
    currentPage = result.page;
    const visible = new Set(result.items);
    items.forEach(item => { item.row.hidden = !visible.has(item); });
    root.querySelector('[data-page-summary]').textContent = t('ui.f32c9f13d498', "{0}–{1} / {2}개", result.from, result.to, result.total);
    root.querySelector('[data-page-number]').textContent = `${result.pages ? result.page : 0} / ${result.pages}`;
    previous.disabled = result.page <= 1;
    next.disabled = result.page >= result.pages;
  };
  previous.addEventListener('click', () => { currentPage--; render(); });
  next.addEventListener('click', () => { currentPage++; render(); });
  render();
  root.querySelectorAll('[data-list-controls]').forEach(control => { control.hidden = false; });
});
