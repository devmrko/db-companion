# Ontology relationship canvas

This component lets users explore table relationships and open the existing review
panel. React Flow renders the cards and interactions; ELK computes a left-to-right
layout. Only the relationship tab uses this component. Database writes remain in
the existing application review workflow.

Build from the repository root with Node.js and npm:

```sh
npm --prefix frontend ci --ignore-scripts
npm --prefix frontend run build
npm --prefix frontend test
npm --prefix frontend run check
```

Then build the Java application normally. Generated JS, CSS and license notices in
`src/main/resources/static/vendor/ontology-flow` are included in the repository so
Java-only builds can package them without downloading npm packages. Run `check`
after changing frontend source to detect stale generated files.

Open Ontology → Relationships. Confirmed relations are visible initially. Toggle
candidate relations, select a card or edge, or use the existing searchable list to
open its review panel. Moving cards changes only the current canvas. Relayout resets
positions. Candidate filtering keeps existing card positions and fits the viewport.

React Flow/React use MIT licenses; ELK.js uses EPL-2.0. The build copies the full
license notices of bundled dependencies. ELK is distributed unmodified; its source
reference is included in the generated notices. No Pro subscription or CDN is used.
