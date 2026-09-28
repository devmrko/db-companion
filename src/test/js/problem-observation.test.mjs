import test from 'node:test';
import assert from 'node:assert/strict';
import {attemptObservation,clearSavedComparison} from '../../main/resources/static/js/problem-questions.mjs';
test('canonical optionSnapshot is displayed with value, status and time',()=>{
 const snapshot={value:'{"comments":"true"}',availability:'POST_GENERATION_READ',checkedAt:'2026-01-01T00:00:10Z'};
 assert.deepEqual(attemptObservation({optionSnapshot:snapshot,options:'old'},'options'),snapshot);
});
test('missing canonical evidence does not fall back to a stale legacy value',()=>{
 assert.equal(attemptObservation({optionSnapshot:{value:'',availability:'NOT_QUERIED'},options:'old'},'options').value,'');
 assert.equal(attemptObservation({options:'old'},'options').availability,'LEGACY');
});
test('changing parent clears and hides its saved comparison',()=>{
 const host={hidden:false,children:['stale parent result'],replaceChildren(){this.children=[];}};
 clearSavedComparison(host);assert.equal(host.hidden,true);assert.deepEqual(host.children,[]);
});
