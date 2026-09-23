import {t} from './i18n.mjs';
import {diffHistoryText} from './history-compare.mjs';
import {snapshotChanges, valueChange, compactRows} from './profile-diff.mjs';

export function versionOf(entry) {
  let data;
  try { data = JSON.parse(entry.payload); } catch { return null; }
  if (entry.kind === 'SNAPSHOT' && typeof data?.exists === 'boolean')
    return {key: `${entry.profile}\0SNAPSHOT`, text: JSON.stringify(data, null, 2), notice: entry.editStage ? t('ui.d4f1c85b3cfd', "앱 저장 {0} · {1}", entry.editStage, entry.editOutcome === 'VERIFIED' ? t('ui.5d9c284e2909', "저장 확인") : t('ui.4a7770229e8f', "저장 결과 확인 필요")) : t('ui.ce78fef5cf26', "조회 시점의 현재값 · 변경 요청의 성공 여부와 별개"), data};
  const request = data?.request;
  if (entry.kind !== 'REQUEST' || !request || !['LITERAL', 'BIND_UNVERIFIED'].includes(request.quality)
      || typeof request.attribute !== 'string' || (request.value !== null && typeof request.value !== 'string')) return null;
  return {key: `${entry.profile}\0REQUEST\0${request.attribute}`, text: request.value === null ? '' : request.value,
    notice: (request.quality === 'LITERAL' ? t('ui.43fd84015a14', "변경 요청 · 적용 미확인") : t('ui.d990ba2ab28b', "변경 요청 · 적용 및 값의 전체 여부 미확인"))
      + (request.value === null ? ' · SQL NULL' : request.value === '' ? t('ui.00c750fa91ce', " · 빈 문자열") : t('ui.23e5ecb4e3a0', " · 문자열")), data};
}
export function editPair(entry) {
  if(entry.kind!=='EDIT')return null;
  const data=JSON.parse(entry.payload);
  const version=(side,label)=>data[side]===null?null:{...entry,seq:`${entry.seq}:${side}`,kind:'SNAPSHOT',payload:JSON.stringify(data[side]),editStage:label,editOutcome:data.outcome};
  if(typeof data.before?.exists!=='boolean'||(data.after!==null&&typeof data.after?.exists!=='boolean'))throw new Error(t('ui.339deadbe991', "변경 전후 값을 확인해 주세요."));
  return {left:version('before',t('ui.0c263cac5947', "변경 전")),right:version('after',t('ui.cc34819ae36e', "변경 후")),outcome:data.outcome,attribute:data.attribute};
}
function versions(left, right) {
  const a = versionOf(left), b = versionOf(right);
  if (!a || !b || a.key !== b.key) throw new Error(t('ui.d1db2e4e0017', "같은 프로필·종류·속성끼리 비교할 수 있습니다. 기준을 다시 선택해 주세요."));
  return {left: a, right: b};
}
export function compareVersions(left, right) {
  const pair = versions(left, right);
  return {...pair, ...diffHistoryText(pair.left.text, pair.right.text)};
}
export function changedProfileFields(left, right) {
  const pair = versions(left, right);
  const changes = left.kind === 'SNAPSHOT' ? snapshotChanges(pair.left.data, pair.right.data)
    : [valueChange(pair.left.data.request.attribute, pair.left.data.request.value, pair.right.data.request.value)].filter(Boolean);
  return {...pair, changes};
}
function sameEntry(a, b) {
  return !!a && !!b && String(a.seq) === String(b.seq) && a.profile === b.profile && a.kind === b.kind && a.storage === b.storage;
}
export function selectProfileVersion(current, entry, side) {
  if (!['left', 'right'].includes(side) || !versionOf(entry)) throw new Error(t('ui.75aad10c6541', "비교할 이력을 확인해 주세요."));
  if (side === 'left' || !current.left) return {left: entry, right: null};
  if (sameEntry(current.left, entry)) throw new Error(t('ui.0d54b55e6ed1', "기준과 다른 이력을 선택해 주세요."));
  versions(current.left, entry);
  return {left: current.left, right: entry};
}
export function profileSelection(entry, left, right) {
  return {baseline: sameEntry(entry, left), candidate: sameEntry(entry, right), compareDisabled: sameEntry(entry, left)};
}
export function auditControls(state, busy = false) {
  const known = typeof state?.enabled === 'boolean';
  return {unknown: !known, checked: state?.enabled === true,
    toggleDisabled: busy || !known || state?.canManage !== true,
    collectDisabled: busy || !known || state?.installed !== true || state?.canCollect !== true};
}
const node = (tag, text = '', className) => {
  const el = document.createElement(tag); el.textContent = text; if (className) el.className = className; return el;
};
const button = text => { const el = node('button', text, 'btn app-btn app-btn-quiet'); el.type = 'button'; return el; };
const label = entry => `#${entry.seq} · ${entry.eventAt} · ${entry.profile ?? t('ui.6ef390cf7926', "미분류")} · ${entry.actor}`;

