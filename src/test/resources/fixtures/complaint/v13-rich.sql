-- Synthetic schema-preservation fixture, not real credentials or signed publication evidence.
INSERT INTO users(id,email,password_hash,role,enabled,created_at,updated_at) VALUES
('10000000-0000-4000-8000-000000000001','migration-admin@example.test',
 '{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy','ADMIN',true,'2026-01-02T03:04:05Z','2026-01-03T03:04:05Z'),
('10000000-0000-4000-8000-000000000002','migration-user@example.test',
 '{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy','USER',false,'2026-01-02T03:04:05Z','2026-01-03T03:04:05Z');

INSERT INTO source_configs(id,api,display_name,language,engine,status,position,base_url,adult,created_at,updated_at,published_at)
VALUES ('20000000-0000-4000-8000-000000000001','fixture','مصدر تجريبي','ar','generic','active',0,
 'https://example.test',false,'2026-01-02T03:04:05Z','2026-01-03T03:04:05Z','2026-01-03T03:04:05Z');
INSERT INTO source_config_revisions(id,source_config_id,revision_number,config_canonical_json,checksum,canon_version,status,created_by,notes,created_at,published_at)
VALUES ('20000000-0000-4000-8000-000000000002','20000000-0000-4000-8000-000000000001',1,
 '{ "z":2, "a":1 }',repeat('a',64),'kcj-1','published','10000000-0000-4000-8000-000000000001',
 'synthetic exact-byte sentinel','2026-01-02T03:04:05Z','2026-01-03T03:04:05Z');
UPDATE source_configs SET current_published_revision_id='20000000-0000-4000-8000-000000000002';
INSERT INTO source_validation_results(id,revision_id,valid,errors,warnings,rules_version,validated_at)
VALUES ('20000000-0000-4000-8000-000000000003','20000000-0000-4000-8000-000000000002',true,'[]',
 '[{"code":"SYNTHETIC","path":"fixture","message":"fixture"}]','fixture-v1','2026-01-03T03:04:05Z');

INSERT INTO published_documents(id,document_revision,schema_version,document_json,checksum,canon_version,source_count,created_by,created_at,notes,
 signature_format,signature_algorithm,signing_key_id,signature_base64)
VALUES ('20000000-0000-4000-8000-000000000004',100,1,'{"sources":[],"revision":100}',repeat('b',64),'kcj-1',1,
 '10000000-0000-4000-8000-000000000001','2026-01-03T03:04:05Z','schema-only synthetic signature','fixture-v1','Ed25519','fixture-key','Zml4dHVyZQ==');
UPDATE document_publication_state SET latest_document_revision=100;
SELECT setval('seq_document_revision',100,true);
INSERT INTO published_source_catalogs(id,catalog_revision,schema_version,source_schema_version,manifest_json,checksum,canon_version,source_count,
 created_by,created_at,signature_format,signature_algorithm,signing_key_id,signature_base64)
VALUES ('20000000-0000-4000-8000-000000000005',100,1,1,'{"fixture":true}',repeat('c',64),'kcj-1',1,
 '10000000-0000-4000-8000-000000000001','2026-01-03T03:04:05Z','fixture-v1','Ed25519','fixture-key','Zml4dHVyZQ==');
INSERT INTO published_source_catalog_entries(catalog_id,source_config_id,source_revision_id,api,source_revision,checksum,source_order,lifecycle,engine,
 source_signing_key_id,source_signature)
VALUES ('20000000-0000-4000-8000-000000000005','20000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000002',
 'fixture',1,repeat('a',64),0,'active','generic','fixture-key','Zml4dHVyZQ==');
INSERT INTO published_source_catalog_removed(catalog_id,api) VALUES ('20000000-0000-4000-8000-000000000005','fixture-old');

INSERT INTO source_editor_drafts(id,source_config_id,based_on_revision_number,content_json,version,created_by,updated_by,created_at,updated_at)
VALUES ('20000000-0000-4000-8000-000000000006','20000000-0000-4000-8000-000000000001',1,'{"unfinished":',7,
 '10000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001','2026-01-02T03:04:05Z','2026-01-03T03:04:05Z');
INSERT INTO source_changesets(id,name,description,operations_json,status,version,created_by,updated_by,created_at,updated_at)
VALUES ('20000000-0000-4000-8000-000000000007','synthetic open','unchanged draft','[]','open',3,
 '10000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001','2026-01-02T03:04:05Z','2026-01-03T03:04:05Z');
