package me.manga.kira.backend.database.complaint

import java.sql.Connection

/** Row-local synthetic state builders, not authenticated events, writers or recovery implementations. */
fun Connection.completeFixtureRetirement() = exec(
    "UPDATE complaint_deletion_journal_retirements SET state='COMPLETED',completion_catalog_generation=3," +
        "completion_catalog_hash=$FIXTURE_DIGEST,completion_bytes=$FIXTURE_BYTES,completion_hash=$FIXTURE_HASH,completed_at=$FIXTURE_INSTANT",
)

fun Connection.verifyFixtureSeal() = exec(
    controlUpdate(
        "seal_state='SEAL_VERIFIED',seal_epoch=1,seal_writer_generation='64000000-0000-4000-8000-000000000001'," +
            "seal_operation_token='63000000-0000-4000-8000-000000000001',seal_object_key='fixture/seal'," +
            "seal_bytes=$FIXTURE_BYTES,seal_hash=$FIXTURE_HASH,seal_object_version='version-S',seal_ciphertext_hash=$FIXTURE_DIGEST," +
            "seal_retain_until=$FIXTURE_INSTANT,seal_verified_at=$FIXTURE_INSTANT,seal_verification_bytes=$FIXTURE_BYTES," +
            "seal_verification_hash=$FIXTURE_HASH",
    ),
)

fun Connection.completeFixtureOwnerDeletion() = exec(
    receiptUpdate("operation='OWNER_DELETE',$RECEIPT_COMPLETED,outcome='APPLIED',response_status=204,$RECEIPT_EXTERNAL"),
)

fun Connection.deleteFixtureCredential() = exec(
    "DELETE FROM complaints WHERE owner_id='$INSTALLATION_ID'; " +
        "UPDATE complaint_installation_ids SET state='DELETED',terminal_at=$FIXTURE_INSTANT WHERE id='$INSTALLATION_ID'; " +
        "UPDATE app_installations SET state='DELETED',platform=NULL,owner_reference=NULL,last_authenticated_at=NULL," +
        "deleted_at=$FIXTURE_INSTANT,verifier_expires_at=$FIXTURE_INSTANT+interval '192 hours' WHERE id='$INSTALLATION_ID'",
)

fun Connection.prepareFixtureSignerRotation() = exec(
    catalogUpdate(
        "operation_type='SIGNER_ROTATION_OVERLAP',signer_policy='ROTATION_OVERLAP',predecessor_generation=1," +
            "predecessor_hash=$FIXTURE_DIGEST,successor_generation=2,signer_two_id='fixture-second',signer_two_algorithm='RSASSA_PSS_SHA_256'",
    ),
)
