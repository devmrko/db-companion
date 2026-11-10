import test from 'node:test';
import assert from 'node:assert/strict';
import {parseFlow,inputValue,components} from '../../main/resources/static/js/agent-engine.mjs';
const spec={component_type:'Flow',agentspec_version:'25.4.2',inputs:[{title:'quantity',type:'number'}]};
test('flow import rejects duplicates and unsupported input schemas',()=>{
  assert.equal(parseFlow(JSON.stringify(spec)).inputs.length,1);
  assert.throws(()=>parseFlow(JSON.stringify({...spec,inputs:[...spec.inputs,...spec.inputs]})));
  assert.throws(()=>parseFlow('{"component_type":"Agent"}'));
});
test('input values keep JSON types without coercing invalid data',()=>{
  assert.equal(inputValue({title:'q',type:'number'},'12.5'),12.5);
  assert.equal(inputValue({title:'q',type:'string'},'001'),'001');
  assert.throws(()=>inputValue({title:'q',type:'integer'},'1.5'));
  assert.throws(()=>inputValue({title:'q',type:'number'},'"3"'));
  assert.throws(()=>inputValue({title:'q',type:'object'},'null'));
});
test('preview includes nested parallel flows and PL/SQL tools',()=>{
  assert.deepEqual(components({...spec,nodes:[{component_type:'ParallelFlowNode',branches:[{component_type:'ToolNode'}]}]}).map(x=>x.component_type),['Flow','ParallelFlowNode','ToolNode']);
});
