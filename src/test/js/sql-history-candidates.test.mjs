import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {candidateEvidence, candidateAccess, candidateOffset, renderCandidates} from '../../main/resources/static/js/sql-history-candidates.mjs';

test('candidate labels distinguish session, coordinator, weak evidence and query failure', () => {
  assert.match(candidateEvidence('SESSION'), /세션·일련번호 일치/);
  assert.match(candidateEvidence('COORDINATOR'), /병렬 조정/);
  assert.match(candidateEvidence('TIME_SCHEMA'), /세션 연결 미확인/);
  assert.match(candidateAccess('UNAVAILABLE'), /조회할 수 없습니다/);
  assert.match(candidateAccess('UNCONFIRMED'), /완료하지 못했습니다/);
  assert.match(candidateAccess('AVAILABLE'), /증명하지 않습니다/);
  assert.match(candidateOffset(7), /\+7초/);
  assert.match(candidateOffset(-2), /-2초/);
  assert.match(candidateOffset(0), /소요시간 아님/);
});
class Node {
  constructor(tag) { this.tag=tag;this.children=[];this.textContent=''; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children=children; }
}
function flatten(node) { return [node,...node.children.flatMap(flatten)]; }
test('candidate renderer preserves full SQL as text, zero metrics, omissions and uncertainty', () => {
  const previous=globalThis.document;globalThis.document={createElement:tag=>new Node(tag)};
  try {
    const root=new Node('div'),sql="SELECT '<img src=x onerror=alert(1)>' FROM APP.SALES";
    renderCandidates(root,{schema:'APP',anchorTime:'2026-01-01',sessionEvidence:'UNAVAILABLE',omitted:2,limited:true,items:[{sqlId:'test',sql,objects:['APP.SALES'],offsetSeconds:7,executions:0,elapsedSeconds:0,evidence:'TIME_SCHEMA'}]});
    const nodes=flatten(root);
    assert.equal(nodes.find(n=>n.tag==='pre').textContent,sql);
    assert.equal(nodes.filter(n=>n.tag==='img').length,0);
    assert.ok(nodes.some(n=>n.textContent.includes('누적 실행: 0회')));
    assert.ok(nodes.some(n=>n.textContent.includes('제외 2개')));
    assert.ok(nodes.some(n=>n.textContent.includes('전체 후보 목록이 아닙니다')));
    renderCandidates(root,{items:[],sessionEvidence:'NOT_CHECKED',omitted:0});
    assert.ok(!flatten(root).some(n=>n.tag==='pre'));
    assert.ok(flatten(root).some(n=>n.textContent.includes('생성 SQL이 없었다는 뜻은 아닙니다')));
  } finally { globalThis.document=previous; }
});
test('lookup is explicit, cancellable and read-only; table identifiers remain inside real cells', () => {
  const js=readFileSync('src/main/resources/static/js/sql-history-candidates.mjs','utf8');
  for(const text of ['new URLSearchParams({id: selection})','controller?.abort()','current !== version || !dialog.open',"cache: 'no-store'"]) assert.ok(js.includes(text));
  for(const text of ['innerHTML','insertAdjacentHTML','setInterval','eval(',"method: 'POST'"]) assert.ok(!js.includes(text));
  const html=readFileSync('src/main/resources/templates/ai-sql-history.html','utf8');
  assert.ok(html.includes('<span class="app-table-name"'));
  assert.ok(!html.includes('<td th:unless="${kind == \'audit\'}" class="app-table-name"'));
  assert.ok(html.includes('data-sql-candidates hidden'));
  const css=readFileSync('src/main/resources/static/css/common.css','utf8');
  assert.match(css,/\.app-sql-source-table td \{ display: table-cell/);
});
