import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {nativeSelection,nativeCanPreview} from '../../main/resources/static/js/ontology-native.mjs';
import {nativeRdfSql} from '../../main/resources/static/js/sql-help-native-source.mjs';
import {helpFor,operationsFor} from '../../main/resources/static/js/sql-help.mjs';
test('native selection requires two captured objects and enforces limit',()=>{
  assert.deepEqual(nativeSelection(['B','A','A']),['A','B']);
  assert.equal(nativeCanPreview(['A'],[{table:'A'}]),false);
  assert.equal(nativeCanPreview(['A','B'],[{table:'A'}]),false);
  assert.equal(nativeCanPreview(['A','B'],[{table:'A'},{table:'B'}]),true);
  const names=Array.from({length:51},(_,i)=>String(i));assert.equal(nativeCanPreview(names,names.map(table=>({table}))),false);
});
test('native SQL help uses exact executable app resources',()=>{
  for(const [name,sql] of Object.entries(nativeRdfSql)){
    assert.equal(sql,readFileSync(new URL('../../main/resources/sql/ontology-native/'+name+'.sql',import.meta.url),'utf8'));
    assert.ok(helpFor('ontology','ko','native-'+name).sql.includes(sql));
  }
  assert.equal(operationsFor('ontology-native').length,8);
  assert.match(helpFor('ontology-native','ko','native-candidates').sql,/VARIABLE candidates REFCURSOR/);
  assert.match(helpFor('ontology-native','ko','native-read').sql,/VARIABLE columns REFCURSOR/);
  for(const op of operationsFor('ontology-native'))assert.ok(helpFor('ontology-native','en',op.id).result);
});
test('capture reads only metadata and discovery is bound Oracle RDF SQL',()=>{
  assert.match(nativeRdfSql.capture,/USER_TAB_COLUMNS/);assert.doesNotMatch(nativeRdfSql.capture,/DBMS_CLOUD_AI/);
  assert.match(nativeRdfSql.candidates,/SEM_MATCH/);assert.match(nativeRdfSql.candidates,/JSON_TABLE\(:selection/);
  assert.match(nativeRdfSql.candidates,/a\.column_name_value=b\.column_name_value/);
  assert.match(nativeRdfSql.candidates,/FETCH FIRST 2001/);
});
