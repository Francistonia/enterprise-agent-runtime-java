package com.example.runtime.core;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class Audit {
    private final JdbcTemplate jdbc;
    public Audit(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void record(Actor actor, String action, String target, String outcome) {
        jdbc.update("INSERT INTO runtime_audit VALUES (?,?,?,?,?,?,?)", UUID.randomUUID().toString(),
                actor.tenant(), actor.subject(), action, target, outcome, Instant.now().toString());
    }
    public List<Map<String, Object>> list(Actor actor) {
        actor.require("audit:read");
        return jdbc.queryForList("SELECT * FROM runtime_audit WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200", actor.tenant());
    }
}
