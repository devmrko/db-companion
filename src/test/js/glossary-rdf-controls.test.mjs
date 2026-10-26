import test from 'node:test';
import assert from 'node:assert/strict';
import {mountGlossaryRdf} from '../../main/resources/static/js/glossary-rdf.mjs';

class Element {
  constructor(tag){this.tag=tag;this.children=[];this.events={};this.value='';this.textContent='';this.disabled=false;}
  append(...nodes){for(const node of nodes){node.parent=this;this.children.push(node);}}
  after(node){this.parent.append(node);}
  replaceChildren(...nodes){this.children=[];this.append(...nodes);}
  setAttribute(){}
  addEventListener(name,fn){this.events[name]=fn;}
  fire(name){return this.events[name]?.();}
  all(){return this.children.flatMap(node=>[node,...node.all()]);}
}
test('RDF controls keep unsaved/export and empty-SQL analysis disabled and discard stale analysis',async()=>{
  const previousDocument=globalThis.document,previousFetch=globalThis.fetch;
  globalThis.document={createElement:tag=>new Element(tag)};
  const root=new Element('div'),host=new Element('label');root.append(host);
  let resolve,calls=0;
  globalThis.fetch=()=>{calls++;return new Promise(done=>{resolve=done;});};
  try{
    const editor=mountGlossaryRdf(host,{dataset:{csrfHeader:'X-CSRF'},value:'synthetic'});
    const exportButton=root.all().find(n=>n.tag==='button'&&n.textContent==='저장된 RDF 확인·내보내기');
    const check=root.all().find(n=>n.tag==='button'&&n.textContent==='SQL 참조 확인');
    const sql=root.all().find(n=>n.tag==='textarea'&&n.maxLength===20000&&n.rows===5);
    editor.edit(null);editor.controls(false);assert.equal(exportButton.disabled,true);assert.equal(check.disabled,true);
    editor.edit({id:'term-a',revision:1});assert.equal(exportButton.disabled,false);assert.equal(check.disabled,true);
    sql.value='SELECT AMOUNT FROM APP.SALES';sql.fire('input');assert.equal(check.disabled,false);
    editor.controls(true);assert.equal(exportButton.disabled,true);assert.equal(check.disabled,true);
    editor.controls(false);const pending=check.fire('click');assert.equal(check.disabled,true);
    await check.fire('click');assert.equal(calls,1);
    editor.edit({id:'term-b',revision:2});
    resolve({ok:true,status:200,headers:{get:()=> 'application/json'},json:async()=>({status:'PARSED',references:[],rules:['OLD ANALYSIS']})});
    await pending;assert.equal(root.all().some(n=>n.textContent==='OLD ANALYSIS'),false);
    assert.equal(check.disabled,true);assert.equal(sql.value,'');
    assert.equal(root.all().filter(n=>n.tag==='input').length,2);
  }finally{globalThis.document=previousDocument;globalThis.fetch=previousFetch;}
});
