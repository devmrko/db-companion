import test from 'node:test';
import assert from 'node:assert/strict';
import {editPair,changedProfileFields,selectProfileVersion,profileSelection,versionOf} from '../../main/resources/static/js/profile-history.mjs';

test('profile edit keeps complete long before/after and actual actor',()=>{
  const original='한글 지침\n'.repeat(5000),before={exists:true,attributes:{instruction:original}},after={exists:true,attributes:{instruction:original+'...'}};
  const entry={seq:7,profile:'P',actor:'LOGIN_USER',kind:'EDIT',storage:'COMMON',payload:JSON.stringify({before,after,outcome:'VERIFIED',attribute:'instruction'})};
  const pair=editPair(entry);
  assert.equal(pair.left.actor,'LOGIN_USER');assert.equal(JSON.parse(pair.left.payload).attributes.instruction,original);
  assert.equal(JSON.parse(pair.right.payload).attributes.instruction,original+'...');
  assert.notEqual(pair.left.seq,pair.right.seq);
  const changes=changedProfileFields(pair.left,pair.right).changes;
  assert.equal(changes.length,1);assert.equal(changes[0].name,'instruction');
  assert.match(versionOf(pair.right).notice,/앱 저장 변경 후.*저장 확인/);
  assert.equal(selectProfileVersion({left:pair.left},pair.right,'right').right,pair.right);
});
test('before-only and uncertain are not reported as verified or fabricated after values',()=>{
  const entry={seq:8,profile:'P',kind:'EDIT',payload:JSON.stringify({before:{exists:true,attributes:{}},after:null,outcome:'BEFORE_SAVED'})};
  const pair=editPair(entry);assert.equal(pair.right,null);assert.match(versionOf(pair.left).notice,/확인 필요/);
  assert.throws(()=>editPair({...entry,payload:'{}'}));assert.equal(editPair({...entry,kind:'REQUEST'}),null);
});
test('legacy and common sequence collisions cannot change version selection',()=>{
  const entry={seq:1,profile:'P',kind:'SNAPSHOT',payload:'{"exists":true}',storage:'LEGACY'};
  const other={...entry,storage:'COMMON'};
  assert.equal(profileSelection(other,entry,null).baseline,false);
  assert.equal(selectProfileVersion({left:entry},other,'right').right,other);
  assert.match(versionOf(entry).notice,/조회 시점/);
});
