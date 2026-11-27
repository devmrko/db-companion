import test from 'node:test';
import assert from 'node:assert/strict';
import {jsonTree} from '../../main/resources/static/js/json-view.mjs';

test('JSON tree retains numeric lexemes, duplicate fields and string data',()=>{
  const source='{"id":9007199254740993123,"v":1.00000000000000001,"id":1e100,"html":"<img src=x onerror=alert(1)>","items":[true,null,"a\\n\\\"b"]}';
  const tree=jsonTree(source);
  assert.equal(tree.entries[0][1].value,'9007199254740993123');
  assert.equal(tree.entries[1][1].value,'1.00000000000000001');
  assert.equal(tree.entries[2][0],'id');assert.equal(tree.entries[2][1].value,'1e100');
  assert.equal(tree.entries[3][1].value,'<img src=x onerror=alert(1)>');
  assert.equal(tree.entries[4][1].entries[2][1].value,'a\n"b');
});
test('non JSON, scalars, oversized and overly deep values keep the original presentation',()=>{
  for(const value of ['select 1 from dual','', 'null','42','"text"','{} {}','{bad}','{"a":undefined}','['.repeat(34)+'0'+']'.repeat(34),'["'+'x'.repeat(250000)+'"]'])assert.equal(jsonTree(value),null);
  assert.deepEqual(jsonTree('  []\n'),{kind:'array',entries:[]});
  assert.equal(jsonTree('{"escaped\\\"key":{"a":[]}}').entries[0][0],'escaped"key');
});
