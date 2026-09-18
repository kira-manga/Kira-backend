# Fixed immediate signer activation3

This is the narrow schema1 producer after actual projected overlap2, not a general catalog
writer or a new desired profile. `CatalogSignerRotationActivationProfileV1` derives its one
signer from the retained cold D7 deployment's second key. D7 canonical bytes, descriptors,
inventory, trust inputs and desired generation remain unchanged. There is no D8 or database
migration. Schema1 `ROTATION_ACTIVATE` maps to existing V14 `SIGNER_ROTATION_ACTIVATION`:
generation2 -> generation3, `SINGLE/ALL_MEMBERS`, new key in slot one, all signer-two SQL
fields NULL. No old-key disable, trust update or human-approval authentication is implied.

## Named entry and ordered effects

Use `assembleTargetSignerRotationActivation` / `prepareTargetSignerRotationActivation`,
then `CatalogSignerRotationActivationV1.begin(process)`. The immutable activation purpose
refuses ordinary, G1, overlap2 and epoch/cutoff phase selection. Ordinary/deletion/epoch
participants remain sealed; no operator pool or generic writer is introduced.

`activate(request, newSigningCredentials, primaryPutCredentials, primaryReadCredentials,
replicaReadCredentials)` accepts the existing intent/approval/root request shape. It performs:

1. Exact bounded intent/approval acquisition, actual released snapshot2, authenticated raw
   G1+overlap2 and a new real B2 lease with both administrative gates closed.
2. Locked exact-two-row history, another independently cleaned raw round and full33 recheck.
   G1 and projected2 frozen bytes must match the authenticated prefix; actual overlap C6 must
   equal the independent canonical raw2 copy evidence. Only then allocate and PREPARE3.
3. Retain the original COMMITTED+released PREPARE, preserve its unsigned outcome, reread raw
   unsigned3/head2 and lock/recheck all three rows. Arm exactly one new-key Sign before SDK
   construction; preserve its verified return, SQL arm and envelope before signature SQL.
4. After actual COMMITTED+released signature persistence, reread/recheck signed PREPARED3.
   A newly created publication arm grants at most one conditional PRIMARY PUT3. No replica
   write or SDK retry exists. After a returned acknowledgement, independently read both
   locations. A lost acknowledgement aborts this invocation; a separate cold recovery must
   obtain fresh raw observations without attempting another PUT.
5. A primary-only observed3 produces `AWAIT_REPLICATION`, not completion. Exact dual3 allows
   COMPLETE3, atomically advancing head3 and pending3 with the same frozen bytes and actual C6.
6. `project()` is a separate no-argument transaction on the same retained owner. It projects
   that exact pending3 and clears only its marker. The result is diagnostic, not transferable
   lease/tuple/slot/project authority. Callers must close an abandoned pending owner.

Each invocation retains one original allowance, refresh slot, native/SDK constructions,
SQL phase/refund and acquisition-dispatch start. At most five raw rounds, one Sign and one
PUT are available. Existing SQL, SDK, readback and lease caps remain unchanged. There is no
renewal, budget reset, capacity recharge or automatic effect retry. UNKNOWN or unproven
cleanup permanently removes that original invocation's eligibility.

## Protected sibling custody

The existing protected root and permanent `rotation.lock` contain only the fixed siblings
`rotation-overlap-2` and `rotation-activation-3`. Activation accesses only its own bounded leaf
inventory, excluding every signer-two leaf. An existing other sibling must be a genuine
same-owner 0700 directory with retained identity; its contents are not opened or modified.
Selected allocation/leaf completeness pairs retain existing no-symlink, permissions, size,
identity, fsync/reread and original-handle cleanup checks. Partial pairs are never repaired.

Existing unsigned binding/allocation/freeze records are reused, not elevated to approval or
physical durability qualification. Independent durable-root provisioning remains mandatory;
choosing another root is not global allocation enforcement. Root/custodian rollback, mount
replacement, hardware durability and external deployment/restore authority remain outside
this boundary.

## Fresh recovery and the only pre-Sign continuation

`recover(request, primaryReadCredentials, replicaReadCredentials)` has no Sign or PUT
credentials. Every supported recovery still needs the matching protected allocation, actual
raw bytes, a genuinely new DB lease after unowned/expired state, and exact locked history.
Present historical owners/tokens and the actual locked previous owner constrain acquisition.

| Current actual state | Permitted action |
|---|---|
| Unsigned PREPARED3; no Sign arm/return | Read-only `PREPARED_UNSIGNED`; cold absence never grants Sign. |
| Unsigned PREPARED3; complete original Sign return/signature/SQL-arm/envelope | Persist that exact retained signature, never sign; return `SIGNED_UNPUBLISHED`. |
| Signed PREPARED3; raw tail2 | `SIGNED_UNPUBLISHED`; no cold publication, even if the old PUT arm has no acknowledgement. |
| Signed PREPARED3; exact primary3 only | Preserve matching observation and return `AWAIT_REPLICATION`; no COMPLETE. |
| Signed PREPARED3; exact dual3 | Fresh DB-only COMPLETE after released PREPARED_RECHECK; retain same-owner separate PROJECT. |
| Actual pending3; exact dual3 | Retain the actual PENDING_RECHECK operation and separate PROJECT; do not invent or replay COMPLETE. |
| Actual projected3; exact dual3 | Exact no-op `PROJECTED` after cleanup; no timestamp rewrite. |

The sole signing continuation is `continueUnattempted(request, previousInvocation, ...)`.
It requires the same retained process/caller, original known-COMMITTED+released PREPARE,
successfully written PREPARED marker, no Sign-arm attempt/construction/return or signature SQL
attempt, and positively proven original close/release under the original allowance. The
one-use witness is consumed before any new custody/lease/provider attempt and never restored.
The new explicit invocation has its own bounded allowance but must acquire a genuinely fresh
lease, independently compare current unsigned3/full33 to the actual earlier PREPARE and verify
the complete untouched prefix. It never replays PREPARE. Later passage of the old deadline
does not invalidate historical cleanup, but no old owner/campaign/slot is reopened.

The existing `signature-sql` marker attests byte-identical stored signature/envelope, without
acquisition/outcome fields. Only a new actual COMMITTED+released SIGNATURE or exact signed
PREPARED_RECHECK may append its identical absent marker from complete return/SQL-arm/envelope
custody. It never turns the original UNKNOWN into success. COMPLETE/PROJECT arms and outcomes
are acquisition-bound: a new lease must not fill an older arm's missing outcome, even after
its own successful DB retry. Exact projected observation is not an invented effect receipt.

Lost/unknown Sign return, partial/mismatched custody, substituted keys/input/root, live lease,
wrong current policy, schema2/interleaved/fourth generation or inconsistent history fail closed.
Cold history capture does not reconstruct an unavailable precrash predecessor full33 preimage.
No broader signing recovery, generic resume command or App29 completion is claimed.
