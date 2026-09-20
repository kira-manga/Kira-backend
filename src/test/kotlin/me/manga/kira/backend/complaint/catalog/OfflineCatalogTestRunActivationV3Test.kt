package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalDocumentV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogInventoryDeltaV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogRotationState
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunActivationHeadV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunActivationRecordV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainLimits
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationManifestV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogFrozenManifestParser
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCanonicalV3
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogInventoryChainVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogRotationChainVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleVerifier
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogInventoryParser
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunActivationParser
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import me.manga.kira.backend.complaint.parsing.catalog.ParsedOfflineTestRunCatalogGeneration
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import java.util.UUID

/** Supplied bytes and actual cold declarations only; no accepted TEST run, SQL projection or issuer. */
class OfflineCatalogTestRunActivationV3Test {
    @Test
    fun canonicalRunHistoryAccountingAndNoticeBytesBindRetainedTestProcess() {
        withActivationEvidence { f ->
            val unsigned = CatalogTestRunActivationEvidenceFixture.manifestBytes(f.manifest)
            assertArrayEquals(unsigned, f.assembled())
            assertEquals(f.manifest, OfflineCatalogTestRunActivationParser.parseManifest(unsigned, 4096))
            val run = f.manifest.activationRecord.run
            assertEquals(run, f.expected.run()) // Independent fixed22/literal-notice fixture, not production derivation.
            assertEquals(Sha256.hex(f.process.canonicalBytes()), run.configurationSha256)
            assertEquals(1, run.implementationSchema)
            assertEquals(7L, run.desiredGeneration)
            assertEquals(1L, run.firstPublicationEpoch)
            assertEquals(501L, run.installationLimit)
            assertArrayEquals(
                f.journal.canonicalBytes(),
                CanonicalJson.canonicalize(TestOwnerDeleteJournalDocumentV1.serializer(), run.journalConfiguration).toByteArray(Charsets.UTF_8),
            )
            assertArrayEquals(f.journal.canonicalBytes(), TestOwnerDeleteJournalConfigurationV1.fromDocument(f.journal.document()).canonicalBytes())
            assertEquals("TEST", run.journalConfiguration.dataScopeKind)
            assertEquals(f.journal.scope.id.toString(), run.testRunId)

            val record = Json.parseToJsonElement(CanonicalJson.canonicalize(CatalogTestRunActivationRecordV1.serializer(), f.manifest.activationRecord)).jsonObject
            assertEquals(setOf("operationToken", "generation", "previousEnvelopeSha256", "run"), record.keys)
            val runJson = record.getValue("run").jsonObject
            assertEquals(
                setOf("testRunId", "implementationSchema", "desiredGeneration", "configurationSha256", "journalConfiguration", "firstPublicationEpoch", "installationLimit", "terminalEncoding", "accounting", "noticeSeeds"),
                runJson.keys,
            )
            assertEquals("{\"profile\":\"TEST_TERMINAL_V1\",\"schemaVersion\":1}", CanonicalJson.canonicalize(runJson.getValue("terminalEncoding")))
            val heads = Json.parseToJsonElement(unsigned.decodeToString()).jsonObject.getValue("history").jsonObject
            assertEquals(
                setOf("expiredRestoreSources", "testRunActivations", "testRunTerminals", "installationManifests", "epochSeals", "retirementAuthorizations", "retirementCompletions"),
                heads.keys,
            )
            val emptyHead = Json.parseToJsonElement("{\"count\":0,\"sha256\":\"4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945\"}")
            heads.filterKeys { it != "testRunActivations" }.values.forEach { assertEquals(emptyHead, it) }
            assertEquals(1L, f.manifest.history.testRunActivations.count)
            assertEquals(Sha256.hexUtf8(CanonicalJson.canonicalize(JsonArray(listOf(record)))), f.manifest.history.testRunActivations.sha256)

            assertEquals("TEST_TERMINAL_ACCOUNTING_V1", run.accounting.profile)
            assertEquals(1, run.accounting.capacityEncodingVersion)
            assertEquals(listOf<Long>(0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3_219_968, 0), run.accounting.activationCatalogPrepareActual)
            assertEquals(listOf<Long>(0, 4, 0, 2, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 2, 0, 0, 1_933_120, 1), run.accounting.activationProjectionActual)
            assertEquals(listOf<Long>(501, 504, 1, 0, 0, 0, 0, 501, 0, 0, 0, 3, 0, 0, 0, 0, 3, 0, 20_000, 2, 833_972_608, 0), run.accounting.originalUnusedReserve)
            assertEquals(listOf("complaints.notice.content-policy", "complaints.notice.source-requirements"), run.noticeSeeds.map { it.noticeKey })
            run.noticeSeeds.forEach {
                assertEquals(1, it.definitionVersion)
                assertEquals(4, UUID.fromString(it.resourceId).version())
                assertEquals(2, UUID.fromString(it.resourceId).variant())
            }

            val checked = f.verify()
            assertArrayEquals(unsigned, checked.canonicalManifestBytes)
            assertArrayEquals(f.envelopeBytes, checked.canonicalEnvelopeBytes)
            checked.canonicalEnvelopeBytes.fill(0)
            checked.canonicalManifestBytes.fill(0)
            (checked.manifest.activationRecord.run.accounting.originalUnusedReserve as MutableList<*>).clear()
            (f.expected.run().journalConfiguration.routingKeys as MutableList<*>).clear()
            (f.expected.run().noticeSeeds as MutableList<*>).clear()
            assertEquals(f.manifest, checked.manifest)
            assertEquals(run, f.expected.run())
            assertArrayEquals(f.envelopeBytes, checked.canonicalEnvelopeBytes)
            assertTrue(listOf(f.pools.ordinary, f.pools.deletion, f.pools.catalogCoordinator.dataSource).none { actualPool(it).isRunning })
        }
    }

