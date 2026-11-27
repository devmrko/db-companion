import test from 'node:test';
import assert from 'node:assert/strict';
import {creationCommand,selectedObjects,mergeObjects,creationConversation} from '../../main/resources/static/js/profile-creation-chat.mjs';
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
    review(title,before,after,save){state.body=node('fieldset');state.save=save;state.review=structuredClone(after);},
    async api(url){state.requests.push({url});if(url.includes('/form?'))return {sourceVersion:'a'.repeat(64),historyReady:ready,credentials:['CRED']};
      if(url.includes('/profile-options?'))return {schemas:['APP'],objects:{objects:rows,more:false},models:['MODEL'],regions:['R']};
      if(url.includes('/history?'))return {entries:[]};throw new Error(url);},
    async post(url,data){state.requests.push({url,data});if(url.endsWith('/preview'))return {token:'single-use',input:data,feedback:[]};
      if(url.endsWith('/create'))return {name:'AU_PROFILE',verified,done:verified};throw new Error(url);}};
  state.chat=creationConversation(ui);return state;
}
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
