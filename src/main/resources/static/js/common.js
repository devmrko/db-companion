import {t} from './i18n.mjs';
import {editGuard} from './edit-guard.mjs';
'use strict';

document.querySelectorAll('[data-language-form]').forEach(form=>{
  const select=form.querySelector('[data-language-select]'), original=select.value;
  form.querySelector('[data-language-return]').value=location.pathname+location.search;
  form.querySelector('[data-language-submit]').hidden=true;
  let edited=false;
  document.addEventListener('input',event=>{if(!form.contains(event.target)&&event.target.closest('form,dialog'))edited=true;});
  document.addEventListener('change',event=>{if(!form.contains(event.target)&&event.target.closest('dialog'))edited=true;});
  select.addEventListener('change',()=>{
    if(!editGuard.canLeave(()=>confirm(t('language.unsaved','언어를 바꾸면 입력 중인 내용이 사라집니다. 계속할까요?')),edited)){select.value=original;return;}
    form.requestSubmit();
  });
});

document.querySelectorAll('[data-sidebar-toggle]').forEach((button) => {
  const setCollapsed = (collapsed) => {
    document.body.classList.toggle('is-sidebar-collapsed', collapsed);
    button.setAttribute('aria-expanded', String(!collapsed));
    button.setAttribute('aria-label', collapsed ? t('ui.1f213133dc70', "메뉴 펼치기") : t('ui.9f6ec12024c7', "메뉴 접기"));
    button.title = collapsed ? t('ui.1f213133dc70', "메뉴 펼치기") : t('ui.9f6ec12024c7', "메뉴 접기");
  };
  try { setCollapsed(sessionStorage.getItem('sidebarCollapsed') === 'true'); } catch (_) { /* Storage is optional. */ }
  button.addEventListener('click', () => {
    const collapsed = !document.body.classList.contains('is-sidebar-collapsed');
    setCollapsed(collapsed);
    try { sessionStorage.setItem('sidebarCollapsed', String(collapsed)); } catch (_) { /* Storage is optional. */ }
  });
});

document.querySelectorAll('[data-schema-select]').forEach((select) => {
  const original = select.value;
  select.addEventListener('change', () => {
    if (!editGuard.canLeave(() => confirm(t('ontology.discard', '저장하지 않은 변경을 버릴까요?')))) {
      select.value = original;
      return;
    }
    select.form.requestSubmit();
  });
});

document.querySelectorAll('[data-query-select]').forEach((select) => {
  select.addEventListener('change', () => select.form.requestSubmit());
});
