import test from 'node:test';
import assert from 'node:assert/strict';
import {confirmAction} from '../../main/resources/static/js/confirm-action.mjs';
test('confirmation defaults to cancel and only the explicit accept button resolves true',async()=>{
 const saved=globalThis.document;
 try{for(const choice of ['accept','cancel','dismiss']){
   const nodes=[];let restored=0;
   globalThis.document={activeElement:{focus(){restored++;}},body:{append(){}},createElement(tag){const listeners=new Map();const n={tag,style:{},children:[],setAttribute(){},append(...children){this.children.push(...children);},addEventListener(type,handler){listeners.set(type,handler);},focus(){},showModal(){},remove(){},close(){listeners.get('close')?.();},click(){listeners.get('click')?.();}};nodes.push(n);return n;}};
   const promise=confirmAction('<img onerror=alert(1)>');const dialog=nodes.find(n=>n.tag==='dialog'),buttons=nodes.filter(n=>n.tag==='button');
   assert.equal(nodes.find(n=>n.tag==='p').textContent,'<img onerror=alert(1)>');
   if(choice==='accept')buttons[1].click();else if(choice==='cancel')buttons[0].click();else dialog.close();
   assert.equal(await promise,choice==='accept');assert.equal(restored,1);
 }}finally{if(saved===undefined)delete globalThis.document;else globalThis.document=saved;}
});
