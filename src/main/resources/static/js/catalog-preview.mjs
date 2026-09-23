import {t} from './i18n.mjs';
import {functionUrl} from './functions.mjs';
export function scalar(value) {
  try { const decoded = JSON.parse(value); return typeof decoded === 'string' ? decoded : value; }
  catch (_) { return value; }
}

export function references(kind, attributes) {
  const values = Object.fromEntries(attributes.map(item => [item.name.toLowerCase(), item.value]));
  const result = [];
  const add = (attribute, type, value) => {
    if (typeof value === 'string' && value.trim()) result.push({attribute, type, name: value.trim()});
  };
  if (kind === 'Agent' || kind === 'Supervisor Agent') add('profile_name', 'profile', scalar(values.profile_name));
  if (kind === 'Tool') {
    add('function', 'routine', scalar(values.function));
    if (scalar(values.tool_type)?.toUpperCase() === 'SQL') {
      try { add('tool_params', 'profile', JSON.parse(values.tool_params).profile_name); }
      catch (_) { /* Keep the original invalid JSON visible; never guess a profile. */ }
    }
  }
  return result;
}

if (typeof document !== 'undefined') {
  const dialog = document.querySelector('[data-catalog-preview]');
  if (dialog) {
    const title = dialog.querySelector('[data-preview-title]');
    const body = dialog.querySelector('[data-preview-body]');
    let pending;
    let version = 0;
    const node = (tag, text, className) => {
      const result = document.createElement(tag);
      if (text !== undefined) result.textContent = text ?? '—';
      if (className) result.className = className;
      return result;
    };
    const fields = values => {
      const list = node('dl', undefined, 'app-detail-list');
      values.forEach(([label, value]) => {
        const row = node('div'); row.append(node('dt', label), node('dd', value, 'app-metadata-text')); list.append(row);
      });
      body.append(list);
    };
    const showProfile = data => {
      const profile = data.profiles[0];
      if (!profile) throw new Error(t('ui.6831b13d5ef8', "프로필이 없거나 조회 권한이 없습니다."));
      fields([[t('ui.c55831fbc2ba', "프로필명"), profile.name], [t('ui.3f863f643fcc', "스키마"), dialog.dataset.schema], ['ID', profile.id],
        [t('ui.e10195a1239f', "상태"), profile.status], [t('ui.8c3f651d3083', "설명"), profile.description], [t('ui.5efd3ddd4584', "생성일"), profile.created], [t('ui.d3a98476e16a', "수정일"), profile.modified]]);
      body.append(node('h3', t('ui.286d43f0f3cf', "속성"), 'app-section-title'));
      if (!data.attributes.length) body.append(node('p', t('ui.d14b7f78faa4', "등록된 속성이 없습니다."), 'app-muted'));
      data.attributes.forEach(attribute => {
        body.append(node('h4', attribute.name, 'app-attribute-title'), node('pre', attribute.value, 'app-preview-value'));
      });
    };
    const showRoutine = data => {
      if (!data.definitions.length) {
        body.append(node('p', t('ui.266421021813', "함수·프로시저가 없거나 소스 조회 권한이 없습니다. Synonym과 DB link의 자동 해석은 지원하지 않습니다."), 'app-alert is-warning'));
        return;
      }
      if (data.definitions.length > 1) body.append(node('p', t('ui.ec8647cbd4fb', "동일한 이름으로 해석되는 후보가 여러 개입니다. 소유자와 패키지를 확인해 주세요."), 'app-alert is-warning'));
      data.definitions.forEach(definition => {
        body.append(node('h3', `${definition.owner}.${definition.object}${definition.member ? '.' + definition.member : ''}`, 'app-section-title'));
        const link=node('a',t('functions.open','함수 화면에서 보기'),'btn app-btn app-btn-secondary');
        const route=new URL(dialog.dataset.routineUrl,window.location.href).pathname.replace(/\/catalog-preview\/routine$/,'/db/functions');
        link.href=functionUrl(route,dialog.dataset.schema,definition);body.append(link);
        if (definition.member) body.append(node('p', t('ui.7abfe24b2322', "패키지 전체 명세·본문입니다."), 'app-muted'));
        if (!definition.sections.length) body.append(node('p', t('ui.6d893718182f', "객체는 확인되었으나 소스가 공개되지 않았거나 조회 권한이 없습니다."), 'app-alert is-warning'));
        definition.sections.forEach(section => {
          body.append(node('h4', section.type, 'app-attribute-title'));
          if (/\bwrapped\b/i.test(section.text)) body.append(node('p', t('ui.c22eaf5da82e', "Wrapped 소스는 저장된 형태로 표시합니다."), 'app-muted'));
          body.append(node('pre', section.text, 'app-preview-source'));
        });
      });
    };
    const open = async reference => {
      pending?.abort();
      pending = new AbortController();
      const requestVersion = ++version;
      title.textContent = `${reference.type === 'profile' ? t('ui.e65249eb5f3c', "Select AI 프로필") : t('ui.5ced9df5266a', "함수 정의")} · ${reference.name}`;
      body.replaceChildren(node('p', t('ui.8bf609c884ca', "불러오는 중…"), 'app-muted'));
      body.setAttribute('aria-busy', 'true');
      if (!dialog.open) dialog.showModal();
      try {
        const url = new URL(reference.type === 'profile' ? dialog.dataset.profileUrl : dialog.dataset.routineUrl, window.location.href);
        url.searchParams.set('schema', dialog.dataset.schema); url.searchParams.set('name', reference.name);
        const response = await fetch(url, {headers: {Accept: 'application/json'}, signal: pending.signal, cache: 'no-store'});
        if (response.redirected || response.status === 401) throw new Error(t('ui.b9c067f345b1', "로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || t('ui.2107b379c1ed', "정보를 조회하지 못했습니다."));
        if (requestVersion !== version || !dialog.open) return;
        body.replaceChildren();
        if (reference.type === 'profile') showProfile(data); else showRoutine(data);
      } catch (error) {
        if (error.name === 'AbortError' || requestVersion !== version || !dialog.open) return;
        body.replaceChildren(node('p', error.message, 'app-alert is-error'));
      } finally {
        if (requestVersion === version) body.setAttribute('aria-busy', 'false');
      }
    };
    dialog.querySelector('[data-preview-close]').addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => { ++version; pending?.abort(); body.replaceChildren(); body.setAttribute('aria-busy', 'false'); });
    document.querySelectorAll('[data-component-kind]').forEach(component => {
      const rows = Array.from(component.querySelectorAll('[data-attribute]'));
      const attributes = rows.map(row => ({name: row.dataset.attribute, value: row.querySelector('[data-attribute-value]').textContent}));
      references(component.dataset.componentKind, attributes).forEach(reference => {
        const row = rows.find(row => row.dataset.attribute.toLowerCase() === reference.attribute);
        const value = row.querySelector('[data-attribute-value]');
        const button = node('button', reference.name, 'app-inline-link');
        button.type = 'button'; button.setAttribute('aria-haspopup', 'dialog');
        button.addEventListener('click', () => open(reference));
        if (reference.attribute === 'tool_params') {
          const link = node('div'); link.append(node('span', t('ui.b3a7b2351659', "프로필: ")), button); value.prepend(link);
        } else value.replaceChildren(button);
      });
    });
  }
}
