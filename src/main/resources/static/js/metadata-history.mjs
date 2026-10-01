import {t} from './i18n.mjs';
import {mountHistoryComparison} from './history-compare.mjs';

export function formatHistoryJson(value) {
  try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value ?? ''; }
}
export function canRestoreHistory(kind, annotationEditable) {
  return kind === 'COMMENT' || (kind === 'ANNOTATION' && annotationEditable !== 'false');
}
export function historyToggleView(data) {
  const access=data.setup?.access, step=data.setup?.nextStep;
  const labels={
    INSTALL:t('history.inline.unconfigured','이력 수집 미설정'),
    ENABLE:t('history.setup.enable','이력 수집이 꺼져 있습니다'),
    AUDIT_UPDATE:t('history.setup.audit','공통 패키지 업데이트 필요'),
    TRIGGER_UPDATE:t('history.setup.trigger','이 테이블·뷰의 트리거 업데이트 필요')
  };
  const compact=data.healthy===true && !installationUnconfirmed(data) && ['ALLOWED','REQUIRED'].includes(access) && labels[step];
  const permission=access==='REQUIRED' ? step==='INSTALL'
    ? t('history.inline.installPrivilege','설치 권한 필요') : t('history.inline.managePrivilege','관리 권한 필요') : '';
  return {
    checked: data.enabled === true,
    disabled: data.canManage !== true,
    text: historyCollecting(data) ? t('history.summary.active', '이력 수집 중')
      : compact ? [compact,permission].filter(Boolean).join(' · ') : historyStateDetails(data)
  };
}
export function historyStateDetails(data) {
  const message = !data.enabled && installationUnconfirmed(data)
    ? t('ui.4904aa7dea58', "트리거 설치 상태 확인 불가") : data.message;
  return [message, data.triggerOwner ? t('ui.600c611ea4ea', "관리 계정: {0}", data.triggerOwner) : '', data.managementMessage].filter(Boolean).join(' · ');
}
export function historyUpgradeView(data) {
  const required = data.triggerUpgradeRequired === true;
  return {hidden: !required,
    disabled: !required || data.auditUpgradeRequired === true || data.trackingEnabled === true || data.codeUpgradeAllowed !== true};
}
export function historyAuditUpgradeView(data) {
  const required = data.sharedAudit?.upgradeRequired === true;
  return {hidden: !required, disabled: !required || data.sharedAudit?.allowed !== true};
}
export function historyCodeLabel(version, kind = 'audit') {
  const labels = {
    V1: t('history.code.v1', 'v1 · 테이블 이력'),
    V2: t('history.code.v2', 'v2 · Materialized View 코멘트 지원'),
    V3: t('history.code.v3', 'v3 · 일반 View 코멘트 지원'),
    MISSING: t('history.code.missing', '미설치 또는 원문 조회 불가'),
    UNKNOWN: t('history.code.unknown', '확인되지 않은 코드 · 자동 교체 안 함')
  };
  if(kind === 'trigger') Object.assign(labels, {
    V1: t('history.code.triggerV1', 'v1 · 테이블 이벤트'),
    V2: t('history.code.triggerV2', 'v2 · 컬럼 코멘트 이벤트 지원'),
    V3: t('history.code.triggerV3', 'v3 · 테이블·뷰·컬럼 코멘트 이벤트 지원')
  });
  return labels[version] || labels.UNKNOWN;
}
export function historyUpgradeRequest(schema, table) {
  // The shared Toggle request requires enabled; upgrades stay OFF and recheck DB state.
  return {schema, table, enabled: false};
}
export function historyInstallView(data) {
  return {hidden: data.installed === true || installationUnconfirmed(data), disabled: data.canManage !== true};
}
export function historySetupView(data) {
  const access=data.setup?.access, step=data.setup?.nextStep;
  const titles={
    AUDIT_UPDATE:t('history.setup.audit','공통 패키지 업데이트 필요'),
    TRIGGER_UPDATE:t('history.setup.trigger','이 테이블·뷰의 트리거 업데이트 필요'),
    INSTALL:t('history.setup.install','이력 초기 설정 필요'),
    ENABLE:t('history.setup.enable','이력 수집이 꺼져 있습니다')
  };
  const next={
    AUDIT_UPDATE:t('history.setup.auditNext','‘설치 확인 열기’ → ‘공통 패키지 업데이트 → v3’를 확인하세요. 스키마 공통 작업이며, 이후 이 테이블·뷰의 트리거 설치 상태를 확인합니다.'),
    TRIGGER_UPDATE:t('history.setup.triggerNext','‘설치 확인 열기’ → ‘이 테이블·뷰의 트리거 업데이트 → v3’를 확인하세요. 업데이트 후 이력 수집은 별도로 켭니다.'),
    INSTALL:t('history.setup.installNext','‘설치 확인 열기’로 준비 상태를 확인한 뒤, 창을 닫고 ‘트리거 설치·켜기’를 누르세요. 권한·기존 객체 검증을 통과한 경우에만 설치합니다.'),
    ENABLE:t('history.setup.enableNext','이력 수집을 시작하려면 ‘이력 관리’를 켜세요. 이전에 저장된 이력은 유지됩니다.'),
    NONE:t('history.setup.activeNext','이력 수집은 켜져 있습니다. 현재 계정의 관리 권한은 ON/OFF 등 설정 변경에 필요합니다.')
  };
  return {
    hidden:historyCollecting(data)||(data.canManage===true&&data.healthy===true&&data.installed===true),
    administrator:access==='REQUIRED'||access==='UNCONFIRMED',
    title:access==='REQUIRED'?t('history.setup.admin','관리자 설정 필요')
      :access==='UNCONFIRMED'?t('history.setup.unconfirmed','관리 권한·설치 상태 확인 필요')
      :titles[step]||t('history.setup.review','이력 설정 확인 필요'),
    next:next[step]||t('history.setup.reviewNext','‘설치 확인 열기’에서 권한·코드·객체 상태와 다음 작업을 확인하세요. 확인되지 않은 설정은 자동 변경하지 않습니다.')
  };
}
function historyCollecting(data) {
  return data.installed===true && data.healthy===true && data.enabled===true;
}
export function historyInspectionView(data) {
  if(data.delegatedState) {
    const state=data.delegatedState;
    return {active:historyCollecting(state),audit:state.healthy?t('history.summary.ready','준비 완료'):t('history.summary.review','상태 확인 필요'),
      triggers:historyToggleView(state).text,next:data.note||''};
  }
  const compatible=(owner,name,type)=>data.assetValidation?.some(a=>a.owner===owner&&a.name===name&&a.type===type&&a.compatible===true);
  const packageReady=data.sharedAudit?.version==='V3' && ['PACKAGE','PACKAGE BODY'].every(type=>
    compatible(data.schema,'DBC_METADATA_AUDIT',type) && data.objects?.some(o=>
      o.OWNER===data.schema&&o.OBJECT_NAME==='DBC_METADATA_AUDIT'&&o.OBJECT_TYPE===type&&o.STATUS==='VALID'));
  const triggers=data.triggerVersions||[];
  const triggerReady=triggers.length===2 && triggers.every(t=>t.version==='V3' && compatible(t.owner,t.name,'TRIGGER') &&
    data.tableTriggers?.some(o=>o.OWNER===t.owner&&o.TRIGGER_NAME===t.name&&o.COMPILE_STATUS==='VALID'));
  const triggersHaveStatus=status=>triggerReady && triggers.every(t=>data.tableTriggers.some(o=>o.OWNER===t.owner&&o.TRIGGER_NAME===t.name&&o.STATUS===status));
  const active=packageReady && triggersHaveStatus('ENABLED') && data.trackingEnabled===true && data.missingAssets?.length===0 &&
    data.assetValidation?.every(a=>a.compatible===true) && data.trackingUpgradeRequired!==true;
  const packageMissing=data.missingAssets?.some(a=>a===`${data.schema}.DBC_METADATA_AUDIT PACKAGE`||a===`${data.schema}.DBC_METADATA_AUDIT PACKAGE BODY`);
  const triggersMissing=triggers.length===2 && triggers.every(t=>data.missingAssets?.includes(`${t.owner}.${t.name} TRIGGER`));
  const ready=t('history.summary.ready','준비 완료'),review=t('history.summary.review','상태 확인 필요');
  return {
    active:active===true,
    audit:packageReady?ready:data.auditUpgradeRequired===true?t('history.summary.update','업데이트 필요')
      :packageMissing?t('history.summary.install','설치 필요'):review,
    triggers:active?t('history.summary.active','이력 수집 중'):data.triggerUpgradeRequired===true?t('history.summary.update','업데이트 필요')
      :triggersMissing?t('history.summary.install','설치 필요')
      :triggersHaveStatus('DISABLED')&&data.trackingEnabled===false?t('history.summary.off','준비 완료 · 수집 꺼짐'):review,
    next:active?'':data.auditUpgradeRequired===true?t('history.summary.auditNext','먼저 공통 패키지를 업데이트하세요.')
      :data.triggerUpgradeRequired===true?t('history.code.triggerNext','이 테이블·뷰의 이력을 끈 뒤 트리거를 업데이트하세요. 자동으로 켜지지 않습니다.')
      :triggersMissing?t('history.code.installNext','트리거가 아직 없습니다. 설치 확인 창을 닫고 ‘트리거 설치·켜기’를 사용하세요. 설치 권한은 별도로 필요합니다.')
      :packageReady&&triggersHaveStatus('DISABLED')&&data.trackingEnabled===false?t('history.setup.enableNext','이력 수집을 시작하려면 ‘이력 관리’를 켜세요. 이전에 저장된 이력은 유지됩니다.')
      :t('history.code.review','코드 호환성과 설치 상태를 검사 상세에서 확인하세요.')
  };
}
export function historySetupInstructions(schema,table) {
  return [t('history.setup.target','설정 대상: {0}.{1}',schema,table),
    t('history.setup.login','같은 DB/Wallet에 ADMIN 또는 필요한 권한을 가진 관리 계정으로 로그인하세요. 현재 계정의 권한을 자동으로 높이지 않습니다.'),
    t('history.setup.route','스키마 {0} 선택 → 테이블·뷰·컬럼 → {1} → 설치 확인',schema,table),
    t('history.setup.order','공통 패키지 업데이트는 스키마당 한 번, 트리거 설치·업데이트는 대상 테이블·뷰별로 진행합니다. 각 작업의 사전 검사를 통과한 뒤 이력 수집을 켭니다.')].join('\n');
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
    const auditUpgrade = root.querySelector('[data-history-audit-upgrade]');
    const setup = root.querySelector('[data-history-setup]');
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
      if(setup){
        const guide=historySetupView(data);setup.hidden=guide.hidden;
        if(guide.hidden)setup.open=false;
        setup.querySelector('[data-history-setup-title]').textContent=guide.title;
        setup.querySelector('[data-history-setup-next]').textContent=guide.next;
        setup.querySelector('[data-history-setup-reason]').textContent=historyStateDetails(data);
        setup.querySelector('[data-history-admin-guide]').hidden=!guide.administrator;
      }
    };
    const loadState = async (force = false) => {
      if (switching) return;
      toggle.disabled = true; refresh.disabled = true;
      if (install) install.disabled = true;
      try { const address=url('/state'); if(force)address.searchParams.set('refresh','true'); applyState(await request(address)); }
      catch (error) {
        if(setup){const guide=historySetupView({});setup.hidden=false;setup.querySelector('[data-history-setup-title]').textContent=guide.title;setup.querySelector('[data-history-setup-next]').textContent=guide.next;setup.querySelector('[data-history-setup-reason]').textContent=error.message;setup.querySelector('[data-history-admin-guide]').hidden=true;}
        status.textContent = error.message; status.className = 'app-alert is-error';
      }
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
    document.addEventListener('metadata-history-access-changed', () => loadState(true));
    const element = (tag, text, className) => {
      const item = document.createElement(tag); item.textContent = text ?? ''; if (className) item.className = className; return item;
    };
    const loadPage = async number => {
      upgrade.hidden = true; upgrade.disabled = true;
      auditUpgrade.hidden = true; auditUpgrade.disabled = true;
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
          item.append(element('summary', `#${row.seq} · ${row.column || root.dataset.objectLabel || t('ui.3d721f9ac601', "테이블")} · ${row.kind}${row.annotationName ? ' / ' + row.annotationName : ''}`));
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
          try {
            const before = JSON.parse(row.beforeJson);
            for (const [phase, raw] of [['before', row.beforeJson], ['after', row.afterJson]]) { const snapshot=JSON.parse(raw); if (snapshot.exists === true && typeof snapshot.value === 'string' && canRestoreHistory(row.kind, root.dataset.annotationEditable)) { const restore = element('button', t('metadataRestore.use', "이 값으로 수정하기") + ` (${phase})`, 'btn app-btn app-btn-secondary'); restore.type='button'; restore.addEventListener('click',()=>document.dispatchEvent(new CustomEvent('metadata-history-restore',{detail:{kind:row.kind.toLowerCase(),column:row.column||null,name:row.annotationName||null,value:snapshot.value}})));actions.append(restore); } }
          } catch { /* Malformed history is display-only and cannot seed an editor. */ }
          rowContainer.append(item, actions); entries.append(rowContainer);
        });
        message.textContent = data.entries.length ? '' : t('ui.94e819855034', "저장된 변경 이력이 없습니다.");
        root.querySelector('[data-history-page]').textContent = t('ui.25be58ceeac8', "{0} 페이지", page);
        prev.disabled = page <= 1; next.disabled = !data.hasNext;
      } catch (error) { if (error.name !== 'AbortError' && current === version) message.textContent = error.message; }
    };
    root.querySelector('[data-history-open]').addEventListener('click', () => { versionComparison.reset(); dialog.querySelector('h2').textContent=t('ui.e78d03ec8597','변경 이력');dialog.showModal(); loadPage(1); });
    const inspect = async () => {
      if (switching) return;
      upgrade.hidden = true; upgrade.disabled = true;
      auditUpgrade.hidden = true; auditUpgrade.disabled = true;
      versionComparison.reset();
      listRequest?.abort(); const current = ++version;
      dialog.querySelector('h2').textContent=t('ui.542ee3de69a8','설치 확인');dialog.showModal(); entries.replaceChildren(); message.textContent = t('ui.002ddfc1817b', "설치 권한과 객체를 조회하고 있습니다…");
      prev.disabled = true; next.disabled = true; root.querySelector('[data-history-page]').textContent = '';
      try {
        const data = await request(url('/readiness'));
        if (current !== version || !dialog.open) return;
        message.textContent = t('ui.46f9e1e9a6c7', "읽기 전용 검사 · 객체를 생성하거나 권한을 부여하지 않습니다.");
        const view=historyInspectionView(data);
        const summary = element('section', '', 'app-history-code-summary');
        summary.append(element('p',t('history.code.shared','스키마 공통 패키지')+' · '+view.audit),
          element('p',t('history.code.triggers','현재 테이블·뷰의 트리거')+' · '+view.triggers));
        if(view.next) summary.append(element('p',view.next,'app-history-next-action'));
        const blockers = data.sharedAudit?.blockers || [];
        if(!view.active&&blockers.length) {const list=element('ul');for(const reason of blockers)list.append(element('li',reason));summary.append(list);}
        if(!view.active&&data.missingPrivileges?.length) {const list=element('ul');for(const reason of data.missingPrivileges)list.append(element('li',reason));summary.append(element('p',t('history.code.triggerPrivileges','트리거 작업에 필요한 권한')),list);}
        entries.append(summary);
        const details=element('details');details.dataset.historyDiagnostics='';
        details.append(element('summary',t('history.code.diagnostics','검사 상세')));
        const versions=element('section','','app-history-code-summary');
        versions.append(element('h3',t('history.code.shared','스키마 공통 패키지')),
          element('p','DBC_METADATA_AUDIT · '+historyCodeLabel(data.sharedAudit?.version)),
          element('p',t('history.code.guide','v1·v2 원본은 기존 설치본 식별에 사용합니다. 새 설치와 업데이트는 v3이며, 구버전으로 되돌리지는 않습니다.')),
          element('h3',t('history.code.triggers','현재 테이블·뷰의 트리거')));
        for(const trigger of data.triggerVersions||[]) versions.append(element('p',trigger.name+' · '+historyCodeLabel(trigger.version,'trigger')));
        if(!data.delegatedState) details.append(versions);
        details.append(element('pre',JSON.stringify(data,null,2),'app-history-diagnostics'));entries.append(details);
        const upgradeView = historyUpgradeView(data);
        upgrade.hidden = upgradeView.hidden; upgrade.disabled = upgradeView.disabled;
        const auditView = historyAuditUpgradeView(data);
        auditUpgrade.hidden = auditView.hidden; auditUpgrade.disabled = auditView.disabled;
        if (data.auditUpgradeRequired) message.textContent = t('history.code.scope', '공통 패키지는 같은 스키마 전체에 적용됩니다. 모든 이력과 의존 트리거가 OFF여야 하며, 선택한 테이블의 트리거가 없어도 업데이트할 수 있습니다.');
        else if (data.triggerUpgradeRequired) message.textContent = data.trackingEnabled
          ? t('ui.7e3d5dcd722a', "구버전 트리거입니다. 이력을 OFF로 한 뒤 업데이트해 주세요.")
          : t('ui.a366ec5fc803', "구버전 트리거입니다. 업데이트는 이 테이블의 확인된 구버전만 교체하며, 이력은 OFF로 유지합니다.");
      } catch (error) { if (current === version) message.textContent = error.message; }
    };
    root.querySelector('[data-history-inspect]').addEventListener('click', inspect);
    setup?.querySelector('[data-history-setup-open]').addEventListener('click',inspect);
    setup?.querySelector('[data-history-setup-copy]').addEventListener('click',async()=>{
      const value=historySetupInstructions(root.dataset.schema,root.dataset.table);
      const result=setup.querySelector('[data-history-copy-result]'),fallback=setup.querySelector('[data-history-copy-value]');
      fallback.hidden=true;
      try{await navigator.clipboard.writeText(value);result.textContent=t('history.setup.copied','대상과 관리자 작업 안내를 복사했습니다.');}
      catch{fallback.value=value;fallback.hidden=false;fallback.focus();fallback.select();result.textContent=t('history.setup.copyFailed','자동 복사를 사용할 수 없습니다. 아래 안내를 직접 복사하세요.');}
    });
    auditUpgrade.addEventListener('click', async () => {
      if(switching || auditUpgrade.disabled || !confirm(t('history.code.confirm','{0} 스키마의 공통 감사 패키지를 v3로 업데이트할까요? 트리거 생성·교체, 권한 부여, 이력 켜기는 수행하지 않습니다.',root.dataset.schema)))return;
      switching=true; ++version; auditUpgrade.disabled=true; upgrade.disabled=true; toggle.disabled=true; refresh.disabled=true;if(install)install.disabled=true;
      message.textContent=t('history.code.updating','공통 패키지 원문·권한·의존 객체를 확인하고 업데이트하고 있습니다…');
      const csrf=root.querySelector('[data-history-csrf]');
      try {
        const result=await request(root.dataset.url+'/audit-upgrade',{method:'POST',headers:{'Content-Type':'application/json',[csrf.dataset.csrfHeader]:csrf.value},body:JSON.stringify(historyUpgradeRequest(root.dataset.schema,root.dataset.table))});
        applyState(result); switching=false;
        if(dialog.open) await inspect();
      }catch(error){message.textContent=error.message;status.textContent=t('ui.c655940662f3','업데이트 결과를 설치 확인에서 다시 확인해 주세요.');}
      finally{switching=false;refresh.disabled=false;}
    });
    upgrade.addEventListener('click', async () => {
      if (switching || upgrade.disabled) return;
      switching = true; upgrade.disabled = true; auditUpgrade.disabled = true; toggle.disabled = true; refresh.disabled = true;
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
