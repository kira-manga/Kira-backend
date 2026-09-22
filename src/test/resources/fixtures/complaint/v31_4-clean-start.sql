-- Synthetic current new-data storage specimens, NOT authenticated journal/catalog/provider evidence.
-- Requires v13-rich.sql; keeps its nonlegacy FK/reset coverage without any imported history.
-- The original v14-rich.sql and all historical backup/reset oracles remain unchanged.
INSERT INTO complaint_installation_ids(id,data_scope_id,test_only,state,created_at) VALUES
('60000000-0000-4000-8000-000000000001','00000000-0000-0000-0000-000000000000',false,'ACTIVE','2026-01-02T03:04:05Z'),
('60000000-0000-4000-8000-000000000002','00000000-0000-0000-0000-000000000000',false,'RECOVERY_RESERVED','2026-01-02T03:04:05Z');

INSERT INTO app_installations(id,data_scope_id,test_only,secret_verifier,platform,state,credential_version,
 owner_reference,created_at,last_authenticated_at,version) VALUES
('60000000-0000-4000-8000-000000000001','00000000-0000-0000-0000-000000000000',false,
 decode(repeat('12',32),'hex'),'ANDROID','ACTIVE',2,'68000000-0000-4000-8000-000000000001',
 '2026-01-02T03:04:05Z','2026-01-03T03:04:05Z',3);

INSERT INTO complaint_resource_ids(id,data_scope_id,test_only,state,created_at) VALUES
('61000000-0000-4000-8000-000000000001','00000000-0000-0000-0000-000000000000',false,'LIVE','2026-01-02T03:04:05Z'),
('61000000-0000-4000-8000-000000000003','00000000-0000-0000-0000-000000000000',false,'LIVE','2026-01-02T03:04:05Z'),
('61000000-0000-4000-8000-000000000004','00000000-0000-0000-0000-000000000000',false,'LIVE','2026-01-02T03:04:05Z');

INSERT INTO complaints(id,data_scope_id,test_only,owner_id,ownership,kind,type,status,subject,body,
 platform,os_version,manufacturer,device_model,closure_reason,closed_at,closure_provenance,closure_actor_id,
 created_at,updated_at,version) VALUES
('61000000-0000-4000-8000-000000000001','00000000-0000-0000-0000-000000000000',false,
 '60000000-0000-4000-8000-000000000001','INSTALLATION','REPORT','TECHNICAL','CLOSED','مثال تجريبي','Synthetic fixture body',
 'ANDROID','15','Fixture','Synthetic','Synthetic resolution','2026-01-03T03:04:05Z','ADMIN',
 '10000000-0000-4000-8000-000000000001','2026-01-02T03:04:05Z','2026-01-03T03:04:05Z',7);

INSERT INTO complaints(id,data_scope_id,test_only,ownership,kind,status,notice_key,created_at,updated_at,version) VALUES
('61000000-0000-4000-8000-000000000003','00000000-0000-0000-0000-000000000000',false,'SYSTEM','NOTICE','PINNED',
 'fixture.notice','2026-01-02T03:04:05Z','2026-01-03T03:04:05Z',1);

INSERT INTO complaints(id,data_scope_id,test_only,owner_id,ownership,kind,type,status,notice_key,body,parent_resource_id,
 platform,os_version,manufacturer,device_model,created_at,updated_at,version) VALUES
('61000000-0000-4000-8000-000000000004','00000000-0000-0000-0000-000000000000',false,
 '60000000-0000-4000-8000-000000000001','INSTALLATION','REPLY','CUSTOM','OPEN','fixture.notice','Synthetic notice reply',
 '61000000-0000-4000-8000-000000000003','ANDROID','','','','2026-01-02T03:04:05Z','2026-01-03T03:04:05Z',2);

INSERT INTO complaint_journal_publications(event_id,data_scope_id,test_only,writer_generation,journal_epoch,event_kind,
 target_count,routing_key_id,object_key,canonicalizer,event_bytes,semantic_hash,state,created_at) VALUES
(repeat('A',43),'00000000-0000-0000-0000-000000000000',false,'64000000-0000-4000-8000-000000000001',1,'OWNER_DELETE',
 1,'fixture-route','fixture/event-a','kcj-1',convert_to('{ "z":2, "a":1 }','UTF8'),
 sha256(convert_to('{ "z":2, "a":1 }','UTF8')),'PREPARED','2026-01-03T03:04:05Z');

INSERT INTO complaint_idempotency_receipts(actor_kind,actor_id,idempotency_key,operation,fingerprint,target_ids,
 data_scope_id,test_only,state,created_at) VALUES
('INSTALLATION','60000000-0000-4000-8000-000000000001','62000000-0000-4000-8000-000000000001','OWNER_EDIT',
 decode(repeat('ef',32),'hex'),ARRAY['61000000-0000-4000-8000-000000000001']::uuid[],
 '00000000-0000-0000-0000-000000000000',false,'IN_PROGRESS','2026-01-03T03:04:05Z');

INSERT INTO installation_deletion_receipts(installation_id,deletion_key,submitted_credential_version,fingerprint,
 data_scope_id,test_only,state,created_at) VALUES
('60000000-0000-4000-8000-000000000001','62000000-0000-4000-8000-000000000002',2,decode(repeat('ef',32),'hex'),
 '00000000-0000-0000-0000-000000000000',false,'IN_PROGRESS','2026-01-03T03:04:05Z');

