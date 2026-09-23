import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {vocabulary,termsPage} from '../../main/resources/static/js/ontology-terms.mjs';
const skos='http://www.w3.org/2004/02/skos/core#',dbc='urn:dbcompanion:ontology:',rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#';
const row=(subject,predicate,value,kind='LITERAL')=>({subject,predicate,object:{kind,value}});
function term(resource,preferred,aliases=[]){const concept=resource+'/concept';return [
  row(resource,dbc+'name',resource),row(resource,dbc+'concept',concept,'IRI'),row(concept,rdf+'type',skos+'Concept','IRI'),
  row(concept,skos+'prefLabel',preferred),...aliases.map(value=>row(concept,skos+'altLabel',value)),row(concept,skos+'definition','description'),row(concept,dbc+'state','DRAFT')];}
test('typed SKOS links associate a label and aliases with one source without merging homonyms',()=>{
  const data={triples:[...term('AMOUNT','매출액',['판매금액']),...term('REFUND','환불액',['판매금액'])]};
  const original=JSON.stringify(data),terms=vocabulary(data);
  assert.equal(terms.length,2);assert.equal(termsPage(terms,'판매금액').total,2);assert.equal(termsPage(terms,'매출액').total,1);
  assert.equal(termsPage(terms,'판매금액').items[0].preferred,'매출액');assert.equal(termsPage(terms,'판매금액').items[0].source,'AMOUNT');
  assert.equal(terms[0].state,'DRAFT');assert.equal(JSON.stringify(data),original);
});
test('term search is literal partial matching with NFC and case folding, not regex or fuzzy inference',()=>{
  const terms=vocabulary({triples:[...term('CODE','café',['A.*B','<script>']),...term('ID','식별자',['ID'])]});
  assert.equal(termsPage(terms,' CAFE\u0301 ').total,1);assert.equal(termsPage(terms,'식별').total,1);
  assert.equal(termsPage(terms,'a.*b').total,1);assert.equal(termsPage(terms,'AxB').total,0);assert.equal(termsPage(terms,'미등록').total,0);
  assert.equal(termsPage(terms,'<script>').items[0].preferred,'café');assert.equal(termsPage(terms,'description').total,0);
});
test('ten-row pages include distinct concepts and clamp empty or out-of-range pages',()=>{
  const terms=vocabulary({triples:Array.from({length:24},(_,i)=>term('C'+i,'용어'+i,['공통'])).flat()});
  assert.equal(termsPage(terms,'공통',2).items.length,10);assert.equal(termsPage(terms,'공통',99).items.length,4);
  assert.equal(termsPage(terms,'없음',3).page,1);assert.equal(termsPage(terms,'없음',3).total,0);
});
test('plain comments, dangling links and string values posing as RDF links are not concepts',()=>{
  const triples=[row('A',dbc+'concept','B'),row('A',dbc+'name','raw'),row('B',skos+'prefLabel','fake'),row('C',dbc+'concept','MISSING','IRI')];
  assert.deepEqual(vocabulary({triples}),[]);
  assert.deepEqual(vocabulary({triples:term('A',' ')}),[]);
});
test('term view uses inert text and local typed values, four languages and existing RDF navigation',()=>{
  const code=readFileSync('src/main/resources/static/js/ontology-terms.mjs','utf8');
  assert.ok(code.includes('textContent'));assert.ok(!/innerHTML|eval\(|fetch\(|localStorage|sessionStorage/.test(code));
  const labels=JSON.parse(readFileSync('tools/i18n/feature-ontology-terms.json','utf8'));
  for(const [key,values] of Object.entries(labels)){assert.equal(values.length,4,key);assert.ok(values.every(v=>v.trim()),key);}
  for(const match of code.matchAll(/label\('([^']+)'\)/g))assert.ok(labels['ontology.terms.'+match[1]],match[1]);
  for(const key of ['preferred','aliases','source','definition'])assert.ok(labels['ontology.terms.'+key]);
  const viewer=readFileSync('src/main/resources/static/js/ontology-rdf.mjs','utf8');assert.ok(viewer.includes('termsViewer(data'));assert.ok(viewer.includes('row.subject===selected.resource||row.subject===selected.concept'));
});
