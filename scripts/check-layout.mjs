import fs from 'node:fs';
import path from 'node:path';
// Synthetic template layout check; no application scripts, login or database calls.
// Set LAYOUT_PLAYWRIGHT_MODULE and LAYOUT_BROWSER when Playwright is not installed locally.
import assert from 'node:assert/strict';
const {chromium}=await import(process.env.LAYOUT_PLAYWRIGHT_MODULE||'playwright');
const root=process.cwd(),dir=path.join(root,'src/main/resources/templates');
const files=fs.readdirSync(dir,{recursive:true}).filter(x=>x.endsWith('.html'));
const messages=Object.fromEntries(fs.readFileSync('src/main/resources/i18n/messages.properties','utf8').split('\n').filter(x=>x.includes('=')).map(x=>[x.slice(0,x.indexOf('=')),x.slice(x.indexOf('=')+1).replace(/\\u([0-9a-fA-F]{4})/g,(_,h)=>String.fromCharCode(parseInt(h,16)))]));
const bootstrap=await (await fetch((process.env.LAYOUT_BASE_URL||'http://127.0.0.1:18094')+'/webjars/bootstrap/5.3.8/css/bootstrap.min.css')).text();
const browser=await chromium.launch({executablePath:process.env.LAYOUT_BROWSER,headless:true});
const page=await browser.newPage();
await page.route('**/*',r=>r.abort());
fs.mkdirSync('.run',{recursive:true});
const results=[];
for(const width of [1440,390])for(const file of files){
 await page.setViewportSize({width,height:1000});
 const source=fs.readFileSync(path.join(dir,file),'utf8').replace(/<script\b[^>]*>[\s\S]*?<\/script>/g,'');
 await page.setContent(source);
 const extra=[...source.matchAll(/@\{\/css\/([^}]+)\}/g)].map(m=>m[1]);
 for(const css of [bootstrap,...['common.css','configuration.css',...extra].map(f=>fs.readFileSync('src/main/resources/static/css/'+f,'utf8'))])await page.addStyleTag({content:css});
 await page.evaluate(messages=>{
  for(const e of document.querySelectorAll('svg[th\\:replace]'))e.classList.add('app-icon');
  const bodyClass=document.body.getAttribute('th:classappend')?.match(/'(app-[^']+)'/)?.[1];
  if(bodyClass)document.body.classList.add(bodyClass);
  for(const e of document.querySelectorAll('[th\\:text]')){
   const key=e.getAttribute('th:text').match(/^#\{([^}]+)\}$/)?.[1];
   if(key&&messages[key])e.textContent=messages[key];
  }
  for(const e of document.querySelectorAll('details'))e.open=true;
 },messages);
 const cards=await page.locator('.app-card').evaluateAll(nodes=>nodes.map(e=>{
  const s=getComputedStyle(e),r=e.getBoundingClientRect();
  const edges=[...e.children].filter(c=>{
   const z=c.getBoundingClientRect();return z.height&&c.textContent.trim()&&['P','H2','H3','A','SUMMARY','LABEL'].includes(c.tagName)&&z.left-r.left+parseFloat(getComputedStyle(c).paddingLeft)<8;
  }).map(c=>c.tagName+'.'+c.className);
  return {class:e.className,data:Object.keys(e.dataset),padding:s.padding,edges,width:r.width};
 }).filter(x=>x.width&&x.edges.length));
 const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2);
 const hiddenVisible=await page.locator('[hidden]').evaluateAll(nodes=>nodes.some(e=>getComputedStyle(e).display!=='none'));
 assert.equal(hiddenVisible,false,file+': hidden content must stay hidden');
 if(cards.length||overflow)results.push({file,width,cards,overflow});
 if(['account-setup.html','scott-demo.html','scheduler.html'].includes(file))await page.screenshot({path:`.run/layout-${file}-${width}.png`,fullPage:true});
}
fs.writeFileSync('.run/layout-check.json',JSON.stringify({files:files.length,results},null,2));
console.log(JSON.stringify({files:files.length,findings:results},null,2));
// Exercise the real code-view observer with synthetic SQL, including reset to empty.
await page.route('http://layout.invalid/**',async route=>{
 const name=new URL(route.request().url()).pathname.slice(1);
 if(name==='fixture')return route.fulfill({contentType:'text/html',body:'<pre data-code-language="sql"></pre>'});
 if(!['code-view.mjs','source-viewer.mjs','i18n.mjs'].includes(name))return route.abort();
 return route.fulfill({contentType:'text/javascript',body:fs.readFileSync('src/main/resources/static/js/'+name,'utf8')});
});
await page.goto('http://layout.invalid/fixture');
await page.evaluate(async()=>{const {mountCodeViews}=await import('/code-view.mjs');mountCodeViews(document);});
assert.equal(await page.locator('.app-code-view').isVisible(),false,'empty SQL preview');
await page.locator('pre').evaluate(e=>e.textContent='SELECT 1 FROM DUAL');
await page.waitForFunction(()=>!document.querySelector('.app-code-view').hidden);
await page.locator('pre').evaluate(e=>e.textContent='');
await page.waitForFunction(()=>document.querySelector('.app-code-view').hidden);
await browser.close();
assert.equal(results.length,0,'Template layout regression: see .run/layout-check.json');
