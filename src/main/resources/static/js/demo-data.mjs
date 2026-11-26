import {assistantApi, assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';

export function demoDataInput(formData) {
  const input = Object.fromEntries(formData);
  input.rows = Number(input.rows);
  const {scenario, questions, rules, date, rows, ...target} = input;
  return {...target, criteria: {scenario, questions, rules, date, rows}};
}

export function mountDemoData(root) {
  const form = root.querySelector('[data-demo-form]');
  const field = name => form.elements.namedItem(name);
  const status = root.querySelector('[data-demo-message]');
  const button = root.querySelector('[data-demo-build]');
  const section = root.querySelector('[data-demo-review]');
  let output = null, revision = 0, walletRevision = 0, busy = false;
  const message = key => t('demoData.export.' + key, key);
  const inputSection = root.querySelector('[data-demo-input]');
  const clear = () => { output = null; section.hidden = true; inputSection.hidden = false; };
  root.querySelector('[data-demo-back]').addEventListener('click', () => { clear(); status.textContent = ''; field('scenario').focus(); });
  form.addEventListener('input', () => { revision++; clear(); });
  async function aliases() {
    revision++;
    const current = ++walletRevision;
    clear(); button.disabled = true; field('tnsAlias').disabled = true;
    field('tnsAlias').replaceChildren(); status.textContent = '';
    if (!field('walletId').value) return;
    try {
      const options = await assistantApi('/db/demo-data/aliases?walletId=' + encodeURIComponent(field('walletId').value));
      if (current !== walletRevision) return;
      for (const item of options) field('tnsAlias').add(new Option(item.alias + ' · ' + item.description, item.alias));
      field('tnsAlias').disabled = false; button.disabled = busy || !options.length;
    } catch (error) { if (current === walletRevision) status.textContent = error.message; }
  }
  field('walletId').addEventListener('change', aliases);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (busy || !form.reportValidity()) return;
    const current = revision;
    clear(); busy = true; button.disabled = true; status.textContent = message('working');
    try {
      const result = await assistantApi('/db/demo-data/prompt', assistantPost(root.querySelector('[data-demo-csrf]'), demoDataInput(new FormData(form))));
      if (current !== revision) { status.textContent = message('changed'); return; }
      output = result;
      for (const key of ['prompt', 'config']) root.querySelector('[data-demo-' + key + ']').textContent = result[key];
      section.hidden = false; inputSection.hidden = true; status.textContent = message('ready');
    } catch (error) { status.textContent = error.message; }
    finally { busy = false; button.disabled = field('tnsAlias').disabled; }
  });
  root.querySelectorAll('[data-copy]').forEach(control => control.addEventListener('click', async () => {
    if (!output) return;
    try { await navigator.clipboard.writeText(output[control.dataset.copy]); status.textContent = message('copied'); }
    catch { status.textContent = message('copyFailed'); }
  }));
  root.querySelectorAll('[data-download]').forEach(control => control.addEventListener('click', () => {
    if (!output) return;
    const key = control.dataset.download;
    const url = URL.createObjectURL(new Blob([output[key]], {type: key === 'config' ? 'application/json' : 'text/markdown;charset=utf-8'}));
    const link = document.createElement('a'); link.href = url;
    link.download = key === 'config' ? 'scenario-config.json' : 'scenario-prompt.md';
    document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }));
  return aliases();
}

if (typeof document !== 'undefined') document.querySelectorAll('[data-demo-data]').forEach(mountDemoData);
