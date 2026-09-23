import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {relationshipGraph,graphElements,keyColumns,tableId,readableZoom,columnDetails} from '../../main/resources/static/js/ontology-erd.mjs';
import {profileTables} from '../../main/resources/static/js/table-list.mjs';

const fk=(name,target,extra={})=>({name,type:'R',columns:['TENANT','PARENT'],targetOwner:'APP',targetTable:target,
  targetColumns:['TENANT','ID'],status:'ENABLED',validated:'VALIDATED',...extra});
const row=(name,keys=[])=>({name,keys,revision:1,state:'DRAFT'});
const data={schema:'APP',tables:[row('A',[fk('FK_A_B','B'),fk('FK_A_B2','B'),fk('FK_A_SELF','A'),fk('FK_A_REMOTE','A',{targetOwner:'OTHER'}),fk('FK_A_MISSING','MISSING')]),row('B'),row('C',[fk('FK_C_A','A')]),row('UNRELATED')]};

test('initial zoom stays legible without forcing the whole graph into the canvas',()=>{
  assert.equal(readableZoom(0.12),0.85);assert.equal(readableZoom(0.9),0.9);
  assert.equal(readableZoom(2.5),1);assert.equal(readableZoom(NaN),1);
});
test('stacked column details retain full identifiers, descriptions, keys and source order',()=>{
  const name='EMERGENCY_FREIGHT_USD_LONG_IDENTIFIER';
  const input={document:{source:{keys:[{type:'P',columns:[name]},{type:'R',columns:[name]}],
    columns:[{name,dataType:'NUMBER(12,2)',comment:'Original'},{name:'OTHER',dataType:'VARCHAR2(128 BYTE)',comment:'<script>raw</script>'}]},
    meaning:{columns:{[name]:{description:'Reviewed description'}}}}};
  assert.deepEqual(columnDetails(input),[{name,key:'PK/FK',type:'NUMBER(12,2)',description:'Reviewed description'},
    {name:'OTHER',key:'',type:'VARCHAR2(128 BYTE)',description:'<script>raw</script>'}]);
});
test('readability controls and stacked details preserve safe read-only rendering',()=>{
  const code=readFileSync('src/main/resources/static/js/ontology-erd.mjs','utf8');
  const html=readFileSync('src/main/resources/templates/fragments/ontology-erd.html','utf8');
  for(const hook of ['focus','actual','zoom'])assert.ok(html.includes('data-erd-'+hook));
  assert.ok(code.includes('new ResizeObserver'));assert.ok(code.includes("cy.on('zoom'"));
  assert.ok(code.includes('columns(entry),relations(id)'));assert.ok(code.includes('mapping(key)'));
  assert.ok(!code.includes('function table('));
});

