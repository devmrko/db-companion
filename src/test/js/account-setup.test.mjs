import test from 'node:test';
import assert from 'node:assert/strict';
import {accountCanCreate} from '../../main/resources/static/js/account-setup.mjs';
test('account creation needs a fresh preview and consent and never repeats an attempted create',()=>{
  assert.equal(accountCanCreate({},true,false,false),true);
  for(const input of [[null,true,false,false],[{},false,false,false],[{},true,true,false],[{},true,false,true]])assert.equal(accountCanCreate(...input),false);
});
