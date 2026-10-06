/* Loaded after ask_oracle_function.js. Does not rerun SQL or an Agent. */
(function installResultRecovery() {
  'use strict';
  if (window.dbcResultRecoveryInstalled) return;
  window.dbcResultRecoveryInstalled = true;
  const original = apex.server.process;
  let active;
  const item = name => String(apex.item(name).getValue() || '');
  const element = (tag, text, parent) => {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (parent) parent.append(node);
    return node;
  };
  function createPanel(request, conversation) {
    if (active) { clearTimeout(active.timer); active.panel.remove(); }
    const panel = element('section');
    panel.id = 'dbc-saved-query-result';
    panel.style.cssText = 'position:relative;margin:16px 16px 220px;padding:16px;border:1px solid #b8cbd5;border-radius:8px;background:#fff;color:#182d39;max-width:100%;';
    panel.setAttribute('aria-label', 'SQL 조회 결과 보관');
    element('h3', 'SQL 조회 결과 · AI 답변과 별도 보관', panel);
    const status = element('p', '조회 결과를 기다리는 중입니다. AI 답변이 지연되어도 보관된 결과를 확인할 수 있습니다.', panel);
    status.setAttribute('role', 'status');
    const refresh = element('button', '보관 결과 확인', panel); refresh.type = 'button';
    const content = element('div', undefined, panel);
    const host = document.querySelector('#chat-container') || document.querySelector('main') || document.body;
    host.append(panel);
    const state = {request, conversation, panel, status, refresh, content, attempts:0, busy:false, saved:false, final:false};
    refresh.onclick = () => retrieve(state);
    active = state;
    // Request IDs only, never business result cells/SQL or credentials.
    if (request) try { sessionStorage.setItem('dbc-result-request', JSON.stringify({request, conversation})); } catch (_) {}
    return state;
  }
  function current(state) { return state === active && item('P1_CONV_ID') === state.conversation; }
  function show(state, data) {
    state.saved = true;
    state.content.replaceChildren();
    const result = data.result || {}, rows = result.rows || [], columns = result.columns || [];
    state.status.textContent = `SQL 조회 성공 · 보관 ${rows.length}행${result.more ? ' (최대 1,000행, 이후 행 있음)' : ''}. ${state.final ? 'AI 요청이 종료되었습니다.' : 'AI 답변은 아직 완료되지 않았을 수 있습니다.'} 일부 행으로 전체 합계를 추정하지 않습니다.`;
    element('p', '보관 결과는 업무 정확성 검증을 뜻하지 않습니다. 보관 기한은 7일이며 현재 로그인 세션·대화에서만 확인할 수 있습니다.', state.content);
    const sql = element('details', undefined, state.content);
    element('summary', '실행된 SQL', sql);
    const pre = element('pre', data.sql || '', sql); pre.style.cssText = 'white-space:pre-wrap;overflow-wrap:anywhere;';
    const nav = element('div', undefined, state.content);
    const previous = element('button', '이전', nav); previous.type = 'button';
    const label = element('span', '', nav);
    const next = element('button', '다음', nav); next.type = 'button';
    const scroll = element('div', undefined, state.content); scroll.style.overflowX = 'auto';
    const table = element('table', undefined, scroll); table.style.cssText = 'border-collapse:collapse;width:100%;';
    const header = element('tr', undefined, element('thead', undefined, table));
    for (const column of columns) element('th', column, header).scope = 'col';
    const body = element('tbody', undefined, table);
    let page = 0;
    function render() {
      body.replaceChildren();
      for (const row of rows.slice(page * 25, (page + 1) * 25)) {
        const tr = element('tr', undefined, body);
        for (const cell of row) {
          const td = element('td', cell === null ? 'NULL' : String(cell), tr);
          td.style.cssText = 'padding:6px;border-bottom:1px solid #ddd;overflow-wrap:anywhere;';
        }
      }
      label.textContent = ` ${rows.length ? page * 25 + 1 : 0}–${Math.min((page + 1) * 25, rows.length)} / 보관 ${rows.length}행 `;
      previous.disabled = page === 0; next.disabled = (page + 1) * 25 >= rows.length;
    }
    previous.onclick = () => { page--; render(); }; next.onclick = () => { page++; render(); }; render();
  }
  function retrieve(state) {
    if (!current(state) || state.busy) return;
    state.busy = true; state.refresh.disabled = true;
    original.call(apex.server, 'DBC_GET_SAVED_QUERY_RESULT', {x01:state.request, x02:state.conversation}, {
      dataType:'json', timeout:15000,
      success(data) {
        if (!current(state)) return;
        if (data.status === 'SUCCESS') show(state, data);
        else if (data.status === 'ERROR') {
          state.saved = true; state.content.replaceChildren();
          state.status.textContent = `조회 실패 · ${data.message || '도구 오류'} · 단계: ${data.phase || '확인 불가'}. 결과를 0이나 조회 성공으로 표시하지 않습니다. AI를 자동 재호출하지 않습니다.`;
          if (data.sql) {
            const detail = element('details', undefined, state.content);
            element('summary', '실패한 생성 SQL 확인', detail);
            element('pre', data.sql, detail).style.cssText = 'white-space:pre-wrap;overflow-wrap:anywhere;';
          }
        }
        else if (data.status === 'GENERATED') {
          state.status.textContent = 'SQL 생성 완료 · 실행 결과 대기 중입니다. 아직 조회 성공이 아닙니다.';
        }
        else if (!state.saved) state.status.textContent = data.status === 'PENDING'
          ? 'SQL 조회 결과가 아직 보관되지 않았습니다. 조회가 진행 중이거나 실패했을 수 있습니다.'
          : '현재 세션·대화에서 확인할 보관 결과가 없습니다. SQL이나 Agent를 자동 재실행하지 않습니다.';
      },
      error() { if (current(state)) state.status.textContent = '보관 결과 확인 요청이 지연되거나 실패했습니다. 아래 버튼은 보관 결과만 확인하며 AI를 재호출하지 않습니다.'; },
      complete() {
        state.busy = false; state.refresh.disabled = false;
        if (current(state) && !state.saved && !state.final && ++state.attempts < 18)
          state.timer = setTimeout(() => retrieve(state), 10000);
      }
    });
  }
  apex.server.process = function(name, data, options) {
    const team = item('P1_AGENT_PROFILE');
    if (name !== 'EXECUTE_PROMPT' || team !== 'DBC_SEMANTIC_QUERY_TEAM' || !item('P1_CONV_ID'))
      return original.apply(this, arguments);
    const request = crypto.randomUUID().replaceAll('-', '');
    const state = createPanel(request, item('P1_CONV_ID'));
    const wrapped = {...options};
    for (const key of ['success', 'error']) wrapped[key] = function(...args) {
      state.final = true; clearTimeout(state.timer);
      try { return options?.[key]?.apply(this, args); }
      finally { if (current(state)) retrieve(state); }
    };
    state.timer = setTimeout(() => retrieve(state), 10000);
    return original.call(this, name, {...data, x10:request}, wrapped);
  };
  function syncConversation() {
    if (active && !current(active)) {
      clearTimeout(active.timer); active.panel.remove(); active = null;
    }
    if (!active) try {
      const stored = JSON.parse(sessionStorage.getItem('dbc-result-request') || 'null');
      if (stored && /^[a-f0-9]{32}$/.test(stored.request) && stored.conversation === item('P1_CONV_ID')) {
        const state = createPanel(stored.request, stored.conversation);
        state.final = true;
        state.status.textContent = '이 대화의 보관 결과 확인 버튼을 누르세요. AI 또는 SQL을 다시 실행하지 않습니다.';
      }
    } catch (_) {}
    if (!active && item('P1_AGENT_PROFILE') === 'DBC_SEMANTIC_QUERY_TEAM' && item('P1_CONV_ID')) {
      const state = createPanel('', item('P1_CONV_ID'));
      state.final = true;
      state.status.textContent = '이 대화의 가장 최근 보관 결과를 확인할 수 있습니다. 버튼은 AI나 SQL을 다시 실행하지 않습니다.';
    }
  }
  // Conversation navigation is AJAX-based; clear an old panel even after polling ends.
  setInterval(syncConversation, 1000);
  syncConversation();
})();
