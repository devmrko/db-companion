import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {Approval,catalogMessage,filterTargets,policyStatements} from '../../main/resources/static/js/vpd-management.mjs';
import {helpFor,featureForPath} from '../../main/resources/static/js/sql-help.mjs';
const preview=(gap=false)=>({token:'one-use',target:'APP.T.P',gap,expiresAt:new Date(Date.now()+300000).toISOString()});
const targets=[{name:'OWN_T',type:'TABLE',owned:true,privileges:[],grantSources:[]},
  {name:'Role_View',type:'VIEW',owned:false,privileges:['SELECT','UPDATE'],grantSources:['ROLE']},
  {name:'PUBLIC_V',type:'VIEW',owned:false,privileges:['READ'],grantSources:['PUBLIC']},
  {name:'DIRECT_T',type:'TABLE',owned:false,privileges:['DELETE'],grantSources:['DIRECT']},
  {name:'VISIBLE_T',type:'TABLE',owned:false,privileges:[],grantSources:[]}];
test('policy statement chips include only enabled statement types',()=>{
  assert.deepEqual(policyStatements({SEL:'YES',INS:'NO',UPD:'YES',DEL:'NO',IDX:'YES'}),['SELECT','UPDATE','INDEX']);
  assert.deepEqual(policyStatements({}),[]);
});
test('VPD empty state renders without an empty table header',()=>{
  const script=readFileSync('src/main/resources/static/js/vpd-management.mjs','utf8');
  assert.match(script,/if\(catalog.status!=='AVAILABLE'\|\|!catalog.policies.length\)return/);
  const html=readFileSync('src/main/resources/templates/vpd-management.html','utf8');
  assert.match(html,/management-workbench\.css/);
  assert.match(html,/workbench.vpd.policies/);
  assert.match(html,/workbench.vpd.target/);
});
test('VPD object filters combine type, ownership, grants and literal case-insensitive names',()=>{
  assert.equal(filterTargets(targets).length,5);
  assert.deepEqual(filterTargets(targets,{kind:'VIEW',search:' role_ ',access:'GRANTED'}).map(x=>x.name),['Role_View']);
  assert.deepEqual(filterTargets(targets,{access:'OWNED'}).map(x=>x.name),['OWN_T']);
  assert.deepEqual(filterTargets(targets,{access:'GRANTED'}).map(x=>x.name),['Role_View','PUBLIC_V','DIRECT_T']);
  assert.deepEqual(filterTargets(targets,{search:'['}),[]);assert.equal(targets.length,5);
});
test('SELECT filter includes READ and owner but does not invent access for dictionary-visible objects',()=>{
  assert.deepEqual(filterTargets(targets,{access:'SELECT'}).map(x=>x.name),['OWN_T','Role_View','PUBLIC_V']);
  assert.deepEqual(filterTargets(targets,{access:'UPDATE'}).map(x=>x.name),['OWN_T','Role_View']);
  assert.deepEqual(filterTargets(targets,{access:'DELETE'}).map(x=>x.name),['OWN_T','DIRECT_T']);
  assert.deepEqual(filterTargets(targets,{access:'INSERT'}).map(x=>x.name),['OWN_T']);
  assert.deepEqual(filterTargets(targets,{access:'UNKNOWN'}),[]);
});
test('exact target, explicit security consent and gap consent control execution',()=>{
  const a=new Approval();a.accept(a.invalidate(),preview(true));
  assert.equal(a.allowed('APP.T.P',false,true),false);assert.equal(a.allowed('APP.T.P',true,false),false);
  assert.equal(a.allowed('APP.T.OTHER',true,true),false);assert.equal(a.allowed('APP.T.P',true,true),true);
  assert.deepEqual(a.consume('APP.T.P',true,true),{token:'one-use',target:'APP.T.P',confirmed:true,gapConfirmed:true});
  assert.equal(a.allowed('APP.T.P',true,true),false);assert.throws(()=>a.consume('APP.T.P',true,true));
});
test('form edits and refresh invalidate even an in-flight preview response',()=>{
  const a=new Approval(),version=a.invalidate();a.invalidate();assert.equal(a.accept(version,preview()),false);assert.equal(a.preview,null);
  a.accept(a.version,preview());a.invalidate();assert.equal(a.allowed('APP.T.P',true,false),false);
});
test('expired previews are refused without relying on disabled-button state',()=>{
  const a=new Approval();a.accept(a.version,{...preview(),expiresAt:new Date(Date.now()-1000).toISOString()});assert.equal(a.allowed('APP.T.P',true,false),false);assert.throws(()=>a.consume('APP.T.P',true,false));
});
test('empty policy list differs from missing catalog and execute access',()=>{
  assert.equal(catalogMessage({status:'AVAILABLE',policies:[]}), 'vpd.empty');
  assert.equal(catalogMessage({status:'ACCESS_REQUIRED',policies:[]}), 'vpd.accessRequired');
  assert.equal(catalogMessage({status:'AVAILABLE',policies:[],reason:'vpd.executeRequired'}),'vpd.executeRequired');
});
test('all VPD operations have executable-source SQL help and explicit security risks',()=>{
  assert.equal(featureForPath('/db/vpd'),'vpd');
  assert.match(helpFor('vpd','en','objects').sql,/ALL_OBJECTS[\s\S]*'TABLE', 'VIEW'[\s\S]*ALL_TAB_PRIVS[\s\S]*SESSION_ROLES/);
  assert.match(helpFor('vpd','en','list').sql,/ALL_POLICIES[\s\S]*ALL_SEC_RELEVANT_COLS[\s\S]*ALL_POLICY_ATTRIBUTES/);
  for(const action of ['add','replace','delete','enable'])assert.equal(helpFor('vpd','en',action).risk,'ddl');
  assert.match(helpFor('vpd','en','replace').sql,/NOT atomic/);assert.match(helpFor('vpd','en','delete').sql,/DROP_POLICY/);
  assert.match(helpFor('vpd','en','enable').sql,/ENABLE_POLICY/);
});
test('VPD field guidance is folded but real execution risks and consent remain visible in preview',()=>{
  const html=readFileSync('src/main/resources/templates/vpd-management.html','utf8');
  for(const key of ['access','function','options'])assert.match(html,new RegExp('<details class="app-context-help"><summary th:text="#\\{vpd\\.'+key+'HelpTitle\\}"'));
  assert.match(html,/data-vpd-dialog[\s\S]*vpd\.impact/);
  assert.match(html,/data-vpd-gap-warning/);assert.match(html,/data-vpd-consent/);assert.match(html,/data-vpd-gap-consent/);
});
