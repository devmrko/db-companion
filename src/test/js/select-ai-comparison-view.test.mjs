import test from 'node:test';
import assert from 'node:assert/strict';
import {comparisonRows,renderComparisonRows} from '../../main/resources/static/js/select-ai-comparison-view.mjs';
const row=(result,key)=>comparisonRows(result).find(item=>item.key===key);
const inspection={settings:{comments:'true'},objects:[{owner:'APP',name:'T'}],tables:[],checkedAt:'now',feedback:null,feedbackDetails:[]};
test('unobserved values never appear equal',()=>assert.ok(comparisonRows({}).every(item=>!item.same)));
test('object list loading alone is not a metadata read',()=>{
  const value=row({leftInspection:inspection,rightInspection:inspection},'metadata');
  assert.equal(value.left.status,'NOT_QUERIED');assert.equal(value.same,false);
});
test('options compare values independently of observation timestamps',()=>{
  assert.equal(row({leftInspection:inspection,rightInspection:{...inspection,checkedAt:'later'}},'options').same,true);
  assert.equal(row({leftInspection:inspection,rightInspection:{...inspection,settings:{comments:'false'}}},'options').same,false);
});
test('metadata read error cannot be an equal empty result',()=>{
  const value={...inspection,tables:[{owner:'APP',name:'T',error:'NOT_VISIBLE'}]};
  assert.equal(row({leftInspection:value,rightInspection:value},'metadata').same,false);
});
test('Feedback details are necessary and participate in comparison',()=>{
  const feedback={rows:{items:[{id:'r'}],hasNext:false},checkedAt:'now'};
  const left={...inspection,feedback,feedbackDetails:[{id:'r',detail:{sqlText:'SELECT 1'},checkedAt:'now'}]};
  const right={...left,feedbackDetails:[{id:'r',detail:{sqlText:'SELECT 2'},checkedAt:'later'}]};
  assert.equal(row({leftInspection:left,rightInspection:right},'feedback').same,false);
  assert.equal(row({leftInspection:{...left,feedbackDetails:[]}},'feedback').left.status,'PARTIAL');
});
test('SHOWPROMPT remains explicitly reconstructed and failed text stays error',()=>{
  assert.equal(row({leftPrompt:{text:'prompt',requestedAt:'now'}},'prompt').left.status,'RECONSTRUCTED_SHOWPROMPT');
  assert.equal(row({leftPrompt:{text:'partial',error:'failure'}},'prompt').left.status,'ERROR');
});
test('rendering uses text nodes, labels both sides and retains unknown differences',()=>{
  class Node {constructor(tag){this.tag=tag;this.children=[];}append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this.children=nodes;}}
  const previous=globalThis.document;globalThis.document={createElement:tag=>new Node(tag)};
  try{const host=new Node('host');renderComparisonRows(host,{left:{text:'<script>unsafe()</script>'}},true);
    const all=[];const visit=node=>{all.push(node);node.children?.forEach(visit);};visit(host);
    assert.ok(all.some(node=>node.tag==='pre'&&node.textContent==='<script>unsafe()</script>'));
    assert.ok(all.some(node=>node.tag==='h4'&&node.textContent==='A'));
    assert.ok(all.some(node=>node.tag==='h4'&&node.textContent==='B'));
    assert.ok(all.some(node=>node.textContent?.includes('NOT_QUERIED')));
  }finally{if(previous===undefined)delete globalThis.document;else globalThis.document=previous;}
});
