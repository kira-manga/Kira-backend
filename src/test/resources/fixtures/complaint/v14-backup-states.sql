-- Backup-only synthetic storage specimens. Requires v13-rich.sql and v14-rich.sql.
-- These descriptors/signatures are NOT authenticated provider events or production recovery evidence.
-- Do not load this overlay in the base constraint fixture: those tests deliberately use single rows.

-- Scoped pending content, terminal replay credentials, and permanent credential-free identities.
INSERT INTO complaint_installation_ids(id,data_scope_id,test_only,state,created_at,terminal_at) VALUES
('60000000-0000-4000-8000-000000000010','65000000-0000-4000-8000-000000000001',true,
 'DELETION_PENDING','2026-01-03T03:04:05Z',NULL),
('60000000-0000-4000-8000-000000000020','00000000-0000-0000-0000-000000000000',false,
 'DELETED','2026-01-03T03:04:05Z','2026-03-07T17:00:00Z'),
('60000000-0000-4000-8000-000000000030','65000000-0000-4000-8000-000000000002',true,
 'RETIRED','2026-01-03T03:04:05Z','2026-03-07T17:00:00Z');
INSERT INTO app_installations(id,data_scope_id,test_only,secret_verifier,platform,state,credential_version,
 owner_reference,created_at,last_authenticated_at,deleted_at,verifier_expires_at,version) VALUES
('60000000-0000-4000-8000-000000000010','65000000-0000-4000-8000-000000000001',true,
 decode(repeat('34',32),'hex'),'IOS','DELETION_PENDING',3,'68000000-0000-4000-8000-000000000010',
 '2026-01-03T03:04:05Z','2026-03-07T17:00:00Z',NULL,NULL,4),
('60000000-0000-4000-8000-000000000020','00000000-0000-0000-0000-000000000000',false,
 decode(repeat('56',32),'hex'),NULL,'DELETED',4,NULL,'2026-01-03T03:04:05Z',NULL,
 '2026-03-07T17:00:00Z','2026-03-07T17:00:00Z'::timestamptz+interval '192 hours',8);
INSERT INTO complaint_resource_ids(id,data_scope_id,test_only,state,created_at,deleted_at) VALUES
('61000000-0000-4000-8000-000000000010','65000000-0000-4000-8000-000000000001',true,
 'DELETION_PENDING','2026-01-03T03:04:05Z',NULL),
('61000000-0000-4000-8000-000000000011','65000000-0000-4000-8000-000000000001',true,
 'LIVE','2026-01-03T03:04:05Z',NULL),
('61000000-0000-4000-8000-000000000020','00000000-0000-0000-0000-000000000000',false,
 'DELETED','2026-01-03T03:04:05Z','2026-03-07T17:00:00Z');
INSERT INTO complaints(id,data_scope_id,test_only,ownership,kind,status,notice_key,created_at,updated_at,version) VALUES
('61000000-0000-4000-8000-000000000011','65000000-0000-4000-8000-000000000001',true,
 'SYSTEM','NOTICE','PINNED','fixture.notice','2026-01-03T03:04:05Z','2026-03-07T17:00:00Z',2);
INSERT INTO complaints(id,data_scope_id,test_only,owner_id,ownership,kind,type,status,notice_key,body,parent_resource_id,
 platform,os_version,manufacturer,device_model,closure_reason,closed_at,closure_provenance,closure_actor_id,created_at,updated_at,version) VALUES
('61000000-0000-4000-8000-000000000010','65000000-0000-4000-8000-000000000001',true,
 '60000000-0000-4000-8000-000000000010','INSTALLATION','REPLY','CUSTOM','CLOSED','fixture.notice',
 'Synthetic scoped notice reply','61000000-0000-4000-8000-000000000011','IOS',
 'fixture-ios-version','fixture-manufacturer','fixture-device','Synthetic scoped resolution',
 '2026-03-07T17:00:00Z','ADMIN','10000000-0000-4000-8000-000000000001',
 '2026-01-03T03:04:05Z','2026-03-07T17:00:00Z',9);
UPDATE complaint_resource_ids SET state='DELETION_PENDING' WHERE id='61000000-0000-4000-8000-000000000001';
UPDATE complaint_test_runs SET enrolled_count=1 WHERE data_scope_id='65000000-0000-4000-8000-000000000001';

