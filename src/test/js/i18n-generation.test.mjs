import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createRequire} from 'node:module';

const require=createRequire(import.meta.url);
const unescape=value=>value.replace(/\\u([0-9a-fA-F]{4})|\\(.)/g,(_,hex,char)=>
  hex?String.fromCharCode(parseInt(hex,16)):({n:'\n',r:'\r',t:'\t',f:'\f'}[char]??char));
const messages=content=>Object.fromEntries(content.split(/\r?\n/)
  .filter(line=>line && !line.startsWith('#'))
  .map(line=>{
    const separator=line.indexOf('=');
    assert.ok(separator>0,'Expected generated key=value message');
    return [line.slice(0,separator),unescape(line.slice(separator+1))];
  }));

test('regenerating every locale preserves the shipped message keys and wording',()=>{
  const generated=new Map();
  const capture={...fs,
    writeFileSync(file,content){generated.set(file,content);},
    appendFileSync(file,content){generated.set(file,(generated.get(file)??'')+content);}
  };
  vm.runInNewContext(fs.readFileSync('scripts/i18n-bundles.cjs','utf8'),{
    require:name=>name==='node:fs'?capture:require(name),
    __dirname:path.resolve('scripts'),console:{log(){}}
  });
  assert.equal(generated.size,5,'Default and four locale bundles');
  for(const [file,content] of generated){
    const actual=messages(content),expected=messages(fs.readFileSync(file,'utf8'));
    const differences=[...new Set([...Object.keys(actual),...Object.keys(expected)])]
      .filter(key=>actual[key]!==expected[key]);
    assert.deepEqual(differences,[],path.basename(file)+' differs from reviewed translation sources');
  }
});
