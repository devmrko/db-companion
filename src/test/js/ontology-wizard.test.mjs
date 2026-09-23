import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {sampleRequest,editsOf,canSample,definitionDetails,recommendationRow,labelColumnChoices,editDefinition} from '../../main/resources/static/js/ontology-wizard.mjs';
test('sampling requires explicit limited selection and confirmation',()=>{
  assert.equal(canSample(['C'],false,false),false);assert.equal(canSample([],true,false),false);assert.equal(canSample(['C'],true,true),false);assert.equal(canSample(Array(21).fill('C'),true,false),false);assert.equal(canSample(['C'],true,false),true);
  assert.deepEqual(sampleRequest('A',{revision:2,document:{source:{table:'T'}}},['C'],'10',true),{schema:'A',table:'T',revision:2,columns:['C'],count:10,confirmed:true});
});
test('only opted-in recommendations are saved without client-authored evidence',()=>{
  const row={column:'C',description:'d',label:'l',aliases:'a\n b \n',unit:'u',role:'r',reason:'do not trust',accepted:true};
  assert.deepEqual(editsOf([row,{...row,column:'OTHER',accepted:false}]),[{column:'C',description:'d',label:'l',aliases:['a','b'],unit:'u',role:'r',valueMeaning:'',labelColumn:'',usageGuidance:''}]);assert.deepEqual(editsOf([]),[]);
});
test('saved definition details retain sampling provenance not raw values',()=>{
  assert.deepEqual(definitionDetails(null),[]);const result=definitionDetails({label:'L',aliases:['A'],unit:'U',role:'R',assessment:'UNKNOWN',reason:'why',uncertainty:'verify',profile:'P',sampledAt:'now',sampleRows:2});assert.ok(result.some(([key,value])=>key==='sampleRows'&&value==='2'));assert.equal(result.length,13);
});
test('manual definition editing is independent of names and never fabricates AI evidence',()=>{
  let d=editDefinition(null,'role','code');d=editDefinition(d,'valueMeaning','Business abbreviations');d=editDefinition(d,'labelColumn','CAPTION');d=editDefinition(d,'usageGuidance','Filter by the supplied code');d=editDefinition(d,'aliases','alias\n alternate \n');
  assert.equal(d.profile,'');assert.equal(d.sampleRows,0);assert.equal(d.sampledAt,'');assert.equal(d.assessment,'UNKNOWN');assert.deepEqual(d.aliases,['alias','alternate']);
  assert.ok(!definitionDetails(d).some(([k])=>['profile','sampleRows','assessment'].includes(k)));
  const before=structuredClone(d),after=editDefinition(d,'role','identifier');assert.deepEqual(d,before);assert.equal(after.role,'identifier');
  assert.throws(()=>editDefinition(d,'profile','FAKE'));
});
test('manual and AI review paths keep usage guidance in the saved definition',()=>{
  const definition={label:'Amount',aliases:[],role:'amount',unit:'USD',usageGuidance:'Display as money; do not sum ratios'};
  const row=recommendationRow({column:'VALUE_X',description:'Amount',definition});row.accepted=true;
  assert.equal(editsOf([row])[0].usageGuidance,definition.usageGuidance);
  const js=readFileSync('src/main/resources/static/js/ontology-wizard.mjs','utf8');assert.ok(js.includes('control.disabled=!editable'));assert.ok(js.includes("column+' '+text(key)"));
  const main=readFileSync('src/main/resources/static/js/ontology.mjs','utf8');assert.ok(main.includes('definitionEditor({column:c.name'));assert.ok(main.includes('value.definition=d;dirty=true'));assert.ok(main.includes("savePayload(schema,entry,meaning,key==='approve'?'APPROVED':'DRAFT')"));
});
test('semantic recommendations use JSON properties and retain reviewed fields',()=>{
  const definition={label:'Area',aliases:['Territory'],unit:'',role:'code',valueMeaning:'Business area identifiers',labelColumn:'LABEL'};
  const row=recommendationRow({column:'CODE',description:'Area code',definition});assert.equal(row.accepted,false);assert.deepEqual(editsOf([row]),[]);
  row.accepted=true;row.valueMeaning='Reviewed meaning';row.labelColumn='';const [edit]=editsOf([row]);assert.equal(edit.valueMeaning,'Reviewed meaning');assert.equal(edit.labelColumn,'');assert.ok(!('reason' in edit));
  const legacy=recommendationRow({column:'CODE',description:'Code',definition:{label:'Code',aliases:[],unit:'',role:'code'}});assert.equal(legacy.valueMeaning,'');assert.equal(legacy.labelColumn,'');
  assert.deepEqual(labelColumnChoices(['CODE','LABEL'],'CODE'),['LABEL']);assert.deepEqual(labelColumnChoices(['CODE'],'CODE'),[]);
});
test('wizard is explicit and releases both client and server raw data',()=>{
  const js=readFileSync('src/main/resources/static/js/ontology-wizard.mjs','utf8');assert.ok(!/innerHTML|eval\(|localStorage|sessionStorage|setInterval/.test(js));
  for(const required of ["post('/wizard/cancel'","post('/wizard/sample'","post('/wizard/generate'","post('/wizard/apply'","spent=true","accepted:false","preview=null","get('payload').textContent=''","e.preventDefault();close()"] )assert.ok(js.includes(required),required);
});
test('wizard labels and accessible controls cover four languages',()=>{
  const labels=JSON.parse(readFileSync('tools/i18n/feature-ontology-wizard.json','utf8'));for(const [key,values] of Object.entries(labels)){assert.equal(values.length,4,key);assert.ok(values.every(v=>v.trim()),key);}
  const html=readFileSync('src/main/resources/templates/fragments/ontology-wizard.html','utf8');for(const name of ['data-w-local-consent','data-w-consent','data-w-sample disabled','data-w-generate disabled','data-w-save disabled','aria-labelledby','aria-live'])assert.ok(html.includes(name));
});
