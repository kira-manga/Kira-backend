package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.NeverOwnerDeleteAllDataKeys
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.journal.TestOrdinaryInventoryHttpFixtureV1
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Genuine lower owner/Admin/empty-ALL AUTH, publication, VERIFY and APPLY -> registered drain.
 * Historical open/D comparisons, signed denial and raw provider replies retain their explicit
 * synthetic fixture provenance. Empty ALL creates no synthetic content. Alias objects have real
 * codec/AEAD bytes but obtain their actual applied rows/U only through the native registered drain.
 * No ACTIVE registered request issuer, production authority or fabricated successful cut is used.
 */
internal fun withManifestFamilyJoinRun(tls: VersionBoundPersistenceConnectedFixture, aliases: Boolean = false,
    action: (TestRunOrdinaryDrainFixtureV1, TestRunVerifiedOwnerDeleteFixture.AdminHistory, TestRunOrdinaryDrainV1) -> Unit) {
    val inputs = TestOrdinaryDrainFixtureInputsV1()
    TestOrdinarySealHttpFixtureV1(manifestPublication = true).use { http ->
        ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls, ordinarySealHttp = http, ordinaryDrain = inputs,
            registeredAdminDelete = true, expireClosedSetupPredecessors = true) { p, runtime, registration, _ ->
            assertTrue(registration.process.consumers.journalConfiguration.ownerDeleteAll)
            assertTrue(registration.process.consumers.journalConfiguration.registeredAdminDelete)
            assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
            ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, audit ->
                TestRunVerifiedOwnerDeleteFixture(p, runtime, registration, ordinary, audit, expectSubsequentProviderReads = true).use { history ->
                    history.authorEarlierHistory(verified = true, applied = true)
                    val ownerProvider = history.provider
                    val admin = history.authorEarlierAdminHistory(verified = true, applied = true)
                    val adminProvider = history.provider
                    history.authorEarlierAllHistory(0, verified = true, applied = true)
                    mergeManifestFamilyProvider(history.provider, ownerProvider)
                    mergeManifestFamilyProvider(history.provider, adminProvider)
                    assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, TestRunSealingV1.begin(registration).seal())
                    TestRunOrdinaryDrainFixtureV1(history, http, inputs).use { f ->
                        if (aliases) retainManifestAdminAliases(f, admin)
                        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
                            val drain = f.begin()
                            val approval = f.approval(drain)
                            assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                                drain.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
                            f.assertReleased(); f.probe.assertReleased(); f.jdbc.assertReleased()
                            TestOrdinaryDrainAccountingCasesV1.assertPaidOrdinaryResult(f, f.provider.objects.toList(), approval, admin.eventId)
                            val ordinaryReads = history.providerImage()
                            val inventoryReads = native.requests.size
                            action(f, admin, drain)
                            f.assertReleased()
                            assertEquals(ordinaryReads, history.providerImage(), "Manifest successors cannot repeat ordinary S3/KMS or registration reads.")
                            assertEquals(inventoryReads, native.requests.size)
                        }
                    }
                }
            }
        }
    }
}

private fun mergeManifestFamilyProvider(target: TestOwnerDeleteJournalPublisherFixture, previous: TestOwnerDeleteJournalPublisherFixture) {
    target.objects.addAll(previous.objects)
    val contexts = previous.kms.requests.filter { it.target() == AwsJournalKmsFixture.GENERATE_TARGET }.map { it.fields()["EncryptionContext"] }.toSet()
    val originalReply = target.kms.respond
    target.kms.respond = { request ->
        if (request.target() == AwsJournalKmsFixture.DECRYPT_TARGET && request.fields()["EncryptionContext"] in contexts) previous.kms.respond(request)
        else originalReply(request)
    }
}

/** Four TOTAL Admin versions: primary, one exact-wire same-key copy, and two retained-key aliases. */
private fun retainManifestAdminAliases(f: TestRunOrdinaryDrainFixtureV1, admin: TestRunVerifiedOwnerDeleteFixture.AdminHistory) {
    requireConnectionFree()
    val primary = f.provider.objects.single { it.key == admin.event.route.objectKey }
    f.provider.objects.add(primary.copy(version = "z-same-key-manifest", bytes = primary.bytes.copyOf()))
    val noKeys = NeverOwnerDeleteAllDataKeys()
    val codec = TestOwnerDeleteJournalCodecV1(f.provider.routing, noKeys)
    f.provider.journal.declaration().routing.keys.filter { it.keyId != admin.event.route.routingKeyId }.take(2).forEach { key ->
        val event = codec.canonicalizeAdmin(admin.event.adminTuple, admin.target, key.keyId)
        val wire = f.provider.envelope(event)
        try { f.provider.objects.add(f.provider.objectFor(wire, event, "retained-${key.keyId}-manifest")) }
        finally { wire.fill(0) }
    }
    assertEquals(0, noKeys.calls.get())
    assertEquals(6, f.provider.objects.size)
    f.provider.assertClientsClosed()
}

/** Exact ordinary receipt/P/U/domain/audit images, excluding only the separate terminal rows. */
internal fun manifestOrdinaryFamilyImage(f: TestRunOrdinaryDrainFixtureV1): Map<String, List<String>> = manifestRaw(f) { connection ->
    fun rows(table: String, relation: String): List<String> = connection.prepareStatement(
        "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t $relation ORDER BY to_jsonb(t)::text",
    ).use { statement ->
        statement.setObject(1, f.scope)
        statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getString(1)) } }
    }
    buildMap {
        listOf("complaint_idempotency_receipts", "installation_deletion_receipts", "complaint_deletion_journal_applied",
            "complaint_installation_ids", "app_installations", "complaint_resource_ids", "complaints", "complaint_deletion_journal_retirements").forEach { table ->
            put(table, rows(table, "WHERE t.data_scope_id = ?"))
        }
        put("audit_log", rows("audit_log", "WHERE t.complaint_data_scope_id = ?"))
        put("complaint_journal_publications", rows("complaint_journal_publications", "WHERE t.data_scope_id = ? AND t.event_kind <> 'INSTALLATION_MANIFEST'"))
        put("complaint_recovery_capacity_reservations", rows("complaint_recovery_capacity_reservations",
            "JOIN complaint_journal_publications p ON p.event_id = t.publication_ref WHERE t.data_scope_id = ? AND p.event_kind <> 'INSTALLATION_MANIFEST'"))
    }
}
