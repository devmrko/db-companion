import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {mountDemoData, demoDataInput} from '../../main/resources/static/js/demo-data.mjs';

test('scenario inputs preserve dates and coerce only row count', () => {
  assert.deepEqual(demoDataInput([['rows','1200'],['date','2025-01-01'],['prefix','DEMO_'],['scenario','Fictional'],['questions','Q'],['rules','R']]), {criteria:{rows:1200,date:'2025-01-01',scenario:'Fictional',questions:'Q',rules:'R'},prefix:'DEMO_'});
});

test('all scenario translations and template keys exist in every bundle', () => {
  const catalog = JSON.parse(fs.readFileSync('tools/i18n/feature-demo-data.json','utf8'));
  const html = fs.readFileSync('src/main/resources/templates/demo-data.html','utf8');
  for (const match of html.matchAll(/#\{(demoData\.[^}]+)\}/g)) assert.ok(catalog[match[1]],match[1]);
  for (const [suffix,index] of [['',0],['_ko',0],['_en',1],['_zh_CN',2],['_ja',3]]) {
    const bundle = fs.readFileSync('src/main/resources/i18n/messages'+suffix+'.properties','utf8');
    for (const [key,values] of Object.entries(catalog).filter(([key])=>key.startsWith('demoData.export.'))) assert.ok(bundle.includes(key+'='+values[index]+'\n'),key+suffix);
  }
});

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(),'scenario-wrapper-'));
  t.after(() => fs.rmSync(root,{recursive:true,force:true}));
  const repo=path.join(root,'repo'), scripts=path.join(repo,'scripts/scenario'), home=path.join(root,'home'), bin=path.join(root,'bin');
  for (const folder of [scripts,path.join(home,'.dbcompanion'),bin,path.join(repo,'scenario-results/DEMO_')]) fs.mkdirSync(folder,{recursive:true});
  for (const name of ['sqlcl.sh','sqlcl.py']) fs.copyFileSync('scripts/scenario/'+name,path.join(scripts,name));
  const secret=path.join(home,'.dbcompanion/scenario.env');
  const password='synthetic-only-$(never-execute)&pass';
  fs.writeFileSync(secret,`USERNAME=SCENARIO_USER\nPASSWORD=${password}\nWALLET_PATH=${root}/wallet\nTNS_ALIAS=fixture_low\n`,{mode:0o600});
  const config={version:1,username:'SCENARIO_USER',tnsAlias:'fixture_low',walletPath:root+'/wallet',prefix:'DEMO_',outputDirectory:'scenario-results/DEMO_'};
  const configFile=path.join(repo,'scenario-config.json');
  fs.writeFileSync(configFile,JSON.stringify(config));
  const sql=path.join(repo,'scenario-results/DEMO_/run.sql');
  fs.writeFileSync(sql,"select count(*) from DEMO_ORDERS;\n");
  const marker=path.join(root,'called');
  fs.writeFileSync(path.join(bin,'sql'),`#!/usr/bin/env python3
import os, sys
from pathlib import Path
data=sys.stdin.read()
assert sys.argv[1:]==['-S','-L','-R','3','/nolog']
assert 'set echo off' in data and 'set define off' in data
assert 'CURRENT_SCHEMA' in data and 'DEMO_ORDERS' in data
assert not os.environ.get('SQLPATH')
Path(os.environ['SCENARIO_TEST_MARKER']).write_text('called')
print(data.split('connect ',1)[1].split('\\n',1)[0])
print(os.environ.get('SCENARIO_TEST_OUTPUT','Rows checked: 3'))
sys.exit(int(os.environ.get('SCENARIO_TEST_EXIT','0')))
`,{mode:0o700});
  const run=(extra={})=>spawnSync('sh',[path.join(scripts,'sqlcl.sh'),configFile,sql],{cwd:repo,encoding:'utf8',env:{...process.env,HOME:home,PATH:bin+path.delimiter+process.env.PATH,SQLPATH:'/untrusted',SCENARIO_TEST_MARKER:marker,...extra}});
  return {root,repo,home,secret,password,config,configFile,sql,marker,run};
}

test('wrapper uses stdin, checks schema and redacts captured output and logs', {skip:process.platform==='win32'}, t => {
  const f=fixture(t), result=f.run();
  assert.equal(result.status,0,result.stderr); assert.ok(fs.existsSync(f.marker));
  assert.match(result.stdout,/Rows checked: 3/); assert.ok(!result.stdout.includes(f.password));
  const log=path.join(f.repo,'scenario-results/DEMO_/sqlcl-output.log');
  assert.ok(!fs.readFileSync(log,'utf8').includes(f.password));
  assert.equal(fs.statSync(log).mode&0o777,0o600);
});

test('wrapper refuses non-private permissions, symlinks and mismatched targets before launch', {skip:process.platform==='win32'}, t => {
  const f=fixture(t);
  for (const mode of [0o644,0o640,0o660,0o700]) {
    fs.chmodSync(f.secret,mode); const result=f.run(); assert.equal(result.status,2); assert.match(result.stderr,/mode 600 or 400/); assert.ok(!fs.existsSync(f.marker));
  }
  fs.chmodSync(f.secret,0o600);
  fs.writeFileSync(f.configFile,JSON.stringify({...f.config,tnsAlias:'other_low'}));
  assert.match(f.run().stderr,/does not match/); assert.ok(!fs.existsSync(f.marker));
  fs.writeFileSync(f.configFile,JSON.stringify(f.config));
  fs.renameSync(f.secret,f.secret+'.actual'); fs.symlinkSync(f.secret+'.actual',f.secret);
  assert.match(f.run().stderr,/symlink/); assert.ok(!fs.existsSync(f.marker));
});

