# Ephemeral synthetic W03 TLS material — NEVER deploy

This directory intentionally contains **no private key, certificate, encoded key constant, or
keystore**. `PgLifecycleTlsMaterialOwner` creates fresh test-only material before each candidate
child launch using the selected standard JDK21 `keytool`. There is no shell, OpenSSL invocation,
new dependency, private-JDK API, trust-all client, secret-scanner exception, or user-key fallback.

Eight fixed sequential commands generate two independent RSA-2048/SHA-256 CA keys and one server
key, produce a CSR, sign matching/wrong-host leaves, and export the two CA certificates. Roots
have CA=true/pathlen=0; leaves have serverAuth and either IP `127.0.0.1`/DNS `localhost` or IP
`127.0.0.2`/DNS `wrong-host.synthetic.invalid`. Dates are exactly 2025-01-01 through 2055-01-01 UTC.
Both leaves share the server key and matching issuer. The wrong CA is not a missing-path test.

Generation uses only a private 0700 staging directory and precreated 0600 files under the owned
test root. The public low-entropy `synthetic` store password is not a deployment credential.
JKS files are **runtime-only**, not a disguise for committed private material. No home keystore,
root `.p12`, `.env`, signing key, client identity or real-service credential is read or imported;
stock JDK security defaults remain intact. The utility environment is the existing exact sanitized
probe environment, with explicit synthetic home/tmp/name and UTC JVM options. Standard JDK tools
may read their installation's public security configuration; no installed trust store is modified.

All eight process owners and output-reader threads exist before generation starts. Generation has
one 60s observation budget, a 15s per-command cap, bounded discarded output, closed stdin and actual
process/thread cleanup. Failure, timeout, overflow, emergency termination or incomplete cleanup is
not a skip/PASS. Both CA private stores and the CSR are removed before the candidate child exists.
The child receives four public PEM certificates, one ephemeral server JKS and a bounded per-run
SHA-256 manifest. It checks permissions, hashes, dates, CA independence, signatures, exact SANs,
key usage and key matching before constructing its server-only JSSE context through public APIs.

The stock pgjdbc `LibPQFactory` still receives an existing absolute CA path, `verify-full`, and
explicit empty client cert/key properties. Ordinary 6000ms and deletion 2000ms lifecycle budgets
are unchanged; material generation finishes before either starts. The child deletes material after
its sockets/threads close. The parent verifies that deletion and separately retains fallback cleanup
for partial generation, launch failure or child crash. An unended generation actor fails cleanup;
files are not removed underneath a process that might recreate them. Deletion is not a claim of
physical-memory or storage-media cryptographic erasure, nor hard native-call containment.

These tests are **REAL_TLS+PROTOCOL_PEER**, not PostgreSQL or a remote-session disappearance oracle.
The server selects TLSv1.2 only. Wrong-CA handling witnesses an actual ClientHello and a failed
initial TLS handshake, then independently requires raw EOF/reset while the local raw socket is
open and neither half is shut down. The already-closed `autoClose=false` TLS layer's documented
no-op close barrier cannot manufacture that witness. A TLS exception or alert string alone never
proves disconnect. Matching, wrong-host and partial-handshake vectors keep their existing oracles.
