import test from 'node:test';
import assert from 'node:assert/strict';
import {policyFunctionTarget,matchingFunctionDetail} from '../../main/resources/static/js/vpd-function-source.mjs';
const policy=(owner,fn,pkg='')=>({editable:false,definition:null,properties:{PF_OWNER:owner,FUNCTION:fn,PACKAGE:pkg,EDITION_NAME:''}});
test('policy function target comes from each row even for unsupported policies',()=>{
  const a=policyFunctionTarget(policy('APP','TENANT_FILTER')),b=policyFunctionTarget(policy('SECURITY','ROW_FILTER'));
  assert.deepEqual(a,{owner:'APP',object:'TENANT_FILTER',member:null,label:'APP.TENANT_FILTER',reference:'"APP"."TENANT_FILTER"'});
  assert.equal(b.reference,'"SECURITY"."ROW_FILTER"');assert.notEqual(a.reference,b.reference);
});
test('packaged functions and quoted metadata remain separate exact identifiers',()=>{
  assert.equal(policyFunctionTarget(policy('OTHER','FILTER','SECURITY_PKG')).reference,'"OTHER"."SECURITY_PKG"."FILTER"');
  assert.equal(policyFunctionTarget(policy('Mixed.Owner','Odd"Function','Quoted.Package')).reference,'"Mixed.Owner"."Quoted.Package"."Odd""Function"');
  for(const p of [null,{},policy('','F'),policy('APP',''),policy('APP','F',42),policy('APP','F\0')])assert.equal(policyFunctionTarget(p),null);
});
test('source results must match owner, object, member and function type exactly',()=>{
  const target=policyFunctionTarget(policy('APP','FILTER'));
  const expected={definition:{owner:'APP',object:'FILTER',member:null,type:'FUNCTION',sections:[]}};
  const ambiguous={definition:{owner:'OTHER',object:'APP',member:'FILTER',type:'PACKAGE'}};
  assert.equal(matchingFunctionDetail([ambiguous,expected],target),expected);
  assert.equal(matchingFunctionDetail([ambiguous],target),null);
  assert.equal(matchingFunctionDetail([expected,expected],target),null);
  assert.equal(matchingFunctionDetail([{definition:{...expected.definition,type:'PROCEDURE'}}],target),null);
  assert.equal(matchingFunctionDetail(null,target),null);
});
test('package source selection does not confuse functions with the same name',()=>{
  const target=policyFunctionTarget(policy('SEC','FILTER','PKG'));
  const expected={definition:{owner:'SEC',object:'PKG',member:'FILTER',type:'PACKAGE'}};
  assert.equal(matchingFunctionDetail([expected],target),expected);
  assert.equal(matchingFunctionDetail([{definition:{...expected.definition,member:'OTHER'}}],target),null);
});
