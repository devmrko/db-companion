import {t} from './i18n.mjs';
import './execution-history.mjs';

export function agentRequest(kind, schema, runId, order, page = 1) {
  if (!['task', 'conversations'].includes(kind)) throw new Error('Invalid agent history request');
  const params = new URLSearchParams({schema, runId, order});
  if (kind === 'conversations') params.set('page', page);
  return `/ai-executions/agents/${kind}?${params}`;
}
export function taskFields(detail) {
  return [['Task', detail.task], ['Agent', detail.agent], [t('ui.8576ea403d3e', "순번"), detail.order], [t('ui.e10195a1239f', "상태"), detail.state],
    [t('ui.5820f0a91949', "시작일시 (DB)"), detail.started], [t('ui.6e331c928584', "종료일시 (DB)"), detail.ended], [t('ui.73a91d202958', "실행 ID"), detail.runId], [t('ui.a53af20a117e', "대화 ID"), detail.conversationId]]
    .map(([label, value]) => [label, value == null || value === '' ? '—' : String(value)]);
}
export function createReadGate() {
  let controller, version = 0;
  return {
    begin() {
      controller?.abort(); controller = new AbortController();
      const current = ++version;
      return {signal: controller.signal, current: () => current === version};
    },
    cancel() { version++; controller?.abort(); },
  };
}
async function readJson(url, signal) {
  const result = await fetch(url, {signal, cache: 'no-store', headers: {Accept: 'application/json'}});
  if (result.redirected || !result.headers.get('content-type')?.includes('application/json')) throw new Error(t('ui.9f0bb0f1f663', "다시 로그인해 주세요."));
  const data = await result.json();
  if (!result.ok) throw new Error(data.error || t('ui.e4596b202ad1', "기록을 조회하지 못했습니다."));
  return data;
}

if (typeof document !== 'undefined') document.querySelectorAll('[data-agent-executions]').forEach(root => {
  const element = name => root.querySelector(`[data-agent-${name}]`);
  const dialog = element('dialog'), content = element('detail'), message = element('message');
  const taskGate = createReadGate(), conversationGate = createReadGate(), promptGate = createReadGate();
  let order, page = 1;
  function clearPrompt() {
    promptGate.cancel(); element('prompt-detail').hidden = true; element('prompt-message').textContent = '';
    element('prompt').textContent = ''; element('response').textContent = '';
  }
  function clearConversations() {
    conversationGate.cancel(); clearPrompt(); element('conversation-list').replaceChildren();
    element('conversation-pages').hidden = true; element('conversation-message').textContent = '';
  }
  function clearTask() {
    taskGate.cancel(); clearConversations(); content.hidden = true;
    element('summary').replaceChildren(); element('input').textContent = ''; element('result').textContent = '';
  }
  element('close').addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', clearTask);
  root.querySelectorAll('[data-agent-task]').forEach(button => button.addEventListener('click', async () => {
    clearTask(); order = button.dataset.agentTask; page = 1;
    const request = taskGate.begin(); message.textContent = t('ui.8bf609c884ca', "불러오는 중…"); dialog.showModal();
    try {
      const data = await readJson(agentRequest('task', root.dataset.schema, root.dataset.runId, order), request.signal);
      if (!request.current() || !dialog.open) return;
      taskFields(data).forEach(([label, value]) => {
        const row = document.createElement('div'), term = document.createElement('dt'), text = document.createElement('dd');
        term.textContent = label; text.textContent = value; row.append(term, text); element('summary').append(row);
      });
      element('input').textContent = data.input ?? '—'; element('result').textContent = data.result ?? '—';
      element('conversations').disabled = !data.conversationId;
      element('conversation-message').textContent = data.conversationId ? '' : t('ui.ccc748e9d037', "연결된 대화 ID가 없습니다.");
      content.hidden = false; message.textContent = '';
    } catch (error) {
      if (error.name !== 'AbortError' && request.current() && dialog.open) message.textContent = error.message;
    }
  }));
  async function showPrompt(id, selectedButton) {
    clearPrompt();
    element('conversation-list').querySelectorAll('button').forEach(button => button.removeAttribute('aria-current'));
    selectedButton.setAttribute('aria-current', 'true');
    const request = promptGate.begin(); element('prompt-message').textContent = t('ui.8bf609c884ca', "불러오는 중…");
    try {
      const data = await readJson(`/ai-executions/detail?${new URLSearchParams({schema: root.dataset.schema, id})}`, request.signal);
      if (!request.current() || !dialog.open) return;
      element('prompt').textContent = data.prompt ?? '—'; element('response').textContent = data.response ?? '—';
      element('prompt-detail').hidden = false; element('prompt-message').textContent = '';
    } catch (error) {
      if (error.name !== 'AbortError' && request.current() && dialog.open) element('prompt-message').textContent = error.message;
    }
  }
  async function showConversations(nextPage) {
    clearConversations();
    const request = conversationGate.begin(); element('conversation-message').textContent = t('ui.8bf609c884ca', "불러오는 중…");
    try {
      const data = await readJson(agentRequest('conversations', root.dataset.schema, root.dataset.runId, order, nextPage), request.signal);
      if (!request.current() || !dialog.open) return;
      page = data.prompts.number;
      data.prompts.items.forEach(item => {
        const row = document.createElement('div'), time = document.createElement('time'), button = document.createElement('button');
        row.className = 'app-run-message'; time.textContent = item.created;
        button.type = 'button'; button.className = 'app-inline-link app-table-description';
        button.textContent = item.preview || t('ui.e3ea1373abf1', "질문·응답 상세"); button.title = item.preview || '';
        button.addEventListener('click', () => showPrompt(item.id, button)); row.append(time, button); element('conversation-list').append(row);
      });
      element('conversation-message').textContent = !data.conversationId ? t('ui.ccc748e9d037', "연결된 대화 ID가 없습니다.")
        : data.prompts.items.length ? '' : t('ui.13dec5bdf99b', "보관된 질문·응답이 없습니다.");
      element('conversation-page').textContent = t('ui.25be58ceeac8', "{0} 페이지", page);
      element('conversation-prev').disabled = page <= 1;
      element('conversation-next').disabled = !data.prompts.hasNext || page >= 1000;
      element('conversation-pages').hidden = false;
      if (data.prompts.hasNext && page >= 1000) element('conversation-message').textContent = t('ui.3ac0383b8d23', "연결된 대화는 10,000건까지 조회할 수 있습니다.");
    } catch (error) {
      if (error.name !== 'AbortError' && request.current() && dialog.open) element('conversation-message').textContent = error.message;
    }
  }
  element('conversations').addEventListener('click', () => showConversations(1));
  element('conversation-prev').addEventListener('click', () => showConversations(page - 1));
  element('conversation-next').addEventListener('click', () => showConversations(page + 1));
});