INSERT INTO complaint_deletion_journal_applied(object_key,object_version,event_id,ciphertext_hash,writer_generation,
 journal_epoch,event_kind,target_count,data_scope_id,test_only,applied_at) VALUES
('fixture/applied','version-A',repeat('B',42)||'A',decode(repeat('aa',32),'hex'),'64000000-0000-4000-8000-000000000001',
 1,'OWNER_DELETE',1,'00000000-0000-0000-0000-000000000000',false,'2026-01-03T03:04:05Z');

INSERT INTO complaint_deletion_journal_retirements(object_key,object_version,data_scope_id,test_only,event_kind,state,
 authorization_catalog_generation,authorization_catalog_hash,restore_floor,authorization_bytes,authorization_hash,authorized_at) VALUES
('fixture/applied','version-A','00000000-0000-0000-0000-000000000000',false,'OWNER_DELETE','AUTHORIZED',2,
 decode(repeat('bb',32),'hex'),'2026-01-01T00:00:00Z',convert_to('{"synthetic":true}','UTF8'),
 sha256(convert_to('{"synthetic":true}','UTF8')),'2026-01-03T03:04:05Z');

INSERT INTO complaint_recovery_capacity_reservations(event_id,data_scope_id,test_only,publication_ref,state,
 accounting_version,reserved_amounts,created_at) VALUES
(repeat('A',43),'00000000-0000-0000-0000-000000000000',false,repeat('A',43),'RESERVED',1,
 ARRAY[10,10,10,10,0,0,0,10,10,10,10,10,10,0,10,10,10,10,10,10,10,10]::bigint[],'2026-01-03T03:04:05Z');

UPDATE complaint_capacity_counters SET configuration_hash=decode(repeat('bb',32),'hex'),
 hard_limit=CASE WHEN ordinal IN (5,6,7,14) THEN 0 ELSE 1000 END,
 creation_limit=CASE WHEN ordinal IN (5,6,7,14) THEN 0 ELSE 500 END,
 free_units=CASE WHEN ordinal IN (5,6,7,14) THEN 0 ELSE 900 END,
 actual_units=CASE WHEN ordinal IN (5,6,7,14) THEN 0 ELSE 30 END,
 recovery_reserved_units=CASE WHEN ordinal IN (5,6,7,14) THEN 0 ELSE 40 END,
 test_reserved_units=CASE WHEN ordinal IN (5,6,7,14) THEN 0 ELSE 30 END;

INSERT INTO complaint_test_runs(data_scope_id,test_only,state,configuration_hash,accounting_version,installation_limit,
 enrolled_count,original_reserve,unused_reserve,activation_catalog_generation,activation_catalog_hash,created_at) VALUES
('65000000-0000-4000-8000-000000000001',true,'ACTIVE',decode(repeat('bb',32),'hex'),1,10,0,
 ARRAY[20,20,20,20,0,0,0,20,20,20,20,20,20,0,20,20,20,20,20,20,20,20]::bigint[],ARRAY[20,20,20,20,0,0,0,20,20,20,20,20,20,0,20,20,20,20,20,20,20,20]::bigint[],2,decode(repeat('bc',32),'hex'),'2026-01-03T03:04:05Z');

INSERT INTO complaint_catalog_mutations(operation_token,operation_type,predecessor_generation,predecessor_hash,
 successor_generation,catalog_writer_generation,approval_bytes,approval_hash,canonicalizer,unsigned_bytes,unsigned_hash,
 signer_policy,signer_one_id,signer_one_algorithm,signer_one_signature,object_key,state,created_at) VALUES
('63000000-0000-4000-8000-000000000001','GENESIS',0,decode(repeat('00',32),'hex'),1,
 '64000000-0000-4000-8000-000000000001',convert_to('{"synthetic":true}','UTF8'),sha256(convert_to('{"synthetic":true}','UTF8')),
 'kcj-1',convert_to('{ "z":2, "a":1 }','UTF8'),sha256(convert_to('{ "z":2, "a":1 }','UTF8')),
 'SINGLE','fixture-signer','RSASSA_PSS_SHA_256',decode('010203','hex'),'fixture/catalog/1','PREPARED','2026-01-03T03:04:05Z');

INSERT INTO complaint_journal_scan_runs(scan_id,pass,data_scope_id,test_only,restore_identity,desired_generation,
 fencing_token,writer_generation,cutoff_epoch,maximum_entries,maximum_bytes,entry_count,entry_bytes,state,started_at) VALUES
('66000000-0000-4000-8000-000000000001',1,'00000000-0000-0000-0000-000000000000',false,
 '64000000-0000-4000-8000-000000000002',1,1,'64000000-0000-4000-8000-000000000001',1,100,65536,1,512,'SCANNING','2026-01-03T03:04:05Z');

INSERT INTO complaint_journal_scan_entries(scan_id,pass,data_scope_id,test_only,object_key,object_version,ciphertext_hash,
 semantic_hash,event_id,event_kind,writer_generation,journal_epoch,entry_bytes,replay_state) VALUES
('66000000-0000-4000-8000-000000000001',1,'00000000-0000-0000-0000-000000000000',false,'fixture/applied','version-A',
 decode(repeat('aa',32),'hex'),decode(repeat('ba',32),'hex'),repeat('B',42)||'A','OWNER_DELETE',
 '64000000-0000-4000-8000-000000000001',1,512,'APPLIED');
