import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const source=JSON.parse(fs.readFileSync(new URL('../../../tools/i18n/feature-vpd-management.json',import.meta.url),'utf8'));
const decode=s=>s.replace(/\\u([0-9a-fA-F]{4})|\\([nrt\\ ])/g,(_m,hex,escaped)=>hex?String.fromCharCode(parseInt(hex,16)):({n:'\n',r:'\r',t:'\t','\\':'\\',' ':' '}[escaped]));
test('VPD translations remain reproducible from the source catalog in every locale',()=>{
  for(const [suffix,index] of [['',0],['_ko',0],['_en',1],['_zh_CN',2],['_ja',3]]){
    const rows=fs.readFileSync(new URL(`../../main/resources/i18n/messages${suffix}.properties`,import.meta.url),'utf8').split(/\r?\n/).filter(s=>s.startsWith('vpd.'));
    const bundle=new Map(rows.map(s=>[s.slice(0,s.indexOf('=')),decode(s.slice(s.indexOf('=')+1))]));
    assert.equal(bundle.size,Object.keys(source).length);assert.equal(rows.length,bundle.size);
    for(const [key,translations] of Object.entries(source)){assert.equal(translations.length,4);assert.ok(translations[index]);assert.equal(bundle.get(key),translations[index],`${suffix}: ${key}`);}
  }
});
