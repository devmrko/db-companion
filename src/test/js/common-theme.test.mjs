import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const css=readFileSync('src/main/resources/static/css/common.css','utf8');
const variable=name=>css.match(new RegExp('--app-'+name+':\\s*(#[a-f0-9]{6});'))?.[1];
function luminance(hex){return [1,3,5].map(i=>parseInt(hex.slice(i,i+2),16)/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4).reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0);}
function contrast(a,b){const x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
test('theme text and buttons retain normal-text contrast on their surfaces',()=>{
  for(const [fg,bg] of [['text','canvas'],['muted','surface'],['link','surface'],['chrome-text','chrome'],['surface','accent']])
    assert.ok(contrast(variable(fg),variable(bg))>=4.5,fg+' on '+bg);
});
test('shared chrome and selection use brand-neutral styling without remote assets',()=>{
  assert.match(css,/\.app-topbar, \.app-login-header, \.app-sidebar > \.app-brand/);
  assert.match(css,/border-left-color: var\(--app-accent\)/);
  assert.doesNotMatch(css,/@import|url\(\s*['"]?https?:/i);
  const template=readFileSync('src/main/resources/templates/fragments/common.html','utf8');
  assert.match(template,/DB<span class="app-brand-light">companion<\/span>/);
});
test('theme preserves feedback colors, success states and accessible responsive behavior',()=>{
  assert.match(css,/\.app-ref-sql[^}]*#e5f3e6/s);
  assert.match(css,/\.app-ref-feedback[^}]*#f1eafa/s);
  assert.match(css,/\.app-status-dot[^}]*var\(--app-success\)/);
  assert.match(css,/@media \(max-width: 700px\)/);
  assert.match(css,/@media \(prefers-reduced-motion: reduce\)/);
  assert.match(css,/@media \(forced-colors: active\)/);
  assert.match(css,/:focus-visible/);
});
