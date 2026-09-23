import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {compactIri,triplesPage,meaningChanges} from '../../main/resources/static/js/ontology-rdf.mjs';
import {readOnlyPanel} from '../../main/resources/static/js/ontology.mjs';
const data={documentIri:'urn:uuid:ID',versionIri:'urn:uuid:ID/revision/3',tableIri:'urn:uuid:ID/revision/3/table',prefixes:{dbc:'urn:dbcompanion:ontology:',rdf:'http://www.w3.org/1999/02/22-rdf-syntax-ns#'},triples:[]};
test('IRI display abbreviates only the exact resource or a slash-delimited descendant',()=>{
  assert.equal(compactIri(data.documentIri,data),'document');assert.equal(compactIri(data.versionIri,data),'revision');assert.equal(compactIri(data.tableIri,data),'table');
  assert.equal(compactIri(data.tableIri+'/column/ID',data),'table/column/ID');assert.equal(compactIri(data.tableIri+'Other',data),data.tableIri+'Other');
  assert.equal(compactIri('urn:dbcompanion:ontology:name',data),'dbc:name');
});
test('triples search all values and paginate ten at a time without mutating input',()=>{
  const rows=Array.from({length:24},(_,i)=>({subject:data.tableIri+'/column/C'+i,predicate:'urn:dbcompanion:ontology:name',object:{kind:'LITERAL',value:i%2?'고객':'주문'}}));
  const value={...data,triples:rows};assert.equal(triplesPage(value,'',2).items.length,10);assert.equal(triplesPage(value,'',3).items.length,4);
  assert.equal(triplesPage(value,'고객',1).total,12);assert.equal(triplesPage(value,'dbc:name',1).total,24);assert.equal(triplesPage(value,'/column/c23',1).total,1);
  assert.equal(triplesPage(value,'missing',9).page,1);assert.equal(triplesPage(value,'missing',9).total,0);assert.equal(rows[0].name,undefined);
});
test('AI review compares only changed meanings and keeps markup inert as strings',()=>{
  const before={concept:'',description:'old',columns:{ID:{description:'identifier'},NAME:{description:'name'}}};
  const after={concept:'고객',description:'old',columns:{ID:{description:'identifier'},NAME:{description:'<script>alert(1)</script>'}}};
  assert.deepEqual(meaningChanges(before,after),[{field:'concept',before:'',after:'고객'},{field:'column',column:'NAME',before:'name',after:'<script>alert(1)</script>'}]);
  assert.deepEqual(meaningChanges(before,before),[]);
});
test('older versions remain immutable while RDF search and identifier selection stay usable',()=>{
  for(const tab of ['definition','columns','relations'])assert.equal(readOnlyPanel({revision:1},3,tab),true);
  for(const tab of ['rdf','history'])assert.equal(readOnlyPanel({revision:1},3,tab),false);
  assert.equal(readOnlyPanel({revision:3},3,'definition'),false);
});
test('viewer uses typed data and textContent, with explicit download and no egress or state writes',()=>{
  const code=readFileSync('src/main/resources/static/js/ontology-rdf.mjs','utf8');
  assert.ok(code.includes('textContent'));assert.ok(code.includes('input.readOnly=true'));assert.ok(code.includes("type:'text/turtle;charset=utf-8'"));
  assert.ok(!/innerHTML|eval\(|fetch\(|localStorage|sessionStorage/.test(code));
  const main=readFileSync('src/main/resources/static/js/ontology.mjs','utf8');assert.ok(main.includes('rdfData??=await'));assert.ok(main.includes('entry=data;rdfData=null;'));
  assert.ok(main.includes('rdfViewer(rdfData,dirty)'));assert.ok(main.includes('meaningChanges(entry.document.meaning,proposal.meaning)'));
});
test('RDF labels include all four languages and cover every literal label call',()=>{
  const labels=JSON.parse(readFileSync('tools/i18n/feature-ontology-rdf.json','utf8'));
  for(const [key,value] of Object.entries(labels)){assert.equal(value.length,4,key);assert.ok(value.every(s=>typeof s==='string'&&s.trim()),key);}
  const code=readFileSync('src/main/resources/static/js/ontology-rdf.mjs','utf8');
  for(const match of code.matchAll(/label\('([^']+)'\)/g))assert.ok(labels['ontology.rdf.'+match[1]],match[1]);
});
