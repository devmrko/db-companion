import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {profileField,booleanValue,validateTypedValue,credentialChoices,objectEntries,addObject,removeObject,distinctObjectChoices} from '../../main/resources/static/js/profile-fields.mjs';

test('all current profile attributes have a useful type and Korean help',()=>{
  const expected={annotations:'boolean',comments:'boolean',conversation:'boolean',credential_name:'credential',enforce_object_list:'boolean',max_tokens:'integer',seed:'integer',temperature:'number',provider:'select',model:'suggest',region:'suggest',oci_compartment_id:'text',additional_instructions:'multiline',object_list:'objects'};
  for(const [name,type] of Object.entries(expected)){const spec=profileField(name);assert.equal(spec.type,type);assert.match(spec.help,/[가-힣]/);}
  assert.equal(profileField('future_attribute').type,'multiline');assert.ok(profileField('future_attribute').help);
});
test('boolean is never inferred from nonboolean or absent values',()=>{
  assert.equal(booleanValue('TRUE'),'true');assert.equal(booleanValue('False'),'false');
  for(const value of ['',null,undefined,'yes','1',' false '])assert.equal(booleanValue(value),null);
  assert.ok(validateTypedValue('comments','yes'));assert.equal(validateTypedValue('annotations','false'),'');
});
test('signed 64bit seed retains precision and bounds without Number coercion',()=>{
  for(const value of ['42','-1','9223372036854775807','-9223372036854775808'])assert.equal(validateTypedValue('seed',value),'');
  for(const value of ['9223372036854775808','-9223372036854775809','1.1','1e2','NaN'])assert.ok(validateTypedValue('seed',value));
});
test('token and temperature fields reject invalid numeric inputs',()=>{
  assert.equal(validateTypedValue('max_tokens','2048'),'');
  for(const value of ['0','-1','1.5'])assert.ok(validateTypedValue('max_tokens',value));
  for(const value of ['0','0.25','1e-2'])assert.equal(validateTypedValue('temperature',value),'');
  for(const value of ['NaN','Infinity','-0.1','1e999'])assert.ok(validateTypedValue('temperature',value));
});
test('missing current credential is displayed but never fabricated as an enabled candidate',()=>{
  assert.deepEqual(credentialChoices('OLD',['NEW','NEW']),[{value:'OLD',label:'OLD (현재값 · 목록에 없음)',disabled:true},{value:'NEW',label:'NEW',disabled:false}]);
  assert.deepEqual(credentialChoices('A',['A']),[{value:'A',label:'A',disabled:false}]);
  assert.deepEqual(credentialChoices(null,[]),[]);
});
test('object picker keeps original formatting until explicit add or remove',()=>{
  const raw='[ { "owner": "APP", "name": "T", "type": "table" }, { "owner": "OTHER" } ]';
  assert.equal(addObject(raw,'APP','T'),raw);
  const changed=addObject(raw,'APP','V');assert.deepEqual(objectEntries(changed),[...JSON.parse(raw),{owner:'APP',name:'V'}]);
  assert.deepEqual(JSON.parse(removeObject(changed,2)),JSON.parse(raw));
  assert.deepEqual(JSON.parse(addObject('[]','APP',null)),[{owner:'APP'}]);
});
test('materialized view and same-name table are one object choice in either order',()=>{
  const mv={name:'MV',type:'MATERIALIZED VIEW'},table={name:'MV',type:'TABLE'},other={name:'OTHER',type:'TABLE'};
  assert.deepEqual(distinctObjectChoices([table,mv,other]),[mv,other]);
  assert.deepEqual(distinctObjectChoices([mv,table,other]),[mv,other]);
});
test('unrecognized object JSON is not lossy-converted by the structured editor',()=>{
  for(const raw of ['null','{}','[null]','[1]','[{"owner":"APP","extra":9007199254740993}]','[{"name":"T"}]'])assert.throws(()=>objectEntries(raw));
  assert.throws(()=>removeObject('[]',0));assert.throws(()=>addObject('[]','',null));
});
test('UI uses native accessible help and no automatic save or HTML injection',()=>{
  const fields=readFileSync(new URL('../../main/resources/static/js/profile-fields.mjs',import.meta.url),'utf8');
  assert.match(fields,/popovertarget/);assert.match(fields,/aria-expanded/);assert.match(fields,/role','tooltip/);assert.ok(!fields.includes('innerHTML'));
  const objects=readFileSync(new URL('../../main/resources/static/js/profile-objects.mjs',import.meta.url),'utf8');
  assert.match(objects,/AbortController/);assert.match(objects,/id!==generation/);assert.ok(!objects.includes('innerHTML'));assert.ok(!objects.includes("method:'POST'"));
  const editor=readFileSync(new URL('../../main/resources/static/js/profile-editor.mjs',import.meta.url),'utf8');
  assert.match(editor,/fields.disabled=true/);assert.match(editor,/body:JSON.stringify\(\{\.\.\.state.target,value:submitted,version:state.version\}\)/);
});
