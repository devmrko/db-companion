import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {credentialPage,refreshCredentials,resultMessage} from '../../main/resources/static/js/credentials.mjs';

test('credential search is contains and pagination is ten rows',()=>{
  const items=Array.from({length:23},(_,i)=>({name:`CRED_${i}`,owner:'APP',enabled:'TRUE'}));
  assert.equal(credentialPage(items,'',1).items.length,10);assert.equal(credentialPage(items,'',2).from,11);
  assert.equal(credentialPage(items,'',99).items.length,3);assert.equal(credentialPage(items,'red_2',1).total,4);
  assert.equal(credentialPage(items,'missing',1).total,0);assert.equal(items.length,23);
});
test('refresh waits for list and does not select a deleted or inaccessible credential',async()=>{
  const order=[];let release;
  const work=refreshCredentials(async()=>{order.push('list');return await new Promise(r=>release=r);},async name=>order.push(name),'C');
  assert.deepEqual(order,['list']);release({status:'AVAILABLE',items:[{name:'C'}]});await work;assert.deepEqual(order,['list','C']);
  for(const data of [null,{status:'ERROR',items:[]},{status:'AVAILABLE',items:[]}])await refreshCredentials(async()=>data,()=>assert.fail('detail must not load'),'C');
});
test('errors remain errors instead of empty success',()=>{
  for(const status of ['ACCESS_REQUIRED','UNSUPPORTED','LIMIT','ERROR'])assert.ok(resultMessage({status,error:'ORA-00942',source:'SYS.USER_CREDENTIALS'}).includes('ORA-00942'));
});
test('safe rendering lazy loading and four language help',()=>{
  const code=fs.readFileSync('src/main/resources/static/js/credentials.mjs','utf8');
  assert.doesNotMatch(code,/innerHTML|insertAdjacentHTML|eval\(|localStorage|sessionStorage|method:\s*['"]POST/);
  assert.match(code,/textContent/);assert.match(code,/pending\?\.abort\(\)/);assert.match(code,/stamp===version/);
  const values=JSON.parse(fs.readFileSync('tools/i18n/feature-credentials.json','utf8'));
  for(const [key,translations] of Object.entries(values)){
    assert.equal(translations.length,4,key);translations.forEach((text,i)=>{assert.ok(text.length);if(i)assert.doesNotMatch(text,/[가-힣]/u,key);});
  }
  assert.match(values['credentials.help'][0],/참조 0건은 미사용을 뜻하지/);
  assert.match(values['credentials.help'][0],/다른 스키마/);
});
