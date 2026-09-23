import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {selectedRoute,groupedTables,columnPage,routeArrow,resultPage} from '../../main/resources/static/js/ontology-query.mjs';

test('route selection groups tables and preserves actual foreign key direction',()=>{
  const route={id:'P1',tables:['A','LINK','B'],relations:['R1','R2']};
  const search={routes:[route],tables:[{name:'B'},{name:'A'},{name:'LINK'},{name:'OTHER'}],evidence:[{id:'R1',source:'LINK'},{id:'R2',source:'LINK'}]};
  assert.equal(selectedRoute(search,'P1'),route);assert.equal(selectedRoute(search,'unknown'),null);assert.equal(selectedRoute(null,''),null);
  assert.deepEqual(groupedTables(search,route).map(t=>t.name),['A','LINK','B']);assert.equal(routeArrow(search,route,0),'←');assert.equal(routeArrow(search,route,1),'→');
});
test('local contains filtering and ten-row pagination preserve evidence IDs',()=>{
  const values=Array.from({length:23},(_,i)=>({name:'M'+i,label:'CUSTOMER',description:'정의',aliases:['고객']}));
  const page=columnPage(values,'customer',2);assert.equal(page.total,23);assert.equal(page.items.length,10);assert.equal(page.items[0].name,'M10');assert.equal(columnPage(values,'M22',1).items[0].name,'M22');assert.equal(columnPage(values,'missing',1).total,0);assert.equal(columnPage(values,'고객',1).total,23);
  const rows=Array.from({length:23},(_,i)=>[{value:String(i),truncated:false}]);assert.equal(resultPage(rows,3).items.length,3);assert.equal(resultPage(rows,3).items[0].cells[0].value,'20');
});
test('generation and execution are separate explicit token-bound actions with no row retransmission',()=>{
  const code=readFileSync('src/main/resources/static/js/ontology-query.mjs','utf8');
  assert.ok(code.includes("get('generate').addEventListener('click'"));assert.ok(code.includes("get('execute').addEventListener('click'"));assert.ok(code.includes("post('execute',{token,confirmed:true})"));assert.ok(code.includes("post('generate',{token,consent:get('consent').checked})"));
  assert.doesNotMatch(code,/innerHTML|eval\(|localStorage|sessionStorage|runsql/);assert.ok(code.includes('textContent'));assert.ok(code.includes("get('reviewed').checked=false"));assert.ok(code.includes("get('question').addEventListener('input',()=>invalidate(true))"));
  assert.doesNotMatch(code,/post\([^\n]+(?:rows|result\.rows|sql:draft)/);assert.ok(code.includes("get('result-next').addEventListener('click',()=>{rp++;renderRows();})"));
});
test('all labels are translated with matching placeholders',()=>{
  const labels={...JSON.parse(readFileSync('tools/i18n/feature-ontology-query.json','utf8')),...JSON.parse(readFileSync('tools/i18n/feature-ontology-query-paths.json','utf8'))};const code=readFileSync('src/main/resources/static/js/ontology-query.mjs','utf8'),html=readFileSync('src/main/resources/templates/ontology-query.html','utf8');
  for(const match of code.matchAll(/q\('([^']+)'/g))if(!match[1].endsWith('.'))assert.ok(labels['ontology.query.'+match[1]],match[1]);
  for(const match of html.matchAll(/#\{(ontology\.query\.[^}]+)\}/g))assert.ok(labels[match[1]],match[1]);
  for(const [key,values] of Object.entries(labels)){assert.equal(values.length,4,key);values.forEach((v,i)=>{assert.ok(v.trim(),key);assert.deepEqual(v.match(/\{\d+\}/g),values[0].match(/\{\d+\}/g),key);if(i)assert.doesNotMatch(v,/[가-힣]/u,key);});}
});
