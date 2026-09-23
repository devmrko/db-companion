import {t} from './i18n.mjs';
export function pageOf(items, query = '', requestedPage = 1) {
  const term = query.trim().toLowerCase();
  const filtered = items.filter(item => [item.name, item.description]
    .some(value => (value ?? '').toLowerCase().includes(term)));
  const pages = Math.ceil(filtered.length / 10);
  const page = Math.max(1, Math.min(requestedPage, pages || 1));
  const start = (page - 1) * 10;
  return { items: filtered.slice(start, start + 10), total: filtered.length,
    page, pages, from: filtered.length ? start + 1 : 0, to: Math.min(start + 10, filtered.length) };
}

export function profileTables(items, schema, objectList) {
  if (objectList == null || objectList.trim() === '') throw new Error(t('ui.45bfc3b1cf86', "object_list가 등록되지 않아 필터링할 수 없습니다."));
  let objects;
  try { objects = JSON.parse(objectList); } catch (_) { throw new Error(t('ui.a6cc6b2d60e9', "object_list의 JSON 형식을 확인해 주세요.")); }
  const identifier = value => value.startsWith('"') && value.endsWith('"')
    ? value.slice(1, -1).replaceAll('""', '"') : value.toUpperCase();
  if (!Array.isArray(objects) || objects.some(value => !value || typeof value.owner !== 'string'
      || !value.owner || (value.name !== undefined && (typeof value.name !== 'string' || !value.name)))) {
    throw new Error(t('ui.75e64338ee44', "object_list의 owner/name 형식을 확인해 주세요."));
  }
  const scoped = objects.filter(value => identifier(value.owner) === schema);
  if (scoped.some(value => value.name === undefined)) return items;
  const names = new Set(scoped.map(value => identifier(value.name)));
  return items.filter(item => names.has(item.name));
}

if (typeof document !== 'undefined') {
  document.querySelectorAll('[data-table-list]').forEach(root => {
    const items = Array.from(root.querySelectorAll('[data-table-row]'), row => ({
      name: row.dataset.name, description: row.dataset.description, row
    }));
    const input = root.querySelector('[data-table-filter]');
    const profile = root.querySelector('[data-profile-filter]');
    const message = root.querySelector('[data-profile-message]');
    let scopedItems = items;
    let requestVersion = 0;
    const showMessage = text => { message.textContent = text; message.hidden = !text; };
    const previous = root.querySelector('[data-page-prev]');
    const next = root.querySelector('[data-page-next]');
    let currentPage = 1;
    const render = () => {
      const result = pageOf(scopedItems, input.value, currentPage);
      currentPage = result.page;
      const visible = new Set(result.items);
      items.forEach(item => { item.row.hidden = !visible.has(item); });
      root.querySelector('[data-filter-empty]').hidden = items.length === 0 || result.total !== 0;
      root.querySelector('[data-page-summary]').textContent = t('ui.f32c9f13d498', "{0}–{1} / {2}개", result.from, result.to, result.total);
      root.querySelector('[data-page-number]').textContent = `${result.pages ? result.page : 0} / ${result.pages}`;
      previous.disabled = result.page <= 1;
      next.disabled = result.page >= result.pages;
    };
    input.addEventListener('input', () => { currentPage = 1; render(); });
    const readProfiles = async name => {
      const url = new URL(root.dataset.profilesUrl, window.location.href);
      url.searchParams.set('schema', root.dataset.schema);
      if (name !== undefined) url.searchParams.set('profile', name);
      const response = await fetch(url, {headers: {Accept: 'application/json'}});
      if (response.redirected) throw new Error(t('ui.b9c067f345b1', "로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || t('ui.a4ddf9028813', "프로필을 불러오지 못했습니다."));
      return data;
    };
    profile.addEventListener('change', async () => {
      const version = ++requestVersion;
      currentPage = 1;
      if (!profile.value) { scopedItems = items; showMessage(''); render(); return; }
      scopedItems = []; render(); showMessage(t('ui.599db5420e0e', "프로필 대상 테이블을 확인하고 있습니다."));
      try {
        const data = await readProfiles(profile.value);
        if (version !== requestVersion) return;
        scopedItems = profileTables(items, root.dataset.schema, data.objectList);
        showMessage(scopedItems.length ? '' : t('ui.f9563e13e669', "현재 스키마에 해당하는 테이블이 없습니다."));
      } catch (error) {
        if (version !== requestVersion) return;
        scopedItems = []; showMessage(error.message);
      }
      currentPage = 1; render();
    });
    readProfiles().then(profiles => {
      profiles.forEach(value => {
        const option = document.createElement('option');
        option.value = value.name; option.textContent = value.name;
        profile.append(option);
      });
      profile.disabled = false;
    }).catch(error => showMessage(error.message));
    previous.addEventListener('click', () => { currentPage--; render(); });
    next.addEventListener('click', () => { currentPage++; render(); });
    render();
    root.querySelectorAll('[data-list-controls]').forEach(control => { control.hidden = false; });
  });
}
