import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {lineDiff, compactRows, snapshotChanges, valueChange} from '../../main/resources/static/js/profile-diff.mjs';
import {changedProfileFields, selectProfileVersion, profileSelection} from '../../main/resources/static/js/profile-history.mjs';

const side = (result, side) => result.rows.filter(r => r.kind !== (side === 'before' ? 'added' : 'removed')).map(r => r.text).join('');
const snapshot = data => ({profile: 'P', kind: 'SNAPSHOT', payload: JSON.stringify(data)});
const request = (value, quality = 'LITERAL') => ({profile:'P',kind:'REQUEST',payload:JSON.stringify({request:{attribute:'instructions',value,quality}})});

test('line insert delete replace and empty values reconstruct both original texts exactly', () => {
  for (const [before, after] of [['',''],['','first\n'],['last',''],['a\nb\nc\n','a\nB\nc\n'],
    ['a\nb\n','a\nx\nb\n'],['a\nx\nb\n','a\nb\n'],['one','one\n'],['a\r\nb\r\n','a\nb\n'],
    ["한글 👩‍💻 é\n'\"<script>","한글 👩‍💻 é\n새 값"]]) {
    const diff=lineDiff(before,after);
    assert.equal(side(diff,'before'),before); assert.equal(side(diff,'after'),after);
  }
});
test('old and new line numbers follow insertions and deletions independently', () => {
  assert.deepEqual(lineDiff('a\nb\n','a\nx\nb\n').rows.map(r=>[r.kind,r.oldLine,r.newLine]),
    [['equal',1,1],['added',null,2],['equal',2,3]]);
  assert.deepEqual(lineDiff('a\nx\nb\n','a\nb\n').rows.map(r=>[r.kind,r.oldLine,r.newLine]),
    [['equal',1,1],['removed',2,null],['equal',3,2]]);
});
test('long unchanged text is hidden with context while complete rows are retained', () => {
  const before=Array.from({length:400},(_,i)=>`line ${i}\n`).join('');
  const after=before.replace('line 201\n','changed 201\n');
  const diff=lineDiff(before,after), compact=compactRows(diff.rows);
  assert.ok(compact.length<12);
  assert.equal(compact.filter(r=>r.kind==='removed').map(r=>r.text).join(''),'line 201\n');
  assert.equal(compact.filter(r=>r.kind==='added').map(r=>r.text).join(''),'changed 201\n');
  assert.equal(compact.filter(r=>r.kind==='gap').reduce((n,r)=>n+r.count,0),395);
  assert.equal(side(diff,'before'),before); assert.equal(side(diff,'after'),after);
  assert.throws(()=>compactRows(diff.rows,-1));
});
test('multiple distant changes create separate gaps without duplicating or dropping changed rows', () => {
  const before=Array.from({length:30},(_,i)=>`line ${i}\n`).join('');
  const diff=lineDiff(before,before.replace('line 2\n','first\n').replace('line 25\n','second\n'));
  const compact=compactRows(diff.rows);
  assert.deepEqual(compact.filter(r=>r.kind==='added').map(r=>r.text),['first\n','second\n']);
  assert.ok(compact.some(r=>r.kind==='gap'));
});
test('large replacements use bounded fallback without cutting any characters', () => {
  const before='same\n'+'가\n'.repeat(1000)+'end',after='same\n'+'나\n'.repeat(1000)+'end';
  const result=lineDiff(before,after); assert.equal(result.coarse,true);
  assert.equal(side(result,'before'),before); assert.equal(side(result,'after'),after);
});
test('snapshot diff selects changed attributes and decodes real instruction newlines', () => {
  const original={exists:true,profile:{PROFILE_ID:'303',STATUS:'ENABLED'},attributes:{additional_instructions:'same\n'.repeat(4000)+'end',model:'model',object_list:'[{"name":"T"}]'}};
  const modified=structuredClone(original); modified.attributes.additional_instructions+='\n...';
  const changes=snapshotChanges(original,modified);
  assert.equal(changes.length,1); assert.equal(changes[0].name,'additional_instructions');
  assert.ok(changes[0].rows.some(r=>r.kind==='added' && r.text==='...'));
  assert.ok(compactRows(changes[0].rows).length<10);
  assert.equal(side(changes[0],'before'),original.attributes.additional_instructions);
  assert.equal(side(changes[0],'after'),modified.attributes.additional_instructions);
  assert.deepEqual(snapshotChanges(original,structuredClone(original)),[]);
});
test('missing NULL empty string literal markers and types remain distinct', () => {
  const values=[{present:false,value:undefined},{present:true,value:null},{present:true,value:''},
    {present:true,value:'null'},{present:true,value:'항목 없음'},{present:true,value:1},{present:true,value:'1'}];
  for(let i=0;i<values.length;i++) for(let j=0;j<values.length;j++) {
    const a=values[i],b=values[j],change=valueChange('x',a.value,b.value,a.present,b.present);
    assert.equal(change===null,i===j);
    if(change) assert.ok(change.rows.some(r=>r.kind!=='equal'));
  }
  assert.equal(snapshotChanges({exists:true,attributes:{}},{exists:true}).length,1);
});
test('all profile metadata and attributes including added removed keys stay comparable', () => {
  const result=snapshotChanges({exists:true,profile:{STATUS:'ENABLED',DESCRIPTION:'before'},attributes:{gone:'x',a:null}},
    {exists:true,profile:{STATUS:'DISABLED',DESCRIPTION:'after'},attributes:{added:'y',a:''}});
  assert.equal(result.length,5);
  assert.ok(result.some(r=>r.name==='gone' && r.afterState==='항목 없음'));
  assert.ok(result.some(r=>r.name==='added' && r.beforeState==='항목 없음'));
  assert.ok(result.some(r=>r.name==='프로필 · STATUS'));
});
test('request comparison retains audit uncertainty and never compares mixed meanings', () => {
  const equal=changedProfileFields(request('prefix','BIND_UNVERIFIED'),request('prefix','BIND_UNVERIFIED'));
  assert.deepEqual(equal.changes,[]); assert.match(equal.left.notice,/전체 여부 미확인/);
  assert.equal(changedProfileFields(request(null),request('')).changes.length,1);
  assert.throws(()=>changedProfileFields(snapshot({exists:true}),request('value')));
  assert.throws(()=>changedProfileFields(request('a'),{...request('b'),profile:'OTHER'}));
  assert.throws(()=>changedProfileFields(snapshot({exists:true}),{profile:'P',kind:'SNAPSHOT',payload:'broken'}));
});
test('UI uses text nodes, preserves full view, and CSS distinguishes signs without color alone', () => {
  const js=readFileSync(new URL('../../main/resources/static/js/profile-history.mjs',import.meta.url),'utf8');
  const css=readFileSync(new URL('../../main/resources/static/css/common.css',import.meta.url),'utf8');
  assert.ok(!js.includes('innerHTML')); assert.match(js,/전체 보기/); assert.match(js,/full\.addEventListener\('toggle'/);
  assert.match(js,/row\.kind === 'removed' \? '-' : row\.kind === 'added' \? '\+'/);
  assert.match(css,/\.app-diff-added/); assert.match(css,/\.app-diff-removed/); assert.match(css,/overflow-wrap: anywhere/);
});
test('changing comparison 2 to 3 and back to 2 retains baseline and restores the actual difference', () => {
  const original={...snapshot({exists:true,attributes:{instructions:'original'}}),seq:1};
  const modified={...snapshot({exists:true,attributes:{instructions:'original\n...'}}),seq:2};
  const returned={...original,seq:3};
  let selected=selectProfileVersion({left:null,right:null},original,'left');
  for(const [entry,count] of [[modified,1],[returned,0],[modified,1]]) {
    selected=selectProfileVersion(selected,entry,'right');
    assert.strictEqual(selected.left,original);
    assert.equal(changedProfileFields(selected.left,selected.right).changes.length,count);
  }
  assert.deepEqual(profileSelection(original,selected.left,selected.right),{baseline:true,candidate:false,compareDisabled:true});
  assert.deepEqual(profileSelection(modified,selected.left,selected.right),{baseline:false,candidate:true,compareDisabled:false});
  assert.equal(original.payload,JSON.stringify({exists:true,attributes:{instructions:'original'}}));
});
test('self comparison and mixed kinds are rejected without overwriting previous selection', () => {
  const a={...snapshot({exists:true}),seq:'1'},b={...snapshot({exists:false}),seq:'2'};
  const selected={left:a,right:b};
  assert.throws(()=>selectProfileVersion(selected,{...a},'right'),/기준/);
  assert.throws(()=>selectProfileVersion(selected,{...request('x'),seq:21},'right'),/같은 프로필/);
  assert.strictEqual(selected.left,a); assert.strictEqual(selected.right,b);
  assert.deepEqual(selectProfileVersion(selected,b,'left'),{left:b,right:null});
  assert.deepEqual(selectProfileVersion({left:null,right:null},b,'right'),{left:b,right:null});
});
test('selection controls are explicit and full view captures the selected pair', () => {
  const js=readFileSync(new URL('../../main/resources/static/js/profile-history.mjs',import.meta.url),'utf8');
  assert.match(js,/기준 선택됨/); assert.match(js,/비교 선택됨/); assert.match(js,/aria-pressed/);
  assert.match(js,/candidate\.disabled = selected\.compareDisabled/);
  assert.match(js,/compareVersions\(selectedLeft, selectedRight\)/);
});
