import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {helpFor,operationsFor} from '../../main/resources/static/js/sql-help.mjs';

test('default mapping help surfaces the login grant, exposure, refresh and history limits',()=>{
  const h=helpFor('executions','ko','mapping');
  assert.match(h.sql,/-- GRANT READ ON SYS\.V_\$MAPPED_SQL TO "<LOGIN_USER>";/);
  assert.match(h.purpose,/로그인 계정/);assert.match(h.result,/다른 계정 SQL/);
  assert.match(h.result,/앱 재시작은 필요 없습니다/);assert.match(h.result,/모든 GENERATE 호출을 보장하는 이력이 아닙니다/);
  assert.equal(h.risk,'read');assert.match(h.doc,/V-MAPPED_SQL/);
  assert.match(readFileSync('src/main/resources/templates/ai-mapped-sql.html','utf8'),/sqlHelp\('executions', 'mapping'\)/);
});
test('separate grant recipe is copy-only DDL and grants only the mapped view',()=>{
  const h=helpFor('executions','en','mapping-access');assert.equal(h.risk,'ddl');
  const sql=h.sql.split('\n').filter(l=>!l.trim().startsWith('--')).join('\n').trim();
  assert.equal(sql,'GRANT READ ON SYS.V_$MAPPED_SQL TO "<LOGIN_USER>";');
  assert.match(h.verify,/WHERE 1 = 0/);assert.match(h.result,/never executes this GRANT/);
  assert.match(h.result,/one view only/);assert.match(h.result,/implicitly commit/);
  assert.ok(operationsFor('executions').some(o=>o.id==='mapping-access'));
});
test('conversation alternative distinguishes durable history from short-term and isolated calls',()=>{
  const h=helpFor('executions','ko','conversations');assert.equal(h.risk,'read');
  assert.match(h.sql,/USER_CLOUD_AI_CONVERSATION_PROMPTS/);assert.match(h.purpose,/권한은 필요하지 않습니다/);
  for(const text of ['장기 대화','conversation_id','conversation=false','소급','이전 질문·응답'])assert.ok(h.result.includes(text),text);
  assert.doesNotMatch(h.sql,/CREATE_CONVERSATION|GENERATE\(|GRANT /);
  const source=readFileSync('src/main/java/com/dbcompanion/repository/AiAssistantRepository.java','utf8');
  assert.match(source,/GET_CONVERSATION_ID IS NOT NULL/);assert.ok(source.includes('conversation\\\":false'));
});
test('selected test history is independent but does not claim automatic full execution capture',()=>{
  const h=helpFor('executions','en','saved-tests');assert.equal(h.risk,'read');
  assert.match(h.sql,/DBC_APP_RECORD/);assert.match(h.sql,/RECORD_TYPE = 'SELECT_AI_ATTEMPT'/);assert.match(h.sql,/\$\.parentId/);
  assert.match(h.result,/does not automatically capture every execution/);assert.match(h.result,/Unsaved historical runs cannot be recovered/);
  for(const locale of ['ko','en','ja','zh-CN'])assert.ok(helpFor('executions',locale,'mapping-access').result);
});
