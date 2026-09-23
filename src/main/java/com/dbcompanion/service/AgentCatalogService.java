package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.model.AgentCatalog.*;
import com.dbcompanion.repository.AgentCatalogRepository;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import static com.dbcompanion.common.exception.AppException.Code.*;

@Service
public class AgentCatalogService {
    private final SessionDataSource source;
    private final AgentCatalogRepository repository;
    private final TransactionTemplate read;

    public AgentCatalogService(SessionDataSource source, AgentCatalogRepository repository) {
        this.source = source; this.repository = repository;
        read = new TransactionTemplate(new DataSourceTransactionManager(source));
        read.setReadOnly(true); read.setTimeout(10);
    }

    public List<Item> teams(PoolSession session, String schema) {
        return query(session, schema, scope -> repository.items(scope, Kind.TEAM, null));
    }
    public List<Item> objects(PoolSession session,String schema,Kind kind){
        if(kind==null||kind==Kind.TEAM)throw new IllegalArgumentException("Agent, Task or Tool required");
        return query(session,schema,scope->repository.items(scope,kind,null));
    }
    public Component object(PoolSession session,String schema,Kind kind,String name){
        if(kind==null||kind==Kind.TEAM)throw new IllegalArgumentException("Agent, Task or Tool required");
        com.dbcompanion.common.db.TeamEditPolicy.identifier(name);
        return query(session,schema,scope->required(scope,kind,name));
    }

    public TeamPage team(PoolSession session, String schema, String name) {
        return query(session, schema, scope -> {
            var team = required(scope, Kind.TEAM, name);
            var assignments = AgentRelationships.assignments(value(team, "agents"));
            String supervisor = AgentRelationships.optionalName(value(team, "supervisor_agent"));
            var agentNames = new LinkedHashSet<String>();
            assignments.forEach(pair -> agentNames.add(pair.agent()));
            if (supervisor != null) agentNames.add(supervisor);
            var agents = components(scope, Kind.AGENT, List.copyOf(agentNames));
            var tasks = byName(repository.items(scope, Kind.TASK,
                    assignments.stream().map(Assignment::task).distinct().toList()));
            var rows = assignments.stream().map(pair -> new TaskRow(pair.agent(), pair.task(), tasks.get(pair.task()))).toList();
            return new TeamPage(team, agents, rows, supervisor);
        });
    }

    public TaskPage task(PoolSession session, String schema, String teamName, String taskName) {
        return query(session, schema, scope -> {
            var team = required(scope, Kind.TEAM, teamName);
            var assignments = AgentRelationships.assignments(value(team, "agents"));
            if (assignments.stream().noneMatch(pair -> pair.task().equals(taskName))) throw new AppException(AGENT_TASK_NOT_IN_TEAM);
            var task = required(scope, Kind.TASK, taskName);
            var tools = components(scope, Kind.TOOL, AgentRelationships.tools(value(task, "tools")));
            return new TaskPage(task, tools);
        });
    }

    private Component required(Scope scope, Kind kind, String name) {
        var found = components(scope, kind, List.of(name)).getFirst();
        if (found.info() == null) throw new AppException(AGENT_OBJECT_NOT_ACCESSIBLE);
        return found;
    }

    private List<Component> components(Scope scope, Kind kind, List<String> names) {
        var items = repository.items(scope, kind, names);
        var attributes = repository.attributes(scope, kind, items)
                .stream().collect(Collectors.groupingBy(Attribute::objectName));
        var indexed = byName(items);
        return names.stream().map(name -> {
            var item = indexed.get(name);
            return new Component(name, item, item == null ? List.of() : attributes.getOrDefault(item.name(), List.of()));
        }).toList();
    }

    private Map<String, Item> byName(List<Item> items) {
        return items.stream().collect(Collectors.toMap(Item::name, Function.identity()));
    }

    private String value(Component component, String name) {
        return component.attributes().stream().filter(attribute -> name.equalsIgnoreCase(attribute.name()))
                .map(Attribute::value).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private <T> T query(PoolSession session, String schema, Function<Scope, T> work) {
        synchronized (session) {
            var metadata = session.metadata();
            if (!schema.equals(metadata.selectedSchema())) throw new AppException(AGENT_SCHEMA_CHANGED);
            var scope = new Scope(schema, schema.equals(metadata.info().username()));
            source.bind(session.pool(), metadata.selectedSchema());
            try { return read.execute(status -> work.apply(scope)); }
            finally { source.clear(); }
        }
    }
}
