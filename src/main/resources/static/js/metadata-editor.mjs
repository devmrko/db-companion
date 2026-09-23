import {t} from './i18n.mjs';
export const byteLength = value => new TextEncoder().encode(value).length;

if (typeof document !== 'undefined') {
  const dialog = document.querySelector('[data-metadata-editor]');
  if (dialog) {
    const form = dialog.querySelector('[data-editor-form]');
    const fields = dialog.querySelector('[data-editor-fields]');
    const column = dialog.querySelector('[data-editor-column]');
    const name = dialog.querySelector('[data-editor-name]');
    const value = dialog.querySelector('[data-editor-value]');
    const message = dialog.querySelector('[data-editor-message]');
    const close = dialog.querySelector('[data-editor-close]');
    const save = dialog.querySelector('[data-editor-save]');
    let state, pending, saving = false, reload = false, version = 0;
    const show = (text, error = false) => {
      message.textContent = text; message.hidden = !text;
      message.className = error ? 'app-alert is-error' : 'app-alert';
    };
    const count = () => { dialog.querySelector('[data-editor-bytes]').textContent = byteLength(value.value); };
    const read = async (url, options) => {
      const response = await fetch(url, {cache: 'no-store', ...options});
      if (response.redirected || response.status === 401) throw new Error(t('ui.43956c4e70d1', "로그인 세션이 만료되었습니다. 입력을 복사한 뒤 다시 로그인해 주세요."));
      const data = await response.json();
      if (!response.ok) throw Object.assign(new Error(data.error || t('ui.8df80d2b80a6', "요청을 처리하지 못했습니다.")), {status: response.status});
      return data;
    };
    document.querySelectorAll('[data-edit-metadata]').forEach(button => button.addEventListener('click', async () => {
      pending?.abort(); pending = new AbortController(); const requestVersion = ++version;
      state = null; reload = false; fields.disabled = true; save.disabled = true;
      name.value = ''; value.value = ''; column.replaceChildren(); count(); show(t('ui.8bf609c884ca', "불러오는 중…"));
      dialog.querySelector('[data-editor-object]').textContent = `${dialog.dataset.schema}.${dialog.dataset.table}`;
      dialog.showModal();
      const url = new URL(dialog.dataset.url, window.location.href);
      url.searchParams.set('schema', dialog.dataset.schema); url.searchParams.set('table', dialog.dataset.table);
      url.searchParams.set('kind', button.dataset.editMetadata);
      if (button.dataset.column) url.searchParams.set('column', button.dataset.column);
      if (button.dataset.name) url.searchParams.set('name', button.dataset.name);
      try {
        const data = await read(url, {signal: pending.signal, headers: {Accept: 'application/json'}});
        if (requestVersion !== version || !dialog.open) return;
        state = data;
        const targets = data.mode === 'add' ? ['', ...data.columns] : [data.target.column || ''];
        targets.forEach(item => { const option = document.createElement('option'); option.value = item; option.textContent = item || t('ui.3d721f9ac601', "테이블"); column.append(option); });
        column.value = data.target.column || ''; column.disabled = data.mode !== 'add';
        dialog.querySelector('[data-editor-name-group]').hidden = data.kind !== 'annotation';
        name.value = data.name || ''; name.readOnly = data.mode !== 'add';
        value.value = data.value || ''; count();
        dialog.querySelector('[data-editor-hint]').textContent = data.kind === 'annotation'
          ? t('ui.295a8d77a3b1', "값을 비우면 이름만 있는 Annotation으로 저장합니다. 한 항목씩 반영됩니다.")
          : t('ui.b911ac018803', "저장하면 기존 코멘트를 교체합니다. 빈 코멘트 저장은 지원하지 않습니다.");
        fields.disabled = false; save.disabled = false; show(''); value.focus();
      } catch (error) { if (error.name !== 'AbortError' && requestVersion === version) show(error.message, true); }
    }));
    value.addEventListener('input', count);
    form.addEventListener('submit', async event => {
      event.preventDefault();
      if (!state || saving || save.disabled) return;
      if (byteLength(value.value) > 4000) { show(t('ui.edeb6964d6c7', "내용은 UTF-8 기준 4000바이트 이내로 입력해 주세요."), true); return; }
      if (state.kind === 'comment' && !value.value.trim()) { show(t('ui.e9de1749a7ee', "코멘트를 입력해 주세요."), true); return; }
      if (state.kind === 'annotation' && !name.value.trim()) { show(t('ui.abe32d6cdd6a', "Annotation 이름을 입력해 주세요."), true); return; }
      const csrf = dialog.querySelector('[data-editor-csrf]');
      saving = true; save.disabled = true; close.disabled = true; show(t('ui.88daaeedfe4c', "저장 중…"));
      try {
        const result = await read(dialog.dataset.url, {method: 'POST', headers: {'Content-Type': 'application/json', Accept: 'application/json', [csrf.dataset.csrfHeader]: csrf.value},
          body: JSON.stringify({schema: state.target.schema, table: state.target.table, column: column.value || null,
            kind: state.kind, mode: state.mode, name: name.value, value: value.value, version: state.version})});
        show(result.message, !result.verified); reload = true;
        // A completed or uncertain DDL must not be resubmitted from the same editor state.
        save.disabled = true;
      } catch (error) {
        show(error.message, true);
        // Fetch errors can occur after the DB committed. Preserve input and require a fresh read.
        reload = error.status !== 400; save.disabled = error.status !== 400;
      } finally { saving = false; close.disabled = false; }
    });
    dialog.addEventListener('cancel', event => { if (saving) event.preventDefault(); });
    close.addEventListener('click', () => { if (!saving) dialog.close(); });
    dialog.addEventListener('close', () => { ++version; pending?.abort(); if (reload) window.location.reload(); });
  }
}
