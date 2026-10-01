import {metadataGraphSql} from './sql-help-metadata-source.mjs';
const pair=(ko,en)=>({ko,en});
const doc='https://docs.oracle.com/en/database/oracle/property-graph/26.2/spgdg/creating-sql-property-graph.html';
const project=name=>metadataGraphSql[name].replaceAll('@@CATALOG@@','"<SCHEMA>"."DBC_ONTOLOGY_CATALOG"').replaceAll('@@OWNER@@',"'<SCHEMA>'").replaceAll('@@SEQS@@','<CONFIRMED_CATALOG_SEQ_LIST>').replaceAll('@@LINK_FILTER@@',"c.SEQ=<SOURCE_SEQ> AND l.id='<APPROVED_RELATION_ID>'").replaceAll('@@FK_FILTER@@','1=0');
export const metadataGraphHelp=[
  {id:'metadata-graph',title:pair('메타데이터 관계 Property Graph','Metadata relationship Property Graph'),purpose:pair('원본 행 대신 저장된 테이블·뷰·컬럼 정의를 노드로 만듭니다. 앱 미리보기에서 실제 저장 버전과 승인 관계 목록을 고정한 SQL을 확인하세요.','Nodes represent saved objects and columns, not business rows. App preview pins actual catalog revisions and reviewed relationship IDs.'),risk:'ddl',doc,source:'src/main/java/com/dbcompanion/model/MetadataGraph.java',sql:`-- Run as the owner. Required: CREATE VIEW and CREATE PROPERTY GRAPH.
-- Use the concrete SQL shown by app preview: it verifies revision/approval freshness.
-- These examples require substitution; never infer approved IDs from candidates.
CREATE VIEW "<SCHEMA>"."<GRAPH>_NODES" AS
${project('nodes')};

CREATE VIEW "<SCHEMA>"."<GRAPH>_EDGES" AS
${project('edges')};

CREATE PROPERTY GRAPH "<SCHEMA>"."<GRAPH>"
VERTEX TABLES ("<SCHEMA>"."<GRAPH>_NODES" AS META_NODE KEY (NODE_ID)
 LABEL METADATA_NODE PROPERTIES (NODE_KIND,OWNER_NAME,OBJECT_NAME,COLUMN_NAME,DATA_TYPE,REVISION))
EDGE TABLES ("<SCHEMA>"."<GRAPH>_EDGES" AS META_EDGE KEY (EDGE_ID)
 SOURCE KEY (SOURCE_ID) REFERENCES META_NODE (NODE_ID)
 DESTINATION KEY (TARGET_ID) REFERENCES META_NODE (NODE_ID)
 LABEL METADATA_LINK PROPERTIES (EDGE_KIND,RELATION_ID,RELATION_LABEL,CONDITION_TEXT,ORIGIN,RELATION_STATE,MAPPING_POSITION,MAPPING_COUNT))
OPTIONS (TRUSTED MODE);`,note:pair('DBC_ONTOLOGY_CATALOG의 저장 JSON을 Oracle JSON_TABLE로 투영합니다. 네이티브 RDF를 직접 변환하는 구문은 아니며 RDF에서 찾은 후보도 앱에서 저장·승인한 관계를 통해 연결됩니다. 원본 업무 행·PK·AI는 사용하지 않습니다. 키는 저장 SEQ와 컬럼 순번으로 구성하며 미리보기에서 실제 고유성과 수를 확인합니다. 복합 관계의 컬럼 쌍은 RELATION_ID·MAPPING_COUNT로 묶고 조건은 실행하지 않습니다. 생성 버전이 고정되므로 갱신은 새 이름으로 생성합니다. DDL 일부 실패는 롤백되지 않으며 자동 삭제·덮어쓰기·권한 부여하지 않습니다.','Oracle JSON_TABLE projects stored catalog JSON, not native RDF directly. RDF candidates enter through reviewed catalog links. No business rows, source PKs or AI are used. Keys use pinned sequence/column ordinals; preview checks actual counts and uniqueness. RELATION_ID/MAPPING_COUNT group composite pairs; conditions are not executed. Revisions are fixed; refresh with a new name. Partial DDL cannot be rolled back; no automatic cleanup, overwrite or grants.')},
  {id:'metadata-graph-read',title:pair('메타데이터 그래프 조회','Query the metadata graph'),purpose:pair('포함 관계와 승인된 컬럼 매핑을 SQL로 조회합니다. 업무 데이터 조회 결과가 아닙니다.','Read containment and approved column mappings, not business data.'),risk:'read',doc,source:'src/main/java/com/dbcompanion/model/MetadataGraph.java',sql:`SELECT * FROM GRAPH_TABLE ("<SCHEMA>"."<GRAPH>"
 MATCH (a IS METADATA_NODE)-[e IS METADATA_LINK]->(b IS METADATA_NODE)
 COLUMNS (a.OBJECT_NAME AS SOURCE_OBJECT,a.COLUMN_NAME AS SOURCE_COLUMN,
 e.EDGE_KIND AS EDGE_KIND,e.RELATION_ID AS RELATION_ID,
 e.RELATION_LABEL AS RELATION_LABEL,e.CONDITION_TEXT AS CONDITION_TEXT,
 e.MAPPING_POSITION AS MAPPING_POSITION,e.MAPPING_COUNT AS MAPPING_COUNT,
 b.OBJECT_NAME AS TARGET_OBJECT,b.COLUMN_NAME AS TARGET_COLUMN))
FETCH FIRST 100 ROWS ONLY;`,note:pair('CONTAINS는 테이블·뷰의 컬럼 포함, RELATES_TO는 확정된 객체 간 관계, MAPS_TO는 그 관계에 속한 컬럼 쌍입니다. 서로 다른 원본 테이블을 이 SQL이 조인하지 않습니다.','CONTAINS is object-column containment, RELATES_TO a confirmed object relationship, MAPS_TO a column pair in that relationship. This SQL does not join source business tables.')}
];
