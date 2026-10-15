import test from 'node:test';
import assert from 'node:assert/strict';
import {credentialCanApply} from '../../main/resources/static/js/credential-edit.mjs';
test('credential writes require a reviewed plan consent value and an idle UI',()=>{
  assert.equal(credentialCanApply({},true,true,false),true);
  for(const args of [[null,true,true,false],[{},false,true,false],[{},true,false,false],[{},true,true,true]])assert.equal(credentialCanApply(...args),false);
});
