import test from 'node:test';
import assert from 'node:assert/strict';
import {diagnosticMarkdown,exportDetail} from '../../main/resources/static/js/problem-questions.mjs';
const fields={question:false,sql:false,error:false,options:false,prompt:false,metadata:false,feedback:false};
const detail={parent:{status:'RECEIVED',createdAt:'2026-01-01',question:'original',description:'suspected',expected:'expected'},attempts:[{id:'one',capturedAt:'2026-01-01',profile:'APP.EXAMPLE_PROFILE',model:'example-model',elapsedMillis:42,input:'original',conditions:'CONFIRMED_SCOPE',error:'ORA-00904',response:'unverified response\npassword=SYNTHETIC_SECRET',rows:[{value:'BUSINESS_ROW_MUST_NOT_EXPORT'}]},{id:'two',profile:'APP.UNSELECTED',error:'UNSELECTED_ERROR'}]};
test('diagnostic retains selected profile model timing and conditions under their own switches',()=>{
  const selected=exportDetail(detail,['one']);
  const shown=diagnosticMarkdown(selected,{...fields,question:true,options:true});
  assert.match(shown,/APP.EXAMPLE_PROFILE/);assert.match(shown,/example-model/);assert.match(shown,/Elapsed \(ms\): 42/);assert.match(shown,/CONFIRMED_SCOPE/);
  const hidden=diagnosticMarkdown(selected,fields);assert.doesNotMatch(hidden,/EXAMPLE_PROFILE|example-model|CONFIRMED_SCOPE|original/);
});
test('error selection preserves a masked failed generation response without exporting result rows',()=>{
  const selected=exportDetail(detail,['one']),shown=diagnosticMarkdown(selected,{...fields,error:true});
  assert.match(shown,/ORA-00904/);assert.match(shown,/Generation response \(unverified\)/);assert.match(shown,/password=\[REDACTED\]/);
  assert.doesNotMatch(shown,/SYNTHETIC_SECRET|BUSINESS_ROW_MUST_NOT_EXPORT|UNSELECTED_ERROR|APP.UNSELECTED/);
  assert.doesNotMatch(diagnosticMarkdown(selected,fields),/ORA-00904|unverified response/);
});
