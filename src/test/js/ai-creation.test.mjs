import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {defaults,parseAttributes,fieldValue,canCreate,copySequence,resultUrl} from '../../main/resources/static/js/ai-creation.mjs';
import {teamChanges} from '../../main/resources/static/js/team-history.mjs';
test('creation history is compatible with existing Team comparison',()=>{const changes=teamChanges({exists:false,request:{}},{exists:true,object:{NAME:'T',STATUS:'DISABLED'},attributes:{agents:'[{"name":"A","task":"T"}]'}});assert.ok(changes.some(c=>c.name==='agents'));assert.ok(changes.some(c=>c.after==='true'));});
test('profile creation requires cost consent even with no feedback',()=>{assert.equal(canCreate({input:{kind:'PROFILE'},feedback:[]},true,false,false,false),false);assert.equal(canCreate({input:{kind:'PROFILE'},feedback:[]},true,true,false,false),true);});
test('defaults are independent and never use deployment-specific names',()=>{const first=defaults('PROFILE');first.provider='x';assert.equal(defaults('PROFILE').provider,'oci');assert.equal(defaults('PROFILE').credential_name,'');assert.deepEqual(defaults('PROFILE').object_list,[]);for(const kind of ['TEAM','AGENT','TASK','TOOL'])assert.equal(typeof defaults(kind),'object');});
test('JSON must be an object',()=>{assert.deepEqual(parseAttributes('{"annotations":false}'),{annotations:false});for(const text of ['null','[]','bad','1'])assert.throws(()=>parseAttributes(text));});
test('individual field edits cannot round large numeric inputs',()=>{assert.throws(()=>fieldValue(1,'9223372036854775807'));assert.throws(()=>fieldValue({},'{"v":0.1234567890123456789}'));assert.equal(fieldValue(1,'42'),42);assert.equal(fieldValue('','9223372036854775807'),'9223372036854775807');assert.equal(fieldValue(false,'',true),true);});
test('typed fields refuse lossy numeric conversion while string instructions remain literal',()=>{for(const text of ['{"seed":9223372036854775807}','{"temperature":0.123456789012345678901}','{"v":1e400}'])assert.throws(()=>parseAttributes(text));assert.deepEqual(parseAttributes('{"instructions":"123456789012345678901"}'),{instructions:'123456789012345678901'});});
test('copy requires affirmative cost and creation checks',()=>{const preview={feedback:[{}]};assert.equal(canCreate(preview,true,false,false,false),false);assert.equal(canCreate(preview,false,true,false,false),false);assert.equal(canCreate(preview,true,true,false,false),true);assert.equal(canCreate(preview,true,true,true,false),false);assert.equal(canCreate(preview,true,true,false,true),false);assert.equal(canCreate(null,true,true,false,false),false);});
test('no feedback still needs explicit creation confirmation',()=>{assert.equal(canCreate({feedback:[]},true,false,false,false),true);assert.equal(canCreate({feedback:[]},false,false,false,false),false);});
test('one request per copy with monotonic index and no duplicate retry',async()=>{const calls=[];const result=await copySequence({token:'T'},{verified:true,done:false,copied:0},async request=>{calls.push(request);return{verified:true,copied:request.index+1,done:request.index===2};},()=>{},()=>false);assert.equal(result.copied,3);assert.deepEqual(calls.map(c=>c.index),[0,1,2]);assert.ok(calls.every(c=>c.token==='T'&&c.consent));});
test('all 119 entries are copied sequentially without stopping at 100',async()=>{
  const calls=[],progress=[];let active=0;
  const result=await copySequence({token:'T'},{verified:true,done:false,copied:0,total:119},async request=>{
    assert.equal(++active,1);calls.push(request.index);await Promise.resolve();active--;
    return{verified:true,copied:request.index+1,total:119,done:request.index===118};
  },r=>progress.push(r.copied),()=>false);
  assert.equal(result.done,true);assert.equal(result.copied,119);
  assert.deepEqual(calls,Array.from({length:119},(_,i)=>i));assert.deepEqual(progress,Array.from({length:119},(_,i)=>i+1));
});
test('failure, stop and lost response still halt a copy beyond 100 entries',async()=>{
  for(const mode of ['failure','stop','lost']){
    const calls=[];let stopped=false;
    const run=()=>copySequence({token:'T'},{verified:true,done:false,copied:100,total:119},async request=>{
      calls.push(request.index);
      if(mode==='lost')throw new Error('connection lost');
      return{verified:mode!=='failure',copied:mode==='failure'?100:101,total:119,done:false};
    },()=>{if(mode==='stop')stopped=true;},()=>stopped);
    if(mode==='lost')await assert.rejects(run(),/connection lost/);else assert.equal((await run()).done,false);
    assert.deepEqual(calls,[100]);
  }
});
test('partial result and stop prevent further requests',async()=>{let calls=0;const result=await copySequence({token:'T'},{verified:true,done:false,copied:0},async()=>{calls++;return{verified:false,copied:0,done:false};},()=>{},()=>false);assert.equal(result.verified,false);assert.equal(calls,1);await copySequence({token:'T'},{verified:true,done:false,copied:0},async()=>{calls++;},()=>{},()=>true);assert.equal(calls,1);});
test('lost response is not retried',async()=>{let calls=0;await assert.rejects(copySequence({token:'T'},{verified:true,done:false,copied:0},async()=>{calls++;throw new Error('connection lost');},()=>{},()=>false),/connection lost/);assert.equal(calls,1);});
test('result URLs only point to local read-only pages',()=>{assert.equal(resultUrl('AGENT','APP','A B'),'/ai-agents/object?schema=APP&kind=AGENT&name=A+B');assert.match(resultUrl('PROFILE','APP','P'),/^\/ai-profiles\/detail\?/);});
test('four languages cover help, errors and all UI labels',()=>{const values=JSON.parse(readFileSync('tools/i18n/feature-ai-creation.json','utf8'));for(const [key,labels]of Object.entries(values)){assert.equal(labels.length,4,key);for(const [i,label]of labels.entries()){assert.ok(label.trim());assert.deepEqual(label.match(/\{\d+\}/g),labels[0].match(/\{\d+\}/g),key);if(i)assert.doesNotMatch(label,/[가-힣]/u,key);}}const code=readFileSync('src/main/resources/static/js/ai-creation.mjs','utf8');for(const match of code.matchAll(/tr\('([^']+)'/g))assert.ok(values['creation.'+match[1]],match[1]);assert.doesNotMatch(code,/innerHTML|eval\(|localStorage|sessionStorage/);});
test('copy help and errors match the byte budget in every resource bundle',()=>{
  const values=JSON.parse(readFileSync('tools/i18n/feature-ai-creation.json','utf8'));
  for(const [suffix,index]of [['',0],['_ko',0],['_en',1],['_zh_CN',2],['_ja',3]]){
    const messages=readFileSync(`src/main/resources/i18n/messages${suffix}.properties`,'utf8');
    for(const key of ['creation.copyHelp','creation.copyLimit']){
      assert.ok(messages.includes(`${key}=${values[key][index]}`));
      assert.doesNotMatch(values[key][index],/100/);assert.match(values[key][index],/2\s?MB/);assert.match(values[key][index],/256\s?KiB/);
    }
  }
});