INSERT INTO source_changesets(id,name,operations_json,status,version,applied_document_revision,created_by,updated_by,created_at,updated_at,applied_at)
VALUES ('20000000-0000-4000-8000-000000000008','synthetic applied','[]','applied',2,100,
 '10000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001','2026-01-02T03:04:05Z','2026-01-03T03:04:05Z','2026-01-03T03:04:05Z');

INSERT INTO audit_log(actor_user_id,action,entity_type,entity_id,detail,created_at)
VALUES ('10000000-0000-4000-8000-000000000001','SOURCE_CREATED','source','fixture','{"revision":1}','2026-01-03T03:04:05Z');
INSERT INTO admin_step_up_grants(id,user_id,token_hash,scope,created_at,expires_at,used_at) VALUES
('30000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001',repeat('1',64),'source-admin-mutation',
 '2026-01-03T03:04:05Z','2026-01-03T03:09:05Z',NULL),
('30000000-0000-4000-8000-000000000002','10000000-0000-4000-8000-000000000001',repeat('2',64),'source-admin-mutation',
 '2026-01-03T03:04:05Z','2026-01-03T03:09:05Z','2026-01-03T03:05:05Z');

INSERT INTO completion_requests(id,user_id,provider,model,prompt,status,created_at,updated_at) VALUES
('40000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000002','echo','fixture','synthetic prompt','SUCCEEDED',
 '2026-01-03T03:04:05Z','2026-01-03T03:04:06Z'),
('40000000-0000-4000-8000-000000000002','10000000-0000-4000-8000-000000000002','echo','fixture','synthetic negative','FAILED',
 '2026-01-03T03:04:05Z','2026-01-03T03:04:06Z');
INSERT INTO completion_results(id,request_id,result,error,error_code,latency_ms,created_at) VALUES
('40000000-0000-4000-8000-000000000003','40000000-0000-4000-8000-000000000001','synthetic result',NULL,NULL,5,'2026-01-03T03:04:06Z'),
('40000000-0000-4000-8000-000000000004','40000000-0000-4000-8000-000000000002',NULL,'synthetic error','SYNTHETIC_FAILURE',6,'2026-01-03T03:04:06Z');

INSERT INTO tutorial_categories(id,slug,status,position,created_at,updated_at)
VALUES ('50000000-0000-4000-8000-000000000001','fixture-category','PUBLISHED',0,'2026-01-02T03:04:05Z','2026-01-03T03:04:05Z');
INSERT INTO tutorial_category_revisions(id,category_id,revision_number,content,created_by,created_at)
VALUES ('50000000-0000-4000-8000-000000000002','50000000-0000-4000-8000-000000000001',1,'{"en":"Fixture","ar":"تجريبي"}',
 '10000000-0000-4000-8000-000000000001','2026-01-03T03:04:05Z');
UPDATE tutorial_categories SET published_revision_id='50000000-0000-4000-8000-000000000002';
INSERT INTO tutorial_media(id,storage_filename,content_type,byte_size,width,height,sha256,published,created_by,created_at)
VALUES ('50000000-0000-4000-8000-000000000003','synthetic.png','image/png',64,1,1,repeat('d',64),true,
 '10000000-0000-4000-8000-000000000001','2026-01-03T03:04:05Z');
INSERT INTO tutorials(id,slug,status,position,featured_position,created_at,updated_at)
VALUES ('50000000-0000-4000-8000-000000000004','fixture-tutorial','PUBLISHED',0,0,'2026-01-02T03:04:05Z','2026-01-03T03:04:05Z');
INSERT INTO tutorial_revisions(id,tutorial_id,revision_number,category_id,content,created_by,created_at)
VALUES ('50000000-0000-4000-8000-000000000005','50000000-0000-4000-8000-000000000004',1,
 '50000000-0000-4000-8000-000000000001','{"en":{"title":"Fixture"},"ar":{"title":"تجريبي"}}',
 '10000000-0000-4000-8000-000000000001','2026-01-03T03:04:05Z');
UPDATE tutorials SET published_revision_id='50000000-0000-4000-8000-000000000005';
INSERT INTO tutorial_revision_media(revision_id,media_id,slot_key)
VALUES ('50000000-0000-4000-8000-000000000005','50000000-0000-4000-8000-000000000003','hero');
