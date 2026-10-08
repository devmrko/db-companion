package com.dbcompanion.repository;

import com.dbcompanion.model.BusinessGlossary;
import com.dbcompanion.model.VectorSearch;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Only registered local ONNX models. Never accepts a provider URL, credential, or SQL expression. */
@Repository
public class GlossaryDocumentRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public GlossaryDocumentRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(10); this.json = json;
    }
    public double[] embed(VectorSearch.Model model, String text) {
        String sql = "SELECT VECTOR_SERIALIZE(VECTOR_EMBEDDING(" + VectorSearch.quote(model.owner()) + "." + VectorSearch.quote(model.name()) + " USING ? AS DATA) RETURNING CLOB) FROM SYS.DUAL";
        String value = jdbc.queryForObject(sql, String.class, text);
        if (value == null || value.length() > 200_000) throw BusinessGlossary.invalid();
        double[] vector = json.readValue(value, double[].class);
        if (vector == null || vector.length == 0 || vector.length > 8192) throw BusinessGlossary.invalid();
        com.dbcompanion.model.GlossaryDocument.cosine(vector, vector);
        return vector;
    }
}