    @Test
    fun acceptsGenesisRotationAndInventoryPrefixesOnlyUnderCurrentStableSigner() {
        for (prefix in listOf(ActivationEvidencePrefix.GENESIS, ActivationEvidencePrefix.ROTATED, ActivationEvidencePrefix.INVENTORY_ROTATED)) {
            withActivationEvidence(prefix) { f ->
                val before = OfflineCatalogInventoryChainVerifier.verifyInventoryChain(f.prefix.asSequence(), f.initial, f.current, f.policy)
                val checked = f.verify()
                assertEquals(CatalogRotationState.Stable(OfflineCatalogRotationFixture.member(f.signerId)), checked.rotation)
                assertEquals(f.generation, checked.tail.generation)
                assertTrue(checked.tail.generation in listOf(2L, 4L, 6L))
                assertEquals(3, checked.manifest.schemaVersion) // Profile schema is not catalog generation3.
                assertEquals(before.inventory, checked.inventory)
                assertEquals(CatalogInventoryDeltaV1(emptyList(), emptyList()), checked.manifest.inventoryDelta)
                assertEquals(f.complete.sumOf { it.size.toLong() }, checked.encodedBytes)
                assertEquals(Sha256.hex(f.envelopeBytes), checked.tail.envelopeSha256)
                assertArrayEquals(CatalogTestRunActivationEvidenceFixture.manifestBytes(f.manifest), f.assembled())
                f.prefix.drop(1).forEach {
                    assertInstanceOf(ParsedOfflineTestRunCatalogGeneration.Prefix::class.java, OfflineCatalogTestRunActivationParser.parseGeneration(it, 4096))
                }
                assertEquals("catalog-old", checked.manifest.initialWriterRegistry.catalogWriter.requiredSignerPolicy.members.single().keyId)
                if (prefix != ActivationEvidencePrefix.GENESIS) assertEquals("catalog-new", checked.rotation.active.keyId)
            }
        }
    }

