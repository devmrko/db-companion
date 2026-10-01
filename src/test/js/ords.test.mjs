import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {canApply,editorDefaults,changeLines,requestState,ordsRequest,resourcePath,previewNotices} from '../../main/resources/static/js/ords.mjs';
import {helpFor} from '../../main/resources/static/js/sql-help.mjs';

test('new resources default to unpublished, REST disabled and metadata authentication',()=>{
  assert.equal(editorDefaults('MODULE').status,'NOT_PUBLISHED');
  assert.equal(editorDefaults('SCHEMA').enabled,'false');assert.equal(editorDefaults('SCHEMA').autoRestAuth,'true');
  assert.equal(editorDefaults('SCHEMA',{STATUS:'ENABLED',AUTO_REST_AUTH:'DISABLED'}).enabled,'true');
  assert.equal(editorDefaults('SCHEMA',{AUTO_REST_AUTH:'DISABLED'}).autoRestAuth,'false');
});
test('exact confirmation and single attempt gate prevent unconfirmed or duplicate writes',()=>{
  const preview={confirmation:'DELETE APP / HANDLER / demo / items/ / GET'};
  assert.equal(canApply(preview,preview.confirmation,false,false),true);
  for(const [p,c,b,a]of [[null,preview.confirmation,false,false],[preview,'DELETE',false,false],[preview,preview.confirmation,true,false],[preview,preview.confirmation,false,true]])assert.equal(canApply(p,c,b,a),false);
});
test('input edits or newer preview invalidate late response',()=>{
  const gate=requestState(),old=gate.start();assert.equal(old(),true);gate.invalidate();assert.equal(old(),false);
  const newer=gate.start(),latest=gate.start();assert.equal(newer(),false);assert.equal(latest(),true);
});
test('network errors are not retried and CSRF accompanies every JSON POST',async()=>{
  const calls=[],csrf={dataset:{csrfHeader:'X-CSRF-TOKEN'},value:'synthetic-token'};
  await assert.rejects(ordsRequest('/db/ords','/apply',{token:'once'},csrf,async(...args)=>{calls.push(args);throw new Error('network');}),/network/);
  assert.equal(calls.length,1);assert.equal(calls[0][1].headers['X-CSRF-TOKEN'],'synthetic-token');assert.equal(calls[0][1].method,'POST');
  await assert.rejects(ordsRequest('/db/ords','/list',undefined,csrf,async()=>({redirected:true,status:200})),/expired/);
});
test('metadata and source use text-only rendering; preview is invalidated on edits',()=>{
  const source=readFileSync('src/main/resources/static/js/ords.mjs','utf8');
  assert.doesNotMatch(source,/innerHTML|insertAdjacentHTML|eval\(|setInterval|localStorage|sessionStorage/);
  assert.match(source,/input\.addEventListener\('input',invalidate\)/);assert.match(source,/event\.preventDefault\(\)/);
  assert.match(source,/attempted=true;busy=true/);assert.match(source,/textContent=JSON\.stringify\(data\.original/);
  assert.match(changeLines({SOURCE:'old'},{source:'</script>'}),/- old\n\+ <\/script>/);
});
test('copy-only ORDS help covers each hierarchy with actual documented APIs',()=>{
  for(const op of ['list','schema','module','template','handler','delete'])assert.ok(helpFor('ords','ko',op));
  assert.match(helpFor('ords','en','module').sql,/NOT_PUBLISHED/);
  assert.match(helpFor('ords','en','handler').sql,/p_source => :handler_source/);
  assert.match(helpFor('ords','en','delete').sql,/p_uri_template/);
});
test('resource path is metadata-derived and never invents a host or drops path parameters',()=>{
  assert.equal(resourcePath({TYPE:'BASE_PATH',PATTERN:'app'},{URI_PREFIX:'/demo/'},{URI_TEMPLATE:'items/:id'}),'/app/demo/items/:id');
  assert.equal(resourcePath({TYPE:'BASE_PATH',PATTERN:'app'},{URI_PREFIX:'demo/'},{URI_TEMPLATE:'status/'}),'/app/demo/status/');
  assert.equal(resourcePath({TYPE:'BASE_URL',PATTERN:'https://example.invalid'}, {URI_PREFIX:'demo/'},{URI_TEMPLATE:'items/'}),null);
  assert.equal(resourcePath({},null,null),null);
});
test('ORDS renders compact summaries and folded metadata with a separate source viewer',()=>{
  const source=readFileSync('src/main/resources/static/js/ords.mjs','utf8'),template=readFileSync('src/main/resources/templates/ords.html','utf8'),css=readFileSync('src/main/resources/static/css/common.css','utf8');
  assert.match(source,/function metadata\(host,data\)/);assert.match(source,/mountSourceViewer\(source/);assert.match(source,/summary\(schema/);
  assert.doesNotMatch(source,/detail\.open\s*=\s*true|details[^\n]*setAttribute\('open'/);
  assert.match(template,/app-ords-catalog/);assert.match(template,/app-ords-inputs/);assert.match(css,/\.app-ords-panel > h2/);assert.match(css,/\.app-ords-catalog \{ grid-template-columns: minmax\(0, 1fr\)/);
});
test('guidance appears for the actual REST and publication state transition',()=>{
  const p=(kind,action,original,proposed)=>({input:{kind,action},original,proposed});
  assert.deepEqual(previewNotices(p('MODULE','CREATE',{}, {status:'NOT_PUBLISHED'})),[]);
  assert.deepEqual(previewNotices(p('MODULE','CREATE',{}, {status:'PUBLISHED'})),['exposure']);
  assert.deepEqual(previewNotices(p('MODULE','PUBLISH',{STATUS:'NOT_PUBLISHED'},{status:'PUBLISHED'})),['exposure']);
  assert.deepEqual(previewNotices(p('MODULE','UPDATE',{STATUS:'PUBLISHED'},{status:'PUBLISHED',comments:'new'})),[]);
  assert.deepEqual(previewNotices(p('MODULE','PUBLISH',{STATUS:'PUBLISHED'},{status:'NOT_PUBLISHED'})),['unpublishImpact']);
  assert.deepEqual(previewNotices(p('SCHEMA','CREATE',{}, {enabled:'false'})),[]);
  assert.deepEqual(previewNotices(p('SCHEMA','UPDATE',{STATUS:'DISABLED'},{enabled:'true'})),['enableImpact']);
  assert.deepEqual(previewNotices(p('SCHEMA','UPDATE',{STATUS:'ENABLED'},{enabled:'true',autoRestAuth:'true'})),[]);
  assert.deepEqual(previewNotices(p('SCHEMA','UPDATE',{STATUS:'ENABLED'},{enabled:'false'})),['disableImpact']);
  assert.deepEqual(previewNotices(p('HANDLER','UPDATE',{}, {source:'SELECT 1 FROM DUAL'})),[]);
  for(const [kind,key] of [['SCHEMA','schemaDelete'],['MODULE','deleteChildren'],['TEMPLATE','deleteChildren'],['HANDLER','deleteHandler']])assert.deepEqual(previewNotices(p(kind,'DELETE',{},{})),[key]);
});
test('intro is not a warning wall and settings help stays next to the field',()=>{
  const source=readFileSync('src/main/resources/static/js/ords.mjs','utf8'),html=readFileSync('src/main/resources/templates/ords.html','utf8');
  assert.doesNotMatch(html,/ords.exposure|ords.schemaDelete|ords.sourceNotice/);
  assert.match(html,/data-ords-warnings/);assert.match(source,/fieldHelp=\{status:'statusHelp',autoRestAuth:'authHelp',source:'sourceNotice'\}/);
  assert.match(source,/previewNotices\(data\)/);
});
