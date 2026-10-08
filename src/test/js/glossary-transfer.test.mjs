import test from 'node:test';
import assert from 'node:assert/strict';
import {selectedImportRows,renderImportPreview,MAX_IMPORT_BYTES} from '../../main/resources/static/js/glossary-transfer.mjs';
const preview={entries:['NEW','UPDATE','UNCHANGED','CONFLICT'].map((status,row)=>({row,status,value:{term:'<img>',definition:'<script>'}}))};
test('import selection excludes identical ambiguous duplicate and unknown rows',()=>{
  assert.equal(MAX_IMPORT_BYTES,4_000_000);assert.equal(selectedImportRows(preview,[0,1]),true);
  for(const rows of [[],[2],[3],[5],[0,0]])assert.equal(selectedImportRows(preview,rows),false);
  assert.equal(selectedImportRows(null,[0]),false);
});
class Node {
  constructor(tag){this.tag=tag;this.children=[];this.dataset={};this.textContent='';}
  append(...nodes){this.children.push(...nodes);}
  replaceChildren(...nodes){this.children=nodes;}
  addEventListener(){}
  set innerHTML(value){throw Error('Unsafe content rendering');}
}
test('all writes require manual selection; imported markup stays plain text',()=>{
  globalThis.document={createElement:tag=>new Node(tag)};
  try{const root=new Node('main');renderImportPreview(root,preview,()=>{});const all=n=>[n,...n.children.flatMap(all)];
    const nodes=all(root),checks=nodes.filter(n=>n.tag==='input');assert.equal(checks.length,4);
    assert.ok(checks.every(n=>!n.checked));assert.deepEqual(checks.map(n=>n.disabled),[false,false,true,true]);
    assert.ok(nodes.some(n=>n.textContent.includes('<script>')));
  }finally{delete globalThis.document;}
});
