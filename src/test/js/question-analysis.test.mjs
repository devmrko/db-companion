import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {analysisLanguage,analysisEndpoint,configurationMatches,mountQuestionAnalysis} from '../../main/resources/static/js/question-analysis.mjs';

test('question language defaults to UI locale and requests preserve explicit language',()=>{
  assert.equal(analysisLanguage('en-US'),'en');assert.equal(analysisLanguage('ja-JP'),'ja');assert.equal(analysisLanguage('zh-CN'),'zh-CN');assert.equal(analysisLanguage('ko-KR'),'ko');
  assert.equal(analysisEndpoint('search','zh-CN'),'search?language=zh-CN');assert.equal(configurationMatches({language:'ko'},'en'),false);
});
class Node {
  constructor(){this.value='';this.textContent='';this.handlers={};this.selectedOptions=[{textContent:'English'}];}
  addEventListener(name,fn){this.handlers[name]=fn;}
}
test('read-only status shows language-specific SQL and clears stale SQL on failures',async()=>{
  const saved={document:globalThis.document,fetch:globalThis.fetch};
  const nodes=new Map(),get=n=>{if(!nodes.has(n))nodes.set(n,new Node());return nodes.get(n);};
  globalThis.document={documentElement:{lang:'en'}};
  const host={querySelector:s=>get(s.match(/data-analysis-([^\]]+)/)[1])};let state='MISSING',fail=false;const calls=[];
  globalThis.fetch=async(url,options)=>{calls.push({url,options});return new Response(JSON.stringify(fail?{error:'Read failed'}:{language:'en',state,owner:'APP',policy:'DBC_QA_EN_POLICY',lexer:'BASIC_LEXER',setupSql:state==='MISSING'?'BEGIN setup; END;':''}),{status:fail?503:200,headers:{'Content-Type':'application/json'}});};
  try{
    const ui=mountQuestionAnalysis({querySelector:()=>host});assert.equal(ui.language(),'en');await ui.load();
    assert.equal(get('setup').hidden,false);assert.match(get('status').textContent,/BASIC_LEXER/);assert.match(get('sql').textContent,/BEGIN/);
    state='READY';await ui.load();assert.equal(get('setup').hidden,true);assert.equal(get('sql').textContent,'');
    state='MISSING';await ui.load();fail=true;await ui.load();assert.equal(get('setup').hidden,true);assert.equal(get('sql').textContent,'');assert.equal(get('copy').disabled,true);assert.match(get('status').textContent,/Read failed/);
    assert.ok(calls.every(c=>c.url==='/question-analysis/configuration?language=en'&&(!c.options?.method||c.options.method==='GET')));
  }finally{for(const [key,value] of Object.entries(saved)){if(value===undefined)delete globalThis[key];else globalThis[key]=value;}}
});
test('settings are available in both ontology search surfaces and translated in all locales',()=>{
  for(const name of ['ontology-query','ai-test'])assert.match(readFileSync(`src/main/resources/templates/${name}.html`,'utf8'),/fragments\/question-analysis/);
  const translations=JSON.parse(readFileSync('tools/i18n/feature-question-analysis.json','utf8'));
  for(const [key,values] of Object.entries(translations)){
    assert.equal(values.length,4);
    for(const [suffix,index] of [['_ko',0],['_en',1],['_zh_CN',2],['_ja',3]])assert.ok(readFileSync(`src/main/resources/i18n/messages${suffix}.properties`,'utf8').includes(`${key}=${values[index]}`));
  }
});