function mount(root) {
  const profile = root.dataset.profile;
  // Transitional cleanup for already-cached Thymeleaf markup; no server restart/login loss.
  if (!profile) { root.remove(); return; }
  root.querySelector('[data-ph-filter]')?.remove();
  root.querySelector('.app-history-details')?.remove();
  root.querySelector('.app-history-content > p.app-muted:not([data-ph-state])')?.remove();
  root.querySelector('#profile-history-title').textContent = t('ui.ccf66557e2de', "{0} · 변경 이력", profile);
  const find = name => root.querySelector(`[data-ph-${name}]`);
  const dialog = find('dialog'), message = find('message'), entries = find('entries'), comparison = find('comparison');
  const toggle = find('toggle'), collect = find('collect'), csrf = find('csrf'), schema = root.dataset.schema, base = root.dataset.url;
  let state = null, page = 1, hasNext = false, busy = false, left = null, right = null, requestId = 0;
  let rowControls = [], selectionError = '';
  function markSelection() {
    for (const {entry, baseline, candidate} of rowControls) {
      const selected = profileSelection(entry, left, right);
      baseline.textContent = selected.baseline ? t('ui.562a301fb362', "기준 선택됨") : t('ui.eaea55bff600', "기준");
      candidate.textContent = selected.candidate ? t('ui.5a5c21918f81', "비교 선택됨") : t('ui.c0154c812ab6', "비교");
      baseline.setAttribute('aria-pressed', String(selected.baseline));
      candidate.setAttribute('aria-pressed', String(selected.candidate));
      candidate.disabled = selected.compareDisabled;
      candidate.title = selected.compareDisabled ? t('ui.0d54b55e6ed1', "기준과 다른 이력을 선택해 주세요.") : '';
    }
  }
  function choose(entry, side) {
    try { ({left, right} = selectProfileVersion({left, right}, entry, side)); selectionError = ''; }
    catch (error) { selectionError = error.message; }
    drawComparison();
  }
  function controls() {
    const audit = auditControls(state, busy);
    toggle.disabled = audit.toggleDisabled; toggle.checked = audit.checked; toggle.indeterminate = audit.unknown;
    collect.disabled = audit.collectDisabled;
    if(find('install')){find('install').hidden=state?.installed!==false;find('install').disabled=busy||!state;}
    find('prev').disabled = busy || page <= 1; find('next').disabled = busy || !hasNext;
    find('state-refresh').disabled = busy;
  }
  async function api(path = '', body, query = {}) {
    const url = new URL(base + path, location.origin);
    if (!body) for (const [key, value] of Object.entries({schema, ...query})) if (value != null) url.searchParams.set(key, value);
    const response = await fetch(url, {method: body ? 'POST' : 'GET', credentials: 'same-origin', cache: 'no-store',
      headers: body ? {'Content-Type': 'application/json', [csrf.dataset.csrfHeader]: csrf.value} : {}, body: body ? JSON.stringify(body) : undefined});
    if (response.redirected || response.status === 401) throw new Error(t('ui.b9c067f345b1', "로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
    if (!response.headers.get('content-type')?.includes('application/json')) throw new Error(t('ui.03477e1e8a2f', "응답을 읽지 못했습니다. HTTP {0}", response.status));
    const data = await response.json(); if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`); return data;
  }
  async function refreshState() {
    try { state = await api('/state'); find('state').textContent = state.message; }
    catch (error) { state = null; find('state').textContent = error.message; }
    controls();
  }
  function drawComparison() {
    markSelection();
    comparison.replaceChildren(); if (!left) return;
    const selectedLeft = left, selectedRight = right;
    const panel = node('section', '', 'app-history-compare-panel'), header = node('div', '', 'app-history-compare-header');
    const close = button(t('ui.c38ab8893689', "비교 지우기")); close.addEventListener('click', () => { left = right = null; selectionError = ''; drawComparison(); });
    header.append(node('h3', t('ui.e884c7c67060', "버전 비교")), close); panel.append(header);
    panel.append(node('p', t('ui.3c4e70221ed0', "기준 #{0} → {1}", left.seq, right ? t('ui.1a87a79078a1', "비교 #{0}", right.seq) : t('ui.4c947acf46f6', "비교할 이력 선택")), 'app-profile-selection'));
    if (selectionError) { const error = node('p', selectionError, 'app-history-compare-error'); error.setAttribute('role', 'alert'); panel.append(error); }
    panel.append(node('p', t('ui.6daf48631f89', "기준 {0}", label(left)), 'app-muted'));
    if (!right) panel.append(node('p', t('ui.311251130131', "비교할 기록의 ‘비교’ 버튼을 누르세요.")));
    else {
      try {
        const result = changedProfileFields(left, right);
        panel.append(node('p', t('ui.83eccdbf261c', "비교 {0}", label(right)), 'app-muted'));
        for (const notice of new Set([result.left.notice, result.right.notice])) panel.append(node('p', notice, 'app-muted'));
        panel.append(node('p', result.changes.length ? t('ui.c243e5cdb8d6', "변경 {0}개 항목 · − 삭제 / + 추가", result.changes.length)
          : t('ui.2b0c2a8c40e1', "#{0}와 #{1}의 보관된 {2}이 같습니다.", left.seq, right.seq, left.kind === 'SNAPSHOT' ? t('ui.6c91fa536dd6', "현재값") : t('ui.625fa62c2d6b', "요청값")), 'app-history-compare-result'));
        for (const change of result.changes) {
          const section = node('section', '', 'app-profile-change');
          section.append(node('h4', change.name));
          if (change.beforeState !== change.afterState) section.append(node('p', `${change.beforeState} → ${change.afterState}`, 'app-muted'));
          const diff = node('div', '', 'app-unified-diff');
          diff.setAttribute('aria-label', t('ui.efea61c83cc5', "{0} 변경분", change.name));
          for (const row of compactRows(change.rows)) {
            if (row.kind === 'gap') { diff.append(node('div', t('ui.d6b395610065', "⋯ 변경 없는 {0}줄", row.count), 'app-diff-gap')); continue; }
            const line = node('div', '', `app-diff-line app-diff-${row.kind}`);
            line.append(node('span', row.oldLine ?? '', 'app-diff-number'), node('span', row.newLine ?? '', 'app-diff-number'),
              node('span', row.kind === 'removed' ? '-' : row.kind === 'added' ? '+' : ' ', 'app-diff-sign'),
              node('span', row.text.replace(/\n$/, ''), 'app-diff-text'));
            diff.append(line);
            if (!row.text.endsWith('\n') && row.kind !== 'equal') diff.append(node('div', t('ui.8840e83f7a84', "줄 끝 개행 없음"), 'app-diff-eol'));
          }
          section.append(diff); panel.append(section);
          if (change.coarse) section.append(node('p', t('ui.b1cea0877269', "큰 변경은 교체 구간 단위로 표시합니다."), 'app-muted'));
        }
        const full = node('details', '', 'app-profile-full'); full.append(node('summary', t('ui.e078b14a70bc', "전체 보기")));
        full.addEventListener('toggle', () => {
          if (!full.open || full.childElementCount > 1) return;
          const complete = compareVersions(selectedLeft, selectedRight), columns = node('div', '', 'app-history-comparison');
          for (const [side, entry] of [['left', selectedLeft], ['right', selectedRight]]) {
            const column = node('div', '', 'app-history-version'), text = node('pre', '', 'app-history-diff');
            column.append(node('h4', `${side === 'left' ? t('ui.eaea55bff600', "기준") : t('ui.c0154c812ab6', "비교")} #${entry.seq}`));
            for (const part of complete.parts) {
              if ((side === 'left' && part.kind === 'added') || (side === 'right' && part.kind === 'removed')) continue;
              text.append(node(part.kind === 'removed' ? 'del' : part.kind === 'added' ? 'ins' : 'span', part.text));
            }
            column.append(text); columns.append(column);
          }
          full.append(columns);
        });
        panel.append(full);
      } catch (error) { panel.append(node('p', error.message, 'app-history-compare-error')); }
    }
    comparison.append(panel);
    panel.scrollIntoView({block: 'start'});
  }
  function drawRows(rows) {
    entries.replaceChildren(); rowControls = [];
    if (!rows.length) { entries.append(node('p', t('ui.80168c9d1394', "보관된 이력이 없습니다."), 'app-empty')); return; }
    for (const entry of rows) {
      const card = node('article', '', 'app-history-entry'), heading = node('div', '', 'app-history-entry-heading');
      const version = versionOf(entry); let data;
      try { data = JSON.parse(entry.payload); } catch { data = {error: t('ui.85497a31c25c', "보관 JSON을 읽지 못했습니다.")}; }
      heading.append(node('h3', label(entry))); card.append(heading);
      if(entry.kind==='EDIT') {
        try {
          const pair=editPair(entry),show=button(t('ui.d8291eb080d2', "변경 내용"));
          card.append(node('p',t('ui.697d144c1d76', "{0} · {1} · 변경자 {2}", pair.attribute, pair.outcome==='VERIFIED'?t('ui.5d9c284e2909', "저장 확인"):t('ui.4a7770229e8f', "저장 결과 확인 필요"), entry.actor),'app-muted'));
          show.addEventListener('click',()=>{left=pair.left;right=pair.right;selectionError='';drawComparison();});heading.append(show);
          for(const part of [pair.left,pair.right].filter(Boolean)) {
            const actions=node('div','','app-history-toolbar'),baseline=button(t('ui.eaea55bff600', "기준")),candidate=button(t('ui.c0154c812ab6', "비교"));
            baseline.addEventListener('click',()=>choose(part,'left'));candidate.addEventListener('click',()=>choose(part,'right'));
            rowControls.push({entry:part,baseline,candidate});actions.append(node('span',part.editStage),baseline,candidate);card.append(actions);
          }
          if(!pair.right)card.append(node('p',t('ui.a154a47bff26', "변경 전 값만 보관돼 있습니다."),'app-muted'));
          const raw=node('details');raw.append(node('summary',t('ui.e078b14a70bc', "전체 보기")),node('pre',JSON.stringify(data,null,2),'app-history-json'));card.append(raw);
        }catch(error){card.append(node('p',error.message,'app-history-compare-error'));}
        entries.append(card);continue;
      }
      const kind = entry.kind === 'SNAPSHOT' ? t('ui.52e3d517bcff', "현재값 스냅샷 · 작성자는 조회한 계정") : entry.kind === 'REQUEST' ? t('ui.f37982f20f02', "변경 요청 · {0} / {1}", data.request?.operation, data.request?.attribute) : t('ui.5c239b968c9d', "미분류 호출 · 원문 확인 필요");
      card.append(node('p', kind, 'app-muted'));
      if (version) {
        card.append(node('p', version.notice, 'app-muted'));
        const actions = node('div', '', 'app-history-toolbar'), baseline = button(t('ui.eaea55bff600', "기준")), candidate = button(t('ui.c0154c812ab6', "비교"));
        baseline.addEventListener('click', () => choose(entry, 'left'));
        candidate.addEventListener('click', () => choose(entry, 'right'));
        rowControls.push({entry, baseline, candidate});
        actions.append(baseline, candidate); card.append(actions);
        const value = node('details'); value.append(node('summary', entry.kind === 'SNAPSHOT' ? t('ui.56317f96effd', "현재값 보기") : t('ui.f81a6295fca2', "요청값 보기")), node('pre', version.text, 'app-history-json')); card.append(value);
      } else if (entry.kind === 'REQUEST') card.append(node('p', t('ui.c819cf3b3581', "요청값을 추출하지 못했습니다. 원문을 확인해 주세요."), 'app-muted'));
      if (entry.kind !== 'SNAPSHOT') {
        card.append(node('p', t('ui.5b78d7e649b1', "감사 반환 코드 {0} · 0도 속성 반영 성공을 보장하지 않습니다.", data.returnCode ?? t('ui.dc0c487c9693', "미확인")), 'app-muted'));
        if (data.captureTruncated) card.append(node('p', t('ui.efebe3eca724', "원문이 앱 보관 한도(262,144자)를 초과해 일부만 보관했습니다."), 'app-history-compare-error'));
        const raw = node('details'); raw.append(node('summary', t('ui.dcd49416a780', "감사 원문")), node('pre', JSON.stringify(data, null, 2), 'app-history-json')); card.append(raw);
      }
      entries.append(card);
    }
    markSelection();
  }
  async function load(targetPage = 1) {
    const id = ++requestId; busy = true; controls(); message.textContent = t('ui.e630d4ebd914', "이력 조회 중…");
    try {
      const data = await api('', null, {profile, scope: 'profile', page: targetPage}); if (id !== requestId) return;
      page = data.page; hasNext = data.hasNext; drawRows(data.entries); find('page').textContent = t('ui.25be58ceeac8', "{0} 페이지", page); message.textContent = '';
    } catch (error) { if (id === requestId) { message.textContent = error.message; entries.replaceChildren(); hasNext = false; } }
    finally { if (id === requestId) { busy = false; controls(); } }
  }
  find('open').addEventListener('click', async () => {
    dialog.showModal(); busy = true; controls();
    await refreshState(); await load(1);
  });
  find('close').addEventListener('click', () => { if (!busy) dialog.close(); });
  dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
  dialog.addEventListener('close', () => { left = right = null; selectionError = ''; comparison.replaceChildren(); markSelection(); });
  find('state-refresh').addEventListener('click', async () => { busy = true; controls(); await refreshState(); busy = false; controls(); });
  find('prev').addEventListener('click', () => load(page - 1)); find('next').addEventListener('click', () => load(page + 1));
  find('install')?.addEventListener('click',async()=>{
    if(busy||!confirm(t('ui.08eadaea2595', "{0}의 공통 이력 보관 테이블을 준비할까요? 기존 Profile·Team·Agent·Task·Tool 이력을 복사·검증하며 원본은 삭제하지 않습니다. 감사 설정과 업무 값은 바꾸지 않습니다.", schema)))return;
    busy=true;controls();message.textContent=t('ui.51ce4c5b5246', "공통 이력 준비 중…");left=right=null;comparison.replaceChildren();
    try{state=await api('/install',{schema,profile});find('state').textContent=state.message;await load(1);message.textContent=t('ui.2feef4b8e5cc', "공통 이력을 준비했습니다.");}
    catch(error){message.textContent=error.message;await refreshState();}
    finally{busy=false;controls();}
  });
  toggle.addEventListener('change', async () => {
    const enabled = toggle.checked; toggle.checked = state?.enabled ?? false;
    const prompt = enabled ? t('ui.ed55222ca876', "{0} 계정 감사를 켤까요?\n이 계정의 DBMS_CLOUD_AI 전체 호출(SQLcl·AI 실행 포함)이 DB 감사에 남습니다. 보관 테이블과 전용 정책을 설치합니다. 기존 기록은 삭제하지 않습니다.", schema)
      : t('ui.6901431bf357', "{0} 계정 감사를 끌까요?\n이후 호출은 이 정책으로 추적하지 않습니다. 기존 보관 이력은 유지됩니다.", schema);
    if (!confirm(prompt)) return;
    busy = true; controls(); message.textContent = t('ui.5843480a83d8', "감사 설정 변경 중…");
    try { state = await api('/toggle', {schema, enabled}); find('state').textContent = state.message; message.textContent = t('ui.1e8e24870ef1', "감사 설정을 확인했습니다."); }
    catch (error) { message.textContent = error.message; await refreshState(); }
    finally { busy = false; controls(); }
  });
  collect.addEventListener('click', async () => {
    busy = true; controls(); message.textContent = t('ui.35a52eaff9f1', "감사 수집 및 현재값 보관 중…");
    try {
      const data = await api('/collect', {schema, profile});
      await load(1);
      message.textContent = t('ui.829c98c209a4', "감사 {0}건 · 요청/원문 {1}건 보관{2}.{3}", data.auditRows, data.entries, data.snapshotSaved ? t('ui.05df8fcf70f4', " · 현재값 스냅샷 저장") : '', data.more ? t('ui.3fcdef7f8ce2', " 남은 기록이 있습니다. 계속 수집해 주세요.") : t('ui.d2f505fc0bf6', " 원천 감사 반영은 지연될 수 있습니다."));
      collect.textContent = data.more ? t('ui.00d6ff288c11', "계속 수집") : t('ui.24b8a106083a', "이력 새로고침");
    } catch (error) { message.textContent = error.message; }
    finally { busy = false; controls(); }
  });
}
if (typeof document !== 'undefined') document.querySelectorAll('[data-profile-history]').forEach(mount);
