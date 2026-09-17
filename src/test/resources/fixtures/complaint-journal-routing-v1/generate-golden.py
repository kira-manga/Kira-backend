"""Independent stdlib fixture authoring; not a production codec or a test runner."""

import base64
import hashlib
import hmac
import json
import struct


def b64(value):
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


writer = "33333333-3333-4333-8333-333333333333"
scope = "00000000-0000-0000-0000-000000000000"
prefix = f"complaints/journal/v1/{writer}/live/{scope}/ordinary/"
key_id = "route-b"
key = bytes(range(32, 64))
fingerprint = bytes(range(128, 160))
vectors = []
for kind, actor, actor_id, credential, epoch, operation in [
    ("OWNER_DELETE", "INSTALLATION", "71000000-0000-4000-8000-000000000001", 7,
     42, "72000000-0000-4000-8000-000000000001"),
    ("ADMIN_BATCH_DELETE", "ADMIN", "73000000-0000-1000-8000-000000000001", None,
     9223372036854775807, "72000000-0000-4000-8000-000000000002"),
]:
    fields = ["1", writer, prefix, "LIVE", scope, str(epoch), key_id, kind, actor,
              actor_id, "" if credential is None else str(credential), operation, b64(fingerprint)]
    vector = dict(eventKind=kind, actorKind=actor, actorId=actor_id, credentialVersion=credential,
                  epoch=epoch, operationKey=operation, fingerprintHex=fingerprint.hex(),
                  routingKeyId=key_id, keyHex=key.hex(), fieldsAfterDomain=fields)
    for purpose, name in [("routing", "routing"), ("event-id", "eventId")]:
        domain = f"kira-complaint-journal-{purpose}-v1"
        encoded = [field.encode("utf-8") for field in [domain] + fields]
        frame = b"".join(struct.pack(">I", len(field)) + field for field in encoded)
        digest = hmac.new(key, frame, hashlib.sha256).digest()
        vector[name + "FrameHex"] = frame.hex()
        vector[name + "FrameBytes"] = len(frame)
        vector[name + "MacHex"] = digest.hex()
        vector[name] = b64(digest)
    vector["objectKey"] = f"{prefix}writer/{writer}/epoch/{epoch:019d}/{key_id}/{vector['routing']}"
    vectors.append(vector)

print(json.dumps({"description": "Synthetic independent LP32BE-UTF8 deletion-routing vectors; no provider or authorization proof.",
                  "vectors": vectors}, indent=2))
