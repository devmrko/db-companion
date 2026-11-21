import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const root='src/main/resources/templates/';
const templates=fs.readdirSync(root,{recursive:true}).filter(f=>f.endsWith('.html'));

test('management forms use the shared padded surface instead of data-selector spacing',()=>{
  const selectors=['data-account-setup','data-privilege-setup','data-ai-network','data-scheduler-manage','data-ddsm','data-ddsi','data-dds-runtime'];
  const html=templates.map(f=>fs.readFileSync(root+f,'utf8')).join('\n');
  for(const selector of selectors){
    const tag=html.match(new RegExp('<(?:section|details)\\b[^>]*\\b'+selector+'(?:\\s|>)[^>]*>'))?.[0];
    assert.ok(tag,selector+' exists');
    assert.match(tag,/class="[^"]*\bapp-module-card\b/,selector+' must use shared padding');
  }
});

test('checkboxes never inherit the stacked text-field layout',()=>{
  for(const file of templates){
    const html=fs.readFileSync(root+file,'utf8');
    for(const label of html.matchAll(/<label\b[^>]*>[\s\S]*?<\/label>/g)){
      if(/<input\b[^>]*type="checkbox"/.test(label[0])){
        assert.doesNotMatch(label[0].slice(0,label[0].indexOf('>')),/class="[^"]*\bapp-field\b/,file);
      }
    }
  }
});
