import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mappingLines} from '../../main/resources/static/js/glossary-rdf.mjs';
import {internalObject} from '../../main/resources/static/js/internal-objects.mjs';
import {observedTime} from '../../main/resources/static/js/observed-time.mjs';
test('qualified RDF mappings retain repeated column names and reject unknown roles',()=>{
  const values=mappingLines('APP.SALES.AMOUNT | VALUE | SUM\nAPP.REFUNDS.AMOUNT | VALUE | SUM');assert.equal(values.length,2);assert.notEqual(values[0].table,values[1].table);
  assert.throws(()=>mappingLines('AMOUNT | VALUE | SUM'));assert.throws(()=>mappingLines('APP.SALES.AMOUNT | EXECUTE | SUM'));
});
test('default object filter excludes infrastructure but retains ordinary business tables',()=>{
  for(const name of ['DR$TEXT$I','VECTOR$INDEX','DATA#RDF','DATA#SEM','DATA$VECTAB'])assert.equal(internalObject(name),true);
  for(const name of ['SALES','SALES_VECTOR','DATA_RDF'])assert.equal(internalObject(name),false);
});
test('time rendering does not invent a timezone for unzoned database timestamps',()=>{
  assert.match(observedTime('2026-01-02 03:04:05'),/\(DB\)$/);assert.match(observedTime('2026-01-02T03:04:05Z','en-US'),/UTC 2026-01-02T03:04:05.000Z/);
});
