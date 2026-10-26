import test from 'node:test';
import assert from 'node:assert/strict';
import {hasDuplicateTerm} from '../../main/resources/static/js/business-glossary.mjs';
test('duplicate warning excludes own ID, follows pages and includes inactive names',async()=>{
  const offsets=[];
  const found=await hasDuplicateTerm(async(filter,offset)=>{offsets.push(offset);assert.equal(filter,"매출 '😀'");return offset===0?{rows:[{id:'self',term:filter}],more:true}:{rows:[{id:'other',term:filter,enabled:false}],more:false};}," 매출 '😀' ",'self');
  assert.equal(found,true);assert.deepEqual(offsets,[0,1]);
});
test('substring matches and self edits do not warn; lookup failures never become no-duplicate',async()=>{
  assert.equal(await hasDuplicateTerm(async()=>({rows:[{id:'self',term:'Sales'},{id:'other',term:'Sales total'}],more:false}),'sales','self'),false);
  await assert.rejects(hasDuplicateTerm(async()=>{throw Error('unavailable');},'Sales'),/unavailable/);
  await assert.rejects(hasDuplicateTerm(async()=>({rows:[],more:true}),'Sales'));
});
