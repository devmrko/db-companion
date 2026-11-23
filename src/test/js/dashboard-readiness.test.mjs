import test from 'node:test';
import assert from 'node:assert/strict';
import {loadSummary} from '../../main/resources/static/js/dashboard-readiness.mjs';

function fixture() {
  const content = {busy: true, replaced: false, setAttribute(key, value) {this.busy = value;}, replaceChildren() {this.replaced = true;}};
  return {content, host: {dataset: {summaryUrl: '/ai-setup/summary'}, querySelector: () => content}};
}
test('network and authentication failures retain unknown states without retrying', async () => {
  for (const result of ['throw', {ok: false}, {ok: true, redirected: true}]) {
    const {host, content} = fixture(); let calls = 0;
    await loadSummary(host, async (url, options) => {
      calls++; assert.equal(url, '/ai-setup/summary'); assert.equal(options.cache, 'no-store');
      if (result === 'throw') throw new Error('unavailable'); return result;
    });
    assert.equal(calls, 1); assert.equal(content.replaced, false); assert.equal(content.busy, 'false');
  }
});
test('timeout aborts pending request and retains unknown states', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const {host, content} = fixture(); let signal;
  const pending = loadSummary(host, async (url, options) => {
    signal = options.signal;
    await new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(new Error('timeout'))));
  });
  t.mock.timers.tick(8000); await pending;
  assert.equal(signal.aborted, true); assert.equal(content.replaced, false); assert.equal(content.busy, 'false');
});
test('only a readiness fragment replaces the initial unknown states', async t => {
  const previous = globalThis.DOMParser;
  t.after(() => {if (previous) globalThis.DOMParser = previous; else delete globalThis.DOMParser;});
  for (const found of [null, {tagName: 'SECTION'}]) {
    globalThis.DOMParser = class {
      parseFromString(html, type) {
        assert.equal(html, 'synthetic response'); assert.equal(type, 'text/html');
        return {querySelector(selector) {assert.equal(selector, '[data-setup-summary-result]'); return found;}};
      }
    };
    const {host, content} = fixture();
    await loadSummary(host, async () => ({ok: true, redirected: false, text: async () => 'synthetic response'}));
    assert.equal(content.replaced, !!found); assert.equal(content.busy, 'false');
  }
});
