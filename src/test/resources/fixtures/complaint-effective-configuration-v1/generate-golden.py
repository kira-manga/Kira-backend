#!/usr/bin/env python3
"""Independent stdlib-only construction of the documented D-v1 cold fixture.

No production Kotlin is loaded or run. The only existing inputs read are the
frozen J golden and the public certificate literal in a test source. P and every
effective D field below are explicit expected values, not extracted from the
producer. Regeneration is not product test evidence and must not mask a failing
golden comparison.
"""

import hashlib
import json
from pathlib import Path
import textwrap


HERE = Path(__file__).resolve().parent
REPOSITORY = HERE.parents[4]
SECOND = 1_000_000_000
CONSUMER_ARN = (
    "arn:aws:secretsmanager:us-east-1:123456789012:secret:"
    "kira/consumer-fixture-AbC123"
)


def canonical(value):
    # The fixture has integer scalars and code-point-sortable ASCII field names.
    return json.dumps(
        value, sort_keys=True, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")


def sha256(value):
    return hashlib.sha256(value).hexdigest()


def commitment(kind, content):
    return {
        "kind": kind,
        "schemaVersion": 1,
        "canonicalizerId": "kcj-1",
        "sha256": sha256(content),
    }


def secret(family, logical_id, arn, version, purpose="HMAC_SHA256"):
    return {
        "family": family,
        "purpose": purpose,
        "logicalKeyId": logical_id,
        "resourceArn": arn,
        "versionId": version,
    }


def consumer_secret(family, logical_id, seed):
    return secret(
        family, logical_id, CONSUMER_ARN,
        f"68000000-0000-4000-8000-{seed:012x}",
    )


def expected_capacity():
    names = (
        "app_installations", "audit_rows", "catalog_mutations", "complaint_rows",
        "import_artifacts", "import_runs", "import_staging", "installation_ids",
        "installation_receipts", "journal_applied", "journal_control",
        "journal_publications", "journal_retirements", "legacy_records",
        "moderation_grants", "normal_receipts", "recovery_reservations",
        "resource_ids", "scan_entries", "scan_runs", "storage_bytes", "test_runs",
    )
    return canonical({
        "kind": "kira-complaint-capacity-policy",
        "schemaVersion": 1,
        "canonicalizerId": "kcj-1",
        "accountingVersion": 1,
        "dailyEnrollmentLimit": 100,
        "counters": [
            {"name": name, "ordinal": ordinal,
             "hardLimit": 20_000_000, "creationLimit": 18_000_000}
            for ordinal, name in enumerate(names, start=1)
        ],
    })


def public_trust():
    source = REPOSITORY / (
        "src/test/kotlin/me/manga/kira/backend/common/infrastructure/persistence/"
        "VersionBoundPersistenceConfigurationTest.kt"
    )
    literal = source.read_text(encoding="utf-8").split(
        "fun pem(): ByteArray =", 1
    )[1].split('"""', 2)[1]
    pem = (textwrap.dedent(literal).strip() + "\n").encode("ascii")
    assert pem.count(b"-----BEGIN CERTIFICATE-----") == 1
    assert pem.count(b"-----END CERTIFICATE-----") == 1
    return {"sha256": sha256(pem), "byteCount": len(pem), "certificateCount": 1}


def expected_consumers():
    bindings = [
        consumer_secret("USER_ADMIN_JWT", "kira-hs256-1", 91),
        consumer_secret("INSTALLATION_JWT", "installation-z", 81),
        consumer_secret("INSTALLATION_JWT", "installation-a", 82),
        consumer_secret("COMPLAINT_ADMISSION", "admission-z", 61),
        consumer_secret("COMPLAINT_ADMISSION", "admission-a", 62),
        consumer_secret("COMPLAINT_CURSOR", "cursor-z", 41),
        consumer_secret("COMPLAINT_CURSOR", "cursor-a", 42),
        secret(
            "COMPLAINT_JOURNAL_ROUTING", "route-a",
            "arn:aws:secretsmanager:us-east-1:123456789012:secret:journal-routing-a-ABC123",
            "44444444-4444-4444-8444-444444444444",
        ),
        secret(
            "COMPLAINT_JOURNAL_ROUTING", "route-b",
            "arn:aws:secretsmanager:us-east-1:123456789012:secret:journal-routing-b-ABC123",
            "55555555-5555-4555-8555-555555555555",
        ),
    ]
    return {
        "secretBindings": sorted(bindings, key=lambda item: (item["family"], item["logicalKeyId"])),
        "userJwt": {
            "protocolVersion": 1, "algorithm": "HS256", "activeKeyId": "kira-hs256-1",
            "verificationKeyIds": ["kira-hs256-1"], "issuer": "kira-backend", "audience": "kira-api",
            "accessTokenTtl": {"seconds": 3600, "nanoAdjustment": 0},
            "clockSkew": {"seconds": 60, "nanoAdjustment": 0},
        },
        "installationJwt": {
            "protocolVersion": 1, "algorithm": "HS256", "type": "kira-installation+jwt",
            "issuer": "kira-installation", "audience": "kira-complaints", "role": "ROLE_INSTALLATION",
            "activeKeyId": "installation-z", "verificationKeyIds": ["installation-a", "installation-z"],
            "ttlSeconds": 900, "clockSkewSeconds": 60, "maximumCompactBytes": 4096,
            "parser": {"maximumNestingDepth": 3, "maximumStringCharacters": 4096,
                       "maximumNumberCharacters": 20, "signatureBytes": 32},
        },
        "admission": {
            "protocolVersion": 1, "coordinationMode": "memory", "declaredInstances": 1,
            "currentKeyId": "admission-z", "previousKeyIds": ["admission-a"],
            "rotationAllowed": False, "retirementAllowed": False,
            "previousRetentionNanos": 25 * 3600 * SECOND,
            "admissionLifetimeNanos": 5 * SECOND, "concurrentLimit": 2,
            "trustedIp": {
                "protocolVersion": 1, "trustForwardedHeaders": False, "trustedProxies": [],
                "maximumForwardedHeaderBytes": 1024, "selection": "RIGHTMOST_UNTRUSTED",
                "headerPrecedence": ["X-Forwarded-For", "Forwarded"], "invalidChain": "REMOTE_ADDRESS",
            },
            "ingress": {"bucketLimit": 64, "eventLimit": 64 * 120, "pruneBatch": 8,
                        "windowNanos": 60 * SECOND, "idleNanos": 120 * SECOND},
            "ingressPerMinute": 120,
            "semantics": {"bucketLimit": 128, "eventLimit": 4096, "pruneBatch": 8,
                          "windowNanos": 3600 * SECOND, "idleNanos": 3600 * SECOND,
                          "deleteAllWindowNanos": 24 * 3600 * SECOND},
            "ownerReads": {"bucketLimit": 128, "eventLimit": 4096, "pruneBatch": 8,
                           "windowNanos": 60 * SECOND, "idleNanos": 60 * SECOND, "actorPerMinute": 120},
            "quotas": {
                "bootstrapIpPerHour": 120, "sessionActorPerHour": 30, "sessionIpPerHour": 100,
                "enrollmentEnabled": True, "enrollmentIpPerHour": 10, "enrollmentGlobalPerHour": 2,
                "ownerCreateEnabled": True, "ownerCreateActorPerHour": 10, "ownerCreateGlobalPerHour": 2,
                "ownerDeleteAllEnabled": True, "ownerDeleteAllActorPerDay": 5, "ownerDeleteAllIpPerHour": 20,
            },
            "mutationMembers": {"sharedCreateDeleteAll": True, "memberLimit": 64,
                                "pruneBatch": 8, "retentionNanos": 25 * 3600 * SECOND},
        },
        "ownerCursor": {
            "activeKeyId": "cursor-z", "verificationKeyIds": ["cursor-a", "cursor-z"],
            "envelopeVersion": "v1", "selectionDomain": "owner-list-v1",
            "macDomain": "kira-complaint-owner-cursor-v1", "actorKind": "INSTALLATION",
            "route": "GET:/api/v1/complaints", "direction": "DESC", "ttlSeconds": 900,
            "futureSkewSeconds": 60, "maximumPageLimit": 50, "maximumCursorCharacters": 2048,
            "maximumPayloadBytes": 512, "signatureBytes": 32,
        },
    }


def opening(recipe, evidence, route):
    properties = {
        "PGHOST": "db.invalid", "PGPORT": "5432", "PGDBNAME": "fixture_db", "user": "fixture_user",
        "loginTimeout": "0", "connectTimeout": "1", "socketTimeout": "2", "cancelSignalTimeout": "1",
        "sslmode": "verify-full", "sslfactory": "org.postgresql.ssl.LibPQFactory",
        "sslhostnameverifier": "org.postgresql.ssl.PGjdbcHostnameVerifier", "sslcert": "", "sslkey": "",
        "requireAuth": "password,scram-sha-256", "scramMaxIterations": "100000",
        "gssEncMode": "disable", "channelBinding": "prefer",
    }
    if recipe == "TRACKED_STANDARD":
        properties["socketFactory"] = "me.manga.kira.backend.common.infrastructure.persistence.TrackedPgSocketFactory"
    return {
        "recipe": recipe, "evidencePolicy": evidence, "transportRoute": route,
        "driverUrl": "jdbc:postgresql://", "loginBudgetMillis": 2000, "publicDriverProperties": properties,
    }


def expected_pools(trust):
    password = secret(
        "DATABASE", "fixture-db-password",
        "arn:aws:secretsmanager:eu-west-1:123456789012:secret:fixture-db-Ab12Cd",
        "550e8400-e29b-41d4-a716-446655440000", "AUTHENTICATION_PASSWORD",
    )
    result = []
    for role, maximum, minimum, checkout, validation in (
        ("ORDINARY", 2, 0, 2000, 2000),
        ("DELETION", 4, 4, 500, 250),
        ("CATALOG_COORDINATOR", 1, 1, 250, 250),
    ):
        openings = [opening("TRACKED_STANDARD", "TRACKED_CONJUNCTION", "APPROVED_DIRECT")]
        if role == "ORDINARY":
            openings = [
                opening("ORIGINAL_PROVIDER", "DRIVER_CONTRACT_ONLY", "ORDINARY"),
                opening("TRACKED_STANDARD", "DRIVER_CONTRACT_ONLY", "ORDINARY"),
                opening("TRACKED_STANDARD", "TRACKED_CONJUNCTION", "ORDINARY"),
            ]
        result.append({
            "role": role, "authenticationPassword": password, "publicTrust": trust,
            "hikari": {
                "maximumPoolSize": maximum, "minimumIdle": minimum,
                "connectionTimeoutMillis": checkout, "validationTimeoutMillis": validation,
                "initializationFailTimeoutMillis": -1, "idleTimeoutMillis": 600000,
                "maxLifetimeMillis": 1800000, "keepaliveTimeMillis": 120000,
                "leakDetectionThresholdMillis": 0, "autoCommit": True, "readOnly": False,
                "isolateInternalQueries": False,
            },
            "openings": openings,
        })
    return result


def main():
    capacity = expected_capacity()
    journal = (HERE.parent / "complaint-journal-configuration-v1/initial-live.json").read_bytes()
    assert len(journal) == 5046
    assert sha256(journal) == "e1562c3f07c5983ee40f2212c601782dc3bfdc003ee1bf7f7c01b10951ad64f0"
    trust = public_trust()
    document = {
        "kind": "kira-complaint-effective-configuration", "schemaVersion": 1,
        "canonicalizerId": "kcj-1", "profile": "INITIAL_LIVE_MEMORY_SINGLE_INSTANCE",
        "identity": {
            "mode": "LIVE", "implementationSchema": 1, "desiredGeneration": 7,
            "scopeKind": "LIVE", "scopeId": "00000000-0000-0000-0000-000000000000",
            "databaseIdentity": "11111111-1111-4111-8111-111111111111",
            "restoreIdentity": "22222222-2222-4222-8222-222222222222",
            "writerGeneration": "33333333-3333-4333-8333-333333333333",
        },
        "capacityPolicy": commitment("kira-complaint-capacity-policy", capacity),
        "journalConfiguration": commitment("kira-complaint-journal-configuration", journal),
        "consumers": expected_consumers(),
        "persistence": {
            "profileVersion": 1,
            "admission": {
                "ordinaryOwnerLimitRule": "MIN_4_POOL_MINUS_ONE", "ordinaryOwnerLimit": 1,
                "deletionTotalOwners": 4, "deletionRoutineOwners": 3, "catalogOwners": 1,
            },
            "pools": expected_pools(trust),
        },
    }
    encoded = canonical(document)
    (HERE / "initial-live-memory.json").write_bytes(encoded)
    print(f"D fixture: {len(encoded)} bytes; sha256={sha256(encoded)}")
    print(f"Expected P: {len(capacity)} bytes; sha256={sha256(capacity)}")
    print(f"Frozen J: {len(journal)} bytes; sha256={sha256(journal)}")
    print(f"Public trust: {trust['byteCount']} bytes; sha256={trust['sha256']}")
    print("Independent fixture computation only; production/tests NOT_TESTED.")


if __name__ == "__main__":
    main()
