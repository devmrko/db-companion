import {t} from './i18n.mjs';
import {assistantApi,assistantPost} from './ai-assistant.mjs';
export function mountSemanticHistory(root){
  const get=k=>root.querySelector(`[data-sh-${k}]`),csrf=get('csrf');
  const label=k=>t('semanticHistory.'+k,k);
  let busy=false,installed=false,name='';
  const query=extra=>new URLSearchParams({schema:root.dataset.schema,kind:root.dataset.kind,...extra});
  const say=s=>{get('message').textContent=s;};
  const controls=()=>{get('refresh').disabled=busy;get('install').disabled=busy||installed||!get('consent').checked;};
  async function detail(id){
    try{const d=await assistantApi('/semantic-history/detail?'+query({id}));
      get('meta').textContent=`${d.item.actor} · ${d.item.recordedAt} · ${d.item.state}`;
      get('before').textContent=JSON.stringify(d.payload.before??{},null,2);
      get('after').textContent=JSON.stringify(d.payload.after??{status:'CHECK_REQUIRED'},null,2);
      get('dialog').showModal();
    }catch(e){say(e.message);}
  }
  async function refresh(){
    if(busy)return;busy=true;controls();
    try{const status=await assistantApi('/semantic-history/status?'+query());installed=status.storage==='READY';
      get('setup').hidden=status.storage!=='MISSING';get('records').hidden=!installed;get('sql').textContent=status.sql||'';
      say(installed?label('ready'):label(status.storage==='MISSING'?'missing':'unavailable'));
      get('list').replaceChildren();
      if(installed){const rows=await assistantApi('/semantic-history?'+query({name}));get('filter').textContent=name||label('latest');
        for(const row of rows){const tr=document.createElement('tr');
          for(const text of [row.name,row.operation,`${row.actor} · ${row.recordedAt}`,label('state.'+row.state)]){const td=document.createElement('td');td.textContent=text;tr.append(td);}
          const td=document.createElement('td'),button=document.createElement('button');button.type='button';button.className='btn app-btn app-btn-quiet';button.textContent=label('detail');button.addEventListener('click',()=>detail(row.id));td.append(button);tr.append(td);get('list').append(tr);
        }
        if(!rows.length){const tr=document.createElement('tr'),td=document.createElement('td');td.colSpan=5;td.textContent=label('empty');tr.append(td);get('list').append(tr);}
      }
    }catch(e){say(e.message);}finally{busy=false;controls();}
  }
  get('consent').addEventListener('change',controls);get('refresh').addEventListener('click',()=>{name='';refresh();});
  get('close').addEventListener('click',()=>get('dialog').close());
  get('install').addEventListener('click',async()=>{if(get('install').disabled)return;busy=true;controls();
    try{await assistantApi('/semantic-history/install',assistantPost(csrf,{schema:root.dataset.schema,kind:root.dataset.kind,consent:true}));get('consent').checked=false;document.dispatchEvent(new CustomEvent('semantic-history-storage-ready'));}
    catch(e){say(e.message);return;}finally{busy=false;controls();}await refresh();
  });
  document.addEventListener('semantic-history-select',event=>{if(root.dataset.kind!==event.detail.kind)return;name=event.detail.name;refresh();root.scrollIntoView({block:'nearest',behavior:'smooth'});});
  document.addEventListener('semantic-history-changed',()=>refresh());refresh();
}
if(typeof document!=='undefined')document.querySelectorAll('[data-sem-history]').forEach(mountSemanticHistory);
