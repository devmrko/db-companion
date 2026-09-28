import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {validSelection,selectionRequest,sameSelection,renderSearch,renderTokenAnalysis,automaticSelection,MAX_GLOSSARY_DEFINITIONS} from '../../main/resources/static/js/business-glossary.mjs';
const term={id:'id',revision:1,term:'Revenue',aliases:['Sales'],definition:'Definition',criteria:'SUM(amount)',enabled:true};
const search={id:'s',owner:'APP',profile:'P',question:'Sales?',mode:'EXACT_ALIAS',targets:[{expression:'Sales',kind:'ALIAS'}],tokens:[],hits:[{term,kind:'ALIAS',expressions:['Sales']}],expires:'2099-01-01T00:00:00Z'};
test('selection binds question profile expiry and ids, including empty and duplicates',()=>{
  assert.equal(validSelection(search,['id'],'Sales?','P'),true);
  for(const [ids,q,p,now] of [[[],'Sales?','P',0],[['id','id'],'Sales?','P',0],[['other'],'Sales?','P',0],[['id'],'changed','P',0],[['id'],'Sales?','other',0],[['id'],'Sales?','P',Date.parse('2100-01-01')]])assert.equal(validSelection(search,ids,q,p,now),false);
});
test('request carries ids only, not client-authored definitions',()=>{
  assert.deepEqual(selectionRequest(true,search,['id']),{glossary:{enabled:true,searchId:'s',termIds:['id']}});
  assert.deepEqual(selectionRequest(false,search,['id']),{glossary:{enabled:false}});
});
test('all matched definitions are automatically attached; empty searches proceed, overflows do not truncate',()=>{
  assert.deepEqual(automaticSelection(search),['id']);
  const empty={...search,hits:[]};assert.deepEqual(automaticSelection(empty),[]);assert.equal(validSelection(empty,[],'Sales?','P'),true);
  assert.equal(automaticSelection({...search,more:true}),null);
  const large={...search,hits:Array.from({length:31},(_,i)=>({term:{...term,id:String(i)}}))};assert.equal(automaticSelection(large),null);
  assert.equal(validSelection(large,['0'],'Sales?','P'),false);
});
test('11 through 30 definitions reach preview intact; 31 and partial results remain blocked',()=>{
  assert.equal(MAX_GLOSSARY_DEFINITIONS,30);
  for(const count of [11,30,31]){
    const found={...search,hits:Array.from({length:count},(_,i)=>({term:{...term,id:String(i)}}))};
    const ids=found.hits.map(h=>h.term.id);
    assert.deepEqual(automaticSelection(found),count<=30?ids:null);
    assert.equal(validSelection(found,ids,found.question,found.profile),count<=30);
    assert.equal(validSelection(found,ids.slice(0,-1),found.question,found.profile),false);
    assert.equal(automaticSelection({...found,more:true}),null);
  }
});
test('client and server glossary limits and translated counters remain consistent',()=>{
  const java=readFileSync('src/main/java/com/dbcompanion/model/BusinessGlossary.java','utf8');
  assert.match(java,/MAX_HITS=30, MAX_SELECTED=MAX_HITS/);
  for(const suffix of ['', '_ko', '_en', '_ja', '_zh_CN']){
    const bundle=readFileSync(`src/main/resources/i18n/messages${suffix}.properties`,'utf8');
    assert.match(bundle,/^businessGlossary.selected=.*\{0\}.*\{1\}/m);
    assert.match(bundle,/^businessGlossary.limit=.*30/m);
  }
});
test('snapshot matching rejects off toggle and changed revisions',()=>{
  const snapshot={...search,selected:search.hits};assert.equal(sameSelection(snapshot,true,search,['id']),true);
  assert.equal(sameSelection(snapshot,false,search,[]),false);assert.equal(sameSelection(null,false,null,[]),true);
  assert.equal(sameSelection(snapshot,true,{...search,hits:[{term:{...term,revision:2}}]},['id']),false);
});
class Node {
  constructor(tag){this.tag=tag;this.children=[];this.dataset={};this.textContent='';}
  get childNodes(){return this.children;}
  append(...children){this.children.push(...children);}
  replaceChildren(...children){this.children=children;}
  addEventListener(){}
  set innerHTML(value){throw Error('Unsafe HTML rendering');}
}
function texts(node){return [node.textContent,...node.children.flatMap(texts)];}
test('missing independent analysis exposes copy-only setup help, not dictionary installation',()=>{
  globalThis.document={createElement:tag=>new Node(tag)};
  try{
    const host=new Node('main');renderTokenAnalysis(host,{mode:'ORACLE_TEXT_UNCONFIGURED',tokens:[]});
    assert.ok(texts(host).some(t=>t.includes('업무 용어 사전 테이블·인덱스는 필요하지 않습니다')));
    assert.equal(host.children[1].tag,'a');assert.equal(host.children[1].href,'#question-analysis-settings');
    assert.ok(!texts(host).some(t=>t.includes('일치하는 근거가 없습니다')));
    renderTokenAnalysis(host,{mode:'ORACLE_TEXT',tokens:[{token:'사용자'},{token:'권역'}]});
    assert.ok(texts(host).includes('사용자'));assert.ok(texts(host).includes('권역'));
  }finally{delete globalThis.document;}
});
test('renders search expressions definitions reasons and raw text safely, with no auto-selected hit',()=>{
  globalThis.document={createElement:tag=>new Node(tag)};
  try{const host=new Node('main'),value={...search,hits:[{...search.hits[0],term:{...term,definition:'<img src=x onerror=alert(1)>'}}]};renderSearch(host,value,[],()=>{});assert.ok(texts(host).includes('<img src=x onerror=alert(1)>'));assert.ok(texts(host).some(t=>t.includes('Sales')));assert.ok(texts(host).includes('검색된 용어 정의'));const all=n=>[n,...n.children.flatMap(all)];assert.equal(all(host).find(n=>n.tag==='input').checked,false);}finally{delete globalThis.document;}
});
test('empty result differs from Text failure and filters never silently trigger AI',()=>{
 const js=readFileSync('src/main/resources/static/js/business-glossary.mjs','utf8');assert.ok(js.includes('catch(ex){search=null;'));assert.ok(js.includes('current!==sequence'));assert.ok(js.includes('unknownWrite=true'));assert.ok(!js.includes('innerHTML'));assert.ok(!js.includes("post('generate'"));
});
test('forms preserve labels and use wrap-safe shared styles',()=>{
 const html=readFileSync('src/main/resources/templates/business-glossary.html','utf8'),css=readFileSync('src/main/resources/static/css/common.css','utf8');for(const attr of ['data-glossary-definition','data-glossary-criteria','data-glossary-setup-consent','data-glossary-save-consent'])assert.ok(html.includes(attr));assert.ok(css.includes('.app-glossary-toolbar, .app-glossary-actions { display: flex; flex-wrap: wrap;'));assert.ok(css.includes('flex: 0 0 auto; white-space: normal; word-break: keep-all;'));
});
