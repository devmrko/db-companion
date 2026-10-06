import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const source=readFileSync(new URL('./apex-result-recovery.js',import.meta.url),'utf8');
function harness(team='DBC_SEMANTIC_QUERY_TEAM') {
 class Element {
   constructor(tag){this.tag=tag;this.children=[];this.style={};this.textContent='';}
   append(node){this.children.push(node);node.parent=this;}
   setAttribute(){}
   replaceChildren(){this.children=[];}
   remove(){this.parent.children=this.parent.children.filter(n=>n!==this);}
   set innerHTML(v){throw Error('Unsafe HTML rendering');}
 }
 const root=new Element('main'),calls=[],items={P1_AGENT_PROFILE:team,P1_CONV_ID:'conversation-1'};
 const timers=[];
 const apex={item:n=>({getValue:()=>items[n]}),server:{process(name,data,options){calls.push({name,data,options});return {abort(){}};}}};
 const context={window:{},apex,crypto:{randomUUID:()=> '12345678-1234-1234-1234-123456789012'},document:{createElement:t=>new Element(t),querySelector:()=>root,body:root},setInterval(){},setTimeout:f=>{timers.push(f);return timers.length;},clearTimeout(){}};
 vm.runInNewContext(source,context);
 const all=(n=root)=>[n,...n.children.flatMap(c=>all(c))];
 return {root,calls,items,apex,timers,all};
}
test('one Agent submission; recovery only reads stored data after timeout',()=>{
 const h=harness();let errors=0;
 h.apex.server.process('EXECUTE_PROMPT',{pageItems:'test'},{error(){errors++;}});
 assert.match(h.calls[0].data.x10,/^[a-f0-9]{32}$/);
 h.calls[0].options.error({status:504},'error');
 assert.equal(errors,1);assert.equal(h.calls[1].name,'DBC_GET_SAVED_QUERY_RESULT');
 assert.equal(h.calls.filter(c=>c.name==='EXECUTE_PROMPT').length,1);
});
test('1000 fetched rows paginate by 25; SQL, exact decimals, null and HTML remain literal',()=>{
 const h=harness();h.apex.server.process('EXECUTE_PROMPT',{},{});h.calls[0].options.success({status:'success'});
 const rows=Array.from({length:1000},()=>['12345678901234567890.123456789',null,'<img src=x onerror=alert(1)>']);
 h.calls[1].options.success({status:'SUCCESS',sql:'SELECT test',result:{columns:['a','b','c'],rows,more:true}});
 assert.equal(h.all().filter(n=>n.tag==='td').length,75);
 assert.ok(h.all().some(n=>n.textContent==='NULL'));
 assert.ok(h.all().some(n=>n.textContent===rows[0][0]));
 assert.ok(h.all().some(n=>n.textContent===rows[0][2]));
 h.all().find(n=>n.textContent==='다음').onclick();
 assert.ok(h.all().some(n=>n.textContent.includes('26–50')));
});
test('late responses cannot appear after switching conversation',()=>{
 const h=harness();h.apex.server.process('EXECUTE_PROMPT',{},{});h.calls[0].options.success({});
 h.items.P1_CONV_ID='other';h.calls[1].options.success({status:'SUCCESS',result:{rows:[['SECRET']],columns:['a']}});
 assert.ok(!h.all().some(n=>n.textContent==='SECRET'));
});
test('unrelated teams and processes are unchanged',()=>{
 const h=harness('OTHER_TEAM');
 h.apex.server.process('EXECUTE_PROMPT',{x01:'original'},{});
 assert.equal(h.calls[0].data.x01,'original');assert.equal(h.calls[0].data.x10,undefined);assert.equal(h.root.children.length,0);
});
test('unavailable result does not fabricate empty success or retry the Agent',()=>{
 const h=harness();h.apex.server.process('EXECUTE_PROMPT',{},{});h.calls[0].options.error({});
 h.calls[1].options.success({status:'UNAVAILABLE'});h.calls[1].options.complete();
 assert.equal(h.all().filter(n=>n.tag==='table').length,0);assert.equal(h.calls.length,2);
});
test('observed tool failure is terminal, not pending or zero success',()=>{
 const h=harness();h.apex.server.process('EXECUTE_PROMPT',{},{});h.calls[0].options.success({});
 h.calls[1].options.success({status:'ERROR',message:'ORA-00900',phase:'generate_query'});
 h.calls[1].options.complete();
 assert.ok(h.all().some(n=>n.textContent.includes('조회 실패 · ORA-00900')));
 assert.equal(h.all().filter(n=>n.tag==='table').length,0);
});
