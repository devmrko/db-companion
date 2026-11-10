const typeLabel={StartNode:'입력',EndNode:'출력',ToolNode:'도구 실행',ParallelFlowNode:'병렬 처리',BranchingNode:'조건 분기',FlowNode:'하위 흐름',LlmNode:'LLM 호출',AgentNode:'Agent 호출'};
const fields=list=>(list||[]).map(f=>`${f.title} · ${f.type||'JSON'}`);
export function flowGraph(spec) {
  const refs=spec.$referenced_components||{};
  const resolve=n=>n?.$component_ref?refs[n.$component_ref]||n:n;
  function tasks(value,depth=0) {
    if(!value||typeof value!=='object'||depth>12)return [];
    if(value.$component_ref)return tasks(resolve(value),depth+1);
    const c=value.configuration;
    const own=c?.procedure_name?[[c.schema_name,c.package_name,c.procedure_name].filter(Boolean).join('.')]:[];
    return [...own,...Object.values(value).flatMap(v=>typeof v==='object'?tasks(v,depth+1):[])];
  }
  return {
    source:'AgentSpec에 선언된 흐름',
    nodes:(spec.nodes||[]).map(value=>{
      const node=resolve(value),type=node.component_type;
      const rows=type==='StartNode'?fields(spec.inputs):type==='EndNode'?fields(node.inputs||spec.outputs):[...(node.description?[node.description]:[]),...new Set(tasks(node))];
      if(type==='ParallelFlowNode')rows.unshift('하위 흐름을 병렬로 처리');
      if(type==='BranchingNode')rows.push(...(node.branches||[]).map(String));
      return {id:node.id||value.$component_ref,title:node.name||node.id||value.$component_ref,type:typeLabel[type]||type||'처리',rows};
    }),
    edges:(spec.control_flow_connections||[]).map(edge=>({from:resolve(edge.from_node)?.id||edge.from_node?.$component_ref,to:resolve(edge.to_node)?.id||edge.to_node?.$component_ref,label:edge.from_branch==='next'?'':edge.from_branch||''}))
  };
}
export function storedGraph(detail) {
  return {source:'DB 등록 정보 · 내부 단계 정의는 조회 API에서 제공하지 않습니다.',nodes:[
    {id:'input',type:'입력',title:'입력값',rows:fields(detail.inputs)},
    {id:'agent',type:'등록된 Agent 실행',title:detail.name,rows:[detail.description||'설명 미등록','내부 처리 경로는 실행 후 trace에서 확인']},
    {id:'output',type:'출력',title:'반환값',rows:fields(detail.outputs)}
  ],edges:[{from:'input',to:'agent'},{from:'agent',to:'output'}]};
}
export function traceGraph(trace) {
  const steps=(trace.roots||[]).flatMap(root=>root.children?.length?root.children:[root]);
  return {source:'이번 실행의 trace에서 확인된 경로',nodes:steps.map((node,index)=>({
    id:String(index),type:typeLabel[node.component_type]||node.component_type||'처리',title:node.node_name||node.node_id||'Node',
    rows:(node.children||[]).map(child=>`${child.component_type} · ${child.node_name||child.node_id}`)
  })),edges:steps.slice(1).map((_,i)=>({from:String(i),to:String(i+1)}))};
}
export function layoutFlow(graph) {
  const ranks=new Map(graph.nodes.map(node=>[node.id,0])),remaining=new Set(graph.nodes.map(node=>node.id));
  const edges=graph.edges.filter(e=>ranks.has(e.from)&&ranks.has(e.to));
  let level=0;
  while(remaining.size&&level<graph.nodes.length) {
    const ready=[...remaining].filter(id=>!edges.some(e=>e.to===id&&remaining.has(e.from)));
    if(!ready.length){for(const id of remaining)ranks.set(id,level++);break;}
    for(const id of ready){ranks.set(id,level);remaining.delete(id);}level++;
  }
  const counts=new Map();
  const height=Math.max(200,...graph.nodes.map(n=>100+n.rows.reduce((sum,row)=>sum+Math.max(1,Math.ceil(String(row).length/30))*21+8,0)));
  const nodes=graph.nodes.map(node=>{const rank=ranks.get(node.id),row=counts.get(rank)||0;counts.set(rank,row+1);return {...node,x:24+rank*328,y:24+row*(height+48),width:260,height};});
  return {nodes,edges,width:Math.max(300,...nodes.map(n=>n.x+n.width+24)),height:Math.max(200,...nodes.map(n=>n.y+n.height+30))};
}
export function renderFlow(host,graph) {
  host.replaceChildren();if(!graph.nodes.length){host.textContent='표시할 처리 단계가 없습니다.';return;}
  const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
  host.append(el('p',graph.source,'app-muted'));
  const viewport=el('div',undefined,'app-engine-flow-viewport');viewport.tabIndex=0;viewport.setAttribute('aria-label','입력·처리·출력 흐름도. 가로·세로 스크롤로 전체 경로를 확인하세요.');
  const canvas=el('div',undefined,'app-engine-flow-canvas'),layout=layoutFlow(graph);
  canvas.style.width=layout.width+'px';canvas.style.height=layout.height+'px';
  const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');svg.setAttribute('width',layout.width);svg.setAttribute('height',layout.height);svg.setAttribute('aria-hidden','true');
  for(const edge of layout.edges) {
    const a=layout.nodes.find(n=>n.id===edge.from),b=layout.nodes.find(n=>n.id===edge.to);
    const x=a.x+a.width,y=a.y+a.height/2,tx=b.x,ty=b.y+b.height/2,mid=(x+tx)/2;
    const path=document.createElementNS(ns,'path');path.setAttribute('d',`M${x},${y} C${mid},${y} ${mid},${ty} ${tx-8},${ty}`);path.setAttribute('class','app-engine-flow-edge');svg.append(path);
    const arrow=document.createElementNS(ns,'path');arrow.setAttribute('d',`M${tx-10},${ty-5} L${tx},${ty} L${tx-10},${ty+5}`);arrow.setAttribute('class','app-engine-flow-arrow');svg.append(arrow);
    if(edge.label){const text=document.createElementNS(ns,'text');text.textContent=edge.label;text.setAttribute('x',mid);text.setAttribute('y',(y+ty)/2-9);text.setAttribute('text-anchor','middle');text.setAttribute('class','app-engine-flow-label');svg.append(text);}
  }
  canvas.append(svg);
  for(const node of layout.nodes) {
    const card=el('section',undefined,'app-engine-flow-node');card.style.left=node.x+'px';card.style.top=node.y+'px';card.style.width=node.width+'px';card.style.height=node.height+'px';
    card.append(el('span',node.type,'app-badge'),el('h3',node.title));
    const list=el('ul');for(const row of node.rows)list.append(el('li',row));card.append(list);canvas.append(card);
  }
  viewport.append(canvas);host.append(viewport);
}
