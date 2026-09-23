import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {canMountLink,catalogSuggestion,validCatalogName,metadataPage,stageGate,isCommentField,metadataFields,metadataHelpKeys} from '../../main/resources/static/js/catalog-operations.mjs';

test('link ownership and catalog names are explicit, normalized and bounded',()=>{
  assert.equal(canMountLink('APP','APP'),true);assert.equal(canMountLink('APP','PUBLIC'),true);assert.equal(canMountLink('ADMIN','OTHER'),false);
  assert.equal(catalogSuggestion('a.b'),'CAT_A_B');assert.equal(catalogSuggestion('x'.repeat(140)).length,128);
  assert.equal(validCatalogName(' my_catalog '),true);
  for(const name of ['LOCAL','a.b','한글','1ABC',"A';BEGIN",'a'.repeat(129),''])assert.equal(validCatalogName(name),false);
});
test('metadata search and ten-row pages include all scalar fields without modifying originals',()=>{
  const rows=Array.from({length:24},(_,i)=>({TABLE_NAME:'T'+i,COMMENTS:i===23?'description match':'text',DATA_TYPE:null}));
  const grid={fields:['TABLE_NAME','COMMENTS','DATA_TYPE'],rows};
  assert.equal(metadataPage(grid,'',2).from,11);assert.equal(metadataPage(grid,'',3).items.length,4);
  assert.equal(metadataPage(grid,'MATCH',1).items[0].row,rows[23]);assert.equal(metadataPage(grid,'missing',1).total,0);
  assert.equal(rows[0].name,undefined);
});
test('late metadata responses are invalidated by parent changes and tab disposal',()=>{
  const gate=stageGate(),schemas=gate.start(),tables=gate.start();assert.equal(gate.current(schemas),false);assert.equal(gate.current(tables),true);
  gate.cancel();assert.equal(gate.current(tables),false);
});
test('missing comment field differs from an empty returned value without changing the cached grid',()=>{
  const unavailable={fields:['COLUMN_NAME'],rows:[{COLUMN_NAME:'ID'}]};
  assert.deepEqual(metadataFields(unavailable,'columns'),['COLUMN_NAME','COMMENTS']);
  assert.ok(metadataHelpKeys(unavailable,'columns').includes('commentUnavailable'));
  assert.deepEqual(unavailable,{fields:['COLUMN_NAME'],rows:[{COLUMN_NAME:'ID'}]});
  for(const field of ['COMMENTS','DESCRIPTION']){
    const empty={fields:['COLUMN_NAME',field],rows:[{COLUMN_NAME:'ID',[field]:null}]};
    assert.deepEqual(metadataFields(empty,'columns'),empty.fields);
    assert.ok(metadataHelpKeys(empty,'columns').includes('commentHelp'));
    assert.ok(!metadataHelpKeys(empty,'columns').includes('commentUnavailable'));
  }
});
test('table and schema descriptions retain full searchable text and use shared comment styling',()=>{
  for(const field of ['SCHEMA_DESCRIPTION','TABLE_DESCRIPTION']){
    assert.equal(isCommentField(field),true);
    const original='A'.repeat(1000)+' 원문 description';
    const data={fields:[field],rows:[{[field]:original}]};
    assert.deepEqual(metadataFields(data,'tables'),[field]);
    assert.equal(metadataPage(data,'원문',1).items[0].row[field],original);
  }
  assert.equal(isCommentField('METADATA'),false);
  const messages=JSON.parse(readFileSync('tools/i18n/feature-catalog-operations.json','utf8'));
  for(const key of metadataHelpKeys({fields:[]},'columns'))assert.equal(messages['catalogOps.'+key].length,4);
});
test('registration uses CSRF preview then one-shot mount and never automatically retries',()=>{
  const source=readFileSync('src/main/resources/static/js/catalog-operations.mjs','utf8');
  assert.match(source,/\/catalogs\/preview',assistantPost/);assert.match(source,/\/catalogs\/mount',assistantPost/);
  assert.match(source,/attempted=true;controls/);assert.match(source,/token:preview.token/);
  assert.match(source,/receipt.status==='REGISTERED'/);assert.match(source,/dialog.addEventListener\('cancel'/);
  assert.doesNotMatch(source,/innerHTML|insertAdjacentHTML|localStorage|sessionStorage|setInterval/);
  assert.match(source,/if\(!gate.current\(stamp\)\)return/);assert.match(source,/schemaName,tableName/);
});
test('registration and browse help preserve all four languages and public fields',()=>{
  const messages=JSON.parse(readFileSync('tools/i18n/feature-catalog-operations.json','utf8'));
  for(const [key,values]of Object.entries(messages)){
    assert.equal(values.length,4,key);for(const [index,value]of values.entries()){assert.ok(value.trim(),key);if(index)assert.doesNotMatch(value,/[가-힣]/u,key);}
  }
  assert.match(messages['catalogOps.help'][0],/External Table/);assert.match(messages['catalogOps.browseHelp'][0],/캐시.*비동기/);
});
