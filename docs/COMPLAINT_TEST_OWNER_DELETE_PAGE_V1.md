# Registered TEST owner-delete: one local primary page

`TestRunPreparedOwnerDeleteV1.beginPage(...).completePage()` discovers and completes
at most **two** existing local OWNER_DELETE primaries. It requires the same live
`ComplaintTestNamespaceRegistrationV1`, retained deletion ownership/template and BYO
provider credentials as existing single-primary continuation. There is no controller,
automatic pagination, retry loop, new phase enum, pool or publication-lane owner.

The private selection operation uses the existing registered, read-only RELOAD path:
M, E, global/exact TEST controls, current counters, SEALED run and its paid audit. It
reads at most three ordered receipt/publication pairs; the third is a bounded peek.
Both pending OWNER_DELETE receipts (including unexpected IN_PROGRESS) and non-APPLIED
OWNER_DELETE publications anchored in the registered scope are considered. A full join
preserves missing or mismatched partners. Invalid shape, another writer, or authorization
after the seal refuses rather than disappearing behind a validity/time filter. Ordering
is deterministic, with no OFFSET or SKIP LOCKED. Canonical event/receipt/proof checks still
belong to each primary's original RELOAD; selected UUID pairs grant no work authority.

Only the original committed and physically released selection may start a selected child.
Each child uses the existing PREPARED/VERIFIED completion and the exact same enclosing
budget, caller and retained page custody. It rechecks the same registered SEALED timestamp.
No DB resource crosses provider work. An uncertain commit, failed cleanup, cancellation,
deadline or authority refusal stops the page before another child; the failed original
cannot be rehabilitated. Earlier children may already have committed: the page is not one
atomic multi-primary mutation. An explicit fresh attempt, after actual cleanup, starts
again from pending rows and uses existing exact continuation/replay rules.

`PageProgress` contains only the number of positively completed selected primaries and
whether the selection observed a third candidate. Neither zero nor `moreObserved=false`
certifies complete local history, retained-key families, aliases, other writers, provider
inventory, ordinary-range quiescence or unused reserves. Unrelated namespaces are not
selected. Fulfilled histories, scan/reconstruction authority, permanent denial, final
ordinary seals and terminal publication remain outside this page. Ordinary APPLY retains
its existing PARTIAL promise and measured accounting; no reserve conversion/release is added.

Four focused connected methods reuse the current registration/history/provider fixture:
mixed-state paging and empty replay; pending corruption refusal; original selection
commit/release refusal; and shared outer cancellation/deadline. Earlier ACTIVE/checkpoint
comparisons remain explicitly synthetic, not launched-run or provider-drain evidence.
Existing per-primary negatives are not duplicated. All authored tests are **NOT_RUN**;
this source packet has no build, runtime, CI or deployment qualification.
