import test from 'node:test';
import assert from 'node:assert/strict';
import {flowGraph,storedGraph,layoutFlow,traceGraph} from '../../main/resources/static/js/engine-flow.mjs';
test('stored metadata shows input, named processing target and output without invented internal steps',()=>{
 const graph=storedGraph({name:'DEMO',inputs:[{title:'amount',type:'number'}],outputs:[{title:'result',type:'string'}]});
 assert.equal(graph.nodes.length,3);assert.equal(graph.edges.length,2);assert.equal(graph.nodes[1].title,'DEMO');
 assert.deepEqual(graph.nodes[0].rows,['amount · number']);
});
test('declared branches retain arrows and parallel tasks',()=>{
 const graph=flowGraph({inputs:[],nodes:[{id:'start',component_type:'StartNode'},{id:'branch',component_type:'BranchingNode'},{id:'a',component_type:'EndNode'},{id:'b',component_type:'EndNode'}],control_flow_connections:[{from_node:{id:'start'},to_node:{id:'branch'}},{from_node:{id:'branch'},to_node:{id:'a'},from_branch:'yes'},{from_node:{id:'branch'},to_node:{id:'b'},from_branch:'no'}]});
 const layout=layoutFlow(graph);assert.equal(graph.edges[2].label,'no');assert.equal(layout.nodes[2].x,layout.nodes[3].x);assert.notEqual(layout.nodes[2].y,layout.nodes[3].y);
});
test('cyclic flows terminate layout and trace retains actual sequence',()=>{
 const layout=layoutFlow({nodes:[{id:'a',rows:[]},{id:'b',rows:[]}],edges:[{from:'a',to:'b'},{from:'b',to:'a'}]});
 assert.ok(Number.isFinite(layout.width));
 assert.equal(traceGraph({roots:[{children:[{node_name:'input'},{node_name:'tool'},{node_name:'output'}]}]}).edges.length,2);
});
