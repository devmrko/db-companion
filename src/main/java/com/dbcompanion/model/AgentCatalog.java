package com.dbcompanion.model;

import java.util.List;

public final class AgentCatalog {
    private AgentCatalog() {}

    public enum Kind {
        TEAM("AI_AGENT_TEAMS", "AI_AGENT_TEAM_ATTRIBUTES", "TEAM"),
        AGENT("AI_AGENTS", "AI_AGENT_ATTRIBUTES", "AGENT"),
        TASK("AI_AGENT_TASKS", "AI_AGENT_TASK_ATTRIBUTES", "TASK"),
        TOOL("AI_AGENT_TOOLS", "AI_AGENT_TOOL_ATTRIBUTES", "TOOL");

        public final String view;
        public final String attributesView;
        public final String prefix;
        Kind(String view, String attributesView, String prefix) {
            this.view = view; this.attributesView = attributesView; this.prefix = prefix;
        }

        public String[] nameColumns() {
            return this == AGENT ? new String[]{"AGENT_NAME", "NAME"}
                    : new String[]{"AGENT_" + prefix + "_NAME", prefix + "_NAME", "NAME"};
        }

        public String[] idColumns() {
            return this == AGENT ? new String[]{"AGENT_ID", "ID"}
                    : new String[]{"AGENT_" + prefix + "_ID", prefix + "_ID", "ID"};
        }
    }

    public record Scope(String schema, boolean own) {}
    public record Item(String id, String name, String description, String status, String created, String modified) {}
    public record Attribute(String objectName, String name, String value, String modified) {}
    public record Component(String name, Item info, List<Attribute> attributes) {}
    public record Assignment(String agent, String task) {}
    public record TaskRow(String agent, String name, Item info) {}
    public record TeamPage(Component team, List<Component> agents, List<TaskRow> tasks, String supervisor) {}
    public record TaskPage(Component task, List<Component> tools) {}
}
