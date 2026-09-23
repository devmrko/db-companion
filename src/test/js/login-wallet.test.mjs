import test from 'node:test';
import assert from 'node:assert/strict';
import {wireWalletSelector} from '../../main/resources/static/js/login.mjs';

class Element {
  constructor(value=''){this.value=value;this.events={};this.hidden=false;this.disabled=false;}
  addEventListener(type,fn){this.events[type]=fn;}
  fire(type){const e={prevented:false,preventDefault(){this.prevented=true;}};this.events[type]?.(e);return e;}
}
function setup() {
  const select=new Element('wallet-a'),apply=new Element(),alias=new Element('same_low');
  const username=new Element('ADMIN'),password=new Element('secret'),bound=new Element('wallet-a'),button=new Element();
  const form=new Element(),login=new Element(),calls=[];
  form.querySelector=s=>({'[data-wallet-select]':select,'[data-wallet-apply]':apply})[s];
  login.querySelector=s=>({'[name="tnsAlias"]':alias,'[name="username"]':username,'[name="password"]':password,'[name="walletId"]':bound,'button[type="submit"]':button})[s];
  form.requestSubmit=()=>calls.push({walletId:select.value});
  wireWalletSelector(form,login);
  return {select,apply,alias,username,password,bound,button,form,login,calls};
}
test('wallet change submits only wallet ID and clears previous service and credentials',()=>{
  const s=setup();assert.equal(s.apply.hidden,true);assert.equal(s.password.value,'secret');
  s.select.value='wallet-b';s.select.fire('change');
  assert.deepEqual(s.calls,[{walletId:'wallet-b'}]);
  for(const e of [s.alias,s.username,s.password])assert.equal(e.value,'');
  assert.equal(s.alias.disabled,true);assert.equal(s.button.disabled,true);
});
test('blank wallet cannot submit or reuse existing alias',()=>{
  const s=setup();s.select.value='';s.select.fire('change');
  assert.deepEqual(s.calls,[]);assert.equal(s.button.disabled,true);assert.equal(s.apply.hidden,false);
});
test('restored mismatched selector cannot log into the old hidden wallet',()=>{
  const s=setup();s.select.value='wallet-b';const e=s.login.fire('submit');
  assert.equal(e.prevented,true);assert.deepEqual(s.calls,[{walletId:'wallet-b'}]);assert.equal(s.password.value,'');
});
test('consistent wallet submits normal POST without changing credentials',()=>{
  const s=setup();assert.equal(s.login.fire('submit').prevented,false);
  assert.equal(s.password.value,'secret');assert.deepEqual(s.calls,[]);
});
