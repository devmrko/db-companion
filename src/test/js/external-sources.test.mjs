import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {entryKey,externalPage,requestGate,resultMessage,aclEntries,portRange,schemaAclEntries,externalState,externalReturn,aclLabel,catalogEntries,catalogEnabled,catalogApiMessage} from '../../main/resources/static/js/external-sources.mjs';

test('ten-row pages with bounds and case-insensitive contains across metadata',()=>{
  const items=Array.from({length:23},(_,i)=>({owner:i===22?'PUBLIC':'APP',name:`LINK_${i}`,description:'Remote_USER'}));
  assert.equal(externalPage(items,'',1).items.length,10);assert.equal(externalPage(items,'',2).from,11);
  assert.equal(externalPage(items,'',99).items.length,3);assert.equal(externalPage(items,'PUBLIC',1).total,1);
  assert.equal(externalPage(items,'remote_user',1).total,23);assert.equal(externalPage(items,'LINK_1',1).total,11);
  assert.equal(externalPage(items,'missing',1).pages,0);assert.equal(externalPage(items,'',-5).page,1);
});
test('same link names under private and PUBLIC owners do not share selection keys',()=>{
  assert.notEqual(entryKey({owner:'APP',name:'L'}),entryKey({owner:'PUBLIC',name:'L'}));
  assert.notEqual(entryKey({owner:'A.B',name:'C'}),entryKey({owner:'A',name:'B.C'}));
});
test('new requests cancel and invalidate previous selection or tab responses',()=>{
  const gate=requestGate(),first=gate.start(),second=gate.start();
  assert.equal(first.signal.aborted,true);assert.equal(first.current(),false);assert.equal(second.current(),true);
  gate.cancel();assert.equal(second.signal.aborted,true);assert.equal(second.current(),false);
  assert.equal(gate.start().current(),true);
});
test('late completion cannot repaint over newer results',async()=>{
  const gate=requestGate();let complete;const first=gate.start();let shown='';
  const pending=new Promise(resolve=>{complete=resolve;}).then(value=>{if(first.current())shown=value;});
  const next=gate.start();if(next.current())shown='new tab';complete('old tab');await pending;assert.equal(shown,'new tab');
});
test('errors retain catalog and code without inventing empty success',()=>{
  assert.match(resultMessage({status:'ACCESS_REQUIRED',source:'SYS.ALL_DB_LINKS',error:'ORA-00942'}),/SYS.ALL_DB_LINKS.*ORA-00942/);
  assert.match(resultMessage({status:'LIMIT'}),/5,000/);
});
test('DOM uses text nodes, local read endpoints and explicit refresh',()=>{
  const source=readFileSync('src/main/resources/static/js/external-sources.mjs','utf8');
  assert.doesNotMatch(source,/innerHTML|insertAdjacentHTML|method:\s*['"]POST|sessionStorage|localStorage/);
  assert.match(source,/textContent/);assert.match(source,/cache:'no-store'/);assert.match(source,/if\(!busy\)list\(true\)/);
  assert.match(source,/hideDetail\(\);items=\[\]/);assert.match(source,/if\(!request.current\(\)\)return/);
});
test('ACL rows with the same host preserve individual ACE identity and search terms',()=>{
  const entries=aclEntries([{host:'host',principal:'APP',privilege:'http',grantType:'GRANT',aceOrder:'1',lowerPort:'443',upperPort:'443'},
    {host:'host',principal:'APP',privilege:'http',grantType:'DENY',aceOrder:'2'}]);
  assert.notEqual(entryKey(entries[0]),entryKey(entries[1]));
  assert.equal(externalPage(entries,'deny',1).total,1);assert.equal(externalPage(entries,'443',1).total,1);
  assert.equal(externalPage(entries,'app',1).total,2);assert.equal(externalPage(entries,'http',1).total,2);
});
test('ACL ports and missing values are not an invented allow decision',()=>{
  assert.equal(portRange(null,null),'—');assert.equal(portRange('443','443'),'443');assert.equal(portRange('100','200'),'100–200');
  const source=readFileSync('src/main/resources/static/js/external-sources.mjs','utf8');
  assert.match(source,/aclView==='schema'\?'\/acl\/schema':'\/acl'/);assert.match(source,/data.scope==='USER'\?'userScope':'dbScope'/);
  assert.match(source,/item.acl.grantType\|\|item.acl.status/);
});
test('schema ACL preserves separate role paths and filters by schema or nested role',()=>{
  const ace={host:'api.example.test',principal:'R2',privilege:'HTTP',grantType:'GRANT'};
  const rows=schemaAclEntries([{schema:'APP',ace,route:'ROLE',path:['APP','R1','R2']},{schema:'APP',ace,route:'PUBLIC_ROLE',path:['PUBLIC','R2']}]);
  assert.notEqual(entryKey(rows[0]),entryKey(rows[1]));
  assert.equal(externalPage(rows,'r1',1).total,1);assert.equal(externalPage(rows,'public',1).total,1);
  assert.equal(externalPage(rows,'app',1).total,2);assert.equal(externalPage(rows,'api.example',1).total,2);
});
test('tab and ACL mode survive local URL navigation without accepting arbitrary routes',()=>{
  assert.deepEqual(externalState(''),{kind:'links',aclView:'schema'});
  assert.deepEqual(externalState('?tab=acl&aclView=all'),{kind:'acl',aclView:'all'});
  assert.deepEqual(externalState('?tab=evil&aclView=evil'),{kind:'links',aclView:'schema'});
  assert.equal(externalReturn('acl','schema'),'/db/external-sources?tab=acl&aclView=schema');
  assert.equal(externalReturn('//evil','evil'),'/db/external-sources?tab=links&aclView=schema');
  const source=readFileSync('src/main/resources/static/js/external-sources.mjs','utf8');
  assert.match(source,/history.replaceState/);assert.match(source,/input\[name="returnTo"\]/);
  assert.match(source,/data.roleStatus/);assert.match(source,/showReview\(data.review\)/);
});
test('ACL headers retain API terms with real four-language qualifiers and unchanged other labels',()=>{
  const catalog={...JSON.parse(readFileSync('tools/i18n/feature-network-acl.json','utf8')),...JSON.parse(readFileSync('tools/i18n/feature-schema-acl.json','utf8'))};
  const expected=['Principal (스키마 / 역할)','Principal (Schema / Role)','Principal (方案 / 角色)','Principal (スキーマ / ロール)'];
  for(let language=0;language<4;language++){
    const translate=key=>catalog['external.'+key][language];
    assert.equal(aclLabel('host',translate),'Host');assert.equal(aclLabel('privilege',translate),'Privilege');
    assert.equal(aclLabel('principal',translate),expected[language]);
    for(const key of ['ports','grantType','route','schema','aceOrder'])assert.equal(aclLabel(key,translate),translate(key));
  }
});
test('ACL naming is shared by list, review and detail; input label follows the visible placeholder',()=>{
  const source=readFileSync('src/main/resources/static/js/external-sources.mjs','utf8');
  assert.equal((source.match(/\.map\(key=>aclLabel\(key\)\)/g)||[]).length,2);
  assert.match(source,/\[aclLabel\(key\),value\]/);
  assert.match(source,/label\[for="external-filter"\]/);assert.match(source,/Host · Principal · Privilege/);
});
test('catalog tab navigation is allowlisted and scope belongs to the login',()=>{
  assert.deepEqual(externalState('?tab=catalogs&aclView=all'),{kind:'catalogs',aclView:'all'});
  assert.equal(externalReturn('catalogs','schema'),'/db/external-sources?tab=catalogs&aclView=schema');
  const rows=catalogEntries([{name:'CAT_A',type:'ICEBERG',enabled:'YES'}],'LOGIN');
  assert.equal(rows[0].owner,'LOGIN');assert.equal(rows[0].catalog.enabled,'YES');
  assert.equal(externalPage(rows,'iceberg',1).total,1);assert.equal(externalPage(rows,'yes',1).total,1);
});
test('catalog registration flags and API visibility never claim remote connectivity',()=>{
  assert.equal(catalogEnabled('YES'),'활성');assert.equal(catalogEnabled('false'),'비활성');
  assert.equal(catalogEnabled('UNKNOWN'),'UNKNOWN');assert.equal(catalogEnabled(null),'—');
  assert.match(catalogApiMessage({status:'VISIBLE',methods:['GET_TABLES']}),/API 메타데이터.*GET_TABLES/);
  assert.match(catalogApiMessage({status:'NOT_VISIBLE',methods:[]}),/확인 필요/);
  assert.match(catalogApiMessage({status:'ACCESS_REQUIRED',source:'SYS.ALL_PROCEDURES',error:'ORA-00942'}),/ORA-00942/);
});
test('catalog detail uses the dedicated read endpoint and capabilities are cleared on navigation',()=>{
  const source=readFileSync('src/main/resources/static/js/external-sources.mjs','utf8');
  assert.match(source,/entry.catalog\?'\/catalogs\/detail':'\/detail'/);
  assert.match(source,/get\('capabilities'\).textContent=''/);
  assert.match(source,/function showCatalog/);assert.doesNotMatch(source,/get_schemas|get_tables|@catalog|innerHTML/i);
});
test('all catalog fields and help have reviewed four-language strings',()=>{
  const entries=JSON.parse(readFileSync('tools/i18n/feature-mounted-catalogs.json','utf8'));
  for(const [key,values]of Object.entries(entries)){
    assert.equal(values.length,4,key);for(const text of values)assert.ok(typeof text==='string'&&text.length,key);
  }
  assert.match(entries['catalog.help'][0],/로그인 계정.*연결 성공.*ORA-00942/);
  assert.match(entries['catalog.help'][1],/not a tenancy-wide inventory/);
});
