import React, {useState} from 'react';
import {createRoot} from 'react-dom/client';
import {flushSync} from 'react-dom';
import {ReactFlow, Handle, Position, Background, applyNodeChanges} from '@xyflow/react';
import ELK from 'elkjs/lib/elk.bundled.js';
import {graphModel, layoutGraph, highlight} from './graph-model.mjs';
import '@xyflow/react/dist/style.css';
import './ontology-flow.css';

function TableNode({data}) {
  return <div className={'dbc-flow-card' + (data.boundary ? ' is-boundary' : '')}>
    <Handle type="target" position={Position.Left} isConnectable={false}/>
    <div className="dbc-flow-card-heading"><span className="dbc-flow-symbol" aria-hidden="true">▤</span>
      <div><strong title={data.title}>{data.title}</strong><small title={data.name}>{data.name}</small></div></div>
    {data.columns.slice(0, 3).map(c => <div className="dbc-flow-column" key={c} title={c}>{c}</div>)}
    {data.columns.length > 3 && <div className="dbc-flow-column">+{data.columns.length - 3}</div>}
    <Handle type="source" position={Position.Right} isConnectable={false}/>
  </div>;
}
const nodeTypes = {table: TableNode};

export function mountGraph(container, data, callbacks) {
  const elk = new ELK(), root = createRoot(container);
  let disposed = false, generation = 0, selected = null, candidates = false, reveal = null;
  let model = {nodes: [], edges: []}, instance, render;
  function App() {
    const [view, setView] = useState(model);
    render = () => setView(highlight(model, selected));
    return <ReactFlow nodes={view.nodes} edges={view.edges} nodeTypes={nodeTypes}
      minZoom={.15} maxZoom={2} nodesConnectable={false} edgesReconnectable={false}
      deleteKeyCode={null} selectionOnDrag={false} zoomOnScroll={true} zoomOnDoubleClick={true}
      onDoubleClickCapture={event => {
        // Keep double-click zoom on the canvas; cards and edges retain their selection actions.
        if (event.target.closest('.react-flow__node, .react-flow__edge, .react-flow__panel'))
          event.stopPropagation();
      }}
      onInit={value => {instance = value;}}
      onNodesChange={changes => {
        const positions = changes.filter(c => c.type === 'position' || c.type === 'dimensions');
        model = {...model, nodes: applyNodeChanges(positions, model.nodes)}; render();
      }}
      onNodeClick={(_event, node) => callbacks.node(node.id)}
      onEdgeClick={(_event, edge) => callbacks.edge(edge.id)}
      onMove={(_event, viewport) => callbacks.zoom(viewport.zoom)}
      onPaneClick={() => {selected = null; render();}}
      defaultEdgeOptions={{interactionWidth: 20, labelStyle: {fontSize: 11, fill: '#52605d'},
        labelBgStyle: {fill: '#fff', fillOpacity: .94}, labelBgPadding: [6, 3], labelBgBorderRadius: 4}}>
      <Background color="#dce3df" gap={24} size={1}/>
    </ReactFlow>;
  }
  async function layout(reset = false) {
    const token = ++generation;
    const next = graphModel(data, {candidates, reveal});
    const previous = new Map(model.nodes.map(n => [n.id, n.position]));
    const laid = await layoutGraph(next, elk);
    if (disposed || token !== generation) return;
    model = {...laid, nodes: laid.nodes.map(n => ({...n,
      position: !reset && previous.has(n.id) ? previous.get(n.id) : n.position}))};
    flushSync(() => render());
    requestAnimationFrame(() => {if (!disposed && token === generation) instance?.fitView({padding: .18, maxZoom: 1});});
  }
  flushSync(() => root.render(<App/>));
  const ready = layout(true);
  return {
    ready,
    select(id) {
      selected = id;
      const r = data.relations.find(r => r.id === id);
      if (r && !model.edges.some(e => e.id === id) && r.status !== 'REJECTED') {
        reveal = id; layout().catch(callbacks.error);
      } else render();
    },
    candidates(value) {candidates = value; reveal = null; return layout();},
    layout() {return layout(true);},
    fit() {return instance?.fitView({padding: .18, maxZoom: 1});},
    focus() {
      const edge = model.edges.find(e => e.id === selected);
      const ids = edge ? [edge.source, edge.target] : selected ? [selected] : model.nodes.slice(0, 1).map(n => n.id);
      return instance?.fitView({nodes: ids.map(id => ({id})), padding: .35, maxZoom: 1});
    },
    zoomBy(factor) {return instance?.zoomTo(instance.getZoom() * factor);},
    resize() {},
    // Bundled ELK uses an in-process worker shim; there is no native worker to terminate.
    destroy() {disposed = true; generation++; root.unmount();}
  };
}
