# Source-document signing and rotation

Every new production snapshot is signed with Ed25519. The signature covers the exact `kcj-1` document
bytes plus format, revision, previous revision/checksum, current checksum, and creation timestamp. The
public document retains its strong checksum ETag and returns detached signature metadata in
`X-Config-*` headers and `/api/v1/source-config/document/meta`.

The signed byte sequence is UTF-8:

```text
kira-source-signature-v1\n
<revision>\n
<previous-revision-or-0>\n
<previous-checksum-or-->\n
<current-checksum>\n
<ISO-8601-created-at>\n
<exact canonical document bytes>
```

Clients must recompute SHA-256, select a locally pinned X.509 Ed25519 public key by key id, verify the
signature, and reject revisions below their last accepted revision. `/signing-keys` is discovery only;
it is not a trust root.

## Initial key

```bash
scripts/signing/generate-key.sh prod-YYYY-NN .secrets/signing
scripts/signing/install-github-secret.sh kira-manga/Kira-backend prod-YYYY-NN .secrets/signing
```

Commit only `<key-id>.public.b64` to the application trust store. Never commit or print either private
file. Back up the private key in the organization's secret manager and record its recovery controls.

The repository intentionally contains no production private key and no placeholder public pin. At
release time, verify the protected values exist without reading them:

```bash
gh secret list --repo kira-manga/Kira-backend
```

Required names are `KIRA_SIGNING_PRIVATE_KEY`, `KIRA_SIGNING_ACTIVE_KEY_ID`,
`KIRA_SIGNING_VERIFICATION_KEYS_0_KEY_ID`, and
`KIRA_SIGNING_VERIFICATION_KEYS_0_PUBLIC_KEY`. The app release must receive the same public key through
`KIRA_SOURCE_CONFIG_PINNED_KEYS`. Do not publish either artifact until the two public values match.

## Rotation

1. Generate a new key id and add its public key to backend `verification-keys` and the app's pinned
   trust store while the old key remains accepted.
2. Release the app containing both public keys.
3. Change the active key id/private secret and publish a new document. Verify its new key id and chain.
4. After the supported app population has moved past the overlap release, remove the old public key.

Never reuse a key id for different bytes. Rotation does not reset document revision or the client's
accepted revision floor. If private material is suspected compromised, stop publication, rotate
immediately, and ship a trust-store update through the application release channel.
