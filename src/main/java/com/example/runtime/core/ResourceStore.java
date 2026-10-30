package com.example.runtime.core;

import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ResourceStore {
    public record Versioned<T>(T value, long revision) {}
    private final JdbcTemplate jdbc;
    private final Json json;
    public ResourceStore(JdbcTemplate jdbc, Json json) { this.jdbc = jdbc; this.json = json; }
    public <T> T get(String tenant, String kind, String id, Class<T> type) {
        return versioned(tenant, kind, id, type).value();
    }
    public <T> Versioned<T> versioned(String tenant, String kind, String id, Class<T> type) {
        var rows = jdbc.query("SELECT payload,revision FROM runtime_resource WHERE tenant_id=? AND kind=? AND resource_id=?",
                (rs, n) -> new Versioned<>(json.read(rs.getString(1), type), rs.getLong(2)), tenant, kind, id);
        if (rows.isEmpty()) throw Problem.missing();
        return rows.get(0);
    }
    public <T> List<T> list(String tenant, String kind, Class<T> type) {
        return jdbc.query("SELECT payload FROM runtime_resource WHERE tenant_id=? AND kind=? ORDER BY resource_id",
                (rs, n) -> json.read(rs.getString(1), type), tenant, kind);
    }
    public void create(String tenant, String kind, String id, Object value) {
        if (id == null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}")) throw Problem.bad("Invalid resource ID");
        try {
            jdbc.update("INSERT INTO runtime_resource VALUES (?,?,?,?,?,?)",
                    tenant, kind, id, json.write(value), 1, Instant.now().toString());
        } catch (DuplicateKeyException e) { throw Problem.conflict("Resource already exists"); }
    }
    public void save(String tenant, String kind, String id, Object value, long expected) {
        int changed = jdbc.update("UPDATE runtime_resource SET payload=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND kind=? AND resource_id=? AND revision=?",
                json.write(value), Instant.now().toString(), tenant, kind, id, expected);
        if (changed != 1) throw Problem.conflict("Resource changed concurrently; reload and retry");
    }
    @Transactional
    public <T> T modify(String tenant, String kind, String id, Class<T> type, UnaryOperator<T> change) {
        try {
            jdbc.queryForObject("SELECT revision FROM runtime_resource WHERE tenant_id=? AND kind=? AND resource_id=? FOR UPDATE",
                    Long.class, tenant, kind, id);
        } catch(org.springframework.dao.EmptyResultDataAccessException e) { throw Problem.missing(); }
        var old = versioned(tenant, kind, id, type);
        T value = change.apply(old.value());
        save(tenant, kind, id, value, old.revision());
        return value;
    }
    public void delete(String tenant, String kind, String id) {
        jdbc.update("DELETE FROM runtime_resource WHERE tenant_id=? AND kind=? AND resource_id=?", tenant, kind, id);
    }
}
