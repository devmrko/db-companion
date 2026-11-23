import {assistantApi, assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';

export function scenarioInput(formData) {
  const input = Object.fromEntries(formData);
  input.rows = Number(input.rows);
  return input;
}

export function mountScenario(root) {
  const form = root.querySelector('[data-scenario-form]');
  const field = name => form.elements.namedItem(name);
  const status = root.querySelector('[data-scenario-status]');
  const button = root.querySelector('[data-scenario-generate]');
  const section = root.querySelector('[data-scenario-output]');
  let output = null, revision = 0, walletRevision = 0, busy = false;
  const message = key => t('scenario.' + key, key);
  const clear = () => { output = null; section.hidden = true; };
  form.addEventListener('input', () => { revision++; clear(); });
  async function aliases() {
    revision++;
    const current = ++walletRevision;
    clear(); button.disabled = true; field('tnsAlias').disabled = true;
    field('tnsAlias').replaceChildren(); status.textContent = '';
    if (!field('walletId').value) return;
    try {
      const options = await assistantApi('/db/scenario-data/aliases?walletId=' + encodeURIComponent(field('walletId').value));
      if (current !== walletRevision) return;
      for (const item of options) field('tnsAlias').add(new Option(item.alias + ' · ' + item.description, item.alias));
      field('tnsAlias').disabled = false; button.disabled = busy || !options.length;
    } catch (error) { if (current === walletRevision) status.textContent = error.message; }
  }
  field('walletId').addEventListener('change', aliases);
  field('preset').addEventListener('change', () => { field('description').required = field('preset').value === 'custom'; });
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (busy || !form.reportValidity()) return;
    const current = revision;
    clear(); busy = true; button.disabled = true; status.textContent = message('working');
    try {
      const result = await assistantApi('/db/scenario-data/generate', assistantPost(root.querySelector('[data-scenario-csrf]'), scenarioInput(new FormData(form))));
      if (current !== revision) { status.textContent = message('changed'); return; }
      output = result;
      for (const key of ['prompt', 'config']) root.querySelector('[data-scenario-' + key + ']').textContent = result[key];
      section.hidden = false; status.textContent = message('ready');
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

if (typeof document !== 'undefined') document.querySelectorAll('[data-scenario]').forEach(mountScenario);
