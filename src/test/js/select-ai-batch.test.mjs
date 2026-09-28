import test from 'node:test';
import assert from 'node:assert/strict';
import {runBatchPlan} from '../../main/resources/static/js/select-ai-batch.mjs';
const initial=()=>({generation:'g',items:['a','b','c'].map(token=>({token,status:'PENDING',question:token}))});
test('batch never sends without consent',async()=>{let calls=0;await assert.rejects(runBatchPlan(initial(),{consent:false,next:async()=>{calls++;}}));assert.equal(calls,0);});
test('generation is sequential and a completed failed item does not cause retry',async()=>{
 let plan=initial(),active=0,max=0;const calls=[];
 const result=await runBatchPlan(plan,{consent:true,next:async request=>{calls.push(request.token);active++;max=Math.max(max,active);await Promise.resolve();active--;const value={...plan.items.find(i=>i.token===request.token),status:request.token==='b'?'FAILED':'SUCCEEDED',error:request.token==='b'?'provider-error':null};plan={...plan,items:plan.items.map(i=>i.token===request.token?value:i)};return value;},status:async()=>plan});
 assert.deepEqual(calls,['a','b','c']);assert.equal(max,1);assert.equal(result.items[1].status,'FAILED');
});
test('cancel stops remaining calls while preserving completed results',async()=>{
 let stop=false,plan=initial(),calls=0;
 const result=await runBatchPlan(plan,{consent:true,next:async request=>{calls++;const item={...plan.items[0],status:'SUCCEEDED'};plan={...plan,items:plan.items.map(i=>i.token===request.token?item:{...i,status:'SKIPPED'})};stop=true;return item;},status:async()=>plan,shouldStop:()=>stop});
 assert.equal(calls,1);assert.equal(result.items[0].status,'SUCCEEDED');assert.equal(result.items[1].status,'SKIPPED');
});
test('ambiguous transport failure is not retried and previous results survive',async()=>{
 let plan=initial(),calls=0,shown=null;
 await assert.rejects(runBatchPlan(plan,{consent:true,next:async request=>{calls++;if(calls===2)throw new Error('network');const item={...plan.items[0],status:'SUCCEEDED'};plan={...plan,items:plan.items.map(i=>i.token===request.token?item:i)};return item;},status:async()=>plan,onChange:p=>shown=p}));
 assert.equal(calls,2);assert.equal(shown.items[0].status,'SUCCEEDED');
});
test('stale server plan stops without sending next question',async()=>{
 let calls=0;await assert.rejects(runBatchPlan(initial(),{consent:true,next:async()=>{calls++;return {token:'a',status:'SUCCEEDED'};},status:async()=>({...initial(),generation:'other'})}));assert.equal(calls,1);
});
