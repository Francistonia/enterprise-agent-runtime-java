CREATE TABLE IF NOT EXISTS runtime_resource (
  tenant_id VARCHAR(128) NOT NULL,
  kind VARCHAR(40) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  payload TEXT NOT NULL,
  revision BIGINT NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (tenant_id, kind, resource_id)
);
CREATE TABLE IF NOT EXISTS runtime_audit (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(128) NOT NULL,
  actor_id VARCHAR(256) NOT NULL,
  action_name VARCHAR(80) NOT NULL,
  target_id VARCHAR(256) NOT NULL,
  outcome VARCHAR(40) NOT NULL,
  created_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS runtime_chunk (
  tenant_id VARCHAR(128) NOT NULL,
  kb_id VARCHAR(128) NOT NULL,
  document_id VARCHAR(128) NOT NULL,
  chunk_id VARCHAR(128) NOT NULL,
  generation_id VARCHAR(36) NOT NULL,
  content TEXT NOT NULL,
  embedding TEXT NOT NULL,
  PRIMARY KEY (tenant_id, kb_id, chunk_id)
);
