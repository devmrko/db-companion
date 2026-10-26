import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {queryGraph,graphPanel,graphLabel} from '../../main/resources/static/js/ontology-query-graph.mjs';
const source=name=>({name,schema:'APP',concept:'Concept '+name,revision:2,state:'DRAFT',versionIri:'urn:source:'+name,columns:[{name:'CODE',type:'NUMBER',description:'Identifier'}]});
const relation=(id='AB',extra={})=>({id,source:'A',target:'B',from:['DAY','CODE'],to:['DAY','CODE'],status:'STALE',usable:false,label:'same person',condition:'same day only',origin:'AI',evidence:['unverified'],resourceIri:'urn:relation:'+id,...extra});
const summary=()=>({sources:['A','B','C'].map(source),rules:[{term:'Standard',definition:'Independent count.',criteria:'SELECT COUNT(*) FROM A'},{term:'Business',definition:'Different population.',criteria:'SELECT COUNT(*) FROM B'}],termReferences:[{id:'T1',revision:1,term:'Standard',sources:['A']},{id:'T2',revision:3,term:'Business',sources:['B']}],relations:[relation(),relation('AC',{target:'C'})]});
const selection={tables:['A','B'],mode:'INDEPENDENT',relations:[]};
test('independent aggregates show terms, only selected sources and a distinct operation, never a join',()=>{
 const g=queryGraph(summary(),selection);assert.equal(g.nodes.length,7);assert.equal(g.edges.length,6);
 assert.equal(g.nodes.filter(v=>v.data.kind==='operation').length,1);assert.ok(!g.nodes.some(v=>v.data.id==='source:C'));
 assert.equal(g.referenceCount,1);assert.equal(g.omitted,1);assert.ok(!g.edges.some(v=>v.data.kind==='join'||v.data.kind==='reference'));
 assert.deepEqual(g.edges.filter(v=>v.data.kind==='mapping').map(v=>v.data.target),['source:A','source:B']);
 assert.equal(g.nodes.filter(v=>v.data.kind==='rule').length,2);
 assert.ok(g.edges.some(v=>v.data.source==='source:A'&&v.data.target==='rule:0'));
 assert.ok(g.edges.some(v=>v.data.source==='rule:0'&&v.data.target==='operation:independent'));
 assert.ok(!g.nodes.some(v=>v.data.label.includes('UNION ALL')));
 assert.equal(g.nodes.find(v=>v.data.id==='source:A').data.label,'Concept A');
});
test('long and unbroken names are bounded on canvas while full evidence is retained',()=>{
 const value='LONG_IDENTIFIER_'.repeat(30),s=summary();s.sources[0].concept='';s.sources[0].name=value;
 const g=queryGraph(s,{...selection,tables:[value]});const n=g.nodes.find(v=>v.data.kind==='source');
 assert.ok(n.data.label.endsWith('…'));assert.ok(n.data.label.split('\n').every(v=>Array.from(v).length<=22));assert.equal(n.data.record.name,value);
 assert.equal(graphLabel('😀'.repeat(30)).replaceAll('\n',''),'😀'.repeat(30));
});
test('multi-source rules and missing dictionary references never fabricate separate per-source criteria',()=>{
 const s=summary();s.termReferences[0].sources=['A','B'];s.termReferences[1].sources=[];
 const g=queryGraph(s,selection);assert.equal(g.nodes.filter(v=>v.data.kind==='rule').length,0);
 assert.equal(g.edges.filter(v=>v.data.kind==='operation').length,2);
 const joined=queryGraph(summary(),{...selection,mode:'JOIN'});assert.equal(joined.nodes.filter(v=>v.data.kind==='rule').length,0);
});
test('rule excerpts retain verbatim provenance and never replace the full definition',()=>{
 const s=summary();s.rules[0].definition='Important filter. '+'More evidence. '.repeat(50);
 const g=queryGraph(s,selection),r=g.nodes.find(v=>v.data.id==='rule:0').data;
 assert.ok(r.label.endsWith('…'));assert.equal(r.record.definition,s.rules[0].definition);assert.equal(r.reference.id,'T1');
});
test('no dictionary reference means no invented edge; unavailable mapping stays unconnected',()=>{
 const s=summary();s.termReferences=[];let g=queryGraph(s,selection);assert.equal(g.edges.filter(v=>v.data.kind==='mapping').length,0);
 s.termReferences=[{term:'not the term',sources:['A']}];g=queryGraph(s,selection);assert.equal(g.edges.filter(v=>v.data.kind==='mapping').length,0);
 assert.equal(queryGraph(summary(),{...selection,tables:[]}).edges.length,0);
});
test('reference toggle never authorizes stale joins or changes the selection',()=>{
 const input=structuredClone(selection);input.mode='JOIN';input.relations=['AB'];
 const g=queryGraph(summary(),input,true);assert.equal(g.edges.filter(v=>v.data.kind==='join').length,0);
 const r=g.edges.find(v=>v.data.kind==='reference').data.record;assert.deepEqual(r.from,['DAY','CODE']);assert.equal(r.condition,'same day only');
 assert.deepEqual(input,{tables:['A','B'],mode:'JOIN',relations:['AB']});assert.equal(g.nodes.filter(v=>v.data.kind==='operation').length,0);
});
test('only selected usable relationships are solid joins; parallel and self edges keep stable IDs',()=>{
 const s=summary();s.relations=[relation('AB',{usable:true,status:'APPROVED'}),relation('AB2',{usable:true,status:'FK'}),relation('AA',{target:'A'})];
 const g=queryGraph(s,{...selection,mode:'JOIN',relations:['AB','AB2']},true);
 assert.equal(g.edges.filter(v=>v.data.kind==='join').length,2);assert.equal(new Set(g.edges.map(v=>v.data.id)).size,g.edges.length);
 assert.ok(g.edges.some(v=>v.data.source===v.data.target));assert.equal(g.referenceCount,1);
});
test('reference display is bounded and reports omissions without dropping selected joins',()=>{
 const s=summary();s.relations=Array.from({length:55},(_,i)=>relation(String(i)));
 const g=queryGraph(s,selection,true);assert.equal(g.referenceCount,55);assert.equal(g.omitted,15);assert.equal(g.edges.filter(v=>v.data.kind==='reference').length,40);
});
const el=tag=>({tag,children:[],listeners:{},style:{},attrs:{},append(...xs){this.children.push(...xs);},replaceChildren(...xs){this.children=xs;},setAttribute(k,v){this.attrs[k]=v;},addEventListener(k,f){this.listeners[k]=f;}});
const all=e=>[e,...e.children.flatMap(all)];
test('clicks inspect escaped text and full composite evidence; redraw and disconnect dispose the renderer',()=>{
 const before={document:globalThis.document,cytoscape:globalThis.cytoscape,ResizeObserver:globalThis.ResizeObserver};let destroyed=0,disconnected=0,tap;
 globalThis.document={createElement:el};globalThis.ResizeObserver=class{observe(){}disconnect(){disconnected++;}};
 globalThis.cytoscape=()=>({destroy(){destroyed++;},resize(){},fit(){},zoom(){return 1;},elements:()=>({unselect(){}}),getElementById:()=>({select(){}}),on(event,selector,fn){tap=fn;}});
 try{
  const host=el('section'),s=summary();s.rules[0].definition='<script>untrusted</script>';const view=graphPanel(host,s);view.update(selection);
  const detail=all(host).find(n=>n.tag==='aside');assert.equal(detail.hidden,true);
  tap({target:{id:()=> 'rule:0'}});assert.equal(detail.hidden,false);assert.ok(all(detail).some(n=>n.textContent==='ruleHelp'));
  all(detail).find(n=>n.tag==='button'&&n.textContent==='closeDetail').listeners.click();assert.equal(detail.hidden,true);
  tap({target:{id:()=> 'term:0'}});assert.ok(all(host).some(n=>n.textContent==='<script>untrusted</script>'));assert.ok(all(host).every(n=>!Object.hasOwn(n,'innerHTML')));
  all(host).find(n=>n.tag==='button'&&n.textContent.startsWith('references')).listeners.click();
  tap({target:{id:()=> 'relation:AB'}});assert.ok(all(host).some(n=>n.textContent==='2. CODE → CODE'));assert.ok(all(host).some(n=>n.textContent==='same day only'));
  assert.equal(destroyed,1);view.destroy();assert.equal(destroyed,2);assert.equal(disconnected,1);view.update(selection);assert.equal(destroyed,2);
 }finally{Object.assign(globalThis,before);}
});
test('missing visualization library preserves a keyboard evidence selector and textual fallback',()=>{
 const before={document:globalThis.document,cytoscape:globalThis.cytoscape};globalThis.document={createElement:el};globalThis.cytoscape=undefined;
 try{const host=el('section'),view=graphPanel(host,summary());view.update(selection);assert.ok(all(host).some(n=>n.textContent==='fallback'));const picker=all(host).find(n=>n.tag==='select');picker.value='source:A';picker.listeners.change();assert.ok(all(host).some(n=>n.textContent==='APP.A'));view.destroy();}finally{Object.assign(globalThis,before);}
});
test('both query consumers use locally bundled Cytoscape; component never sends requests or approves relations',()=>{
 for(const page of ['ontology-query','ai-test'])assert.match(readFileSync('src/main/resources/templates/'+page+'.html','utf8'),/webjars\/cytoscape\/3\.34\.1\/dist\/cytoscape.min.js/);
 const code=readFileSync('src/main/resources/static/js/ontology-query-graph.mjs','utf8');assert.ok(!/fetch\(|assistantApi|innerHTML|\.approve\(/.test(code));assert.match(code,/disconnectedCallback/);
});