test('all saved tables, self-loops, composite and multiple FKs are preserved',()=>{
  const graph=relationshipGraph(data);assert.equal(graph.nodes.filter(n=>!n.boundary).length,4);assert.equal(graph.edges.length,6);
  assert.equal(new Set(graph.edges.map(e=>e.id)).size,6);
  assert.deepEqual(graph.edges[0].key.columns,['TENANT','PARENT']);assert.deepEqual(graph.edges[0].key.targetColumns,['TENANT','ID']);
  assert.ok(graph.edges.some(e=>e.source===e.target));
  assert.equal(graph.nodes.find(n=>n.schema==='OTHER').boundary,'OTHER_SCHEMA');
  assert.equal(graph.nodes.find(n=>n.name==='MISSING').boundary,'MISSING');
});
test('profile filtering keeps incoming and outgoing boundary connections, not unrelated tables',()=>{
  const graph=relationshipGraph(data,new Set(['A']));
  assert.equal(graph.nodes.find(n=>n.name==='B').boundary,'FILTERED');
  assert.equal(graph.nodes.find(n=>n.name==='C').boundary,'FILTERED');
  assert.ok(!graph.nodes.some(n=>n.name==='UNRELATED'));
  const expanded=relationshipGraph(data,new Set(['A']),[tableId('APP','B'),tableId('OTHER','A'),tableId('APP','MISSING')]);
  assert.equal(expanded.nodes.find(n=>n.name==='B').boundary,null);
  assert.equal(expanded.nodes.find(n=>n.schema==='OTHER').boundary,'OTHER_SCHEMA');
  assert.equal(expanded.nodes.find(n=>n.name==='MISSING').boundary,'MISSING');
});
test('profile parser reuses owner-only, quoted identifiers and current-schema contract',()=>{
  assert.equal(profileTables(data.tables,'APP','[{"owner":"APP"}]').length,4);
  assert.deepEqual(profileTables(data.tables,'APP','[{"owner":"OTHER","name":"A"}]'),[]);
  assert.deepEqual(profileTables([row('Mixed')],'APP','[{"owner":"APP","name":"\\"Mixed\\""}]').map(v=>v.name),['Mixed']);
  assert.throws(()=>profileTables(data.tables,'APP','broken'));
  assert.equal(relationshipGraph(data,new Set()).nodes.length,0);
});
test('exact tuple identifiers cannot collapse qualified names, quoted identifiers or case',()=>{
  assert.notEqual(tableId('A.B','C'),tableId('A','B.C'));
  assert.notEqual(tableId('APP','a'),tableId('APP','A'));
  const graph=relationshipGraph({schema:'APP',tables:[row('A"[]<>'),row('a')]});
  assert.equal(new Set(graph.nodes.map(n=>n.id)).size,2);
});
test('keys are summarized without duplicating columns or inferring cardinality',()=>{
  const keys=[{type:'P',columns:['ID']},{type:'R',columns:['ID','PARENT']},{type:'U',columns:['ID']}];
  assert.deepEqual(keyColumns(keys),[{name:'ID',kinds:'PK/FK/UK'},{name:'PARENT',kinds:'FK'}]);
  const elements=graphElements(relationshipGraph(data));
  assert.equal(elements.filter(e=>e.data.source).length,6);
  assert.ok(!elements.some(e=>'cardinality' in e.data));
});
test('disabled or unvalidated keys retain their identity and get a distinct style',()=>{
  const input={schema:'APP',tables:[row('A',[fk('OLD','B',{status:'DISABLED'}),fk('UNVALID','B',{validated:'NOT VALIDATED'})]),row('B')]};
  const edges=graphElements(relationshipGraph(input)).filter(e=>e.data.source);
  assert.ok(edges.every(e=>e.classes==='unchecked'));assert.equal(edges.length,2);
});
test('unknown FK target never invents a relationship and empty schema is valid',()=>{
  assert.equal(relationshipGraph({schema:'APP',tables:[row('A',[fk('UNKNOWN',null)])]}).edges.length,0);
  assert.deepEqual(relationshipGraph({schema:'APP',tables:[]}),{nodes:[],edges:[]});
});
test('ERD uses text, fixed local library, lazy detail and read-only endpoints',()=>{
  const code=readFileSync('src/main/resources/static/js/ontology-erd.mjs','utf8');
  assert.ok(!/innerHTML|eval\(|assistantPost|method:\s*['"]POST|localStorage|sessionStorage/.test(code));
  assert.ok(code.includes("url('/detail',{table:node.name,revision:node.table.revision})"));
  assert.ok(code.includes('details.has(key)'));assert.ok(code.includes('version!==filterVersion'));assert.ok(code.includes('version!==epoch'));
  assert.ok(code.includes("cy.on('tap','node'"));assert.ok(code.includes("get('select').addEventListener('change'"));
  const html=readFileSync('src/main/resources/templates/ontology.html','utf8');
  assert.ok(html.includes('/webjars/cytoscape/3.34.1/dist/cytoscape.min.js'));assert.ok(html.includes('data-on-view="erd"'));
});
test('all ERD literal labels and parameters exist in four languages',()=>{
  const code=readFileSync('src/main/resources/static/js/ontology-erd.mjs','utf8'),labels=JSON.parse(readFileSync('tools/i18n/feature-ontology-erd.json','utf8'));
  for(const match of code.matchAll(/label\('([^']+)'/g))assert.ok(labels['ontology.erd.'+match[1]],match[1]);
  for(const [key,values] of Object.entries(labels)){
    assert.equal(values.length,4,key);for(const value of values){assert.ok(value.trim(),key);assert.deepEqual(value.match(/\{\d+\}/g),values[0].match(/\{\d+\}/g),key);}
  }
});
