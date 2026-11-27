import test from 'node:test';
import assert from 'node:assert/strict';
import {creationCommand,selectedObjects,mergeObjects,creationConversation,creationAttributesJson} from '../../main/resources/static/js/profile-creation-chat.mjs';
import {isConversationQuestion,turnDisposition} from '../../main/resources/static/js/conversation-turn.mjs';
import {requestIntent} from '../../main/resources/static/js/setup-workbench.mjs';
import {navigationItems} from '../../main/resources/static/js/assistant-navigation.mjs';
const rows=[{name:'ACTIVITY',type:'TABLE'},{name:'USERS',type:'TABLE'},{name:'REGIONS',type:'VIEW'}];
test('where is execution history answers with actual routes without selecting an AI model',()=>{
  assert.equal(requestIntent('실행 내역은 어디서봐?').kind,'navigation');
  assert.equal(requestIntent('질문 이력 보여줘').kind,'navigation');
  assert.equal(requestIntent('프로필 설정 어디 있어?').kind,'navigation');
  assert.equal(requestIntent('변경 이력 조회').kind,'history');
  const items=navigationItems('실행 내역은 어디서봐?','P&X');
  assert.equal(items[0].href,'/ai-executions?profile=P%26X');
  assert.equal(items[1].href,'/ai-executions/agents');
  assert.equal(items[2].href,'/ai-executions/sql/sources');
});
test('creation and ADMIN questions do not require an existing target and respect negation',()=>{
  assert.equal(requestIntent('프로파일 만들어봐').kind,'create-profile');
  assert.equal(requestIntent('프로필 생성하지 마 설명만').kind,'assistant');
  assert.equal(requestIntent('프로필 생성에 필요한 ADMIN 권한이 뭐야?').kind,'permissions');
  assert.equal(requestIntent('시멘틱 소스 권한은?').kind,'permissions');
  assert.equal(requestIntent('이전 프로파일 복제 가능?').kind,'clone-profile');
  assert.equal(requestIntent('프로파일 생성 가능한가요?').kind,'help');
  assert.equal(requestIntent('WS_ADMIN_PROFILE').kind,'assistant');
  assert.deepEqual(creationCommand('모델은 openai.gpt-5.4-mini 쓰자'),{kind:'field',key:'model',value:'openai.gpt-5.4-mini'});
  assert.deepEqual(creationCommand('이름은 AU_PROFILE로 바꿔'),{kind:'field',key:'name',value:'AU_PROFILE'});
  assert.equal(creationCommand('모델은 변경하지 마'),null);
  assert.deepEqual(creationCommand('어떤 모델이 있어?'),{kind:'models'});
});
test('ordinal table references resolve only against the displayed list and preserve existing choices',()=>{
  const selected=selectedObjects('첫 번째와 세 번째 테이블 넣어',rows,'APP');
  assert.deepEqual(selected,[{owner:'APP',name:'ACTIVITY'},{owner:'APP',name:'REGIONS'}]);
  assert.deepEqual(mergeObjects(selected,selected),selected);
  assert.deepEqual(mergeObjects(selected,[selected[0]],true),[selected[1]]);
  for(const q of ['그거','999번','MISSING','ACTIVITY, MISSING'])assert.throws(()=>selectedObjects(q,rows,'APP'));
  assert.deepEqual(selectedObjects('APP.USERS',rows,'APP'),[{owner:'APP',name:'USERS'}]);
});
function harness({ready=true,verified=true}={}){
  const state={requests:[],messages:[],handler:null,save:null,created:null,values:[],body:null};
  const node=(tag,text)=>({tag,textContent:text,children:[],value:'',append(...items){this.children.push(...items);},setAttribute(){},addEventListener(){}});
  const ui={schema:'APP',lang:'en',el:node,workspace(){state.body=node('fieldset');return state.body;},body:()=>state.body,
    field(host,label,value){const n=node('input',label);n.value=value;host.append(n);return n;},button:node,
    ask(message,handler){state.handler=handler;state.messages.push(message);},say(role,message){state.messages.push(message);},detail(host,label,value){state.values.push(structuredClone(value));},link(){},created:name=>state.created=name,
    profileNames:()=>['BASE_PROFILE','MODEL_HISTORY'],
    review(title,before,after,save){state.body=node('fieldset');state.save=save;state.review=structuredClone(after);},
    async api(url){state.requests.push({url});if(url.includes('/form?'))return {sourceVersion:(new URL('http://local'+url).searchParams.get('source')?'b':'a').repeat(64),historyReady:ready,credentials:['CRED'],description:'Original description',attributes:'{"provider":"oci","model":"MODEL","seed":9223372036854775807,"temperature":0.1234567890123456789,"object_list":[{"owner":"APP","name":"ACTIVITY"}]}'};
      if(url.includes('/profile-options?'))return {schemas:['APP'],objects:{objects:rows,more:false},models:['MODEL'],regions:['R']};
      if(url.includes('/history?'))return {entries:[]};throw new Error(url);},
    async post(url,data){state.requests.push({url,data});if(url.endsWith('/preview'))return {token:'single-use',input:data,feedback:[]};
      if(url.endsWith('/create'))return {name:'AU_PROFILE',verified,done:verified};throw new Error(url);}};
  state.chat=creationConversation(ui);return state;
}
test('questions and global tasks take precedence over value prompts while exact names remain literal',()=>{
  for(const question of ['이전 프로파일 복제 가능?','모델은 뭐야?','어떤 이름이 좋아?','복제 가능한가요'])assert.equal(isConversationQuestion(question),true);
  for(const q of ['AU_PROFILE','MODEL_HISTORY','WS_ADMIN_PROFILE'])assert.equal(isConversationQuestion(q),false);
  for(const intent of ['clone-profile','permissions','navigation','history','glossary'])assert.equal(turnDisposition(intent,{hasReply:true}),'dispatch');
  assert.equal(turnDisposition('history',{hasReply:true,literal:true}),'reply');
  assert.equal(turnDisposition('clone-profile',{hasReply:true,content:true}),'reply');
  assert.equal(turnDisposition('cancel',{hasReply:true,literal:true}),'dispatch');
});
test('identifier-shaped answers remain names even when also ordinary question words',async()=>{
  const h=harness();await h.chat.start();await h.handler('WHAT');await h.handler('1번');await h.handler('다음');await h.handler('모델은 MODEL 쓰자');await h.handler('이대로 만들어');assert.equal(h.review.name,'WHAT');
});
test('clone question during name input opens actual source choices instead of name validation',async()=>{
  const h=harness();await h.chat.start();await h.handler('이전 프로파일 복제 가능?');
  assert(h.messages.some(v=>v.includes('가능합니다.')));assert(!h.messages.some(v=>v.includes('125자')));
  assert.equal(h.requests.filter(v=>v.data).length,0);
  await h.handler('모델은 뭐야?');await h.handler('BASE_PROFILE');await h.handler('AU_COPY');await h.handler('다음');
  await h.handler('모델은 NEW_MODEL 쓰자');await h.handler('이대로 만들어');
  const request=h.requests.find(v=>v.url==='/ai-create/preview').data;
  assert.equal(request.source,'BASE_PROFILE');assert.equal(request.sourceVersion,'b'.repeat(64));assert.equal(request.copyFeedback,false);
  assert.equal(request.name,'AU_COPY');assert.equal(request.description,'Original description');
  assert.match(request.attributes,/"seed":9223372036854775807/);assert.match(request.attributes,/"temperature":0.1234567890123456789/);assert.match(request.attributes,/"model":"NEW_MODEL"/);
  assert.equal(h.requests.filter(v=>v.url.endsWith('/create')).length,0);
});
test('questions at name table model and review stages keep the draft and never become parameter values',async()=>{
  const h=harness();await h.chat.start();await h.handler('왜 이름이 필요해?');await h.handler('AU_PROFILE');
  await h.handler('1번');await h.handler('모델이 뭐야?');await h.handler('계속');await h.handler('다음');
  await h.handler('모델은 MODEL 쓰자');await h.handler('이대로 만들어');await h.handler('다른 것도 할 수 있어?');await h.handler('계속');await h.handler('이대로 만들어');
  assert.equal(h.review.name,'AU_PROFILE');assert.deepEqual(h.review.object_list,[{owner:'APP',name:'ACTIVITY'}]);assert.equal(h.review.model,'MODEL');
  assert.equal(h.requests.filter(v=>v.url.endsWith('/create')).length,0);
});
test('clone serialization preserves untouched numeric and nested values and refuses ambiguous originals',()=>{
  const original='{"seed":9223372036854775807,"model":"OLD","custom":{"threshold":0.1234567890123456789}}';
  const changed={...JSON.parse(original),model:'NEW'};
  assert.equal(creationAttributesJson(changed,original),'{"seed":9223372036854775807,"model":"NEW","custom":{"threshold":0.1234567890123456789}}');
  for(const raw of ['{"a":1,"a":2}','[]','{"custom":{"a":1,"a":2}}'])assert.throws(()=>creationAttributesJson({},raw));
});
test('new profile dialogue accumulates and edits a draft, previews without writes, then reuses create and history',async()=>{
  const h=harness();await h.chat.start();await h.handler('AU_PROFILE');await h.handler('첫 번째와 세 번째 테이블 넣어');
  await h.handler('첫 번째 테이블 빼');await h.handler('다음');await h.handler('모델은 MODEL 쓰자');await h.handler('이대로 만들어');
  assert.equal(h.review.name,'AU_PROFILE');assert.deepEqual(h.review.object_list,[{owner:'APP',name:'REGIONS'}]);assert.equal(h.review.sourceVersion,undefined);
  assert.equal(h.requests.filter(r=>r.url.endsWith('/create')).length,0);
  await h.save();assert.equal(h.created,'AU_PROFILE');assert.equal(h.chat.active(),false);
  assert.deepEqual(h.requests.find(r=>r.url.endsWith('/create')).data,{token:'single-use',confirmed:true,consent:true});
  assert.ok(h.requests.some(r=>r.url.includes('/history?')));
});
test('missing history, cancellation and uncertain result never claim creation or retry writes',async()=>{
  const blocked=harness({ready:false});await blocked.chat.start();assert.equal(blocked.chat.active(),false);assert.equal(blocked.requests.length,1);
  const h=harness({verified:false});await h.chat.start();await h.handler('AU_PROFILE');await h.handler('1번');await h.handler('다음');await h.handler('모델은 MODEL 쓰자');await h.handler('create');await h.save();
  assert.equal(h.created,null);assert.equal(h.requests.filter(r=>r.url.endsWith('/create')).length,1);
  const cancelled=harness();await cancelled.chat.start();cancelled.chat.clear();assert.equal(cancelled.chat.active(),false);assert.equal(cancelled.requests.length,1);
});
