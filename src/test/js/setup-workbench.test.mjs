import test from 'node:test';
import assert from 'node:assert/strict';
import {requestIntent,appendSource,conversationRequest} from '../../main/resources/static/js/setup-workbench.mjs';

test('bounded chat commands distinguish instructions from explanation and negation',()=>{
  assert.deepEqual(requestIntent('대화 저장 켜기'),{kind:'attribute',attribute:'conversation',value:'true'});
  assert.deepEqual(requestIntent('comments 끄기'),{kind:'attribute',attribute:'comments',value:'false'});
  assert.deepEqual(requestIntent('turn off conversation'),{kind:'attribute',attribute:'conversation',value:'false'});
  for(const request of ['comments는 뭐야?','comments 켜지 말고 현재 상태 알려줘','이 DB의 설정을 전부 알아서 해줘'])assert.equal(requestIntent(request).kind,'assistant');
  assert.equal(requestIntent('이 프로필에 업무 용어를 연결해줘').kind,'sources');
  assert.equal(requestIntent('업무 용어 등록').kind,'glossary');
  assert.equal(requestIntent('AU 용어 수정').kind,'glossary');
  assert.equal(requestIntent('변경 이력 조회').kind,'history');
  assert.equal(requestIntent('취소').kind,'cancel');
  for(const request of ['Connect business definitions','業務用語を接続','连接业务定义'])assert.equal(requestIntent(request).kind,'sources');
  for(const request of ['Register business term','業務用語を登録','注册业务术语'])assert.equal(requestIntent(request).kind,'glossary');
  assert.deepEqual(requestIntent('모델 변경해줘'),{kind:'attribute',attribute:'model',value:null});
});
test('semantic attachment preserves existing sources and requires an enabled server choice',()=>{
  const choices=[{name:'BUSINESS',status:'ENABLED'},{name:'OFF',status:'DISABLED'}];
  assert.equal(appendSource('["EXISTING"]','BUSINESS',choices),'["EXISTING","BUSINESS"]');
  assert.equal(appendSource('["BUSINESS"]','BUSINESS',choices),'["BUSINESS"]');
  for(const before of ['{}','[1]','["A","A"]','broken'])assert.throws(()=>appendSource(before,'BUSINESS',choices));
  assert.throws(()=>appendSource('[]','OFF',choices));
  assert.throws(()=>appendSource('[]','NOT_RETURNED',choices));
});
test('AI context is bounded and never truncates the current user request',()=>{
  assert.equal(conversationRequest([],'current'),'current');
  assert.match(conversationRequest([{role:'assistant',content:'previous'}],'current'),/assistant: previous\nuser: current/);
  assert.equal(conversationRequest([{role:'assistant',content:'a'.repeat(2000)}],'whole current request'),'whole current request');
});
