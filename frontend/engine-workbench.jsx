import React,{useState,useMemo,useEffect,useRef} from 'react';
import {createRoot} from 'react-dom/client';
import {ReactFlow,Handle,Position,Background,Controls,applyNodeChanges} from '@xyflow/react';
import {newFlow,nodes as flowNodes,resolve,updateNode,removeNode,connect,validateFlow,editableFlow} from '../src/main/resources/static/js/engine-editor-model.mjs';
import '@xyflow/react/dist/style.css';
import './engine-workbench.css';

const labels={StartNode:'입력',ToolNode:'PL/SQL 도구',BranchingNode:'조건 분기',ParallelFlowNode:'병렬 처리',EndNode:'출력'};
function WorkflowNode({data,selected}){
  const n=data.node,ports=[...(n.branches||[]).map(b=>({id:'c:'+b,label:b,kind:'control'})),...(n.outputs||[]).map(f=>({id:'d:'+f.title,label:f.title,kind:'data'}))];
  return <div className={'ew-node'+(selected?' selected':'')}><Handle id="control" type="target" position={Position.Left} style={{top:24}}/><div className="ew-node-type">{labels[n.component_type]||n.component_type}</div><strong>{n.name}</strong>{n.description&&<small>{n.description}</small>}
    <div className="ew-ports"><div>{(n.inputs||[]).map((f,i)=><div key={f.title}><Handle id={'d:'+f.title} type="target" position={Position.Left} style={{top:86+i*24,background:'#718096'}}/>{f.title}<small>{f.type}</small></div>)}</div><div>{ports.map((p,i)=><div key={p.id}>{p.label}<Handle id={p.id} type="source" position={Position.Right} style={{top:86+i*24,background:p.kind==='control'?'#267a9a':'#718096'}}/></div>)}</div>
  </div></div>;
}
const nodeTypes={workflow:WorkflowNode};
function Fields({title,fields,onChange}){return <fieldset><legend>{title}</legend>{fields.map((f,i)=><div className="ew-field-row" key={i}><input aria-label={`${title} ${i+1} 이름`} value={f.title} placeholder="이름" onChange={e=>onChange(fields.map((v,j)=>j===i?{...v,title:e.target.value}:v))}/><select aria-label={`${title} ${i+1} 타입`} value={f.type} onChange={e=>onChange(fields.map((v,j)=>j===i?{...v,type:e.target.value}:v))}>{['string','number','integer','boolean','object','array'].map(t=><option key={t}>{t}</option>)}</select><button type="button" aria-label={`${title} ${i+1} 삭제`} onClick={()=>onChange(fields.filter((_,j)=>j!==i))}>×</button></div>)}<button type="button" onClick={()=>onChange([...fields,{title:'field_'+(fields.length+1),type:'string'}])}>+ 항목 추가</button></fieldset>;}
function Workbench({read,subscribe,apply,busy,tools}){
  const fileInput=useRef(null);
  async function importFile(event){
    const file=event.target.files?.[0];if(!file||busy())return;
    try{
      if(file.size>2000000)throw Error('AgentSpec 파일은 2MB 이하만 불러올 수 있습니다.');
      const text=await file.text();
      if(text.length>500000)throw Error('AgentSpec은 500,000자까지 지원합니다.');
      const value=JSON.parse(text);
      if(!value||value.component_type!=='Flow'||!Array.isArray(value.nodes)||!Array.isArray(value.inputs)||!value.agentspec_version)throw Error('노드 정의를 포함한 Flow AgentSpec JSON 파일이 필요합니다. DB 조회 요약은 원본 정의가 아닙니다.');
      const next=editableFlow(value);
      setPast(previous=>draft?[...previous.slice(-29),draft]:previous);
      setDraft(next);setStack([]);setSelected(null);setPickedEdge(null);setEdit(null);setPositions({});
      setMessage(file.name+' 파일을 캔버스에 불러왔습니다. 편집 후 실행에 적용하세요.');
    }catch(error){setMessage('불러오기 실패: '+error.message);}
    finally{event.target.value='';}
  }
  const [draft,setDraft]=useState(null),[stack,setStack]=useState([]),[selected,setSelected]=useState(null),[pickedEdge,setPickedEdge]=useState(null),[past,setPast]=useState([]),[positions,setPositions]=useState({}),[message,setMessage]=useState('노드를 추가하고 연결점을 끌어 흐름을 구성하세요.'),[catalog,setCatalog]=useState([]),[query,setQuery]=useState('');
  let current=draft;for(const entry of stack)current=resolve(current,flowNodes(current).find(n=>n.id===entry.id).subflows[entry.index]);
  const [edit,setEdit]=useState(null);
  const commit=fn=>{if(busy())return;try{const copy=structuredClone(draft);let f=copy;for(const entry of stack)f=resolve(f,flowNodes(f).find(n=>n.id===entry.id).subflows[entry.index]);fn(f);setPast(v=>[...v.slice(-29),draft]);setDraft(copy);setMessage('편집됨 · 저장하거나 실행에 적용하세요.');}catch(e){setMessage(e.message);}};
  const openNode=n=>{setSelected(n.id);setPickedEdge(null);setEdit(structuredClone(n));};
  const scopeKey=stack.map(s=>s.id+':'+s.index).join('/');
  const graph=useMemo(()=>{if(!current)return {nodes:[],edges:[]};const list=flowNodes(current).filter(Boolean);return {nodes:list.map((n,i)=>({id:n.id,type:'workflow',position:positions[scopeKey+'/'+n.id]||{x:70+i*300,y:100},data:{node:n},selected:n.id===selected})),edges:[...(current.control_flow_connections||[]).map((e,i)=>({id:'c:'+i,source:resolve(current,e.from_node)?.id,target:resolve(current,e.to_node)?.id,sourceHandle:'c:'+e.from_branch,targetHandle:'control',label:e.from_branch,style:{stroke:'#267a9a',strokeWidth:2}})),...(current.data_flow_connections||[]).map((e,i)=>({id:'d:'+i,source:resolve(current,e.source_node)?.id,target:resolve(current,e.destination_node)?.id,sourceHandle:'d:'+e.source_output,targetHandle:'d:'+e.destination_input,label:e.source_output+' → '+e.destination_input,style:{stroke:'#8293a3',strokeDasharray:'5 4'}}))].filter(e=>list.some(n=>n.id===e.source)&&list.some(n=>n.id===e.target)).map(e=>({...e,selected:e.id===pickedEdge}))};},[draft,scopeKey,positions,selected,pickedEdge]);
  const [visibleNodes,setVisibleNodes]=useState([]);useEffect(()=>setVisibleNodes(graph.nodes),[graph.nodes]);
  useEffect(()=>{tools().then(setCatalog).catch(e=>setMessage(e.message));},[]);
  useEffect(()=>{
    const receive=value=>{try{setDraft(value?editableFlow(value):null);setStack([]);setSelected(null);setPickedEdge(null);setEdit(null);setPast([]);setPositions({});setMessage(value?'등록 또는 실행 원본을 불러왔습니다. 노드를 선택해 편집하세요.':'등록 Agent를 선택하거나 새 흐름을 만드세요.');}catch(error){setDraft(null);setMessage(error.message);}};
    receive(read());return subscribe(receive);
  },[]);
  function start(){if(draft){setPast(v=>[...v.slice(-29),draft]);}setDraft(newFlow());setStack([]);setEdit(null);setSelected(null);setMessage('새 흐름 · 입력과 출력이 연결되어 있습니다.');}
  function add(type){if(!current){start();return;}if(type==='StartNode'&&flowNodes(current).some(n=>n.component_type==='StartNode')){setMessage('입력 노드는 이미 있습니다. 캔버스에서 선택해 수정하세요.');return;}
    const id='node-'+crypto.randomUUID(),n={id,name:labels[type],component_type:type,inputs:[],outputs:[],branches:type==='EndNode'?[]:['next']};
    if(type==='EndNode'){n.inputs=structuredClone(current.outputs||[]);n.outputs=structuredClone(n.inputs);n.branch_name='next';}
    if(type==='BranchingNode'){n.inputs=[{title:'decision',type:'string'}];n.branches=['yes','no'];n.mapping={yes:'yes',no:'no'};}
    if(type==='ParallelFlowNode')n.subflows=[];
    if(type==='ToolNode')n.tool={component_type:'BuiltinTool',id:id+'-tool',name:'PLSQL',executor_name:'Oracle In-DB Agent Executor',tool_version:'1.0',tool_type:'plsql_exec',configuration:{},inputs:[],outputs:[]};
    commit(f=>updateNode(f,n));setPositions(p=>({...p,[scopeKey+'/'+id]:{x:200,y:350}}));openNode(n);
  }
  function save(){if(!draft)return;const errors=validateFlow(draft);if(errors.length){setMessage(errors.join('\n'));return;}const url=URL.createObjectURL(new Blob([JSON.stringify(draft,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='agent-flow.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);setMessage('AgentSpec JSON 파일을 저장했습니다.');}
  function update(){const n=structuredClone(edit);if(['StartNode','EndNode'].includes(n.component_type))n.outputs=structuredClone(n.inputs);if(n.component_type==='ToolNode'){n.tool=structuredClone(resolve(current,n.tool));n.tool.inputs=structuredClone(n.inputs);n.tool.outputs=structuredClone(n.outputs);}commit(f=>updateNode(f,n));}
  function remove(){if(pickedEdge){const [kind,index]=pickedEdge.split(':');commit(f=>f[kind==='c'?'control_flow_connections':'data_flow_connections'].splice(Number(index),1));setPickedEdge(null);return;}if(selected){commit(f=>removeNode(f,selected));setSelected(null);setEdit(null);}}
  function chooseTool(t){const fields=t.arguments.map(a=>({title:a.name.toLowerCase(),type:a.type}));setEdit({...edit,name:t.name,inputs:fields.filter((_,i)=>t.arguments[i].direction.includes('IN')),outputs:fields.filter((_,i)=>t.arguments[i].direction.includes('OUT')),tool:{...resolve(current,edit.tool),configuration:{schema_name:t.owner,package_name:t.packageName,procedure_name:t.name}}});}
return <div className="ew-workbench"><div className="ew-toolbar"><button onClick={start}>새 흐름</button><button onClick={()=>{try{const f=read();if(!f){fileInput.current.click();return;}setDraft(editableFlow(f));setStack([]);setSelected(null);setEdit(null);}catch(e){setMessage(e.message);}}}>현재 실행 정의 편집</button><button disabled={!past.length} onClick={()=>{setDraft(past.at(-1));setPast(past.slice(0,-1));setStack([]);setEdit(null);setSelected(null);}}>되돌리기</button><span/><button disabled={!draft} onClick={save}>JSON 저장</button><button disabled={!draft} onClick={()=>setMessage(validateFlow(draft).join('\n')||'기본 검증 통과')}>검증</button><button disabled={!draft} onClick={()=>{const errors=validateFlow(draft);if(errors.length){setMessage(errors.join('\n'));return;}apply(structuredClone(draft));setMessage('실행 영역에 적용했습니다. 입력값 확인 후 실행하세요.');}}>실행에 적용</button></div>
    <div className="ew-import"><button type="button" onClick={()=>{if(!busy())fileInput.current.click();}}>AgentSpec JSON 파일 불러오기</button><input ref={fileInput} type="file" accept=".json,application/json" hidden onChange={importFile}/><span>등록 목록은 입출력 요약입니다. 내부 노드 편집에는 원본 JSON 파일이 필요합니다.</span></div>
    <div className="ew-status" role="status">{message}</div><div className="ew-layout">
    <aside className="ew-palette"><h3>노드 추가</h3>{Object.entries(labels).map(([type,label])=><button key={type} disabled={!draft} onClick={()=>add(type)}>+ {label}</button>)}<p>노드를 선택해 설정합니다. 연결점을 끌어 다른 노드에 연결하세요.</p><p>파란 선: 실행 순서<br/>점선: 데이터 전달</p><button disabled={!selected&&!pickedEdge} onClick={remove}>선택 항목 삭제</button>{stack.length>0&&<button onClick={()=>{setStack(stack.slice(0,-1));setSelected(null);setEdit(null);}}>상위 흐름</button>}</aside>
    <div className="ew-canvas"><ReactFlow nodes={visibleNodes} edges={graph.edges} nodeTypes={nodeTypes} onNodesChange={changes=>setVisibleNodes(ns=>applyNodeChanges(changes,ns))} onNodeDragStop={(_,n)=>setPositions(p=>({...p,[scopeKey+'/'+n.id]:n.position}))} onNodeClick={(_,n)=>openNode(n.data.node)} onEdgeClick={(_,e)=>{setPickedEdge(e.id);setSelected(null);setEdit(null);}} onConnect={c=>{const kind=c.sourceHandle?.startsWith('c:')?'control':'data';if(kind==='data'&&!c.targetHandle?.startsWith('d:')||kind==='control'&&c.targetHandle!=='control'){setMessage('실행 경로와 데이터 포트를 구분해 연결하세요.');return;}commit(f=>connect(f,kind,c.source,c.target,c.sourceHandle.slice(2),c.targetHandle?.slice(2)));}} deleteKeyCode={null} minZoom={0.25} maxZoom={2} fitView><Background/><Controls showInteractive={false}/></ReactFlow>{!draft&&<div className="ew-empty">‘새 흐름’으로 시작하거나 원본 AgentSpec을 불러오세요.</div>}</div>
    <aside className="ew-inspector"><h3>{edit?'노드 설정':pickedEdge?'연결 설정':'속성'}</h3>{pickedEdge&&<><p>선택한 연결을 삭제하고 포트를 다시 연결할 수 있습니다.</p><button onClick={remove}>연결 삭제</button></>}{!edit&&!pickedEdge&&<p>캔버스에서 노드를 선택하세요.</p>}{edit&&<><small>{labels[edit.component_type]} · {edit.id}</small><label>이름<input value={edit.name} onChange={e=>setEdit({...edit,name:e.target.value})}/></label><label>처리 내용<input value={edit.description||''} onChange={e=>setEdit({...edit,description:e.target.value})}/></label>
      {edit.component_type==='ToolNode'&&<><label>DB 도구 검색<input value={query} onChange={e=>setQuery(e.target.value)} placeholder="패키지 또는 프로시저"/></label><select aria-label="DB 프로시저 선택" value="" onChange={e=>chooseTool(catalog[Number(e.target.value)])}><option value="">프로시저 선택 · 인자 자동 설정</option>{catalog.map((t,i)=>({t,i})).filter(({t})=>(t.packageName+'.'+t.name).toLowerCase().includes(query.toLowerCase())).map(({t,i})=><option key={i} value={i}>{[t.packageName,t.name].filter(Boolean).join('.')}</option>)}</select><p>{Object.values(resolve(current,edit.tool)?.configuration||{}).filter(Boolean).join('.')}</p></>}
      <Fields title="입력" fields={edit.inputs||[]} onChange={inputs=>setEdit({...edit,inputs})}/>{!['StartNode','EndNode'].includes(edit.component_type)&&<Fields title="출력" fields={edit.outputs||[]} onChange={outputs=>setEdit({...edit,outputs})}/>}
      {edit.component_type==='BranchingNode'&&<fieldset><legend>조건 → 분기</legend>{Object.entries(edit.mapping||{}).map(([key,value],i)=><div className="ew-field-row" key={i}><input aria-label={'조건 '+(i+1)} value={key} onChange={e=>{const entries=Object.entries(edit.mapping);entries[i][0]=e.target.value;setEdit({...edit,mapping:Object.fromEntries(entries)});}}/><input aria-label={'분기 '+(i+1)} value={value} onChange={e=>{const mapping={...edit.mapping,[key]:e.target.value};setEdit({...edit,mapping,branches:[...new Set(Object.values(mapping))]});}}/></div>)}<button onClick={()=>{const branch='branch_'+Object.keys(edit.mapping).length;setEdit({...edit,mapping:{...edit.mapping,[branch]:branch},branches:[...edit.branches,branch]});}}>+ 조건 추가</button></fieldset>}
      {edit.component_type==='ParallelFlowNode'&&<fieldset><legend>병렬 하위 흐름</legend>{(resolve(current,current.nodes.find(n=>resolve(current,n)?.id===edit.id))?.subflows||[]).map((s,i)=><div key={i}><button onClick={()=>{setStack([...stack,{id:edit.id,index:i}]);setEdit(null);setSelected(null);}}>{resolve(current,s)?.name||'하위 흐름'} 편집</button></div>)}<button onClick={()=>{commit(f=>{const n=flowNodes(f).find(n=>n.id===edit.id);n.subflows.push(newFlow('sub-'+crypto.randomUUID()));});setEdit(null);}}>+ 하위 흐름 추가</button></fieldset>}
      {edit.component_type==='BranchingNode'&&Object.keys(edit.mapping||{}).map(key=><button key={key} onClick={()=>{const mapping={...edit.mapping};delete mapping[key];setEdit({...edit,mapping,branches:[...new Set(Object.values(mapping))]});}}>{key} 조건 삭제</button>)}
      {edit.component_type==='ParallelFlowNode'&&(edit.subflows||[]).map((_,i)=><button key={i} onClick={()=>{commit(f=>{flowNodes(f).find(n=>n.id===edit.id).subflows.splice(i,1);});setEdit(null);}}>하위 흐름 {i+1} 삭제</button>)}
      <button className="ew-primary" onClick={update}>노드 설정 적용</button><button onClick={remove}>노드 삭제</button></>}</aside></div></div>;
}
export function mountWorkbench(host,options){const root=createRoot(host);root.render(<Workbench {...options}/>);return ()=>root.unmount();}