    @Test
    fun rejectsWrongSignerPendingOverlapAndCorruptSignature() {
        withActivationEvidence { f ->
            assertEquals(f.head.envelopeSha256, f.verify().tail.envelopeSha256)
            val signature = f.envelope.signatures.single()
            val changed = Base64.getDecoder().decode(signature.signatureBase64).also { it[0] = (it[0].toInt() xor 1).toByte() }
            val forged = f.envelope.copy(signatures = listOf(signature.copy(signatureBase64 = Base64.getEncoder().encodeToString(changed))))
            val bytes = CatalogTestRunActivationEvidenceFixture.bytes(forged)
            assertEquals(forged, OfflineCatalogTestRunActivationParser.parse(bytes, 4096))
            fails(OfflineTrustBundleFailure.INVALID_SIGNATURE) { f.verify(f.prefix + bytes) }
        }
        // The old key is still genuinely in current trust and retained by this real process. The
        // failure must instead be the actual prefix's completed new-key rotation, not an expected-ID mismatch.
        withActivationEvidence(ActivationEvidencePrefix.INVENTORY_ROTATED, "catalog-old") { f ->
            assertEquals(f.envelope, OfflineCatalogTestRunActivationParser.parse(f.envelopeBytes, 4096))
            val before = OfflineCatalogInventoryChainVerifier.verifyInventoryChain(f.prefix.asSequence(), f.initial, f.current, f.policy)
            assertEquals(CatalogRotationState.Stable(OfflineCatalogRotationFixture.member("catalog-new")), before.rotation)
            fails(OfflineTrustBundleFailure.POLICY_MISMATCH) { f.assembled() }
            fails(OfflineTrustBundleFailure.INVALID_DOCUMENT) { f.verify() }
        }
        withActivationEvidence(ActivationEvidencePrefix.PENDING_OVERLAP) { f ->
            assertEquals(f.envelope, OfflineCatalogTestRunActivationParser.parse(f.envelopeBytes, 4096))
            val before = OfflineCatalogInventoryChainVerifier.verifyInventoryChain(f.prefix.asSequence(), f.initial, f.current, f.policy)
            assertInstanceOf(CatalogRotationState.AwaitingActivation::class.java, before.rotation)
            fails(OfflineTrustBundleFailure.POLICY_MISMATCH) { f.assembled() }
            fails(OfflineTrustBundleFailure.INVALID_DOCUMENT) { f.verify() }
        }
    }

