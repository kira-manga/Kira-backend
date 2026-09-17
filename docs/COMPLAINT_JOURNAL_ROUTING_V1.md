# Local acquired journal routing V1

`VersionBoundComplaintJournalRouting.fromAcquired(J, acquisitions)` is the actual
local HMAC consumer for this narrow INITIAL_LIVE deletion profile. It retains the
same immutable J and exactly its complete 1–4 routing-key ID/full-version pairs.
Every acquisition must be `COMPLAINT_JOURNAL_ROUTING / HMAC_SHA256`, with 32–128
bytes. Missing/extra/relabelled versions and same-effective-HMAC keys (including
zero padding and long-key hashing) are rejected. Input material is copied; no
resolver or provider is called. Descriptors and the fixed `journal-routing`
forbidden-family comparison tags derive from these actual retained keys, not a
second material list. The connected consumer factory must retain this same owner.

## Exact routing tuple and bytes

Only `OWNER_DELETE`, `OWNER_DELETE_ALL`, `ADMIN_DELETE`, and `ADMIN_BATCH_DELETE`
are supported. Owner events require an INSTALLATION actor with UUIDv4 and positive
credential version. Admin events require an ADMIN UUID and **absent** credential
version; a supplied version is rejected, not ignored. All require positive signed
64-bit epoch, UUIDv4 operation key, copied 32-byte normalized fingerprint and LIVE
scope. These checks do not prove actor authentication, database assignment of the
epoch, fingerprint normalization or target selection/counts.

Both HMAC-SHA-256 inputs use the fixed J `LP32BE-UTF8` framing: each field has an
unsigned four-byte big-endian UTF-8 byte length, including empty fields and the
first domain field. Exact order:

1. `kira-complaint-journal-routing-v1` for object suffix, or
   `kira-complaint-journal-event-id-v1` for event ID;
2. literal `1`;
3. J writer-generation UUID;
4. complete J-derived ordinary prefix;
5. literal `LIVE`;
6. all-zero LIVE scope UUID;
7. epoch in canonical decimal;
8. this retained routing-key ID;
9. event-kind enum name;
10. actor-kind enum name;
11. actor UUID;
12. positive decimal installation credential version, or empty admin field;
13. operation-key UUID;
14. unpadded Base64url encoding of the 32-byte fingerprint.

UUID spelling is canonical lowercase. Domains, framing version, namespace and
writer are fixed/owner-derived, not caller-selected. Each resulting HMAC is
unpadded Base64url (43 characters, canonical pad bits), matching the V14 event-ID
grammar. Every retained candidate is derived locally, sorted by key ID, and the
explicit J active ID selects one of those same candidates. A retry of this tuple
under this immutable owner returns the same paths/IDs without a clock or lookup.

The object key is exactly:

```
<J ordinaryPrefix>writer/<J writer UUID>/epoch/<epoch padded to 19 digits>/<key ID>/<routing HMAC>
```

The existing ordinary prefix already includes writer/LIVE/scope. Treating that
registered prefix as V6's `<namespace>` deliberately nests the `writer/...`
suffix; it does **not** change J bytes, existing policy prefixes or registration.
Only the allowed nonsecret namespace/writer/epoch/key ID appear in cleartext;
actor ID, operation key and fingerprint appear only in the HMAC input.

These field-order/empty-field/fingerprint-text/padding choices complete the small
internal byte contract left unspecified by V6, using J's fixed framing/domains.
They do not define general event serialization or a separate D fragment/hash.

## Deliberate limits

No retention-policy operation-key producer, INSTALLATION_RETIREMENT, TEST scope,
seal, installation manifest or purge tuple is supported. No target list/count
validation, canonical payload, envelope/AAD/KMS context, encryption, AWS call,
publication/outbox freeze, collision lookup, accepted catalog, key-retirement
permission or activation is implemented. Retained-key coverage and real provider
truth remain separate obligations. Local candidate derivation is not durable
deletion authorization, even when its bytes are deterministic.
