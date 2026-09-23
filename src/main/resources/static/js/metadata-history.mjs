import {t} from './i18n.mjs';
import {mountHistoryComparison} from './history-compare.mjs';

export function formatHistoryJson(value) {
  try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value ?? ''; }
}
export function historyToggleView(data) {
  const message = !data.enabled && installationUnconfirmed(data)
    ? t('ui.4904aa7dea58', "트리거 설치 상태 확인 불가") : data.message;
  return {
    checked: data.enabled === true,
    disabled: data.canManage !== true,
    text: [message, data.triggerOwner ? t('ui.600c611ea4ea', "관리 계정: {0}", data.triggerOwner) : '', data.managementMessage].filter(Boolean).join(' · ')
  };
}
export function historyUpgradeView(data) {
  const required = data.triggerUpgradeRequired === true || data.auditUpgradeRequired === true;
  return {hidden: !required,
    disabled: !required || data.trackingEnabled === true || data.codeUpgradeAllowed !== true};
}
export function historyUpgradeRequest(schema, table) {
  // The shared Toggle request requires enabled; upgrades stay OFF and recheck DB state.
  return {schema, table, enabled: false};
}
export function historyInstallView(data) {
  return {hidden: data.installed === true || installationUnconfirmed(data), disabled: data.canManage !== true};
}
// A recorded installer plus incomplete visibility/management cannot prove absence.
// Keep the existing server-side validation; never offer installation from that display state.
function installationUnconfirmed(data) {
  return data.installed === false && Boolean(data.triggerOwner) && data.canManage !== true;
}
if (typeof document !== 'undefined') {
  const root = document.querySelector('[data-metadata-history]');
  if (root) {
    const toggle = root.querySelector('[data-history-toggle]');
    const status = root.querySelector('[data-history-status]');
    const refresh = root.querySelector('[data-history-refresh]');
    const install = root.querySelector('[data-history-install]');
    const dialog = root.querySelector('[data-history-dialog]');
    const entries = root.querySelector('[data-history-entries]');
    const versionComparison = mountHistoryComparison(entries);
    const message = root.querySelector('[data-history-message]');
    const upgrade = root.querySelector('[data-history-upgrade]');
    const prev = root.querySelector('[data-history-prev]'), next = root.querySelector('[data-history-next]');
    let state, page = 1, listRequest, version = 0, switching = false;
    const url = suffix => {
      const result = new URL(root.dataset.url + suffix, window.location.href);
      result.searchParams.set('schema', root.dataset.schema); result.searchParams.set('table', root.dataset.table);
      return result;
    };
    const request = async (address, options) => {
      const response = await fetch(address, {cache: 'no-store', ...options});
      if (response.redirected || response.status === 401) throw new Error(t('ui.b9c067f345b1', "로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || t('ui.cc474779548c', "이력을 조회하지 못했습니다."));
      return data;
    };
    const applyState = data => {
      const view = historyToggleView(data);
      state = data; toggle.checked = view.checked; toggle.disabled = view.disabled;
      const installation = historyInstallView(data);
      if (install) { install.hidden = installation.hidden; install.disabled = installation.disabled; }
      if (!data.installed && !data.enabled) toggle.disabled = true;
      status.textContent = view.text;
      status.className = data.healthy ? 'app-muted app-history-status' : 'app-alert is-error';
      toggle.title = data.managementMessage || `${data.triggerOwner || t('ui.661e948782ff', "최초 설치 계정")}.${data.beforeTrigger} / ${data.afterTrigger}`;
    };
    const loadState = async (force = false) => {
      if (switching) return;
      toggle.disabled = true; refresh.disabled = true;
      if (install) install.disabled = true;
      try { const address=url('/state'); if(force)address.searchParams.set('refresh','true'); applyState(await request(address)); }
      catch (error) { status.textContent = error.message; status.className = 'app-alert is-error'; }
      finally { refresh.disabled = false; }
    };
    const changeTracking = async desired => {
      if (switching) return;
      switching = true; toggle.disabled = true; refresh.disabled = true;
      if (install) install.disabled = true;
      status.textContent = desired ? t('ui.295f9a3b7334', "이력 객체와 권한을 확인하고 있습니다…") : t('ui.3014f1cdc319', "이력 수집을 끄고 있습니다…");
      const csrf = root.querySelector('[data-history-csrf]');
      try {
        applyState(await request(root.dataset.url + '/toggle', {method: 'POST', headers: {'Content-Type': 'application/json', [csrf.dataset.csrfHeader]: csrf.value},
          body: JSON.stringify({schema: root.dataset.schema, table: root.dataset.table, enabled: desired})}));
      } catch (error) {
        toggle.checked = state?.enabled ?? false;
        status.textContent = error.message; status.className = 'app-alert is-error';
        toggle.disabled = true; // Partial DDL requires a fresh state read before another attempt.
      } finally { switching = false; refresh.disabled = false; }
    };
    toggle.addEventListener('change', () => changeTracking(toggle.checked));
    install?.addEventListener('click', () => {
      if (switching || !state?.canManage || !confirm(t('ui.fd705aa93b8b', "{0}.{1}의 이력 트리거를 설치하고 켤까요? 이력 저장이 실패하면 메타데이터 변경도 중단됩니다.", root.dataset.schema, root.dataset.table))) return;
      changeTracking(true);
    });
    refresh.addEventListener('click', () => loadState(true));
    const element = (tag, text, className) => {
      const item = document.createElement(tag); item.textContent = text ?? ''; if (className) item.className = className; return item;
    };
    const loadPage = async number => {
      upgrade.hidden = true; upgrade.disabled = true;
      listRequest?.abort(); listRequest = new AbortController(); const current = ++version;
      prev.disabled = true; next.disabled = true; message.textContent = t('ui.8bf609c884ca', "불러오는 중…"); entries.replaceChildren();
      const address = url(''); address.searchParams.set('page', number);
      try {
        const data = await request(address, {signal: listRequest.signal});
        if (current !== version || !dialog.open) return;
        page = data.page;
        data.entries.forEach(row => {
          const rowContainer = element('div', '', 'app-history-row');
          const item = element('details', '', 'app-history-entry');
          item.append(element('summary', `#${row.seq} · ${row.column || t('ui.3d721f9ac601', "테이블")} · ${row.kind}${row.annotationName ? ' / ' + row.annotationName : ''}`));
          item.append(element('p', `${row.changedAt} · ${row.changedBy}`, 'app-muted'));
          const comparison = element('div', '', 'app-history-comparison');
          for (const [title, json] of [[t('ui.0c263cac5947', "변경 전"), row.beforeJson], [t('ui.cc34819ae36e', "변경 후"), row.afterJson]]) {
            const block = element('div'); block.append(element('h3', title), element('pre', formatHistoryJson(json))); comparison.append(block);
          }
          item.append(comparison);
          const actions = element('div', '', 'app-history-row-actions');
          for (const [side, label] of [['left', t('ui.bf3589d07cff', "기준 선택")], ['right', t('ui.e5610692e3c2', "비교 선택")]]) {
            const button = element('button', label, 'btn app-btn app-btn-quiet'); button.type = 'button';
            button.setAttribute('aria-label', t('ui.e310f86eebff', "이력 #{0} {1}", row.seq, label));
            button.addEventListener('click', () => versionComparison.select(row, side, button)); actions.append(button);
          }
          rowContainer.append(item, actions); entries.append(rowContainer);
        });
        message.textContent = data.entries.length ? '' : t('ui.94e819855034', "저장된 변경 이력이 없습니다.");
        root.querySelector('[data-history-page]').textContent = t('ui.25be58ceeac8', "{0} 페이지", page);
        prev.disabled = page <= 1; next.disabled = !data.hasNext;
      } catch (error) { if (error.name !== 'AbortError' && current === version) message.textContent = error.message; }
    };
    root.querySelector('[data-history-open]').addEventListener('click', () => { versionComparison.reset(); dialog.showModal(); loadPage(1); });
    root.querySelector('[data-history-inspect]').addEventListener('click', async () => {
      upgrade.hidden = true; upgrade.disabled = true;
      versionComparison.reset();
      listRequest?.abort(); const current = ++version;
      dialog.showModal(); entries.replaceChildren(); message.textContent = t('ui.002ddfc1817b', "설치 권한과 객체를 조회하고 있습니다…");
      prev.disabled = true; next.disabled = true; root.querySelector('[data-history-page]').textContent = '';
      try {
        const data = await request(url('/readiness'));
        if (current !== version || !dialog.open) return;
        message.textContent = t('ui.46f9e1e9a6c7', "읽기 전용 검사 · 객체를 생성하거나 권한을 부여하지 않습니다.");
        entries.append(element('pre', JSON.stringify(data, null, 2), 'app-history-diagnostics'));
        const upgradeView = historyUpgradeView(data);
        upgrade.hidden = upgradeView.hidden; upgrade.disabled = upgradeView.disabled;
        if (data.auditUpgradeRequired) message.textContent = t('ui.a6eb693cceba', "감사 패키지 업데이트가 필요합니다. 같은 스키마의 이력과 의존 트리거가 모두 OFF일 때 공유 본문을 교체합니다. 이력은 OFF로 유지합니다.");
        else if (data.triggerUpgradeRequired) message.textContent = data.trackingEnabled
          ? t('ui.7e3d5dcd722a', "구버전 트리거입니다. 이력을 OFF로 한 뒤 업데이트해 주세요.")
          : t('ui.a366ec5fc803', "구버전 트리거입니다. 업데이트는 이 테이블의 확인된 구버전만 교체하며, 이력은 OFF로 유지합니다.");
      } catch (error) { if (current === version) message.textContent = error.message; }
    });
    upgrade.addEventListener('click', async () => {
      if (switching) return;
      switching = true; upgrade.disabled = true; toggle.disabled = true; refresh.disabled = true;
      message.textContent = t('ui.7d9927886ea9', "원문과 비활성 상태를 확인하고 이력 코드를 업데이트하고 있습니다…");
      const csrf = root.querySelector('[data-history-csrf]');
      try {
        const result = await request(root.dataset.url + '/upgrade', {method: 'POST',
          headers: {'Content-Type': 'application/json', [csrf.dataset.csrfHeader]: csrf.value},
          body: JSON.stringify(historyUpgradeRequest(root.dataset.schema, root.dataset.table))});
        applyState(result); upgrade.hidden = true; entries.replaceChildren();
        message.textContent = t('ui.03865f816273', "이력 코드를 업데이트했습니다. 이력은 OFF입니다.");
      } catch (error) {
        message.textContent = error.message;
        status.textContent = t('ui.c655940662f3', "업데이트 결과를 설치 확인에서 다시 확인해 주세요.");
      } finally { switching = false; refresh.disabled = false; }
    });
    root.querySelector('[data-history-close]').addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => { ++version; listRequest?.abort(); versionComparison.reset(); });
    prev.addEventListener('click', () => loadPage(page - 1)); next.addEventListener('click', () => loadPage(page + 1));
    loadState();
  }
}