    @Test
    fun rejectsMalformedNoncanonicalAndMixedSchemaDocumentsWithoutWideningOldReaders() {
        withActivationEvidence { f ->
            assertEquals(f.envelope, OfflineCatalogTestRunActivationParser.parse(f.envelopeBytes, 4096))
            val text = f.envelopeBytes.decodeToString()
            val root = Json.parseToJsonElement(text).jsonObject
            val malformed = listOf(
                byteArrayOf(0xc3.toByte(), 0x28),
                (text + "{}").toByteArray(),
                ("{\"schemaVersion\":3," + text.drop(1)).toByteArray(),
                CanonicalJson.canonicalize(JsonObject(root + ("unexpected" to JsonPrimitive("field")))).toByteArray(),
                text.replace("\"activationRecord\":{", "\"activationRecord\":{\"envelopeSha256\":\"${"0".repeat(64)}\",").toByteArray(),
                text.replace("\"firstPublicationEpoch\":1", "\"firstPublicationEpoch\":null").toByteArray(),
                text.replace("\"firstPublicationEpoch\":1", "\"firstPublicationEpoch\":1.0").toByteArray(),
                text.replace("\"TEST_TERMINAL_V1\"", "\"LIVE_TERMINAL_V1\"").toByteArray(),
                CatalogTestRunActivationEvidenceFixture.bytes(f.envelope.copy(schemaVersion = 2)),
                signedBytes(f.manifest.copy(schemaVersion = 2)),
                signedBytes(f.manifest.copy(profile = "NEW_BACKEND_LOGICAL_BUNDLE_V1")),
                signedBytes(f.manifest.copy(operation = "ROTATION_ACTIVATE")),
            )
            malformed.forEach { bytes ->
                assertFalse(bytes.contentEquals(f.envelopeBytes))
                assertThrows<OfflineTrustBundleException> { OfflineCatalogTestRunActivationParser.parse(bytes, 4096) }
                assertThrows<OfflineTrustBundleException> { OfflineCatalogTestRunActivationParser.parseGeneration(bytes, 4096) }
            }
            val reordered = JsonObject(root.entries.reversed().associate { it.key to it.value }).toString().toByteArray()
            for (bytes in listOf(f.envelopeBytes + byteArrayOf(10), reordered)) {
                fails(OfflineTrustBundleFailure.NON_CANONICAL) { OfflineCatalogTestRunActivationParser.parse(bytes, 4096) }
            }
            assertThrows<OfflineTrustBundleException> { OfflineCatalogInventoryParser.parse(f.envelopeBytes, 4096) }
            assertThrows<OfflineTrustBundleException> { OfflineTrustBundleParser.parseRotation(f.envelopeBytes, 4096) }
            assertThrows<OfflineTrustBundleException> { OfflineCatalogInventoryChainVerifier.verifyInventoryChain(f.complete.asSequence(), f.initial, f.current, f.policy) }
            assertEquals(CatalogReadbackFailure.INVALID_LOCAL_STATE, assertThrows<CatalogReadbackException> {
                CatalogFrozenManifestParser.unsigned(3, CatalogTestRunActivationEvidenceFixture.manifestBytes(f.manifest), f.policy.limits)
            }.code)
            assertEquals(CatalogReadbackFailure.INVALID_LOCAL_STATE, assertThrows<CatalogReadbackException> {
                CatalogFrozenManifestParser.schemaVersion(f.envelopeBytes, f.policy.limits)
            }.code)
            // The legacy signed parser checks arrays before schema dispatch: TEST's fixed22
            // capacity vectors exceed its unchanged generic16 ceiling.
            assertEquals(CatalogReadbackFailure.LIMIT_EXCEEDED, assertThrows<CatalogReadbackException> {
                CatalogFrozenManifestParser.signed(f.envelopeBytes, f.policy.limits)
            }.code)
        }
        withActivationEvidence(ActivationEvidencePrefix.GENESIS) { f ->
            assertEquals(2L, f.verify().tail.generation)
            assertThrows<OfflineTrustBundleException> {
                OfflineCatalogRotationChainVerifier.verifyRotationChain(f.complete.asSequence(), f.initial, f.current, f.policy)
            }
        }
    }

