// Public reference data, not account settings or a live availability/compatibility check.
// Update from the linked Oracle model cards; never add customer OCIDs or defaults here.
const oracleDocs='https://docs.oracle.com/en-us/iaas/Content/generative-ai/';
const oci={
  reviewedAt:'2026-09-18',
  source:oracleDocs+'pretrained-models.htm',
  models:[
    {name:'cohere.embed-v4.0',deprecated:false,source:oracleDocs+'cohere-embed-4.htm'},
    {name:'cohere.embed-multilingual-v3.0',deprecated:true,source:oracleDocs+'cohere-embed-multilingual-3.htm'},
    {name:'cohere.embed-english-v3.0',deprecated:true,source:oracleDocs+'cohere-embed-english-3.htm'},
    {name:'cohere.embed-multilingual-light-v3.0',deprecated:true,source:oracleDocs+'cohere-embed-multilingual-light-3.htm'},
    {name:'cohere.embed-english-light-v3.0',deprecated:true,source:oracleDocs+'cohere-embed-english-light-3.htm'}
  ]
};
export const MANUAL_MODEL='__manual__';
export function publicCatalog(provider) {
  return provider==='ocigenai' ? {...oci,models:oci.models.map(model=>({...model}))} : null;
}
// Selection is independent of DB owner, table, region, credential and vector dimensions.
// Unknown/new model names remain editable, never rejected by this reference catalogue.
export function publicModelState(provider,name,manual=false) {
  const model=publicCatalog(provider)?.models.find(model=>model.name===name)??null;
  const custom=provider!=='ocigenai'||manual||Boolean(name&&!model);
  return {choice:custom?MANUAL_MODEL:name,manual:custom,model};
}
const ociRegions={
  reviewedAt:'2026-09-18',source:oracleDocs+'regions.htm',realm:'OC1',
  regions:[
    {id:'sa-saopaulo-1',city:'São Paulo'},
    {id:'eu-frankfurt-1',city:'Frankfurt'},
    {id:'ap-hyderabad-1',city:'Hyderabad'},
    {id:'ap-osaka-1',city:'Osaka'},
    {id:'me-riyadh-1',city:'Riyadh'},
    {id:'me-abudhabi-1',city:'Abu Dhabi'},
    {id:'me-dubai-1',city:'Dubai'},
    {id:'uk-london-1',city:'London'},
    {id:'us-ashburn-1',city:'Ashburn'},
    {id:'us-chicago-1',city:'Chicago'},
    {id:'us-phoenix-1',city:'Phoenix'}
  ]
};
export const MANUAL_REGION='__manual__';
export function publicRegions() { return {...ociRegions,regions:ociRegions.regions.map(region=>({...region}))}; }
export function publicRegionState(id,manual=false) {
  const custom=manual||Boolean(id&&!ociRegions.regions.some(region=>region.id===id));
  return {choice:custom?MANUAL_REGION:id,manual:custom};
}
