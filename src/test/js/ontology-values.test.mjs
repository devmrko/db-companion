import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {valueType,aliases,addMapping,eligibleColumns,addColumnMapping} from '../../main/resources/static/js/ontology-values.mjs';
import {readOnlyPanel,savePayload} from '../../main/resources/static/js/ontology.mjs';
test('value types preserve text codes and reject non-code types',()=>{
  for(const value of ['CHAR','NCHAR(10)','VARCHAR2(100 BYTE)','NVARCHAR2(10)'])assert.equal(valueType(value),'TEXT');
  assert.equal(valueType('NUMBER(8,0)'),'NUMBER');assert.equal(valueType('FLOAT'),'NUMBER');
  for(const value of ['CLOB','DATE','BINARY_DOUBLE','VECTOR'])assert.equal(valueType(value),'');
});
test('editor consumes JSON column properties, not Java record methods',()=>{
  const columns=[{name:'CODE',dataType:'VARCHAR2(30 BYTE)'},{name:'LABEL',dataType:'NVARCHAR2(100)'},{name:'N',dataType:'NUMBER(8,0)'},{name:'SECRET',dataType:'VARCHAR2(30)'},{name:'CREATED_AT',dataType:'DATE'}];
  const meaning={columns:Object.fromEntries(columns.map(c=>[c.name,{sensitivity:c.name==='SECRET'?'SENSITIVE':'UNKNOWN'}]))};
  assert.deepEqual(eligibleColumns(columns,meaning).map(c=>c.name),['CODE','LABEL','N']);
  assert.deepEqual(eligibleColumns([],meaning),[]);
  assert.equal(addColumnMapping(meaning,columns[0]).type,'TEXT');
  assert.equal(addColumnMapping(meaning,columns[2]).type,'NUMBER');
  const source=readFileSync('src/main/resources/static/js/ontology-values.mjs','utf8');
  assert.ok(source.includes('eligibleColumns(entry.document.source.columns,meaning)'));
  assert.ok(source.includes('addColumnMapping(meaning,c)'));
  assert.ok(!/\.dataType\s*\(/.test(source));
});
test('manual and imported edits stay in the meaning payload, without mutating source metadata',()=>{
  const meaning={concept:'Region'},row=addMapping(meaning,'CODE','TEXT','001','Area');row.aliases=aliases('Alias\n 별칭 \n');
  assert.equal(row.value,'001');assert.deepEqual(row.aliases,['Alias','별칭']);assert.match(row.id,/^[0-9a-f-]{36}$/);
  assert.throws(()=>addMapping(meaning,'CODE','TEXT','001','Duplicate'));
  const e={revision:2,document:{source:{table:'T'}}};assert.equal(savePayload('APP',e,meaning,'DRAFT').meaning.valueMappings[0],row);
  assert.equal(readOnlyPanel(e,3,'values'),true);assert.equal(readOnlyPanel(e,2,'values'),false);
});
test('editor explicitly gates reads and exposes safe text, not SQL or automatic AI calls',()=>{
  const source=readFileSync('src/main/resources/static/js/ontology-values.mjs','utf8');
  assert.ok(source.includes('if(!consent.checked)throw'));assert.ok(source.includes('revision:entry.revision'));assert.ok(source.includes('if(editable)'));
  assert.ok(source.includes('textContent'));assert.ok(!/innerHTML|eval\(|localStorage|sessionStorage|fetch\(|setInterval/.test(source));
  assert.ok(!source.includes('/save'));assert.ok(!source.includes('/ai/'));
});
test('100 mapping limit and four language help document exact storage and approval',()=>{
  const meaning={valueMappings:Array.from({length:100},()=>({column:'C',value:'x'}))};assert.throws(()=>addMapping(meaning,'C','TEXT'));
  const labels=JSON.parse(readFileSync('tools/i18n/feature-ontology-values.json','utf8'));for(const values of Object.values(labels)){assert.equal(values.length,4);assert.ok(values.every(v=>v.length));}
  for(const text of labels['ontology.values.help'])assert.ok(text.includes('DBC_ONTOLOGY_CATALOG.PAYLOAD'));
});
