import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {profileLabel,assistantPost} from '../../main/resources/static/js/ai-assistant.mjs';
import {explanationRequest} from '../../main/resources/static/js/function-explain.mjs';
test('explanation sends only explicit consent and a one-use token, not editable source or action',()=>{
  assert.deepEqual(explanationRequest({token:'one-use',source:'private',profile:'OTHER',action:'runsql'},true),{token:'one-use',consent:true});
  assert.equal(explanationRequest({token:'one-use'},'true').consent,false);
});
test('profile labels handle default models and preserve exact database text',()=>{
  assert.equal(profileLabel({name:'A<>',provider:'oci',model:null}),'A<> · oci');
  assert.equal(profileLabel({name:'A',provider:'oci',model:'model'}),'A · oci / model');
});
test('POST helper keeps source out of URLs and includes CSRF header',()=>{
  const options=assistantPost({dataset:{csrfHeader:'X-CSRF-TOKEN'},value:'test'},{schema:'APP',reference:'F'});
  assert.equal(options.method,'POST');assert.equal(options.headers['X-CSRF-TOKEN'],'test');assert.deepEqual(JSON.parse(options.body),{schema:'APP',reference:'F'});
});
test('modal uses plaintext and explicit click consent with no retry or browser persistence',()=>{
  const code=fs.readFileSync('src/main/resources/static/js/function-explain.mjs','utf8');
  assert.match(code,/textContent=result.text/);assert.match(code,/running=true;spent=true/);assert.match(code,/!get\('consent'\).checked/);assert.match(code,/dialog.addEventListener\('cancel'/);
  assert.doesNotMatch(code,/innerHTML|eval\(|localStorage|sessionStorage|setInterval|setTimeout/);
  assert.match(code,/get\('source-details'\)/);
});
test('assistant translations are complete and preserve placeholder arguments',()=>{
  for(const [key,values]of Object.entries(JSON.parse(fs.readFileSync('tools/i18n/feature-assistant.json','utf8')))){
    assert.equal(values.length,4,key);const args=v=>[...new Set(v.match(/\{\d+\}/g)||[])].sort();
    values.forEach((value,i)=>{assert.ok(value.trim());assert.deepEqual(args(value),args(values[0]));if(i)assert.doesNotMatch(value,/[가-힣]/u);});
  }
});
