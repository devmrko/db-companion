package com.dbcompanion.repository;

import com.dbcompanion.model.AgentEngine;
import java.sql.Types;
import java.sql.Clob;
import java.sql.SQLException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentEngineRepository {
    private final JdbcTemplate jdbc;
    public AgentEngineRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record ToolArgument(String name, String type, String direction) {}
    public record ToolChoice(String owner, String packageName, String name, java.util.List<ToolArgument> arguments) {}
    public java.util.List<ToolChoice> tools() {
        var rows = jdbc.queryForList("SELECT USER OWNER,PACKAGE_NAME,OBJECT_NAME,ARGUMENT_NAME,DATA_TYPE,IN_OUT,POSITION,SUBPROGRAM_ID,OVERLOAD FROM USER_ARGUMENTS WHERE DATA_LEVEL=0 ORDER BY PACKAGE_NAME,OBJECT_NAME,SUBPROGRAM_ID,POSITION");
        var groups = new java.util.LinkedHashMap<String,java.util.List<java.util.Map<String,Object>>>();
        for (var row : rows) groups.computeIfAbsent(row.get("PACKAGE_NAME")+":"+row.get("OBJECT_NAME"), k -> new java.util.ArrayList<>()).add(row);
        var result = new java.util.ArrayList<ToolChoice>();
        var types = java.util.Map.of("VARCHAR2","string","CHAR","string","CLOB","string","NUMBER","number","BINARY_INTEGER","integer","PLS_INTEGER","integer","BOOLEAN","boolean");
        for (var group : groups.values()) {
            if (group.stream().anyMatch(r -> r.get("OVERLOAD") != null || ((Number)r.get("POSITION")).intValue()==0 || !types.containsKey(String.valueOf(r.get("DATA_TYPE"))))) continue;
            var first = group.getFirst();
            var args = group.stream().map(r -> new ToolArgument((String)r.get("ARGUMENT_NAME"),types.get(String.valueOf(r.get("DATA_TYPE"))),(String)r.get("IN_OUT"))).toList();
            result.add(new ToolChoice((String)first.get("OWNER"),(String)first.get("PACKAGE_NAME"),(String)first.get("OBJECT_NAME"),args));
        }
        return java.util.List.copyOf(result);
    }
    public boolean supported() {
        return signature("EXECUTE_IMMEDIATE", java.util.List.of("0:null:CLOB:OUT", "1:AGENT_SPEC:CLOB:IN", "2:INPUTS:CLOB:IN", "3:TRACES:CLOB:OUT"));
    }
    public String databaseIdentity() {
        return jdbc.queryForObject("SELECT SYS_CONTEXT('USERENV','DB_UNIQUE_NAME') || ':' || SYS_CONTEXT('USERENV','CON_NAME') FROM SYS.DUAL", String.class);
    }
    public boolean catalogSupported() {
        return signature("LIST_AGENTS", java.util.List.of("1:AGENTS:CLOB:OUT"))
                && signature("DESCRIBE_AGENT", java.util.List.of("1:AGENT_NAME:VARCHAR2:IN", "2:AGENT_DETAILS:CLOB:OUT"));
    }
    public boolean storedSupported() {
        return signature("EXECUTE_AGENT", java.util.List.of("0:null:CLOB:OUT", "1:AGENT_NAME:VARCHAR2:IN", "2:INPUTS:CLOB:IN", "3:TRACES:CLOB:OUT"));
    }
    private boolean signature(String method, java.util.List<String> expected) {
        var rows = jdbc.queryForList("SELECT SUBPROGRAM_ID, POSITION, ARGUMENT_NAME, DATA_TYPE, IN_OUT FROM SYS.ALL_ARGUMENTS "
                + "WHERE OWNER='SYS' AND PACKAGE_NAME='DBMS_AI_AGENT' AND OBJECT_NAME=? AND DATA_LEVEL=0 ORDER BY SUBPROGRAM_ID, POSITION", method);
        var signatures = new java.util.HashMap<Object, java.util.List<String>>();
        for (var row : rows) signatures.computeIfAbsent(row.get("SUBPROGRAM_ID"), key -> new java.util.ArrayList<>())
                .add(row.get("POSITION") + ":" + row.get("ARGUMENT_NAME") + ":" + row.get("DATA_TYPE") + ":" + row.get("IN_OUT"));
        return signatures.values().stream().anyMatch(expected::equals);
    }
    public String list() {
        return jdbc.execute((ConnectionCallback<String>) connection -> {
            try (var call = connection.prepareCall("BEGIN SYS.DBMS_AI_AGENT.LIST_AGENTS(?); END;")) {
                call.setQueryTimeout(15); call.registerOutParameter(1, Types.CLOB); call.execute();
                return read(call.getClob(1));
            }
        });
    }
    public String describe(String name) {
        return jdbc.execute((ConnectionCallback<String>) connection -> {
            try (var call = connection.prepareCall("BEGIN SYS.DBMS_AI_AGENT.DESCRIBE_AGENT(?, ?); END;")) {
                call.setQueryTimeout(15); call.setString(1, name); call.registerOutParameter(2, Types.CLOB); call.execute();
                return read(call.getClob(2));
            }
        });
    }
    public AgentEngine.Result runStored(AgentEngine.StoredRun input) {
        return jdbc.execute((ConnectionCallback<AgentEngine.Result>) connection -> {
            try (var call = connection.prepareCall("BEGIN ? := SYS.DBMS_AI_AGENT.EXECUTE_AGENT(?, ?, ?); END;")) {
                call.setQueryTimeout(120); call.registerOutParameter(1, Types.CLOB);
                call.setString(2, input.name()); call.setClob(3, new java.io.StringReader(input.inputs()));
                call.registerOutParameter(4, Types.CLOB); call.execute();
                return new AgentEngine.Result(read(call.getClob(1)), read(call.getClob(4)));
            }
        });
    }
    public AgentEngine.Result run(AgentEngine.Run input) {
        return jdbc.execute((ConnectionCallback<AgentEngine.Result>) connection -> {
            try (var call = connection.prepareCall("BEGIN ? := SYS.DBMS_AI_AGENT.EXECUTE_IMMEDIATE(?, ?, ?); END;")) {
                call.setQueryTimeout(120);
                call.registerOutParameter(1, Types.CLOB);
                call.setClob(2, new java.io.StringReader(input.spec()));
                call.setClob(3, new java.io.StringReader(input.inputs()));
                call.registerOutParameter(4, Types.CLOB);
                call.execute();
                return new AgentEngine.Result(read(call.getClob(1)), read(call.getClob(4)));
            }
        });
    }
    private static String read(Clob value) throws SQLException {
        if (value == null) return "";
        try {
            if (value.length() > 2_000_000) throw new SQLException("Agent Engine output exceeds UI limit");
            return value.getSubString(1, (int) value.length());
        } finally { value.free(); }
    }
}
