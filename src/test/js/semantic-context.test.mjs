import test from 'node:test';
import assert from 'node:assert/strict';
import {validSources,sourceTemplate} from '../../main/resources/static/js/semantic-context.mjs';
import {validateProfileValue} from '../../main/resources/static/js/profile-editor.mjs';
test('source bindings allow detaching and reject malformed, duplicate and qualified names',()=>{
  for(const value of ['[]','["TERMS","POLICY"]']){assert.equal(validSources(value),true);assert.equal(validateProfileValue('semantic_source',value),'');}
  for(const value of ['{}','null','[1]','["A","A"]','["APP.A"]','[] {}']){assert.equal(validSources(value),false);assert.notEqual(validateProfileValue('semantic_source',value),'');}
});
test('MCP template requires explicit tool and response mapping and never carries a secret',()=>{
  const value=JSON.parse(sourceTemplate('mcp'));
  assert.equal(value.scope,'global');assert.equal(value.global_tool.response_mapping.definition,'$.definition');
  assert.ok(value.credential_name);assert.equal(value.password,undefined);
});
test('custom template references the installed database glossary callback',()=>{
  assert.equal(JSON.parse(sourceTemplate('custom','APP')).global_callback_func,'APP.DBC_GLOSSARY_CONTEXT');
});