test('wrapper refuses shell syntax as structure and propagates SQL errors', {skip:process.platform==='win32'}, t => {
  const f=fixture(t);
  fs.appendFileSync(f.secret,'UNKNOWN=$(touch should-not-exist)\n');
  assert.match(f.run().stderr,/Invalid private file format/); assert.ok(!fs.existsSync(f.marker));
  fs.writeFileSync(f.secret,`USERNAME=SCENARIO_USER\nPASSWORD=${f.password}\nWALLET_PATH=${f.root}/wallet\nTNS_ALIAS=fixture_low\n`);
  assert.equal(f.run({SCENARIO_TEST_EXIT:'7'}).status,7);
  assert.equal(f.run({SCENARIO_TEST_OUTPUT:'ORA-00942: synthetic failure'}).status,1);
  fs.writeFileSync(f.sql,'connect different_user\n');
  assert.match(f.run().stderr,/not allowed/);
});

test('macOS extended ACLs cannot override an apparently private mode', {skip:process.platform!=='darwin'}, t => {
  const f=fixture(t);
  const chmod=spawnSync('chmod',['+a','everyone allow read',f.secret],{encoding:'utf8'});
  assert.equal(chmod.status,0,chmod.stderr);
  assert.equal(fs.statSync(f.secret).mode&0o777,0o600);
  const result=f.run();
  assert.equal(result.status,2); assert.match(result.stderr,/extended ACL/); assert.ok(!fs.existsSync(f.marker));
});

test('screen generates, copies, downloads and invalidates late output after edits', async t => {
  class Element {
    constructor(value='') { this.value=value; this.events={}; this.children=[]; this.dataset={}; this.disabled=false; this.hidden=false; }
    addEventListener(name,fn) { this.events[name]=fn; }
    fire(name,event={preventDefault(){}}) { return this.events[name]?.(event); }
    replaceChildren() { this.children=[]; this.value=''; }
    focus() {}
    add(option) { this.children.push(option); this.value ||= option.value; }
  }
  const fields=Object.fromEntries(Object.entries({walletId:'wallet',tnsAlias:'',preset:'commerce',scenario:'Fictional',questions:'Q',rules:'R',rows:'20',date:'2025-01-01',endDate:'2025-12-31',prefix:'DEMO_'}).map(([key,value])=>[key,new Element(value)]));
  const form=new Element(); form.elements={namedItem:key=>fields[key]}; form.reportValidity=()=>true;
  const nodes=Object.fromEntries(['message','build','review','prompt','config','csrf','input','back'].map(key=>[key,new Element()]));
  nodes.csrf.dataset.csrfHeader='X-CSRF-TOKEN'; nodes.csrf.value='fixture'; nodes.review.hidden=true;
  const copy=new Element(),download=new Element(); copy.dataset.copy='config'; download.dataset.download='prompt';
  const root={querySelector:selector=>selector==='[data-demo-form]'?form:nodes[selector.slice(11,-1)],querySelectorAll:selector=>selector==='[data-copy]'?[copy]:[download]};
  const saved=new Map(['fetch','document','FormData','Option','navigator'].map(key=>[key,Object.getOwnPropertyDescriptor(globalThis,key)]));
  t.after(()=>{for(const [key,descriptor] of saved) descriptor?Object.defineProperty(globalThis,key,descriptor):delete globalThis[key];});
  let copied='',downloaded='',finish;
  const calls=[];
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{clipboard:{writeText:async value=>{copied=value;}}}});
  globalThis.document={body:{append(){}},createElement:()=>({click(){downloaded=this.download;},remove(){}})};
  globalThis.FormData=class { constructor(){return Object.entries(fields).map(([key,node])=>[key,node.value]);} };
  globalThis.Option=class { constructor(text,value){this.text=text;this.value=value;} };
  globalThis.fetch=async(url,options)=>{
    calls.push({url,options});
    const data=url.includes('/aliases')?[{alias:'fixture_low',description:'fixture'}]:await new Promise(resolve=>{finish=resolve;});
    return {ok:true,status:200,headers:{get:()=> 'application/json'},json:async()=>data};
  };
  await mountDemoData(root);
  assert.equal(nodes.build.disabled,false); assert.equal(fields.tnsAlias.value,'fixture_low');
  const pending=form.fire('submit');
  assert.equal(calls.at(-1).options.headers['X-CSRF-TOKEN'],'fixture');
  assert.equal(JSON.parse(calls.at(-1).options.body).criteria.rows,20);
  finish({prompt:'Prompt text',config:'{"version":1}'}); await pending;
  assert.equal(nodes.review.hidden,false); assert.equal(nodes.prompt.textContent,'Prompt text');
  await copy.fire('click'); assert.equal(copied,'{"version":1}');
  await download.fire('click'); assert.equal(downloaded,'scenario-prompt.md');
  const late=form.fire('submit'); fields.scenario.value='changed'; form.fire('input');
  finish({prompt:'stale',config:'stale'}); await late;
  assert.equal(nodes.review.hidden,true); assert.equal(nodes.input.hidden,false); assert.notEqual(nodes.prompt.textContent,'stale');
  fields.walletId.value='other'; await fields.walletId.fire('change');
  assert.match(calls.at(-1).url,/walletId=other/);
});
