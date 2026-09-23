import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {profileBytes,validateProfileValue,profileRetryAllowed} from '../../main/resources/static/js/profile-editor.mjs';
test('long values are counted in UTF8 without clipping or normalizing',()=>{
  const value='지침 🌏\r\n  '+'x'.repeat(21000);
  assert.equal(profileBytes(value),Buffer.byteLength(value,'utf8'));
  assert.equal(validateProfileValue('additional_instructions',value),'');
  assert.equal(validateProfileValue('additional_instructions','x'.repeat(32767)),'');
  assert.ok(validateProfileValue('additional_instructions','가'.repeat(10923)));
});
test('blank NUL and invalid Unicode are refused without deletion semantics',()=>{
  for(const value of ['', '  ','\0','\uD800','\uDC00'])assert.ok(validateProfileValue('instructions',value));
});
test('only object_list is required to be a JSON array',()=>{
  assert.equal(validateProfileValue('object_list','[{"owner":"APP","name":"T"}]'),'');
  for(const value of ['{}','null','[broken','[] trailing'])assert.ok(validateProfileValue('object_list',value));
  assert.equal(validateProfileValue('instructions','not JSON'),'');
});
test('conflict permissions network and uncertain errors cannot be retried from stale form',()=>{
  assert.equal(profileRetryAllowed(400),true);
  for(const status of [undefined,401,403,409,500,503])assert.equal(profileRetryAllowed(status),false);
});
test('editor uses latest GET and protected POST without injecting HTML or silently retrying',()=>{
  const js=readFileSync(new URL('../../main/resources/static/js/profile-editor.mjs',import.meta.url),'utf8');
  assert.match(js,/method:'POST'/);assert.match(js,/csrf\.dataset\.csrfHeader/);assert.match(js,/version:state\.version/);
  assert.match(js,/if\(id!==generation\|\|!dialog\.open\)return/);assert.match(js,/initial=currentValue\(\)/);
  assert.ok(!js.includes('innerHTML'));assert.ok(!js.includes('setInterval'));assert.ok(!js.includes('.slice('));
  assert.ok(!js.includes('변경 전·후 전체값을 이력에 보관합니다'));
  assert.match(js,/if\(result\.verified\)initial=submitted/);
  assert.ok(!js.includes('reload || !dirty()'));
});
test('routine instructions are omitted while empty and error states remain',()=>{
  const editor=readFileSync(new URL('../../main/resources/static/js/profile-editor.mjs',import.meta.url),'utf8');
  const objects=readFileSync(new URL('../../main/resources/static/js/profile-objects.mjs',import.meta.url),'utf8');
  for(const copy of ['스키마를 선택하고 조회하세요','객체 내용은 조회하지 않습니다','현재 계정의 활성 Credential입니다','기존 프로필값을 선택하거나 직접 입력하세요'])assert.ok(!(editor+objects).includes(copy));
  assert.match(editor,/활성 Credential이 없습니다/);assert.match(objects,/note.textContent=error.message/);
});
