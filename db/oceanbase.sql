-- OceanBase 4.4.1+ MySQL mode. The embedding model must return exactly 1024 dimensions.
-- Apply through your migration process before running with the prod profile.
CREATE TABLE IF NOT EXISTS runtime_resource (
 tenant_id VARCHAR(128) NOT NULL, kind VARCHAR(40) NOT NULL, resource_id VARCHAR(128) NOT NULL,
 payload LONGTEXT NOT NULL, revision BIGINT NOT NULL, updated_at VARCHAR(40) NOT NULL,
 PRIMARY KEY (tenant_id,kind,resource_id)
);
CREATE TABLE IF NOT EXISTS runtime_audit (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(128) NOT NULL, actor_id VARCHAR(256) NOT NULL,
 action_name VARCHAR(80) NOT NULL, target_id VARCHAR(256) NOT NULL, outcome VARCHAR(40) NOT NULL,
 created_at VARCHAR(40) NOT NULL,
 INDEX audit_tenant_time (tenant_id,created_at)
);
CREATE TABLE IF NOT EXISTS runtime_chunk (
 tenant_id VARCHAR(128) NOT NULL, kb_id VARCHAR(128) NOT NULL, document_id VARCHAR(128) NOT NULL,
 chunk_id VARCHAR(128) NOT NULL, generation_id VARCHAR(36) NOT NULL,
 content LONGTEXT NOT NULL, embedding VECTOR(1024) NOT NULL,
 PRIMARY KEY (tenant_id,kb_id,chunk_id),
 INDEX chunks_document (tenant_id,kb_id,document_id,generation_id)
);
-- Exact cosine search is the default. Enable ANN only after validating scalar filters and recall.
-- CREATE VECTOR INDEX runtime_chunk_cosine ON runtime_chunk(embedding) WITH (distance=cosine,type=hnsw,lib=vsag);
