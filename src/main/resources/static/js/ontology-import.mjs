import {t} from './i18n.mjs';

const label=(key,...args)=>t('ontology.import.'+key,key,...args);

// UI queue only: authentication, selected schema and skip-existing checks live on the server.
export class ImportQueue {
  constructor(names) {
    this.items=[...new Set(names)].map(table=>({table,outcome:'PENDING',revision:null,triples:null}));
    this.index=0;
    this.active=false;
    this.stopped=false;
    this.started=false;
  }
  next() {
    if(this.active||this.stopped||this.index>=this.items.length)return null;
    this.started=true;
    this.active=true;
    this.items[this.index].outcome='RUNNING';
    return this.items[this.index].table;
  }
  accept(result) {
    const item=this.items[this.index];
    if(!this.active||result?.table!==item.table||!['IMPORTED','SKIPPED'].includes(result.outcome)
      ||!Number.isInteger(result.revision)||result.revision<1||!Number.isInteger(result.triples)||result.triples<1)
      throw new Error(label('invalidResponse'));
    Object.assign(item,{outcome:result.outcome,revision:result.revision,triples:result.triples});
    this.active=false;
    this.index++;
  }
  fail() {
    if(this.active)this.items[this.index].outcome='UNKNOWN';
    this.active=false;
    this.stopped=true;
  }
  stop() {this.stopped=true;}
  get complete() {return this.index===this.items.length;}
}

export function metadataImporter(dialog,{schema,post,completed}) {
  const get=key=>dialog.querySelector(`[data-import-${key}]`);
  let queue=null,running=false,cells=[];
  function render() {
    queue.items.forEach((item,i)=>{
      cells[i][0].textContent=label(item.outcome);
      cells[i][1].textContent=item.revision==null?'—':`v${item.revision}`;
      cells[i][2].textContent=item.triples??'—';
    });
    const imported=queue.items.filter(i=>i.outcome==='IMPORTED').length;
    const skipped=queue.items.filter(i=>i.outcome==='SKIPPED').length;
    const state=queue.complete?'finished':queue.stopped?(running?'stopping':'stopped'):running?'running':'ready';
    get('status').textContent=label(state)+' · '+label('counts',queue.index,queue.items.length,imported,skipped);
    get('progress').max=Math.max(queue.items.length,1);
    get('progress').value=queue.index;
    get('start').disabled=running||queue.started||!queue.items.length;
    get('stop').hidden=!running;
    get('stop').disabled=queue.stopped;
    get('close').disabled=running;
  }
  function showError(value) {get('error').textContent=value;get('error').hidden=!value;}
  get('start').addEventListener('click',async()=>{
    if(running||queue.started||!queue.items.length)return;
    running=true;
    let table;
    while((table=queue.next())!==null) {
      render();
      try {
        const result=await post('/capture/missing',{schema,table,confirmed:true});
        queue.accept(result);
      }catch(ex) {
        queue.fail();
        showError(`${schema}.${table} · ${ex.message}\n${label('uncertain')}`);
      }
    }
    // Keep the dialog locked until the saved list has also been refreshed.
    try{await completed();}catch(ex){showError((get('error').textContent+'\n'+label('refreshError')+' · '+ex.message).trim());}
    running=false;
    render();
  });
  get('stop').addEventListener('click',()=>{queue.stop();render();});
  get('close').addEventListener('click',()=>{if(!running)dialog.close();});
  dialog.addEventListener('cancel',e=>{if(running)e.preventDefault();});
  return {
    open(names,total,existing) {
      if(running)return;
      queue=new ImportQueue(names);
      showError('');
      get('scope').textContent=schema+' · '+label('scope',total,existing,queue.items.length);
      cells=[];
      const fragment=document.createDocumentFragment();
      for(const item of queue.items) {
        const row=document.createElement('tr'),name=document.createElement('td');
        name.textContent=item.table;
        row.append(name);
        const values=Array.from({length:3},()=>document.createElement('td'));
        row.append(...values);cells.push(values);fragment.append(row);
      }
      get('rows').replaceChildren(fragment);
      render();
      dialog.showModal();
    }
  };
}
