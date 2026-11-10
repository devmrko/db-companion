import test from 'node:test';
import assert from 'node:assert/strict';
import ELK from 'elkjs/lib/elk.bundled.js';
import {graphModel, layoutGraph, highlight, nodeId} from './graph-model.mjs';
const relation=(id,status,extra={})=>({id,status,source:'ORDERS',targetSchema:'APP',target:'CUSTOMERS',
  sourceColumns:['BUYER_ID'],targetColumns:['ID'],label:'주문 고객',...extra});
const data={schema:'APP',tables:[{name:'ORDERS',concept:'주문'},{name:'CUSTOMERS',concept:'고객'},{name:'ISOLATED'}],
  relations:[relation('fk','FK',{key:{status:'ENABLED',validated:'VALIDATED'}}),relation('candidate','CANDIDATE'),
    relation('stale','STALE'),relation('rejected','REJECTED'),relation('disabled','FK',{key:{status:'DISABLED',validated:'VALIDATED'}})]};
test('confirmed-only default preserves isolated tables and candidates require explicit reveal',()=>{
  const graph=graphModel(data);assert.equal(graph.nodes.length,3);assert.deepEqual(graph.edges.map(e=>e.id),['fk']);
  assert.deepEqual(graphModel(data,{reveal:'candidate'}).edges.map(e=>e.id),['fk','candidate']);
  assert.equal(graphModel(data,{candidates:true}).edges.length,4);
  assert.ok(!graphModel(data,{reveal:'rejected'}).edges.some(e=>e.id==='rejected'));
});
test('cross-schema identity, compound columns and parallel edges remain distinct',()=>{
  const graph=graphModel({...data,relations:[relation('a','APPROVED'),relation('b','APPROVED',{targetSchema:'OTHER',sourceColumns:['BUYER_ID','TENANT_ID'],targetColumns:['ID','TENANT_ID']})]});
  assert.equal(graph.edges.length,2);assert.notEqual(graph.edges[0].target,graph.edges[1].target);
  assert.deepEqual(graph.nodes.find(n=>n.id===nodeId('APP','ORDERS')).data.columns,['BUYER_ID','TENANT_ID']);
  assert.equal(graph.nodes.find(n=>n.id===nodeId('OTHER','CUSTOMERS')).data.boundary,true);
});
test('selection highlights only neighbors without mutating graph positions or records',()=>{
  const graph=graphModel(data),before=JSON.stringify(graph);
  const view=highlight(graph,nodeId('APP','ORDERS'));
  assert.equal(view.nodes.find(n=>n.id===nodeId('APP','ISOLATED')).style.opacity,.3);
  assert.equal(view.edges[0].style.opacity,1);assert.equal(JSON.stringify(graph),before);
});
test('real ELK handles cycles, self-links, parallel edges and isolates without overlapping cards',async()=>{
  const graph=graphModel({...data,relations:[relation('a','APPROVED'),relation('b','APPROVED'),
    relation('c','APPROVED',{source:'CUSTOMERS',target:'ORDERS'}),relation('self','APPROVED',{target:'ORDERS'})]});
  const elk=new ELK();
  try {
    const placed=await layoutGraph(graph,elk);
    for(const n of placed.nodes) {assert.ok(Number.isFinite(n.position.x));assert.ok(Number.isFinite(n.position.y));}
    for(let i=0;i<placed.nodes.length;i++)for(let j=i+1;j<placed.nodes.length;j++) {
      const a=placed.nodes[i],b=placed.nodes[j];
      assert.ok(a.position.x+a.width<=b.position.x||b.position.x+b.width<=a.position.x||a.position.y+a.height<=b.position.y||b.position.y+b.height<=a.position.y);
    }
    assert.equal(placed.edges.length,4);
    assert.deepEqual(await layoutGraph({nodes:[],edges:[]},elk),{nodes:[],edges:[]});
  }finally{/* Bundled ELK has no native worker. */}
});
