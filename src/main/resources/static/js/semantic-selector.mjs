import {t} from './i18n.mjs';
import {confirmAction} from './confirm-action.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {selectionItems,versionLabel} from './semantic-release.mjs';

const label=(key,...args)=>t('semantic.'+key,key,...args);
if(typeof document!=='undefined')document.querySelectorAll('[data-semantic-selector]').forEach(root=>{
  const get=name=>root.querySelector(`[data-sr-${name}]`);
  let state,busy=false;
  const controls=()=>{get('select').disabled=busy||state?.storage!=='READY';get('selection').disabled=busy||state?.storage!=='READY';};
  async function run(work){
    if(busy)return;busy=true;controls();get('message').textContent='';
    try{await work();}catch(error){get('message').textContent=error.message;}finally{busy=false;controls();}
  }
  get('select').addEventListener('click',()=>run(async()=>{
    if(!await confirmAction(label('confirmSelection')))return;
    await assistantApi(root.dataset.base+'/select',assistantPost(get('csrf'),{selection:get('selection').value,expected:state.preference,confirmed:true}));
    location.reload();
  }));
  const refresh=()=>run(async()=>{
    state=await assistantApi(root.dataset.base);
    get('selection').replaceChildren(...selectionItems(state.versions).map(([value,text])=>{
      const option=document.createElement('option');option.value=value;option.textContent=text;return option;
    }));
    get('selection').value=state.preference.selection;
    const name=id=>{const version=state.versions.find(v=>v.id===id);return version?versionLabel(version):id;};
    get('current').textContent=label('current',name(state.head.releaseId)||'—',name(state.selected)||(state.preference.selection==='LIVE'?label('live'):label('pending')));
    get('message').textContent=state.storage!=='READY'?label('storage'):!state.head.releaseId?label('needsSetup'):'';
  });
  document.addEventListener('app-storage-ready',refresh);
  refresh();
});
