# Kira backend — remediation handoff

**2026-09-08: owner-requested WIP checkpoint. NOT READY FOR PRODUCTION.**

This branch checkpoints the incomplete App #29 domain/schema/persistence foundation. W01/W02 have historical local acceptance; W03 is unfinished and the driver owner is not wired to Hikari/Spring/JDBC operations or complaint APIs. Development07 compiled and ran138 focused tests:136 passed,2 failed; one Ktlint and two Detekt findings remain. Do not merge, deploy or close the issue.

## Resume from the authoritative workspace record

Start with the [private workspace handoff](https://github.com/kira-manga/kira-admin/blob/remediation/app-29-backend-complaints/docs/remediation/checkpoint-2026-09-08/README.md) (authorized organization access required).
It includes the full tracker, decisions, all reviewed issue explanations, approved plans/reviews,
failed and passing raw evidence, exact source hashes, unfinished drafts, exclusions, restore tools
and the precise next actions. Do not repeat completed audit/helper cycles or skip unresolved gates.
A fresh workspace must restore its `review/` archive without overwriting existing work.

- This repository's checkpoint branch: `remediation/app-29-backend-complaints`.
- Integration branch: `remediation/production-readiness-2026-09-04` (not advanced to incomplete WIP).
- Campaign delivery: **0/152 issues merged (0%)**. App #29: **2/9 unequal packages locally accepted
  (22.2%)**, W03 active. A checkpoint commit is not a validated fix or package acceptance.
- Read the nearest `AGENTS.md` and relevant owner docs, inspect branch/HEAD/status, then follow
  `review/AGENT_HANDOFF.md` and `review/REMEDIATION_TRACKER.md` in the restored workspace.

## Owner decisions that must survive the handoff

App #29 moves complaints/feedback/moderation behind authenticated backend APIs. **Skip W06 entirely:**
no old Firestore complaint export/import/reconciliation/replay; the backend starts empty. Preserve
new-backend-data and installation recovery. App #2 stays unchanged. App #46 stays non-blocking for
zero active sources. The47 disproved candidates stay dismissed absent new concrete evidence.

Every selected issue still needs its independent branch, investigation, approved plan, authoritative
research, independent regression/actual-diff review, tests and validated integration merge. Approved
reviewer model: GPT-6-Astra/max. Do not infer deployment, main merge or issue-closure authorization.

## Next checkpoint boundary and hygiene

Development07 is finished, not running. Its two test failures and three static findings are saved;
D05's supplemental draft is PARTIAL/NOT IMPORT-READY, D06's is unreviewed/unexecuted. The next agent
must investigate those exact failures and preserve all D01–D15/C6 gates before any new acceptance.
Full later W03 transaction/SQL work and all later packages remain. Stop here until owner resumption.

After each owned Gradle output-generating batch: stop its Gradle daemon immediately, retain required
evidence, clean only unnecessary generated outputs, stop the clean-task daemon and verify cleanup.
Stop owned Node/test/service workers too. Never delete source/configuration/signing material, purge
global dependency caches, stop foreign tasks, expose secrets or publish private audit material here.
