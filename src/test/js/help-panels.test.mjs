import test from 'node:test';
import assert from 'node:assert/strict';
import {helpPanelPosition,mountHelpPanels} from '../../main/resources/static/js/help-panels.mjs';

test('icon help fits desktop, narrow and short viewports on either side of the anchor',()=>{
  for(const viewport of [{width:1440,height:900},{width:768,height:600},{width:320,height:480},{width:600,height:240}]) {
    const panel={width:Math.min(500,viewport.width-24),height:Math.min(320,viewport.height-24)};
    for(const anchor of [{left:20,top:20,bottom:46},{left:viewport.width-40,top:viewport.height-40,bottom:viewport.height-14}]) {
      const p=helpPanelPosition(anchor,panel,viewport);
      assert.ok(p.left>=12&&p.top>=12);
      assert.ok(p.left+panel.width<=viewport.width-12);
      assert.ok(p.top+panel.height<=viewport.height-12);
    }
  }
});
test('help opens below when it fits and above when the bottom edge has no room',()=>{
  assert.deepEqual(helpPanelPosition({left:30,top:40,bottom:66},{width:300,height:200},{width:800,height:600}),{left:30,top:74});
  assert.deepEqual(helpPanelPosition({left:760,top:550,bottom:576},{width:300,height:200},{width:800,height:600}),{left:488,top:342});
});

// A DOM event contract harness, not a browser or layout-rendering substitute.
function harness() {
  const listeners=new Map();
  const doc={addEventListener:(type,fn)=>listeners.set(type,fn),removeEventListener:type=>listeners.delete(type)};
  const viewport={innerWidth:800,innerHeight:600,addEventListener:(type,fn)=>listeners.set(type,fn),removeEventListener:type=>listeners.delete(type)};
  function help() {
    const body={dataset:{},style:{removeProperty(name){delete this[name];}},getBoundingClientRect:()=>({width:300,height:200}),nodeType:1};
    const summary={getBoundingClientRect:()=>({left:760,top:550,bottom:576}),focus(){this.focused=true;},nodeType:1};
    const dialog={};
    const details={open:true,nodeType:1,matches:s=>s==='details.app-dds-help',querySelector:s=>s.endsWith('summary')?summary:body,closest:()=>dialog,
      contains:target=>target===details||target===body||target===summary};
    return {details,body,summary,dialog};
  }
  const dispose=mountHelpPanels(doc,viewport);
  return {listeners,help,dispose};
}
test('dynamically inserted icon help is positioned and opening another closes the previous one',()=>{
  const h=harness(),a=h.help(),b=h.help();
  h.listeners.get('toggle')({target:a.details});
  assert.equal(a.body.dataset.helpPositioned,'true');assert.equal(a.body.style.left,'488px');assert.equal(a.body.style.top,'342px');
  h.listeners.get('toggle')({target:b.details});
  assert.equal(a.details.open,false);assert.equal(a.body.dataset.helpPositioned,undefined);assert.equal(a.body.style.left,undefined);
  assert.equal(b.details.open,true);h.dispose();
});
test('Escape closes help and restores focus without closing the parent dialog',()=>{
  const h=harness(),a=h.help();let prevented=false,stopped=false;
  h.listeners.get('toggle')({target:a.details});
  h.listeners.get('keydown')({key:'Escape',preventDefault(){prevented=true;},stopPropagation(){stopped=true;}});
  assert.equal(a.details.open,false);assert.equal(a.summary.focused,true);assert.ok(prevented&&stopped);h.dispose();
});
test('outside click, external scroll, resize and closing a parent dialog dismiss the help',()=>{
  for(const event of ['click','scroll','resize','close']) {
    const h=harness(),a=h.help();h.listeners.get('toggle')({target:a.details});
    h.listeners.get(event)({target:event==='close'?a.dialog:{}});
    assert.equal(a.details.open,false,event);assert.equal(a.body.dataset.helpPositioned,undefined);h.dispose();
  }
});
test('internal scrolling and clicks retain help; non-icon disclosures are left alone',()=>{
  const h=harness(),a=h.help();h.listeners.get('toggle')({target:a.details});
  h.listeners.get('scroll')({target:a.body});h.listeners.get('click')({target:a.summary});
  h.listeners.get('toggle')({target:{open:true,matches:()=>false}});
  assert.equal(a.details.open,true);h.dispose();assert.equal(h.listeners.size,0);
});
test('native close removes stale positioning and does not affect a newer panel',()=>{
  const h=harness(),a=h.help(),b=h.help();h.listeners.get('toggle')({target:a.details});
  h.listeners.get('toggle')({target:b.details});h.listeners.get('toggle')({target:a.details});
  assert.equal(b.details.open,true);b.details.open=false;h.listeners.get('toggle')({target:b.details});
  assert.equal(b.body.style.top,undefined);h.dispose();
});