    @Test
    fun rejectsHistoryInventoryRecordLinkageAndTrailingGenerationSubstitution() {
        withActivationEvidence { f ->
            assertEquals(f.manifest, f.verify().manifest)
            val h = f.manifest.history
            val nonempty = CatalogTestRunActivationHeadV1(1, Sha256.hexUtf8("[]"))
            listOf(
                h.copy(testRunActivations = h.testRunActivations.copy(count = 0)),
                h.copy(testRunActivations = h.testRunActivations.copy(sha256 = "0".repeat(64))),
                h.copy(expiredRestoreSources = nonempty), h.copy(testRunTerminals = nonempty),
                h.copy(installationManifests = nonempty), h.copy(epochSeals = nonempty),
                h.copy(retirementAuthorizations = nonempty), h.copy(retirementCompletions = nonempty),
            ).forEach { reject(f, f.manifest.copy(history = it), OfflineTrustBundleFailure.BUNDLE_HASH_MISMATCH) }
            reject(f, f.manifest.copy(restoreInventory = f.inventory.copy(copies = f.inventory.copies.dropLast(1))), parses = true)
            reject(f, f.manifest.copy(inventoryDelta = CatalogInventoryDeltaV1(listOf(f.inventory.sources.single().sourceId), emptyList())))
            reject(f, f.manifest.copy(inventoryDelta = CatalogInventoryDeltaV1(emptyList(), listOf(f.inventory.copies.first().copyId))))

            val record = f.manifest.activationRecord
            listOf(record.copy(operationToken = OTHER_TOKEN), record.copy(generation = f.generation + 1), record.copy(previousEnvelopeSha256 = "0".repeat(64))).forEach {
                reject(f, f.manifest.copy(activationRecord = it, history = CatalogTestRunActivationEvidenceFixture.history(it)))
            }
            val wrongLink = record.copy(previousEnvelopeSha256 = "0".repeat(64))
            reject(f, f.manifest.copy(previousEnvelopeSha256 = wrongLink.previousEnvelopeSha256, activationRecord = wrongLink, history = CatalogTestRunActivationEvidenceFixture.history(wrongLink)), parses = true)
            reject(f, f.manifest.copy(oldestRestoreTimeEpochSecond = f.manifest.oldestRestoreTimeEpochSecond + 1), parses = true)
            fails(OfflineTrustBundleFailure.INVALID_DOCUMENT) { f.verify(f.prefix) }

            val nextRecord = record.copy(operationToken = OTHER_TOKEN, generation = f.generation + 1, previousEnvelopeSha256 = Sha256.hex(f.envelopeBytes))
            val second = f.manifest.copy(
                operationToken = nextRecord.operationToken, generation = nextRecord.generation, previousEnvelopeSha256 = nextRecord.previousEnvelopeSha256,
                creation = f.creation.copy(createdAtEpochSecond = f.creation.createdAtEpochSecond + 100),
                approvals = f.approvals.map { it.copy(approvedAtEpochSecond = it.approvedAtEpochSecond + 100) },
                activationRecord = nextRecord, history = CatalogTestRunActivationEvidenceFixture.history(nextRecord),
            )
            val secondBytes = signedBytes(second)
            assertEquals(second, OfflineCatalogTestRunActivationParser.parse(secondBytes, 4096).manifest)
            fails(OfflineTrustBundleFailure.INVALID_DOCUMENT) { f.verify(f.complete + secondBytes) }
            val extraCopy = OfflineCatalogInventoryFixture.copy(f.inventory.sources.single(), 3)
            val trailing = OfflineCatalogInventoryFixture.signed(OfflineCatalogInventoryFixture.manifest(
                f.rotations.genesis, f.generation + 1, f.envelopeBytes, "ADD_COPY",
                f.inventory.copy(copies = f.inventory.copies + extraCopy), CatalogInventoryDeltaV1(emptyList(), listOf(extraCopy.copyId)), "catalog-new",
            ))
            fails(OfflineTrustBundleFailure.INVALID_DOCUMENT) { f.verify(f.complete + OfflineCatalogInventoryFixture.bytes(trailing)) }
        }
    }

