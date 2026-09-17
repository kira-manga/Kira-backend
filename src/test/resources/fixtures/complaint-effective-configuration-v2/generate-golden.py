#!/usr/bin/env python3
"""Independent stdlib-only D-v2 expected document; no production or test execution."""

import base64
import hashlib
import json
from pathlib import Path


HERE = Path(__file__).resolve().parent


def sha256(value):
    return hashlib.sha256(value).hexdigest()


def artifact(value):
    return {"sha256": sha256(value), "byteCount": len(value)}


def main():
    document = json.loads((HERE.parent / "complaint-effective-configuration-v1/initial-live-memory.json").read_bytes())
    initial = (HERE / "initial-trust.json").read_bytes()
    current = (HERE / "current-trust.json").read_bytes()
    genesis = (HERE / "genesis.json").read_bytes()
    root = base64.b64decode((HERE / "root-spki.base64").read_bytes())
    assert sha256(genesis) == "306e383768d7924431ed204ab1811f6926bfb96d95e213bf61f99fca20d6da6f"
    assert sha256(root) == "06266bec5bd5dadf863661a8380fb764154d960fbb9f6b50026caea2d8de3e61"
    document["schemaVersion"] = 2
    document["profile"] = "INITIAL_LIVE_MEMORY_SINGLE_INSTANCE_G1_READBACK"
    document["catalogReadback"] = {
        "profileVersion": 1,
        "profile": "G1_EMPTY_ACCEPTED_INVENTORY",
        "initialTrustBundle": artifact(initial),
        "currentTrustBundle": artifact(current),
        "trust": {
            "protocolVersion": 1,
            "rootPublicKey": artifact(root),
            "rootKeyId": "synthetic-offline-root-1",
            "rootAlgorithmId": "RSASSA_PSS_SHA_256",
            "expectedEnvironment": "synthetic-test",
            "minimumBundleVersion": 9,
            "expectedCatalogLocations": [
                {"role": "PRIMARY", "bucket": "kira-synthetic-catalog-primary", "accountId": "111111111111", "region": "us-east-1"},
                {"role": "REPLICA", "bucket": "kira-synthetic-catalog-replica", "accountId": "222222222222", "region": "us-west-2"},
            ],
        },
        "currentWriterGenerationIds": ["66666666-6666-4666-8666-666666666666"],
        "currentApproverIds": ["catalog-approver-a", "catalog-approver-b"],
        "expectedGenesisEnvelopeSha256": sha256(genesis),
        "chain": {
            "protocolVersion": 1,
            "canonicalizerId": "kcj-1",
            "prefix": "complaints/catalog/v1/",
            "maximumEnvelopeBytes": 8 * 1024 * 1024,
            "maximumManifestRecords": 4096,
            "maximumGenerations": 65536,
            "maximumEncodedBytes": 2 * 1024 * 1024 * 1024,
            "pageSize": 1000,
            "maximumPagesPerLocation": 65536,
        },
        "sdk": {
            "protocolVersion": 1,
            "credentialSelection": "EXPLICIT_PRIMARY_REPLICA_SESSIONS",
            "requestTimeoutMillis": 10000,
            "connectTimeoutMillis": 2000,
            "readTimeoutMillis": 2000,
            "maximumListBytes": 2 * 1024 * 1024,
            "maximumErrorBytes": 64 * 1024,
            "maximumObjectBytes": 8 * 1024 * 1024,
        },
        "totalAttemptMillis": 600000,
        "retention": {
            "protocolVersion": 1,
            "calendar": "UTC",
            "rounding": "CEILING_WHOLE_SECOND",
            "creationAnchor": "SIGNED_G1_CREATION",
            "creationMinimumYears": 10,
            "remainingAnchor": "EVALUATION_PLUS_ATTEMPT",
            "remainingMinimumYears": 2,
            "coversWholeAttempt": True,
            "futureCreation": "REFUSE",
        },
    }
    encoded = json.dumps(document, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    (HERE / "initial-live-memory-g1.json").write_bytes(encoded)
    print("initial-live-memory-g1.json", len(encoded), sha256(encoded))


if __name__ == "__main__":
    main()
