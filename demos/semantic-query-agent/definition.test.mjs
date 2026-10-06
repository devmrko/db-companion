import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {definitions,names} from './definition.mjs';
const sql=readFileSync(new URL('./query-tool.sql',import.meta.url),'utf8');
test('one team, one agent, one task and one custom tool; no alternate SQL path',()=>{
  const items=definitions({owner:'TEST_OWNER',agentProfile:'TEST_PROFILE'});
  assert.deepEqual(items.map(i=>i.kind),['TOOL','AGENT','TASK','TEAM']);
  assert.deepEqual(items[2].attributes.tools,[names.tool]);
  assert.deepEqual(items[3].attributes.agents,[{name:names.agent,task:names.task}]);
  assert.equal(items[0].attributes.function,'TEST_OWNER.DBC_SEMANTIC_QUERY');
  assert.equal(items[0].attributes.tool_type,undefined);
});
test('deployment identifiers cannot inject SQL/configuration',()=>{
  assert.throws(()=>definitions({owner:'TEST_OWNER;DROP',agentProfile:'P'}));
  assert.throws(()=>definitions({owner:'O',agentProfile:"P'"}));
});
test('glossary, query mode and result limit are constants, not tool arguments',()=>{
  const args=definitions({owner:'O',agentProfile:'P'})[0].attributes.tool_inputs.map(i=>i.name);
  assert.deepEqual(args,['p_question','p_use_ontology','p_tables_json']);
  assert.match(sql,/p_use_glossary\s*=>\s*1/);
  assert.match(sql,/p_mode\s*=>\s*'SQL'/);
  assert.match(sql,/query_rows\(src.get_clob\('sql'\),1000\)/);
  assert.ok(sql.indexOf("brief.put('status','GENERATED')")<sql.indexOf("phase := 'execute_generated_sql'"));
  assert.match(sql,/p_max_rows\s*=>\s*1000/);
  assert.equal((sql.match(/DBC_AI_QUERY\.ASK\(/g)||[]).length,1);
  assert.match(sql,/AUTHID CURRENT_USER/);
  assert.doesNotMatch(sql,/CREATE OR REPLACE|AUTHID DEFINER|EXECUTE IMMEDIATE|COMMIT;|ROLLBACK;/);
});
test('actual rows, evidence identities and errors remain distinguishable',()=>{
  assert.match(sql,/out_json.put\('result',src.get\('result'\)\)/);
  assert.match(sql,/out_json.put\('sql',src.get\('sql'\)\)/);
  assert.match(sql,/NO_MATCHED_BUSINESS_TERMS/);
  assert.match(sql,/RESULT_TRUNCATED_DO_NOT_INFER_TOTALS_OR_COMPLETE_CHARTS/);
  assert.match(sql,/out_json.put\('status','ERROR'\)/);
  assert.match(sql,/out_json.put\('oracleCode',failure_code\)/);
  assert.match(sql,/out_json.put\('retryable',FALSE\)/);
});
test('outer Agent conversation is restored after successful or failed inner query',()=>{
  assert.match(sql,/DBMS_CLOUD_AI.CLEAR_CONVERSATION_ID/);
  assert.match(sql,/DBMS_CLOUD_AI.SET_CONVERSATION_ID\(saved_conversation\)/);
  assert.equal((sql.match(/restore_conversation;/g)||[]).length,2);
  assert.doesNotMatch(sql,/DROP_CONVERSATION|SET_CONVERSATION_ID\(NULL\)/);
});
