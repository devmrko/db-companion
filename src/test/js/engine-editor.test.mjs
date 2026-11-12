import test from 'node:test';
import assert from 'node:assert/strict';
import {newFlow,nodes,updateNode,removeNode,connect,validateFlow,editableFlow,resolve} from '../../main/resources/static/js/engine-editor-model.mjs';
test('new flow exports and reloads an explicit input-output mapping',()=>{
  const f=editableFlow(JSON.parse(JSON.stringify(newFlow())));
  assert.deepEqual(validateFlow(f),[]);assert.equal(f.data_flow_connections[0].source_output,'message');
});
test('deleting a node removes its control and data edges and reports incomplete flow',()=>{
  const f=newFlow();removeNode(f,'flow-end');assert.equal(f.nodes.length,1);
  assert.equal(f.control_flow_connections.length,0);assert.equal(f.data_flow_connections.length,0);assert.ok(validateFlow(f).length);
  assert.equal(f.$referenced_components['flow-end'],undefined);
});
test('rewiring rejects a second control destination and mismatched port types',()=>{
  const f=newFlow();assert.throws(()=>connect(f,'control','flow-start','flow-end','next'));
  const end=structuredClone(nodes(f)[1]);end.inputs[0].type='number';updateNode(f,end);
  assert.throws(()=>connect(f,'data','flow-start','flow-end','message','message'));
});
test('editing node preserves extra attributes and reference alias',()=>{
  const f=newFlow();const start=f.$referenced_components['flow-start'];start.metadata={source:'synthetic'};
  const imported=editableFlow(f),changed=structuredClone(nodes(imported)[0]);changed.name='새 입력';updateNode(imported,changed);
  assert.equal(resolve(imported,imported.control_flow_connections[0].from_node).name,'새 입력');assert.deepEqual(nodes(imported)[0].metadata,{source:'synthetic'});
});
test('missing required data mapping and unreachable nodes are reported',()=>{
  const f=newFlow();f.data_flow_connections=[];assert.ok(validateFlow(f).some(e=>e.includes('입력 매핑')));
  updateNode(f,{id:'orphan',name:'orphan',component_type:'EndNode',inputs:[],outputs:[],branches:[]});assert.ok(validateFlow(f).some(e=>e.includes('도달')));
});
test('parallel child flows survive roundtrip and validation recurses',()=>{
  const f=newFlow();updateNode(f,{id:'parallel',name:'parallel',component_type:'ParallelFlowNode',inputs:[],outputs:[],branches:['next'],subflows:[newFlow('child')]});
  f.control_flow_connections=[];connect(f,'control','flow-start','parallel','next');connect(f,'control','parallel','flow-end','next');
  assert.deepEqual(validateFlow(editableFlow(f)),[]);
  nodes(f).find(n=>n.id==='parallel').subflows[0].nodes=[];assert.ok(validateFlow(f).length);
});
