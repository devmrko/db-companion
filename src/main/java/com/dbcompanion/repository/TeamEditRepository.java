package com.dbcompanion.repository;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.ProfileHistorySql;
import com.dbcompanion.common.db.TeamEditPolicy;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.AgentCatalog.*;
import com.dbcompanion.model.TeamEdit.Target;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

@Repository
public class TeamEditRepository {
    private final JdbcTemplate jdbc;
    private final AgentCatalogRepository catalog;
    public TeamEditRepository(JdbcTemplate jdbc, AgentCatalogRepository catalog) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(10);this.catalog=catalog;
    }
    public Component current(Target target) {
        var scope=new Scope(target.schema(),true);
        var items=catalog.items(scope,Kind.TEAM,List.of(target.team()));
        return new Component(target.team(),items.isEmpty()?null:items.getFirst(),catalog.attributes(scope,Kind.TEAM,items));
    }
    public List<String> choices(Kind kind) {
        if (kind!=Kind.AGENT && kind!=Kind.TASK && kind!=Kind.TOOL) throw new IllegalArgumentException("Only agent/task/tool choices are supported");
        String view="USER_"+kind.view;
        var columns=jdbc.query("SELECT * FROM "+view+" WHERE 1=0",(ResultSetExtractor<AgentViewColumns>) rows -> {
            var names=new ArrayList<String>();var metadata=rows.getMetaData();
            for(int i=1;i<=metadata.getColumnCount();i++)names.add(metadata.getColumnLabel(i));
            return new AgentViewColumns(names);
        });
        String name=columns.required(kind.nameColumns());
        return jdbc.queryForList("SELECT "+name+" FROM "+view+" ORDER BY "+name,String.class);
    }
    public void validateReferences(Target target, Component current, String value, tools.jackson.databind.json.JsonMapper json) {
        String pairs="agents".equals(target.attribute())?value:TeamEditPolicy.value(current,"agents");
        var assignments=TeamEditPolicy.assignments(pairs,json);
        var scope=new Scope(target.schema(),true);
        var agentNames=new LinkedHashSet<String>(); var taskNames=new LinkedHashSet<String>();
        assignments.forEach(p -> {agentNames.add(p.agent());taskNames.add(p.task());});
        String supervisor="supervisor_agent".equals(target.attribute())?TeamEditPolicy.referenceName(value)
                :current.attributes().stream().filter(a -> a.name().equals("supervisor_agent")).map(Attribute::value)
                .filter(Objects::nonNull).map(TeamEditPolicy::referenceName).findFirst().orElse(null);
        if(supervisor!=null) {
            if(agentNames.contains(supervisor))throw new MetadataEditException(400,"Supervisor is also a worker",UiMessages.text("ui.bc110eed31b6", "Supervisor Agent는 Agent·Task 연결에 중복 지정할 수 없습니다."));
            agentNames.add(supervisor);
        }
        var agents=catalog.items(scope,Kind.AGENT,List.copyOf(agentNames));
        var tasks=catalog.items(scope,Kind.TASK,List.copyOf(taskNames));
        if(agents.size()!=agentNames.size()||tasks.size()!=taskNames.size())
            throw new MetadataEditException(409,"Team reference missing",UiMessages.text("ui.e5876d1bd2cc", "선택한 Agent 또는 Task가 더 이상 존재하지 않습니다. 최신 목록을 다시 불러와 주세요."));
        if(supervisor!=null) {
            String selected=supervisor;
            var info=agents.stream().filter(a -> a.name().equals(selected)).toList();
            boolean declared=catalog.attributes(scope,Kind.AGENT,info).stream()
                    .anyMatch(a -> a.name().equals("supervisor")&&"true".equalsIgnoreCase(a.value()));
            if(!declared)throw new MetadataEditException(400,"Supervisor agent required",UiMessages.text("ui.c0080b430c4b", "supervisor가 true인 Agent를 선택해 주세요."));
        }
    }
    public record Argument(int subprogram, int position, String name, String type, String mode) {}
    public static boolean compatible(List<Argument> arguments) {
        var groups=new HashMap<Integer,List<Argument>>();
        arguments.forEach(a -> groups.computeIfAbsent(a.subprogram(),k -> new ArrayList<>()).add(a));
        var names=Set.of("OBJECT_NAME","OBJECT_TYPE","ATTRIBUTE_NAME","ATTRIBUTE_VALUE");
        return groups.values().stream().anyMatch(rows -> rows.size()==4
                && rows.stream().map(Argument::name).collect(java.util.stream.Collectors.toSet()).equals(names)
                && rows.stream().allMatch(a -> a.position()>0&&"VARCHAR2".equals(a.type())&&"IN".equals(a.mode())));
    }
    public String packageOwner(String login) {
        var owners=jdbc.queryForList("""
                SELECT s.TABLE_OWNER FROM SYS.ALL_SYNONYMS s JOIN SYS.ALL_OBJECTS o
                  ON o.OWNER=s.TABLE_OWNER AND o.OBJECT_NAME=s.TABLE_NAME
                WHERE s.SYNONYM_NAME='DBMS_CLOUD_AI_AGENT' AND s.OWNER IN (?, 'PUBLIC') AND s.DB_LINK IS NULL
                  AND s.TABLE_NAME='DBMS_CLOUD_AI_AGENT' AND o.OBJECT_TYPE='PACKAGE'
                  AND o.STATUS='VALID' AND o.ORACLE_MAINTAINED='Y'
                ORDER BY CASE WHEN s.OWNER=? THEN 0 ELSE 1 END
                """,String.class,login,login);
        if(owners.isEmpty())throw incompatible();
        String owner=owners.getFirst();
        var arguments=jdbc.query("""
                SELECT SUBPROGRAM_ID, POSITION, ARGUMENT_NAME, DATA_TYPE, IN_OUT FROM SYS.ALL_ARGUMENTS
                WHERE OWNER=? AND PACKAGE_NAME='DBMS_CLOUD_AI_AGENT' AND OBJECT_NAME='SET_ATTRIBUTE' AND DATA_LEVEL=0
                ORDER BY SUBPROGRAM_ID, SEQUENCE
                """,(r,n)->new Argument(r.getInt(1),r.getInt(2),r.getString(3),r.getString(4),r.getString(5)),owner);
        if(!compatible(arguments))throw incompatible();
        return owner;
    }
    private MetadataEditException incompatible() {
        return new MetadataEditException(409,"Compatible SET_ATTRIBUTE not found",UiMessages.text("ui.ea6844004739", "현재 DB에서 지원하는 수정 API의 인자 구성을 확인하지 못했습니다. 객체는 변경하지 않았습니다."));
    }
    public static String sql(String owner) {
        return "DECLARE v_name VARCHAR2(128) := ?; v_attribute VARCHAR2(128) := ?; v_value VARCHAR2(32767) := ?; BEGIN "
                +ProfileHistorySql.object(owner,"DBMS_CLOUD_AI_AGENT")
                +".SET_ATTRIBUTE(object_name => v_name, object_type => 'TEAM', attribute_name => v_attribute, attribute_value => v_value); END;";
    }
    public void save(String owner, Target target, String value) {
        jdbc.update(sql(owner),statement -> {
            statement.setString(1,target.team());statement.setString(2,target.attribute());statement.setString(3,value);
        });
    }
}
