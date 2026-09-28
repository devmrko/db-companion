import {t} from './i18n.mjs';

export function routeCoverage(search,route){
  const matched=[],unmatched=[];
  for(const concept of search?.concepts??[])
    (concept.targets.some(target=>route?.tables?.includes(target.table))?matched:unmatched).push(concept.term);
  return {total:matched.length+unmatched.length,matched,unmatched};
}
export const coverageLabel=coverage=>coverage.total?t('ontology.query.paths.coverage','검색 개념 {0}개 중 {1}개 일치',coverage.total,coverage.matched.length):'';
export const unmatchedLabel=coverage=>coverage.unmatched.length?t('ontology.query.paths.unmatched','이 경로에 포함되지 않은 개념: {0}',coverage.unmatched.join(' · ')):'';
