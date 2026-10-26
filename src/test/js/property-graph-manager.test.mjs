import test from 'node:test';
import assert from 'node:assert/strict';
import {propertyGraphManager} from '../../main/resources/static/js/property-graph-manager.mjs';
import {renderMetadataBrowser} from '../../main/resources/static/js/metadata-graph-browser.mjs';

function element(tag){
  return {tag,children:[],listeners:{},dataset:{},textContent:'',disabled:false,
    append(...nodes){this.children.push(...nodes);},replaceChildren(...nodes){this.children=nodes;},
    setAttribute(key,value){this[key]=value;},addEventListener(event,handler){this.listeners[event]=handler;}};
}
function descendants(node){return [node,...node.children.flatMap(descendants)];}

test('containment-only graph clearly reports no relationships and keeps columns separate',()=>{
  const before=globalThis.document;globalThis.document={createElement:element};
  try{
    const host=element('section');
    renderMetadataBrowser(host,{rows:[],mappings:[],columns:[{SOURCE_OBJECT:'T',TARGET_COLUMN:'ID'}],counts:{RELATES_TO:0,MAPS_TO:0,CONTAINS:1}});
    assert.ok(descendants(host).some(n=>n.textContent.startsWith('No table relationships')));
    assert.ok(!descendants(host).some(n=>n.textContent==='ID'));
    descendants(host).find(n=>n.tag==='button'&&n.textContent==='Column membership (1)').listeners.click();
    assert.ok(descendants(host).some(n=>n.textContent==='ID'));
  }finally{globalThis.document=before;}
});

test('existing graph can be queried in the ontology tab without running creation',async()=>{
  const before=globalThis.document;globalThis.document={createElement:element};
  try{
    const host=element('section'),calls=[];
    const manager=propertyGraphManager(host,{schema:'APP',create:()=>{throw Error('creation must remain explicit');},
      post:async(path,value)=>{calls.push([path,value]);return path.endsWith('/list')?
        [{name:'META',status:'VALID',nodesStatus:'VALID',edgesStatus:'VALID',metadataCandidate:true}]:
        {mode:'METADATA',rows:[{SOURCE_OBJECT:'A',EDGE_KIND:'RELATES_TO',TARGET_OBJECT:'B',RELATION_LABEL:'Owns'}],columns:[{SOURCE_OBJECT:'A',TARGET_COLUMN:'ID'}],counts:{RELATES_TO:1,CONTAINS:150}};}});
    await manager.show();
    const read=descendants(host).filter(node=>node.tag==='button').at(-1);
    assert.equal(read.disabled,false);
    await read.listeners.click();
    assert.deepEqual(calls.map(row=>row[0]),['/metadata-graph/list','/metadata-graph/query']);
    assert.deepEqual(calls[1][1],{schema:'APP',name:'META'});
    assert.ok(descendants(host).some(node=>node.textContent==='Owns'));
    assert.ok(!descendants(host).some(node=>node.textContent==='ID'));
    const columns=descendants(host).find(node=>node.tag==='button'&&node.textContent==='Column membership (150)');
    columns.listeners.click();
    assert.ok(descendants(host).some(node=>node.textContent==='ID'));
    assert.ok(descendants(host).some(node=>node.textContent==='Showing 1 of 150'));
  }finally{globalThis.document=before;}
});

test('a valid graph without companion views gets a generic ID query',async()=>{
  const before=globalThis.document;globalThis.document={createElement:element};
  try{
    const host=element('section'),calls=[];
    const manager=propertyGraphManager(host,{schema:'APP',create:()=>{},post:async path=>{calls.push(path);return path.endsWith('/list')?[{name:'OTHER',status:'VALID',nodesStatus:null,edgesStatus:null,metadataCandidate:false}]:{mode:'GENERIC',rows:[{SOURCE_ID:'A',EDGE_ID:{part:2},TARGET_ID:'B'}]};}});
    await manager.show();
    const read=descendants(host).filter(node=>node.tag==='button').at(-1);
    assert.equal(read.disabled,false);await read.listeners.click();assert.deepEqual(calls,['/metadata-graph/list','/metadata-graph/query']);
    assert.ok(descendants(host).some(node=>node.textContent==='{"part":2}'));
    assert.ok(!descendants(host).some(node=>node.textContent==='[object Object]'));
  }finally{globalThis.document=before;}
});

test('invalid existing graph stays unavailable after page unlock',async()=>{
  const before=globalThis.document;globalThis.document={createElement:element};
  try{
    const host=element('section');
    const manager=propertyGraphManager(host,{schema:'APP',create:()=>{},post:async()=>[{name:'BROKEN',status:'INVALID',nodesStatus:'VALID',edgesStatus:'VALID',metadataCandidate:false}]});
    await manager.show();
    const read=descendants(host).filter(node=>node.tag==='button').at(-1);
    assert.equal(read.disabled,true);assert.equal(read.dataset.boundDisabled,'true');
  }finally{globalThis.document=before;}
});
