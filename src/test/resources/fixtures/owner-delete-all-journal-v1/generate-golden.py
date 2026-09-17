#!/usr/bin/env python3
"""Independent synthetic oracle: Python JSON/struct/HMAC and cryptography AESGCM.

No Kotlin/JDK/product code is imported or executed. The wrapped blobs below are
synthetic trusted-port fixtures, NOT real KMS ciphertext or provider evidence.
Running this writes only the adjacent golden.json; it is not a product test.
"""
import base64
import hashlib
import hmac
import json
from pathlib import Path
import struct

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def b64u(value):
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def frame(values):
    fields = [str(value).encode("utf-8") for value in values]
    return b"".join(struct.pack(">I", len(value)) + value for value in fields)


def canonical(value):
    # These fixed-schema vectors contain ASCII names/strings and integer numbers.
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


WRITER = "33333333-3333-4333-8333-333333333333"
SCOPE = "00000000-0000-0000-0000-000000000000"
PREFIX = f"complaints/journal/v1/{WRITER}/live/{SCOPE}/ordinary/"
BUCKET = "kira-journal-fixture"
KEY_ARN = "arn:aws:kms:us-east-1:123456789012:key/66666666-6666-4666-8666-666666666666"
CONTEXT_KEY = "kira-complaint-journal-context-v1"
HEADER_ORDER = [
    "envelopeSchemaVersion", "payloadSchemaVersion", "canonicalizerId", "objectKind",
    "encryptionAlgorithm", "dataKeyMode", "kmsKeyId", "kmsKeyArn", "bucket", "objectKey",
    "writerGeneration", "ordinaryPrefix", "dataScopeKind", "dataScopeId", "publicationEpoch",
    "routingKeyId", "eventId", "nonce",
]


def vector(count, epoch, key_start, nonce_start):
    actor = "71000000-0000-4000-8000-000000000001"
    operation = "72000000-0000-4000-8000-000000000001"
    fingerprint = b64u(bytes(range(128, 160)))
    routing_key = bytes(range(32, 64))
    routing_fields = [
        "1", WRITER, PREFIX, "LIVE", SCOPE, epoch, "route-b", "OWNER_DELETE_ALL",
        "INSTALLATION", actor, 7, operation, fingerprint,
    ]
    opaque = b64u(hmac.new(routing_key, frame(["kira-complaint-journal-routing-v1"] + routing_fields), hashlib.sha256).digest())
    event_id = b64u(hmac.new(routing_key, frame(["kira-complaint-journal-event-id-v1"] + routing_fields), hashlib.sha256).digest())
    object_key = f"{PREFIX}writer/{WRITER}/epoch/{epoch:019d}/route-b/{opaque}"
    payload = {
        "schemaVersion": 1, "eventKind": "OWNER_DELETE_ALL", "eventId": event_id,
        "publicationEpoch": epoch, "writerGeneration": WRITER, "actorKind": "INSTALLATION",
        "actorId": actor, "credentialVersion": 7, "operationKey": operation,
        "requestFingerprint": fingerprint, "ownerInstallationIds": [actor],
        "dataScopeKind": "LIVE", "dataScopeId": SCOPE,
        "complaintIds": [f"73000000-0000-1000-8000-{index:012x}" for index in range(count)],
    }
    plaintext = canonical(payload)
    nonce = bytes(range(nonce_start, nonce_start + 12))
    key = bytes(range(key_start, key_start + 32))
    wrapped = bytes(range(160, 224))
    header = {
        "envelopeSchemaVersion": 1, "payloadSchemaVersion": 1, "canonicalizerId": "kcj-1",
        "objectKind": "OWNER_DELETE_ALL", "encryptionAlgorithm": "AES-256-GCM",
        "dataKeyMode": "FRESH_PER_OBJECT_KMS_WRAPPED", "kmsKeyId": "journal-kms", "kmsKeyArn": KEY_ARN,
        "bucket": BUCKET, "objectKey": object_key, "writerGeneration": WRITER, "ordinaryPrefix": PREFIX,
        "dataScopeKind": "LIVE", "dataScopeId": SCOPE, "publicationEpoch": epoch,
        "routingKeyId": "route-b", "eventId": event_id, "nonce": b64u(nonce),
    }
    header_bytes = canonical(header)
    header_values = [header[name] for name in HEADER_ORDER]
    context_frame = frame(["kira-complaint-journal-kms-context-v1", "1"] + header_values)
    aad = frame(
        ["kira-complaint-journal-aad-v1", "1", "KJEV", "1", len(header_bytes)] + header_values
        + [len(wrapped), b64u(wrapped), len(plaintext) + 16]
    )
    encrypted = AESGCM(key).encrypt(nonce, plaintext, aad)
    wire = (
        b"KJEV" + struct.pack(">II", 1, len(header_bytes)) + header_bytes
        + struct.pack(">I", len(wrapped)) + wrapped + struct.pack(">I", len(encrypted)) + encrypted
    )
    return {
        "name": f"owner-delete-all-{count}", "payload": payload, "header": header,
        "plaintextHex": plaintext.hex(), "headerHex": header_bytes.hex(),
        "dataKeyHex": key.hex(), "nonceHex": nonce.hex(), "wrappedKeyHex": wrapped.hex(),
        "kmsContextFrameHex": context_frame.hex(), "kmsContextKey": CONTEXT_KEY,
        "kmsContextValue": b64u(context_frame), "aadHex": aad.hex(),
        "ciphertextAndTagHex": encrypted.hex(), "wireHex": wire.hex(),
        "semanticSha256": hashlib.sha256(plaintext).hexdigest(), "wireSha256": hashlib.sha256(wire).hexdigest(),
    }


if __name__ == "__main__":
    output = {
        "fixture": "owner-delete-all-journal-v1",
        "authority": "synthetic bytes only; no KMS, storage or application evidence",
        "vectors": [vector(0, 42, 0, 0), vector(100, 43, 32, 12)],
    }
    Path(__file__).with_name("golden.json").write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