    @Test
    fun rejectsRunJournalAndFullDesiredConfigurationSubstitution() {
        withActivationEvidence { f ->
            val run = f.manifest.activationRecord.run
            assertEquals(run, f.verify().activationRecord.run)
            listOf(run.copy(configurationSha256 = "0".repeat(64)), run.copy(desiredGeneration = 8), run.copy(implementationSchema = 2)).forEach {
                reject(f, f.withRun(it), OfflineTrustBundleFailure.POLICY_MISMATCH, parses = true)
            }
            reject(f, f.withRun(run.copy(testRunId = OTHER_TOKEN)))
            reject(f, f.withRun(run.copy(firstPublicationEpoch = 2)))
            reject(f, f.withRun(run.copy(journalConfiguration = run.journalConfiguration.copy(dataScopeKind = "LIVE"))))
            val declaration = f.journal.declaration()
            val changedJournal = TestOwnerDeleteJournalConfigurationV1.of(declaration.copy(
                limits = declaration.limits.copy(capacity = declaration.limits.capacity.copy(maximumRetainedVersions = 9_999)),
            ))
            val changedDocument = Json.decodeFromString(TestOwnerDeleteJournalDocumentV1.serializer(), changedJournal.canonicalBytes().decodeToString())
            reject(f, f.withRun(run.copy(journalConfiguration = changedDocument, accounting = CatalogTestRunActivationEvidenceFixture.accounting(501, 9_999))), OfflineTrustBundleFailure.POLICY_MISMATCH, parses = true)

            // Same real J/owners, different real desired generation: only complete root D changes.
            val otherProcess = f.process(8)
            val otherExpected = CatalogTestRunActivationCanonicalV3.fromRetained(otherProcess, 501)
            assertArrayEquals(f.journal.canonicalBytes(), otherProcess.consumers.journalConfiguration.canonicalBytes())
            assertNotEquals(f.expected.configurationSha256, otherExpected.configurationSha256)
            val otherManifest = f.withRun(otherExpected.run())
            val otherBytes = signedBytes(otherManifest)
            assertEquals(otherManifest, f.verify(f.prefix + otherBytes, selected = otherExpected).manifest)
            fails(OfflineTrustBundleFailure.POLICY_MISMATCH) { f.verify(selected = otherExpected) }
            reject(f, otherManifest, OfflineTrustBundleFailure.POLICY_MISMATCH, parses = true)

            val substitutedCurrent = OfflineTrustBundleFixture.bytes(OfflineTrustBundleFixture.signed(f.rotations.current.body.copy(version = 10)))
            assertEquals(10L, OfflineTrustBundleVerifier.verify(substitutedCurrent, f.policy.trustBundlePolicy).body.version)
            fails(OfflineTrustBundleFailure.POLICY_MISMATCH) {
                OfflineCatalogInventoryChainVerifier.verifyTestRunActivationChain(f.complete.asSequence(), f.initial, substitutedCurrent, f.policy, f.expected)
            }
            val substitutedPolicy = OfflineCatalogRotationFixture.policy(approvers = f.policy.currentApproverIds + "catalog-approver-c")
            fails(OfflineTrustBundleFailure.POLICY_MISMATCH) { f.verify(chainPolicy = substitutedPolicy) }
        }
    }

    @Test
    fun rejectsChangedAccountingAndNoticeSeedsAfterResigning() {
        withActivationEvidence { f ->
            val run = f.manifest.activationRecord.run
            assertEquals(run, f.verify().activationRecord.run)
            val a = run.accounting
            listOf(
                a.copy(profile = "TEST_TERMINAL_ACCOUNTING_V2"), a.copy(capacityEncodingVersion = 2),
                a.copy(activationCatalogPrepareActual = a.activationCatalogPrepareActual.changedAt(20)),
                a.copy(activationProjectionActual = a.activationProjectionActual.changedAt(1)),
                a.copy(originalUnusedReserve = a.originalUnusedReserve.changedAt(20)),
            ).forEach { reject(f, f.withRun(run.copy(accounting = it))) }
            val first = run.noticeSeeds.first()
            listOf(
                first.copy(noticeKey = "complaints.notice.unapproved"), first.copy(definitionVersion = 2),
                first.copy(resourceId = OTHER_TOKEN), first.copy(definitionSha256 = "0".repeat(64)),
            ).forEach { reject(f, f.withRun(run.copy(noticeSeeds = listOf(it, run.noticeSeeds.last())))) }
            reject(f, f.withRun(run.copy(noticeSeeds = run.noticeSeeds.reversed())))
            reject(f, f.withRun(run.copy(noticeSeeds = listOf(first, first))))

            // Reprice N too: a self-consistent signed alternative still must match retained expected N.
            val anotherLimit = f.withRun(run.copy(installationLimit = 502, accounting = CatalogTestRunActivationEvidenceFixture.accounting(502, 10_000)))
            val bytes = signedBytes(anotherLimit)
            val expected502 = CatalogTestRunActivationCanonicalV3.fromRetained(f.process, 502)
            assertEquals(anotherLimit, f.verify(f.prefix + bytes, selected = expected502).manifest)
            reject(f, anotherLimit, OfflineTrustBundleFailure.POLICY_MISMATCH, parses = true)
        }
    }

