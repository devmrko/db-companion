import {assistantApi,assistantPost} from './ai-assistant.mjs';
import {t} from './i18n.mjs';
const label=key=>t('accountSetup.'+key,key);
export const accountCanCreate=(plan,consent,busy,attempted)=>!!plan&&consent&&!busy&&!attempted;
if(typeof document!=='undefined')document.querySelectorAll('[data-account-setup]').forEach(root=>{
  const get=k=>root.querySelector('[data-as-'+k+']'),post=(path,data)=>assistantApi('/db/accounts/'+path,assistantPost(get('csrf'),data));
  let plan=null,busy=false,attempted=false;
  const controls=()=>{root.querySelectorAll('button,input,select').forEach(n=>n.disabled=busy);get('create').disabled=!accountCanCreate(plan,get('consent').checked,busy,attempted);get('configure').disabled=busy||!attempted||!plan||!get('consent').checked;get('password').disabled=busy||attempted;};
  const invalidate=()=>{plan=null;attempted=false;get('review').hidden=true;get('password').value='';get('consent').checked=false;get('result').textContent='';controls();};
  const render=value=>{get('result').textContent=JSON.stringify(value,null,2);};
  async function run(action){if(busy)return;busy=true;controls();get('message').textContent=label('working');try{await action();}catch(e){get('message').textContent=e.message;get('consent').checked=false;}finally{busy=false;controls();}}
  for(const k of ['name','preset'])get(k).addEventListener('input',invalidate);
  get('consent').addEventListener('change',controls);
  get('form').addEventListener('submit',e=>{e.preventDefault();run(async()=>{invalidate();plan=await post('preview',{name:get('name').value,preset:get('preset').value});get('sql').textContent=plan.statements.join(';\n')+';';get('configure').hidden=true;get('review').hidden=false;get('message').textContent=label('review');});});
  get('status').addEventListener('click',()=>run(async()=>{render(await assistantApi('/db/accounts/status?'+new URLSearchParams({name:get('name').value})));get('message').textContent=label('checked');}));
  get('create').addEventListener('click',()=>{
    if(!accountCanCreate(plan,get('consent').checked,busy,attempted))return;
    const payload={token:plan.token,confirmed:true,password:get('password').value};get('password').value='';attempted=true;get('configure').hidden=false;
    run(async()=>{try{render(await post('create',payload));get('message').textContent=label('created');get('configure').hidden=true;}finally{payload.password='';}});
  });
  get('configure').addEventListener('click',()=>{if(!plan||!attempted||!get('consent').checked)return;run(async()=>{render(await post('configure',{token:plan.token,confirmed:true,password:null}));get('message').textContent=label('created');get('configure').hidden=true;});});
});