-- Distinct pending, verified and applied outbox states with exact applied key/version bindings.
WITH samples(event_id,scope,test_only,kind,targets,object_key,state) AS (
 VALUES
 (repeat('C',42)||'A','65000000-0000-4000-8000-000000000001'::uuid,true,'OWNER_DELETE_ALL',1,'fixture/backup/pending-delete-all','VERIFIED'),
 (repeat('D',42)||'A','00000000-0000-0000-0000-000000000000'::uuid,false,'OWNER_DELETE',1,'fixture/backup/applied-delete','APPLIED'),
 (repeat('E',42)||'A','00000000-0000-0000-0000-000000000000'::uuid,false,'OWNER_DELETE_ALL',0,'fixture/backup/applied-delete-all','APPLIED')
), f AS (
 SELECT convert_to('{"backup-state":true}','UTF8') AS bytes,'2026-03-07T17:00:00Z'::timestamptz AS t
)
INSERT INTO complaint_journal_publications(event_id,data_scope_id,test_only,writer_generation,journal_epoch,event_kind,target_count,
 routing_key_id,object_key,canonicalizer,event_bytes,semantic_hash,state,created_at,object_version,ciphertext_hash,object_created_at,
 retain_until,verified_at,verification_bytes,verification_hash,applied_at)
SELECT event_id,scope,test_only,'64000000-0000-4000-8000-000000000001'::uuid,3,kind,targets,'fixture-route',object_key,
 'kcj-1',bytes,sha256(bytes),state,t,object_key||'/version-1',sha256(bytes),t,t+interval '13 months',t,
 bytes,sha256(bytes),CASE WHEN state='APPLIED' THEN t END
FROM samples CROSS JOIN f;
INSERT INTO complaint_deletion_journal_applied(object_key,object_version,event_id,ciphertext_hash,writer_generation,
 journal_epoch,event_kind,target_count,data_scope_id,test_only,applied_at)
SELECT object_key,object_version,event_id,ciphertext_hash,writer_generation,journal_epoch,event_kind,target_count,data_scope_id,test_only,applied_at
FROM complaint_journal_publications WHERE event_id IN (repeat('D',42)||'A',repeat('E',42)||'A');

-- Preserve the two base IN_PROGRESS row-shape specimens; add durable authorization/result shapes.
INSERT INTO complaint_idempotency_receipts(actor_kind,actor_id,idempotency_key,operation,fingerprint,target_ids,
 data_scope_id,test_only,state,publication_ref,created_at,authorized_at) VALUES
('INSTALLATION','60000000-0000-4000-8000-000000000001','62000000-0000-4000-8000-000000000010','OWNER_DELETE',
 decode(repeat('ef',32),'hex'),ARRAY['61000000-0000-4000-8000-000000000001']::uuid[],
 '00000000-0000-0000-0000-000000000000',false,'AUTHORIZED_DELETE',repeat('A',43),'2026-01-03T03:04:05Z','2026-01-03T03:04:05Z');
INSERT INTO complaint_idempotency_receipts(actor_kind,actor_id,idempotency_key,operation,fingerprint,target_ids,
 data_scope_id,test_only,state,outcome,response_status,publication_ref,external_event_id,external_epoch,
 external_object_version,external_ciphertext_hash,created_at,authorized_at,completed_at,expires_at)
SELECT 'INSTALLATION','60000000-0000-4000-8000-000000000001'::uuid,'62000000-0000-4000-8000-000000000020'::uuid,
 'OWNER_DELETE',decode(repeat('ef',32),'hex'),ARRAY['61000000-0000-4000-8000-000000000020']::uuid[],data_scope_id,test_only,
 'COMPLETED','APPLIED',204,event_id,event_id,journal_epoch,object_version,ciphertext_hash,created_at,created_at,applied_at,applied_at+interval '192 hours'
FROM complaint_journal_publications WHERE event_id=repeat('D',42)||'A';
INSERT INTO complaint_idempotency_receipts(actor_kind,actor_id,idempotency_key,operation,fingerprint,target_ids,
 data_scope_id,test_only,state,outcome,response_status,ack_ids,ack_versions,response_etag,created_at,completed_at,expires_at) VALUES
('INSTALLATION','60000000-0000-4000-8000-000000000001','62000000-0000-4000-8000-000000000030','OWNER_EDIT',
 decode(repeat('ef',32),'hex'),ARRAY['61000000-0000-4000-8000-000000000001']::uuid[],
 '00000000-0000-0000-0000-000000000000',false,'COMPLETED','APPLIED',200,
 ARRAY['61000000-0000-4000-8000-000000000001']::uuid[],ARRAY[7]::bigint[],
 '"complaint-61000000-0000-4000-8000-000000000001-v7"','2026-01-03T03:04:05Z','2026-03-07T17:00:00Z',
 '2026-03-07T17:00:00Z'::timestamptz+interval '192 hours');