    @Test
    fun enforcesExactDocumentManifestRecordVectorAndWholeChainBounds() {
        withActivationEvidence { f ->
            val unsigned = CatalogTestRunActivationEvidenceFixture.manifestBytes(f.manifest)
            val records = OfflineCatalogRotationFixture.manifestRecords(f.envelopeBytes)
            assertEquals(f.envelope, OfflineCatalogTestRunActivationParser.parse(f.envelopeBytes, records, f.envelopeBytes.size))
            assertEquals(f.manifest, OfflineCatalogTestRunActivationParser.parseManifest(unsigned, records, unsigned.size))
            fails(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { OfflineCatalogTestRunActivationParser.parse(f.envelopeBytes, records, f.envelopeBytes.size - 1) }
            fails(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { OfflineCatalogTestRunActivationParser.parseManifest(unsigned, records, unsigned.size - 1) }
            fails(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { OfflineCatalogTestRunActivationParser.parse(f.envelopeBytes, records - 1) }
            fails(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { OfflineCatalogTestRunActivationParser.parseManifest(unsigned, records - 1) }
            assertTrue(f.envelopeBytes.size < 131_072)
            val overHard = f.envelopeBytes.copyOf(131_073).also { it.fill(32, f.envelopeBytes.size) }
            fails(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { OfflineCatalogTestRunActivationParser.parse(overHard, 4096) }
            fails(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { OfflineCatalogTestRunActivationParser.parseManifest(ByteArray(131_073), 4096) }
            fails(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { OfflineCatalogTestRunActivationParser.parseGeneration(overHard, 4096) }

            val run = f.manifest.activationRecord.run
            assertEquals(4, run.journalConfiguration.routingKeys.size)
            val active = run.journalConfiguration.routingKeys.single { it.keyId == run.journalConfiguration.activeRoutingKeyId }
            val oneKey = f.withRun(run.copy(journalConfiguration = run.journalConfiguration.copy(routingKeys = listOf(active))))
            assertEquals(oneKey, OfflineCatalogTestRunActivationParser.parse(signedBytes(oneKey), 4096).manifest)
            val keys = run.journalConfiguration.routingKeys
            reject(f, f.withRun(run.copy(journalConfiguration = run.journalConfiguration.copy(routingKeys = emptyList()))), OfflineTrustBundleFailure.MALFORMED_INPUT)
            reject(f, f.withRun(run.copy(journalConfiguration = run.journalConfiguration.copy(routingKeys = keys + keys.first()))), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
            reject(f, f.withRun(run.copy(noticeSeeds = run.noticeSeeds.dropLast(1))), OfflineTrustBundleFailure.MALFORMED_INPUT)
            reject(f, f.withRun(run.copy(noticeSeeds = run.noticeSeeds + run.noticeSeeds.first())), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
            reject(f, f.manifest.copy(approvals = f.approvals.dropLast(1)), OfflineTrustBundleFailure.MALFORMED_INPUT)
            reject(f, f.manifest.copy(approvals = f.approvals + f.approvals.first()), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
            fails(OfflineTrustBundleFailure.MALFORMED_INPUT) {
                OfflineCatalogTestRunActivationParser.parse(CatalogTestRunActivationEvidenceFixture.bytes(f.envelope.copy(signatures = emptyList())), 4096)
            }
            fails(OfflineTrustBundleFailure.LIMIT_EXCEEDED) {
                OfflineCatalogTestRunActivationParser.parse(CatalogTestRunActivationEvidenceFixture.bytes(f.envelope.copy(signatures = f.envelope.signatures + f.envelope.signatures)), 4096)
            }
            val vector = run.accounting.activationCatalogPrepareActual
            reject(f, f.withRun(run.copy(accounting = run.accounting.copy(activationCatalogPrepareActual = vector.dropLast(1)))), OfflineTrustBundleFailure.MALFORMED_INPUT)
            reject(f, f.withRun(run.copy(accounting = run.accounting.copy(activationCatalogPrepareActual = vector + 0L))), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
            reject(f, f.withRun(run.copy(accounting = run.accounting.copy(activationProjectionActual = run.accounting.activationProjectionActual.dropLast(1)))), OfflineTrustBundleFailure.MALFORMED_INPUT)
            reject(f, f.withRun(run.copy(accounting = run.accounting.copy(originalUnusedReserve = run.accounting.originalUnusedReserve + 0L))), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
            for (changed in listOf(vector.toMutableList().also { it[0] = -1L }, vector.toMutableList().also { it[0] = Long.MAX_VALUE })) {
                reject(f, f.withRun(run.copy(accounting = run.accounting.copy(activationCatalogPrepareActual = changed))))
            }
            val overflow = f.envelopeBytes.decodeToString().replace("\"activationCatalogPrepareActual\":[0,", "\"activationCatalogPrepareActual\":[9223372036854775808,").toByteArray()
            assertFalse(overflow.contentEquals(f.envelopeBytes))
            fails(OfflineTrustBundleFailure.MALFORMED_INPUT) { OfflineCatalogTestRunActivationParser.parse(overflow, 4096) }
            fails(OfflineTrustBundleFailure.INVALID_DOCUMENT) { CatalogTestRunActivationCanonicalV3.fromRetained(f.process, 0) }

            val exact = OfflineCatalogChainLimits(f.complete.maxOf { it.size }, f.complete.maxOf(OfflineCatalogRotationFixture::manifestRecords), f.complete.size, f.complete.sumOf { it.size.toLong() })
            assertEquals(f.head.envelopeSha256, f.verify(chainPolicy = tighterPolicy(f, exact)).tail.envelopeSha256)
            listOf(
                exact.copy(maximumEnvelopeBytes = exact.maximumEnvelopeBytes - 1),
                exact.copy(maximumManifestRecords = exact.maximumManifestRecords - 1),
                exact.copy(maximumGenerations = exact.maximumGenerations - 1),
                exact.copy(maximumEncodedBytes = exact.maximumEncodedBytes - 1),
            ).forEach { limits -> fails(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { f.verify(chainPolicy = tighterPolicy(f, limits)) } }
            assertEquals(OfflineCatalogChainLimits(8_388_608, 4096, 65_536, 2_147_483_648L), f.policy.limits)
            fails(OfflineTrustBundleFailure.INVALID_POLICY) { f.policy.limits.copy(maximumManifestRecords = 4097) }
            fails(OfflineTrustBundleFailure.INVALID_POLICY) { f.policy.limits.copy(maximumGenerations = 65_537) }
            fails(OfflineTrustBundleFailure.INVALID_POLICY) { f.policy.limits.copy(maximumEncodedBytes = 2_147_483_649L) }
        }
    }

    private fun signedBytes(manifest: OfflineCatalogTestRunActivationManifestV3): ByteArray =
        CatalogTestRunActivationEvidenceFixture.bytes(CatalogTestRunActivationEvidenceFixture.signed(manifest))

    /** Rehashing happens at record construction; every semantic candidate is freshly signed here. */
    private fun reject(
        f: CatalogTestRunActivationEvidenceFixture,
        manifest: OfflineCatalogTestRunActivationManifestV3,
        code: OfflineTrustBundleFailure? = null,
        parses: Boolean = false,
    ) {
        val bytes = signedBytes(manifest)
        if (parses) assertEquals(manifest, OfflineCatalogTestRunActivationParser.parse(bytes, 4096).manifest)
        val failure = assertThrows<OfflineTrustBundleException> { f.verify(f.prefix + bytes) }
        if (code != null) assertEquals(code, failure.code)
    }

    private fun fails(code: OfflineTrustBundleFailure, action: () -> Unit) {
        assertEquals(code, assertThrows<OfflineTrustBundleException> { action() }.code)
    }

    private fun tighterPolicy(f: CatalogTestRunActivationEvidenceFixture, limits: OfflineCatalogChainLimits): OfflineCatalogChainReaderPolicy =
        OfflineCatalogChainReaderPolicy(f.policy.trustBundlePolicy, f.policy.currentWriterGenerationIds, f.policy.currentApproverIds, limits)

    private fun List<Long>.changedAt(index: Int): List<Long> = toMutableList().also { it[index] = it[index] + 1 }

    companion object {
        private const val OTHER_TOKEN = "99999999-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}
