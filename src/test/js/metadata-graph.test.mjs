import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {metadataCounts} from '../../main/resources/static/js/metadata-graph.mjs';
import {metadataGraphSql} from '../../main/resources/static/js/sql-help-metadata-source.mjs';
import {helpFor} from '../../main/resources/static/js/sql-help.mjs';
const read=path=>readFileSync(new URL('../../main/resources/'+path,import.meta.url),'utf8');
test('metadata counters distinguish object containment, relations and composite pairs',()=>{
 assert.deepEqual(metadataCounts({objects:16,columns:504,relations:0,mappings:0}),{nodes:520,edges:504});
 assert.deepEqual(metadataCounts({objects:2,columns:4,relations:1,mappings:2}),{nodes:6,edges:7});
});
test('metadata is the default graph kind, with original business graph preserved',()=>{
 const ui=read('static/js/ontology-pipeline.mjs');assert.match(ui,/\['metadata','business'\]/);assert.match(ui,/metadataGraphPanel/);assert.match(ui,/function businessGraph/);assert.match(ui,/\/pipeline\/graph\/create/);
 const mg=read('static/js/metadata-graph.mjs');assert.match(mg,/\/metadata-graph\/preview/);assert.match(mg,/!preview\?\.canCreate\|\|!input.checked/);assert.match(mg,/preview=null/);assert.doesNotMatch(mg,/innerHTML/);
});
test('SQL help uses exact catalog projection resources and explains native RDF boundary',()=>{
 for(const [name,source] of Object.entries(metadataGraphSql))assert.equal(source,read('sql/metadata-graph/'+name+'.sql'));
 const help=helpFor('metadata-graph','ko','metadata-graph');assert.match(help.sql,/JSON_TABLE/);assert.match(help.sql,/CREATE PROPERTY GRAPH/);assert.doesNotMatch(help.sql,/OR REPLACE|@@/);
 assert.match(helpFor('metadata-graph','ko','metadata-graph-read').sql,/MAPPING_COUNT/);
});
