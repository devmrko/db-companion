import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,readdirSync,existsSync} from 'node:fs';
import {helpRegistry,helpFor,operationsFor,featureForPath,featureForHelpOpener,mountSqlHelp} from '../../main/resources/static/js/sql-help.mjs';
const read=path=>readFileSync(path,'utf8');
const common=read('src/main/resources/templates/fragments/common.html');

test('global generic help is removed and unknown features/operations do not invent SQL',()=>{
  assert.doesNotMatch(common,/data-sql-help-open/);
  for(const key of ['overview','unknown','']){assert.equal(helpFor(key),null);assert.deepEqual(operationsFor(key),[]);}
  assert.equal(featureForPath('/'),null);assert.equal(helpFor('profiles','ko','nonexistent'),null);
  for(const ops of Object.values(helpRegistry))for(const op of ops)assert.doesNotMatch(op.sql,/대응 SQL이 없습니다|No user-run equivalent|GET \/ontology|POST \/ontology/);
});
test('all recipes have SQL, source, localized effects and a specific official or project reference',()=>{
  assert.ok(Object.keys(helpRegistry).length>=18);
  for(const [feature,ops]of Object.entries(helpRegistry)){
    assert.equal(new Set(ops.map(op=>op.id)).size,ops.length,feature);
    for(const op of ops){
      assert.ok(existsSync(op.source),op.source);
      assert.match(op.sql,/\b(SELECT|BEGIN|INSERT|UPDATE|ALTER|COMMENT|CREATE|GRANT)\b/,feature+':'+op.id);
      for(const lang of ['ko','en','ja','zh-CN','fr']){
        const help=helpFor(feature,lang,op.id);
        for(const key of ['title','purpose','variables','permission','effects','result','source'])assert.ok(help[key],feature+':'+key);
        if(help.docKind==='oracle')assert.match(help.doc,/^https:\/\/docs\.oracle\.com\/.+\.html(?:#.*)?$/);
        else {
          assert.equal(help.docKind,'project');
          const [path,anchor]=help.doc.split('#');
          assert.equal(path,'/help/sql-reference.html');
          assert.ok(read('src/main/resources/static'+path).includes(`id="${anchor}"`),help.doc);
          assert.match(help.labels.doc,/DB Companion/);
        }
        assert.equal(help.sql,op.sql);assert.equal(help.labels[op.risk]!==undefined,true);
      }
    }
  }
});
test('audit fixes retain default operations and make reference labels change with selection',()=>{
  for(const feature of ['ords','agents'])assert.equal(operationsFor(feature)[0].id,'list');
  const h=harness();h.open('tables','list');
  assert.match(h.node('[data-sql-help-doc]').href,/ALL_TAB_COLUMNS.html$/);
  h.change('history-delegated');
  assert.equal(h.node('[data-sql-help-doc]').href,'/help/sql-reference.html#metadata-history');
  h.change('list');assert.match(h.node('[data-sql-help-doc]').href,/ALL_TAB_COLUMNS.html$/);
  for(const locale of ['ko','en','ja','zh-CN']){
    assert.match(helpFor('tables',locale,'history').labels.doc,/DB Companion/);
    assert.match(helpFor('tables',locale,'list').labels.doc,/Oracle/);
  }
});
test('task definition help reads definitions instead of team execution history',()=>{
  const help=helpFor('agents','ko','task-definition');
  assert.match(help.sql,/USER_AI_AGENT_TASKS WHERE TASK_NAME/);
  assert.match(help.sql,/USER_AI_AGENT_TASK_ATTRIBUTES/);assert.match(help.sql,/USER_AI_AGENT_TOOLS/);
  assert.doesNotMatch(help.sql,/HISTORY|RUN_TEAM/);assert.match(help.result,/Team/);
  assert.match(read('src/main/resources/templates/ai-agent-task.html'),/sqlHelp\('agents', 'task-definition'\)/);
});
test('delegated history documents installer, session authorization and one-based lookahead paging',()=>{
  const help=helpFor('tables','ko','history-delegated');
  assert.match(help.sql,/<AUDIT_OWNER>"\."DBC_METADATA_ACCESS"\.inspect\(/);
  assert.match(help.sql,/\.entries\('<TARGET_SCHEMA>', '<TABLE_OR_VIEW>', 1\)/);
  assert.match(help.result,/SESSION_USER/);assert.match(help.result,/10건/);
  assert.match(help.permission,/직접 SELECT 권한은 이 경로의 필수 조건이 아닙니다/);
  assert.doesNotMatch(help.sql,/GRANT|CREATE|set_enabled\(|FROM.*DBC_METADATA_HISTORY/);
  const source=read('src/main/resources/db/history/access-body.sql');
  assert.match(source,/FUNCTION entries\(p_schema VARCHAR2, p_table VARCHAR2, p_page NUMBER\)/);
  assert.match(source,/\(p_page-1\)\*10/);assert.match(source,/FETCH NEXT 11 ROWS ONLY/);
});
test('executed result review uses a prepared prompt and does not claim numerical validation',()=>{
  const help=helpFor('ai-test','ko','result-review');
  assert.match(help.sql,/prompt => :prepared_review_prompt/);assert.match(help.sql,/:prepared_review_prompt IS NULL/);
  assert.match(help.sql,/action => 'chat'/);assert.match(help.sql,/"conversation":false/);
  assert.equal(help.risk,'ai');assert.match(help.result,/결과 셀 값은 보내지 않습니다/);
  assert.match(help.result,/SQL 해시/);assert.match(help.result,/실제 수치 정답/);
  assert.doesNotMatch(help.sql,/runsql|EXECUTE IMMEDIATE|SELECT .*FROM/);
  assert.match(read('src/main/resources/templates/ai-test.html'),/sqlHelp\('ai-test', 'result-review'\)/);
});
test('discovery help distinguishes persisted plans, receipts, candidates and approval',()=>{
  const help=helpFor('ontology','ko','discovery-history');
  for(const type of ['ONTOLOGY_DISCOVERY_PLAN','ONTOLOGY_DISCOVERY_CALL'])assert.ok(help.sql.includes(type));
  assert.match(help.sql,/\$\.runId/);assert.match(help.sql,/\$\.index' RETURNING NUMBER\) \+ 1/);
  assert.match(help.result,/계획 저장 성공/);assert.match(help.result,/CANDIDATE/);assert.match(help.result,/자동 재시도하지 않습니다/);
  assert.equal(help.risk,'read');assert.doesNotMatch(help.sql,/\b(INSERT|UPDATE|DELETE|GENERATE|CREATE)\b/);
  assert.match(read('src/main/resources/templates/fragments/ontology-relationships.html'),/sqlHelp\('ontology', 'discovery-history'\)/);
});
test('ORDS publication and populated-module path changes preserve descendants',()=>{
  const publish=helpFor('ords','en','publish'),path=helpFor('ords','en','module-path');
  assert.match(publish.sql,/PUBLISH_MODULE\(p_module_name => '<MODULE>', p_status/);
  assert.match(path.sql,/RENAME_MODULE\(p_module_name => '<MODULE>', p_new_base_path/);
  assert.match(path.sql,/PUBLISH_MODULE/);
  for(const help of [publish,path]){assert.match(help.sql,/COMMIT;/);assert.equal(help.risk,'write');assert.doesNotMatch(help.sql,/DEFINE_MODULE|DELETE_|DROP_/);}
  const source=read('src/main/java/com/dbcompanion/service/OrdsManagementService.java');
  assert.match(source,/"PUBLISH_MODULE","p_module_name => \?, p_status => \?"/);
  assert.match(source,/"RENAME_MODULE","p_module_name => \?, p_new_base_path => \?"/);
});
test('profile creation and edits mirror source API and modify only a selected attribute',()=>{
  const create=helpFor('profiles','ko','create'),model=helpFor('profiles','ko','model');
  assert.match(create.sql,/CREATE_PROFILE\(/);assert.match(create.sql,/attributes => a/);assert.match(create.sql,/status => 'ENABLED'/);
  for(const operation of ['model','objects','options']){
    const help=helpFor('profiles','ko',operation);
    assert.match(help.sql,/SET_ATTRIBUTE\(/);assert.doesNotMatch(help.sql,/DROP_PROFILE|CREATE_PROFILE/);
    assert.match(help.verify,/USER_CLOUD_AI_PROFILE_ATTRIBUTES/);assert.equal(help.risk,'write');
  }
  assert.match(model.sql,/attribute_name => 'model'/);assert.match(model.result,/삭제·재생성하지/);
  assert.match(helpFor('profiles','en','objects').sql,/'object_list'/);
  assert.match(helpFor('profiles','en','options').variables,/<ATTRIBUTE_VALUE>/);
});
test('metadata recipes cover table and column comments, annotations and readback',()=>{
  assert.match(helpFor('tables','ko','comment').sql,/COMMENT ON TABLE/);
  assert.match(helpFor('tables','ko','column-comment').sql,/COMMENT ON COLUMN/);
  assert.match(helpFor('tables','ko','annotation').sql,/ANNOTATIONS \(ADD/);
  assert.match(helpFor('tables','ko','column-annotation').sql,/MODIFY.*\n.*ANNOTATIONS \(REPLACE/);
  for(const op of ['comment','column-comment','annotation','column-annotation']){
    const help=helpFor('tables','ko',op);assert.equal(help.risk,'ddl');assert.ok(help.verify);assert.match(help.effects,/COMMIT/);
  }
});
test('Select AI help uses stateless GENERATE with explicit profile, action and conversation guard',()=>{
  for(const op of ['showsql','showprompt','chat']){
    const help=helpFor('ai-test','en',op);
    assert.match(help.sql,/GET_CONVERSATION_ID IS NOT NULL/);assert.match(help.sql,/DBMS_CLOUD_AI\.GENERATE/);
    assert.match(help.sql,/profile_name => '<PROFILE_NAME>'/);assert.ok(help.sql.includes(`action => '${op}'`));
    assert.match(help.sql,/"conversation":false/);assert.match(help.sql,/PRINT ai_response/);
    assert.equal(help.risk,'ai');assert.doesNotMatch(help.sql,/action => 'runsql'|EXECUTE IMMEDIATE/);
  }
});
test('ontology and saved questions show real storage and preserve approval/parent scope',()=>{
  assert.match(helpFor('ontology').sql,/DBC_ONTOLOGY_CATALOG/);
  assert.match(helpFor('ontology','en','append').sql,/INSERT INTO.*DBC_ONTOLOGY_CATALOG/);
  assert.match(helpFor('ontology','en','append').result,/not the full approval workflow/);
  assert.match(helpFor('ontology-terms').sql,/WHERE RN = 1 AND STATE = 'APPROVED'/);
  assert.match(helpFor('ontology-terms','en','text').sql,/PROFILE_REF.*\n.*SOURCE_REVISION/);
  assert.match(helpFor('ontology-terms','en','text').sql,/CONTAINS\(SEARCH_TEXT, :escaped_contains_expression/);
  assert.match(helpFor('problem-questions','en','attempts').sql,/SELECT_AI_ATTEMPT.*\n.*parentId/);
  assert.match(helpFor('problem-questions','en','save').result,/Storage steps only/);
  assert.match(helpFor('ontology-drift').result,/원본을 고치지 않고/);
});
test('question analysis setup matches source and is independent of dictionary tables and indexes',()=>{
  const help=helpFor('ontology-query','ko','analysis-setup');
  assert.equal(help.risk,'ddl');assert.match(help.sql,/CTX_DDL.CREATE_POLICY\('DBC_QA_KO_POLICY'/);
  assert.doesNotMatch(help.sql,/CREATE TABLE|CREATE INDEX|DROP_|GRANT|DBC_BUSINESS_TERM|DBC_BT_CTX/);
  assert.match(help.verify,/CTX_DOC.POLICY_TOKENS/);assert.match(help.result,/자동 설치하지 않습니다/);
  const source=read('src/main/java/com/dbcompanion/common/db/QuestionAnalysisSql.java');
  const statements=text=>[...text.matchAll(/CTX_DDL\.[^;]+;/g)].map(x=>x[0].replace(/\s+/g,''));
  assert.deepEqual(statements(help.sql),statements(source.split('public static String setup(QuestionLanguage')[0]));
});
test('ontology query help distinguishes saved JSON, path traversal, RDF evidence and real rows',()=>{
  const help=helpFor('ontology-query','ko','definitions');
  assert.match(help.purpose,/Oracle Text/);assert.match(help.purpose,/SPARQL을 직접 실행하는 구조가 아닙니다/);
  assert.match(help.purpose,/생성 SQL을 검토하고 별도로 실행/);
  assert.match(help.sql,/ROW_NUMBER\(\) OVER/);assert.match(help.sql,/WHERE RN = 1/);
  for(const path of ['$.source.keys','$.meaning.columns','$.meaning.relations','$.links'])assert.ok(help.sql.includes(path));
  assert.match(help.sql,/\$\.links'[\s\S]*?ERROR ON ERROR NULL ON EMPTY/);
  assert.match(help.result,/서로 다른 2개/);assert.match(help.result,/초안 명칭은 검색에 사용하지만 AI 업무 정의 근거에서는 제외/);
  assert.match(help.result,/재현하지 않습니다/);assert.equal(help.risk,'read');
  assert.match(read('src/main/resources/templates/ontology-query.html'),/sqlHelp\('ontology-query', 'definitions'\)/);
});
test('question token help uses an existing policy without installing or calling AI',()=>{
  const help=helpFor('ontology-query','ko','analysis-tokens');
  assert.match(help.sql,/CTX_DOC.POLICY_TOKENS\(/);assert.match(help.sql,/tokens\.FIRST/);assert.match(help.sql,/tokens\.NEXT\(n\)/);
  assert.ok(help.variables.includes('<POLICY_NAME>'));assert.ok(help.variables.includes('<QUESTION>'));
  assert.doesNotMatch(help.sql,/CREATE_|CREATE TABLE|CREATE INDEX|DBMS_CLOUD_AI/);assert.equal(help.risk,'read');
});
test('FK verification aligns composite columns and manual row SQL uses only the selected joins',()=>{
  const fk=helpFor('ontology-query','ko','fk-columns');
  assert.match(fk.sql,/pc\.POSITION = fc\.POSITION/);assert.match(fk.sql,/fk\.STATUS = 'ENABLED'/);assert.match(fk.sql,/fk\.VALIDATED = 'VALIDATED'/);
  assert.match(fk.sql,/pk\.OWNER = fk\.R_OWNER/);assert.match(fk.result,/스냅샷과 다를 수 있습니다/);
  const join=helpFor('ontology-query','ko','join-rows');
  assert.match(join.sql,/s\."<SOURCE_KEY>" = m\."<LINK_SOURCE_KEY>"/);
  assert.match(join.sql,/m\."<LINK_TARGET_KEY>" = t\."<TARGET_KEY>"/);
  assert.match(join.sql,/FETCH FIRST 200 ROWS ONLY/);assert.match(join.result,/INNER JOIN은 연결이 없는 행을 제외/);
  assert.match(join.result,/DDS\/VPD/);assert.doesNotMatch(join.sql,/SEM_MATCH|GRAPH_TABLE|DBMS_CLOUD_AI|INSERT|UPDATE|DELETE/);
});
test('ontology SQL generation requires prepared evidence and mirrors request-only overrides',()=>{
  const help=helpFor('ontology-query','ko','generate-sql');
  assert.equal(help.risk,'ai');assert.match(help.sql,/:final_prompt IS NULL OR :request_attributes IS NULL/);
  assert.match(help.sql,/GET_CONVERSATION_ID IS NOT NULL/);assert.match(help.sql,/action => 'showsql'/);
  assert.match(help.sql,/prompt => :final_prompt/);assert.match(help.sql,/attributes => :request_attributes/);
  assert.match(help.sql,/"comments":false,"annotations":false/);assert.match(help.sql,/"enforce_object_list":true/);
  assert.doesNotMatch(help.sql,/SET_ATTRIBUTE\(|EXECUTE IMMEDIATE|action => 'runsql'/);
  assert.match(help.result,/원 질문만 전달하면 선택한 관계가 자동 첨부되지 않습니다/);
  const source=read('src/main/java/com/dbcompanion/repository/OntologyQueryRepository.java');
  assert.match(source,/action => 'showsql', attributes => \?/);
});
test('credential examples never insert live credentials or expose private keys in literal SQL',()=>{
  assert.match(helpFor('credentials','en','api-key').sql,/private_key => :private_key_pem/);
  assert.match(helpFor('credentials','en','api-key').variables,/:private_key_pem/);
  assert.match(helpFor('credentials','en','api-key').result,/not a catalog-screen API call/);
  assert.match(helpFor('credentials','en','resource-principal').sql,/Run as ADMIN/);
  assert.match(helpFor('executions','en','awr').result,/licensing/);
  assert.match(helpFor('feedback','en','list').result,/does not prove/);
});
test('controls localize four languages and explicitly announce English recipe fallback',()=>{
  assert.equal(helpFor('profiles','ko').labels.copy,'SQL 복사');
  assert.equal(helpFor('profiles','ja').labels.copy,'SQL をコピー');
  assert.equal(helpFor('profiles','zh-CN').labels.copy,'复制 SQL');
  assert.equal(helpFor('profiles','fr').purpose,helpFor('profiles','en').purpose);
  assert.ok(helpFor('profiles','ja').labels.fallback);assert.ok(helpFor('profiles','zh-CN').labels.fallback);
  assert.equal(helpFor('profiles','ko').labels.fallback,'');
  for(const lang of ['ja','zh-CN'])assert.notEqual(helpFor('ai-test',lang).effects,helpFor('ai-test','en').effects);
});
test('all wired screen and operation keys resolve; every primary SQL screen has an explicit opener',()=>{
  const root='src/main/resources/templates';
  const walk=dir=>readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(dir+'/'+e.name):[dir+'/'+e.name]);
  for(const file of walk(root)){
    const text=read(file);
    for(const match of text.matchAll(/sqlHelp\('([^']+)', '([^']+)'\)/g))assert.ok(helpFor(match[1],'ko',match[2]),file+':'+match[0]);
    for(const match of text.matchAll(/data-sql-help-for="([a-z-]+)"/g))assert.ok(helpFor(match[1]),file+':'+match[1]);
  }
  for(const page of ['ai-profiles','tables','table-detail','credentials','ai-test','ai-feedback','ai-agents','ai-executions','scheduler','external-sources','deep-data-security','functions','vector-search','ontology','ontology-query','ai-assistant'])
    assert.match(read(root+'/'+page+'.html'),/fragments\/common :: sqlHelp\(/,page);
  assert.match(read(root+'/fragments/profile-editor.html'),/sqlHelp\('profiles', 'options'\)/);
  assert.match(read(root+'/ai-profiles.html'),/sqlHelp\('profiles', 'create'\)/);
});
test('help rendering is text-only, copy-only, and has labeled operation/verification controls',()=>{
  const source=read('src/main/resources/static/js/sql-help.mjs');
  assert.doesNotMatch(source,/innerHTML|fetch\(|XMLHttpRequest|window\.open|\.submit\(/);
  assert.match(common,/for="sql-help-operation"/);assert.match(common,/id="sql-help-operation"/);
  assert.match(common,/data-sql-help-status aria-live="polite"/);
  assert.match(common,/data-sql-help-verification hidden/);assert.match(source,/dialog\.addEventListener\('close'/);
  assert.equal(featureForHelpOpener({dataset:{sqlHelpFor:'ai-comparison'}},'ai-test'),'ai-comparison');
  assert.match(read('src/main/resources/static/css/common.css'),/\.app-sql-help-dialog \.app-preview-body\s*\{[^}]*grid-template-columns: minmax\(0, 1fr\)/);
});
test('creation and edit dialogs select SQL help for their actual operation, without binding live values',()=>{
  const creation=read('src/main/resources/static/js/ai-creation.mjs');
  assert.match(creation,/help\.dataset\.sqlHelpFor=kind==='PROFILE'\?'profiles':'agents'/);
  assert.match(creation,/'create-'\+kind\.toLowerCase\(\)/);
  const profile=read('src/main/resources/static/js/profile-editor.mjs');
  assert.match(profile,/sqlHelpOperation=attr==='model'\?'model':attr==='object_list'\?'objects':'options'/);
  const metadata=read('src/main/resources/static/js/metadata-editor.mjs');
  assert.match(metadata,/column\.addEventListener\('change', updateSqlHelp\)/);
  for(const op of ['column-comment','comment','column-annotation-add','column-annotation','annotation','annotation-replace'])
    assert.ok(helpFor('tables','ko',op),op);
});

// Event contract harness only, not a visual/browser test.
function harness(){
  const nodes=new Map(),listeners={};
  const node=key=>{if(!nodes.has(key))nodes.set(key,{textContent:'',hidden:false,dataset:{},children:[],listeners:{},
    addEventListener(type,fn){this.listeners[type]=fn;},setAttribute(name,value){this[name]=value;},
    replaceChildren(){this.children=[];},append(value){this.children.push(value);},focus(){this.focused=true;}});return nodes.get(key);};
  const doc={documentElement:{lang:'ko'},addEventListener(type,fn){listeners[type]=fn;},createElement:()=>({})};
  const dialog={open:false,querySelector:node,querySelectorAll:()=>[],addEventListener(type,fn){listeners['dialog:'+type]=fn;},showModal(){this.open=true;},close(){this.open=false;listeners['dialog:close']();}};
  const root={ownerDocument:doc,querySelector:()=>dialog};mountSqlHelp(root,'profiles');
  const open=(feature,operation)=>{const opener={dataset:{sqlHelpFor:feature,...(operation?{sqlHelpOperation:operation}:{})},focus(){this.focused=true;}};listeners.click({target:{closest:()=>opener}});return opener;};
  return {node,dialog,open,change(id){node('[data-sql-help-operation]').value=id;node('[data-sql-help-operation]').listeners.change();}};
}
test('opening and switching operations updates SQL, risk, readback and source without leaking stale help',()=>{
  const h=harness(),opener=h.open('profiles','model');
  assert.equal(h.dialog.open,true);assert.equal(h.node('[data-sql-help-operation]').focused,true);
  assert.match(h.node('[data-sql-help-sql]').textContent,/SET_ATTRIBUTE/);
  assert.equal(h.node('[data-sql-help-verification]').hidden,false);
  assert.equal(h.node('[data-sql-help-risk]').dataset.risk,'write');
  h.node('[data-sql-help-status]').textContent='stale copy';h.change('list');
  assert.equal(h.node('[data-sql-help-status]').textContent,'');assert.equal(h.node('[data-sql-help-verification]').hidden,true);
  assert.equal(h.node('[data-sql-help-risk]').dataset.risk,'read');
  h.node('[data-sql-help-close]').listeners.click();assert.equal(opener.focused,true);
  h.open('ontology','current');assert.match(h.node('[data-sql-help-sql]').textContent,/DBC_ONTOLOGY_CATALOG/);
  assert.doesNotMatch(h.node('[data-sql-help-sql]').textContent,/SET_ATTRIBUTE/);
});
test('unknown features/operations do not open a misleading fallback',()=>{
  const h=harness();h.open('overview');assert.equal(h.dialog.open,false);
  h.open('profiles','unknown');assert.equal(h.dialog.open,false);
});
test('copy buttons copy only the selected operation or its verification, and report failure',async()=>{
  const old=Object.getOwnPropertyDescriptor(globalThis,'navigator');let text='';
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{clipboard:{writeText:async value=>{text=value;}}}});
  try{
    const h=harness();h.open('profiles','model');
    h.node('[data-sql-help-copy]').listeners.click();await new Promise(resolve=>setImmediate(resolve));
    assert.equal(text,helpFor('profiles','ko','model').sql);assert.doesNotMatch(text,/CREATE_PROFILE/);
    h.node('[data-sql-help-copy-verify]').listeners.click();await new Promise(resolve=>setImmediate(resolve));
    assert.equal(text,helpFor('profiles','ko','model').verify);
    navigator.clipboard.writeText=async()=>{throw Error('denied');};
    h.node('[data-sql-help-copy]').listeners.click();await new Promise(resolve=>setImmediate(resolve));
    assert.match(h.node('[data-sql-help-status]').textContent,/복사하지 못/);
  }finally{if(old)Object.defineProperty(globalThis,'navigator',old);else delete globalThis.navigator;}
});
