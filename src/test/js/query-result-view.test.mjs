import test from 'node:test';
import assert from 'node:assert/strict';
import {renderQueryData} from '../../main/resources/static/js/query-result-view.mjs';
class Node {
  constructor(tag){this.tag=tag;this.children=[];this.events={};}
  append(...nodes){this.children.push(...nodes);}
  replaceChildren(...nodes){this.children=nodes;}
  addEventListener(name,fn){this.events[name]=fn;}
  set innerHTML(value){throw new Error('Unsafe HTML rendering');}
  all(tag){return [...(this.tag===tag?[this]:[]),...this.children.flatMap(n=>n.all?n.all(tag):[])];}
}
const doc={createElement:tag=>new Node(tag),createTextNode:text=>({textContent:text})};
test('shared SQL results paginate actual rows and retain null clipping and literal HTML values',()=>{
  const host=new Node('div');const data={actor:'APP',executedAt:'2026-11-27T00:00:00Z',columns:['VALUE'],truncated:true,rows:Array.from({length:12},(_,i)=>[{value:i===0?null:i===1?'<img src=x onerror=alert(1)>':String(i),truncated:i===1}])};
  renderQueryData(host,data,doc);
  let body=host.all('tbody')[0];assert.equal(body.children.length,10);assert.equal(host.all('td')[0].textContent,'NULL');
  assert.equal(host.all('td')[1].textContent,'<img src=x onerror=alert(1)>');assert.equal(host.all('td')[1].children[0].textContent,'…');
  const [previous,next]=host.all('button');assert.equal(previous.disabled,true);assert.equal(next.disabled,false);
  next.events.click();assert.equal(body.children.length,2);assert.equal(host.all('td')[0].textContent,'10');assert.equal(next.disabled,true);
  next.events.click();assert.equal(body.children.length,2);previous.events.click();assert.equal(body.children.length,10);
  renderQueryData(host,{...data,rows:[],truncated:false},doc);assert.equal(host.all('tbody')[0].children.length,0);assert(host.all('button').every(b=>b.disabled));
});
