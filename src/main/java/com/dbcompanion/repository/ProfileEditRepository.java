package com.dbcompanion.repository;

import com.dbcompanion.common.db.ProfileEditPolicy;
import com.dbcompanion.model.ProfileEdit.Target;
import com.dbcompanion.model.ProfileEdit.ObjectChoice;
import com.dbcompanion.model.ProfileEdit.ObjectChoices;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileEditRepository {
    private final JdbcTemplate jdbc;
    public ProfileEditRepository(JdbcTemplate jdbc) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(10);
    }
    public void save(String packageOwner, Target target, String value) {
        jdbc.update(ProfileEditPolicy.sql(packageOwner), statement -> {
            statement.setString(1,target.profile()); statement.setString(2,target.attribute()); statement.setString(3,value);
        });
    }
    public List<String> credentials() {
        return jdbc.queryForList("SELECT CREDENTIAL_NAME FROM SYS.USER_CREDENTIALS WHERE ENABLED = 'TRUE' ORDER BY CREDENTIAL_NAME", String.class);
    }
    public boolean credentialAvailable(String name) {
        return !jdbc.queryForList("SELECT CREDENTIAL_NAME FROM SYS.USER_CREDENTIALS WHERE ENABLED = 'TRUE' AND CREDENTIAL_NAME = ?", String.class, name).isEmpty();
    }
    public List<String> suggestions(String attribute, String provider) {
        if (provider == null || !java.util.Set.of("model", "region").contains(attribute)) return List.of();
        return jdbc.queryForList("""
                SELECT a.ATTRIBUTE_VALUE FROM USER_CLOUD_AI_PROFILE_ATTRIBUTES a
                WHERE a.ATTRIBUTE_NAME = ? AND EXISTS (
                  SELECT 1 FROM USER_CLOUD_AI_PROFILE_ATTRIBUTES p
                  WHERE p.PROFILE_NAME = a.PROFILE_NAME AND p.ATTRIBUTE_NAME = 'provider'
                    AND DBMS_LOB.SUBSTR(p.ATTRIBUTE_VALUE, 4000, 1) = ?)
                FETCH FIRST 200 ROWS ONLY
                """, String.class, attribute, provider).stream().filter(Objects::nonNull).distinct().sorted().toList();
    }
    public ObjectChoices objects(String owner, String filter) {
        var rows = jdbc.query("""
                SELECT DISTINCT OBJECT_NAME, OBJECT_TYPE FROM SYS.ALL_OBJECTS
                WHERE OWNER = ? AND SUBOBJECT_NAME IS NULL
                  AND OBJECT_TYPE IN ('TABLE', 'VIEW', 'MATERIALIZED VIEW', 'SYNONYM', 'PROPERTY GRAPH')
                  AND (? IS NULL OR INSTR(UPPER(OBJECT_NAME), UPPER(?)) > 0)
                ORDER BY OBJECT_NAME, OBJECT_TYPE FETCH FIRST 101 ROWS ONLY
                """, (row, index) -> new ObjectChoice(row.getString(1), row.getString(2)), owner, filter, filter);
        return new ObjectChoices(List.copyOf(rows.subList(0, Math.min(100, rows.size()))), rows.size() > 100);
    }
}