INSERT INTO complaint_idempotency_receipts(actor_kind,actor_id,idempotency_key,operation,fingerprint,target_ids,
 data_scope_id,test_only,state,outcome,response_status,problem_code,created_at,completed_at,expires_at) VALUES
('INSTALLATION','60000000-0000-4000-8000-000000000001','62000000-0000-4000-8000-000000000031','OWNER_EDIT',
 decode(repeat('ef',32),'hex'),ARRAY['61000000-0000-4000-8000-000000000099']::uuid[],
 '00000000-0000-0000-0000-000000000000',false,'COMPLETED','REJECTED',404,'COMPLAINT_NOT_FOUND',
 '2026-01-03T03:04:05Z','2026-03-07T17:00:00Z','2026-03-07T17:00:00Z'::timestamptz+interval '192 hours');
INSERT INTO installation_deletion_receipts(installation_id,deletion_key,submitted_credential_version,fingerprint,
 data_scope_id,test_only,state,publication_ref,created_at,authorized_at) VALUES
('60000000-0000-4000-8000-000000000010','62000000-0000-4000-8000-000000000011',3,decode(repeat('ef',32),'hex'),
 '65000000-0000-4000-8000-000000000001',true,'AUTHORIZED_DELETE',repeat('C',42)||'A','2026-01-03T03:04:05Z','2026-03-07T17:00:00Z');
INSERT INTO installation_deletion_receipts(installation_id,deletion_key,submitted_credential_version,fingerprint,
 data_scope_id,test_only,state,outcome,response_status,publication_ref,external_event_id,external_epoch,
 external_object_version,external_ciphertext_hash,created_at,authorized_at,completed_at,expires_at)
SELECT '60000000-0000-4000-8000-000000000020'::uuid,'62000000-0000-4000-8000-000000000021'::uuid,4,decode(repeat('ef',32),'hex'),
 data_scope_id,test_only,'COMPLETED','APPLIED',204,event_id,event_id,journal_epoch,object_version,ciphertext_hash,
 created_at,created_at,applied_at,applied_at+interval '192 hours'
FROM complaint_journal_publications WHERE event_id=repeat('E',42)||'A';

-- Permanent compacted evidence survives without its full publication; the reserved C row stays attached.
UPDATE complaint_deletion_journal_retirements SET state='COMPLETED',completion_catalog_generation=3,
 completion_catalog_hash=decode(repeat('ba',32),'hex'),completion_bytes=convert_to('{"retired":true}','UTF8'),
 completion_hash=sha256(convert_to('{"retired":true}','UTF8')),completed_at='2026-03-07T17:00:00Z'
WHERE object_key='fixture/applied' AND object_version='version-A';
INSERT INTO complaint_recovery_capacity_reservations(event_id,data_scope_id,test_only,publication_ref,state,accounting_version,
 reserved_amounts,converted_amounts,created_at,converted_at) VALUES
(repeat('B',42)||'A','00000000-0000-0000-0000-000000000000',false,NULL,'CONVERTED',1,
 array_fill(10::bigint,ARRAY[22]),array_fill(4::bigint,ARRAY[22]),'2026-01-03T03:04:05Z','2026-03-07T17:00:00Z'),
(repeat('C',42)||'A','65000000-0000-4000-8000-000000000001',true,repeat('C',42)||'A','RESERVED',1,
 array_fill(10::bigint,ARRAY[22]),NULL,'2026-03-07T17:00:00Z',NULL);

-- One partially signed rotation intent, not a second concurrent catalog mutation.
UPDATE complaint_catalog_mutations SET operation_type='SIGNER_ROTATION_OVERLAP',signer_policy='ROTATION_OVERLAP',
 predecessor_generation=1,predecessor_hash=decode(repeat('ba',32),'hex'),successor_generation=2,
 object_key='fixture/backup/catalog/2',signer_one_id='fixture-old-signer',signer_one_algorithm='RSASSA_PSS_SHA_256',
 signer_one_signature=NULL,signer_two_id='fixture-new-signer',signer_two_algorithm='RSASSA_PSS_SHA_256',signer_two_signature=decode('040506','hex')
WHERE operation_token='63000000-0000-4000-8000-000000000001';

