import test from 'node:test';
import assert from 'node:assert/strict';
import {routeCoverage,coverageLabel,unmatchedLabel} from '../../main/resources/static/js/ontology-route-coverage.mjs';

test('coverage counts concepts, not intermediate tables or edge count',()=>{
  const search={concepts:[{term:'사용자',targets:[{table:'U'}]},{term:'권역',targets:[{table:'R'}]},{term:'연결',targets:[{table:'LOT'}]}]};
  const coverage=routeCoverage(search,{tables:['U','BRIDGE','R']});
  assert.deepEqual(coverage,{total:3,matched:['사용자','권역'],unmatched:['연결']});
  assert.equal(coverageLabel(coverage),'검색 개념 3개 중 2개 일치');
  assert.equal(unmatchedLabel(coverage),'이 경로에 포함되지 않은 개념: 연결');
  assert.equal(unmatchedLabel(routeCoverage(search,{tables:['U','R','LOT']})),'');
});
test('anchor-only and legacy searches do not claim concept coverage',()=>{
  assert.deepEqual(routeCoverage({}, {tables:['A']}),{total:0,matched:[],unmatched:[]});
  assert.equal(coverageLabel(routeCoverage(null,null)),'');
});
test('several targets count once per concept and untrusted terms remain text',()=>{
  const search={concepts:[{term:'<img onerror=alert(1)>',targets:[{table:'A'},{table:'B'}]}]};
  assert.deepEqual(routeCoverage(search,{tables:['A','B']}).matched,['<img onerror=alert(1)>']);
  assert.equal(routeCoverage(search,null).unmatched.length,1);
});
