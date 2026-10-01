import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {accessRequest,permissionOperation,grantText} from '../../main/resources/static/js/history-access.mjs';
import {historyInspectionView} from '../../main/resources/static/js/metadata-history.mjs';
test('switch requests do not implicitly grant any users',()=>{
  assert.deepEqual(accessRequest('t',['T'],'ENABLE',['READER'],true),{token:'t',tables:['T'],operation:'ENABLE',users:[],allUsers:false});
});
test('PUBLIC is explicit and individual overrides stay distinct',()=>{
  assert.deepEqual(accessRequest('t',['T'],'READ',['READER'],true).users,[]);
  assert.deepEqual(accessRequest('t',['T'],'DENY',['READER'],false).users,['READER']);
  assert.equal(permissionOperation('INHERIT'),true);assert.equal(permissionOperation('ENABLE'),false);
  assert.match(grantText({user:'READER',read:true,manage:false}),/READER/);
});
test('delegated inspection displays verified status, never missing catalog triggers',()=>{
  const result=historyInspectionView({delegatedState:{installed:true,healthy:true,enabled:true,canManage:false},note:'scoped'});
  assert.equal(result.active,true);assert.equal(result.next,'scoped');
});
test('UI is explicit, escaped, responsive and never auto applies',()=>{
  const js=readFileSync('src/main/resources/static/js/history-access.mjs','utf8');
  assert.ok(!js.includes('innerHTML'));assert.ok(js.includes('response.redirected'));assert.ok(js.includes('form.inert=true'));
  const html=readFileSync('src/main/resources/templates/fragments/history-access.html','utf8');
  assert.ok(html.includes('required data-access-confirm'));assert.ok(html.includes('data-access-csrf'));
  assert.ok(readFileSync('src/main/resources/static/css/history-access.css','utf8').includes('@media(max-width: 620px)'));
});