-- Keep the original closed global seed and add a closed, fully bound scoped seal/checkpoint specimen.
INSERT INTO complaint_journal_control(data_scope_id,test_only,publication_epoch,desired_generation,implementation_schema,
 maintenance_closed,creation_closed,scan_requested,lease_token,retention_lease_token,updated_at) VALUES
('65000000-0000-4000-8000-000000000001',true,4,2,1,true,true,false,7,0,'2026-03-07T17:00:00Z');
UPDATE complaint_journal_control SET desired_configuration_hash=decode(repeat('ba',32),'hex'),
 database_identity='64000000-0000-4000-8000-000000000002',restore_identity='64000000-0000-4000-8000-000000000003',
 event_writer_generation='64000000-0000-4000-8000-000000000001',accepted_catalog_generation=3,
 accepted_catalog_hash=decode(repeat('ba',32),'hex'),trust_bundle_hash=decode(repeat('ba',32),'hex'),
 catalog_writer_generation='64000000-0000-4000-8000-000000000001',seal_state='SEAL_VERIFIED',seal_epoch=3,
 seal_writer_generation='64000000-0000-4000-8000-000000000001',seal_operation_token='63000000-0000-4000-8000-000000000002',
 seal_object_key='fixture/backup/seal',seal_bytes=convert_to('{"seal":true}','UTF8'),seal_hash=sha256(convert_to('{"seal":true}','UTF8')),
 seal_object_version='seal-version-1',seal_ciphertext_hash=decode(repeat('ba',32),'hex'),
 seal_retain_until='2026-03-07T17:00:00Z'::timestamptz+interval '13 months',seal_verified_at='2026-03-07T17:00:00Z',
 seal_verification_bytes=convert_to('{"seal-copy":true}','UTF8'),seal_verification_hash=sha256(convert_to('{"seal-copy":true}','UTF8')),
 checkpoint_generation=2,checkpoint_fencing_token=7,checkpoint_catalog_generation=3,checkpoint_catalog_hash=decode(repeat('ba',32),'hex'),
 checkpoint_writer_generation='64000000-0000-4000-8000-000000000001',checkpoint_cutoff_epoch=3,
 checkpoint_configuration_hash=decode(repeat('ba',32),'hex'),checkpoint_database_identity='64000000-0000-4000-8000-000000000002',
 checkpoint_restore_identity='64000000-0000-4000-8000-000000000003',checkpoint_schema=1,
 checkpoint_started_at='2026-01-03T03:04:05Z',checkpoint_completed_at='2026-03-07T17:00:00Z',checkpoint_object_count=4,
 checkpoint_byte_count=2048,checkpoint_result='SUCCESS',checkpoint_bytes=convert_to('{"checkpoint":true}','UTF8'),
 checkpoint_hash=sha256(convert_to('{"checkpoint":true}','UTF8'))
WHERE data_scope_id='65000000-0000-4000-8000-000000000001';

-- Permanent terminal sibling with retained installation; no second nonterminal run.
WITH f AS (
 SELECT '2026-03-07T17:00:00Z'::timestamptz AS t,convert_to('{"terminal-fixture":true}','UTF8') AS b,decode(repeat('ba',32),'hex') AS h
)
INSERT INTO complaint_test_runs(data_scope_id,test_only,state,configuration_hash,accounting_version,installation_limit,enrolled_count,
 original_reserve,unused_reserve,activation_catalog_generation,activation_catalog_hash,created_at,sealed_at,purging_at,purged_at,
 final_ordinary_epoch,terminal_seal_epoch,generation_seal_count,generation_seal_root,seal_set_bytes,seal_set_hash,
 event_manifest_count,event_manifest_root,installation_manifest_count,installation_manifest_root,installation_chunk_count,
 retired_count,deleted_count,permanent_denial_bytes,permanent_denial_hash,terminal_event_id,terminal_object_key,terminal_object_version,
 terminal_ciphertext_hash,terminal_catalog_generation,terminal_catalog_hash)
SELECT '65000000-0000-4000-8000-000000000002'::uuid,true,'PURGED',configuration_hash,accounting_version,installation_limit,1,
 original_reserve,array_fill(0::bigint,ARRAY[22]),activation_catalog_generation,activation_catalog_hash,created_at,t,t,t,
 1,2,1,h,b,sha256(b),0,h,1,h,1,1,0,b,sha256(b),repeat('F',42)||'A','fixture/backup/purged-terminal','terminal-version-1',h,3,h
FROM complaint_test_runs CROSS JOIN f WHERE data_scope_id='65000000-0000-4000-8000-000000000001';
