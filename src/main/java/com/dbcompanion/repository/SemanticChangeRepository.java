package com.dbcompanion.repository;

import com.dbcompanion.common.db.AppRecordSql;
import com.dbcompanion.model.SemanticChange;
import com.dbcompanion.model.SemanticChange.Item;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SemanticChangeRepository {
    private final JdbcTemplate jdbc;
    public SemanticChangeRepository(JdbcTemplate template){jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(10);}
    public List<Item> page(String owner,String kind,String name){
        if(!name.isEmpty())SemanticChange.target(kind,name);
        return jdbc.query("SELECT RECORD_ID,ACTOR,TO_CHAR(RECORDED_AT,'YYYY-MM-DD HH24:MI:SS TZH:TZM'),STATE,"
            +"JSON_VALUE(PAYLOAD,'$.kind'),JSON_VALUE(PAYLOAD,'$.name'),JSON_VALUE(PAYLOAD,'$.operation') FROM "+AppRecordSql.table(owner)
            +" WHERE RECORD_TYPE=? AND JSON_VALUE(PAYLOAD,'$.kind')=?"
            +(name.isEmpty()?"":" AND JSON_VALUE(PAYLOAD,'$.name')=?")+" ORDER BY SEQ DESC FETCH FIRST 30 ROWS ONLY",
            (r,n)->new Item(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)),
            name.isEmpty()?new Object[]{SemanticChange.TYPE,kind}:new Object[]{SemanticChange.TYPE,kind,name});
    }
}
