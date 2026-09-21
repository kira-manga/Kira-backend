package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePoolDescriptor
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.security.ComplaintAdmissionPolicy
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.SecretMaterialPurpose
import me.manga.kira.backend.security.VersionBoundTestComplaintConsumerConfigurationV1

/** Closed complete TEST inventory only; no independent partial-D, caller JSON, LIVE relabel or supplied digest. */
internal object ComplaintEffectiveTestConfigurationV1 {
    fun encode(owner: VersionBoundTestNamespaceProcessV1): ByteArray {
        requireConnectionFree()
        require(CanonicalJson.CANON_VERSION == "kcj-1" && (owner.pools.epochRotation == null) == (owner.activeFirstCut == null)) { INVALID_TEST_PROCESS_CONFIGURATION }
        val journal = owner.consumers.journalConfiguration
        require(!journal.registeredAdminDelete || owner.ordinaryDenial != null && owner.ordinarySeal != null) { INVALID_TEST_PROCESS_CONFIGURATION }
        owner.publicationLanes.requireTestJournal(journal)
        owner.catalogActivation.requireRetained(owner.pools, owner.catalogReadback, journal)
        val descriptors = poolDescriptors(owner)
        val capacityBytes = owner.consumers.capacityPolicy.canonicalBytes()
        val capacity = document(capacityBytes, "kira-complaint-capacity-policy")
        val result = buildJsonObject {
            put("kind", "kira-complaint-effective-test-configuration")
            put("schemaVersion", 1)
            put("canonicalizerId", "kcj-1")
            put("profile", if (owner.initialCheckpoint != null && owner.activeOrdinarySealRecovery != null) "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_INITIAL_EMPTY_RECOVERY_CHECKPOINT_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER"
                else if (owner.activeOrdinarySealRecovery != null) "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_INITIAL_EMPTY_SEAL_RECOVERY_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER"
                else if (owner.initialCheckpoint != null) "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_INITIAL_EMPTY_CHECKPOINT_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER"
                else if (owner.activeFirstCutSuccessor != null) "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_ACTIVE_FIRST_CUT_RESERVED_RECOVERY_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER"
                else if (owner.activeFirstCut != null) "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_ACTIVE_FIRST_CUT_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER"
                else if (journal.registeredAdminBatchDelete) "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER"
                else if (journal.registeredAdminDelete) "PRE_CUTOVER_TEST_ADMIN_ERASURE_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER"
                else if (journal.ownerDeleteAll) "PRE_CUTOVER_TEST_OWNER_ERASURE_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER"
                else "PRE_CUTOVER_TEST_OWNER_DELETE_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER")
            put("identity", identity(owner))
            put("capacityPolicy", commitment(capacity, capacityBytes))
            put("journalConfiguration", journalCommitment(journal))
            put("consumers", consumers(owner.consumers))
            put("persistence", persistence(descriptors))
            put("publicationLanes", publicationLanes(journal))
            put(
                "catalogReadback",
                if (owner.catalogReadback.projectedCurrent) {
                    ComplaintEffectiveCatalogConfigurationV1.encodeProjectedCurrent(owner.catalogReadback)
                } else {
                    ComplaintEffectiveCatalogConfigurationV1.encode(owner.catalogReadback)
                },
            )
            put("catalogActivation", owner.catalogActivation.inventory())
            // Absence preserves the previous profile/preimage exactly and cannot publish a seal.
            owner.ordinarySeal?.let { put("ordinarySeal", it.inventory()) }
            // Independently retained purpose/implementation/timing policy, before D and activation.
            // Legacy absence deliberately leaves its existing preimage byte-for-byte unchanged.
            owner.ordinaryDenial?.let { put("ordinaryDenial", it.inventory()) }
            // A distinct grant must exist before activation. Null deliberately preserves old full-D bytes.
            owner.terminalDenial?.let { put("terminalDenial", it.inventory()) }
            // Explicit retained physical resource + paid row policy; old absent profiles are byte-identical.
            owner.activeFirstCut?.let {
                it.requireRetained(owner.pools, journal, owner.ordinarySeal)
                put("activeFirstCut", it.inventory())
            }
            owner.activeCutoffPublication?.let { put("activeCutoffPublication", it.inventory()) }
            owner.activeFirstCutSuccessor?.let {
                it.requireRetained(owner.pools, journal, owner.activeFirstCut, owner.ordinarySeal)
                put("activeFirstCutSuccessor", it.inventory())
            }
            // Additive independent read owner; no absence rule excludes a separately retained recovery recipe.
            owner.initialCheckpoint?.let { put("initialCheckpoint", it.inventory()) }
            owner.initialCheckpointCreate?.let { put("initialCheckpointCreate", it.inventory()) }
            owner.activeOwnerDeleteQueue?.let { put("activeOwnerDeleteQueue", it.inventory()) }
            owner.activeOrdinarySealRecovery?.let {
                it.requireRetained(owner.pools, owner.consumers.journalRouting, owner.activeFirstCut, owner.ordinarySeal)
                put("activeOrdinarySealRecovery", it.inventory())
            }
        }
        return CanonicalJson.canonicalize(result).toByteArray(Charsets.UTF_8)
    }

    private fun identity(owner: VersionBoundTestNamespaceProcessV1): JsonObject = buildJsonObject {
        put("mode", "PRE_CUTOVER_TEST")
        put("implementationSchema", owner.implementationSchema)
        put("desiredGeneration", owner.desiredGeneration)
        put("scopeKind", "TEST")
        put("scopeId", owner.consumers.journalConfiguration.scope.id.toString())
        put("databaseIdentity", owner.databaseIdentity.toString())
        put("restoreIdentity", owner.restoreIdentity.toString())
        put("writerGeneration", owner.consumers.journalConfiguration.declaration().writer.generationId)
    }

    /** TEST J is FLAT and committed verbatim. The LIVE scope/profile helper must not translate it. */
    private fun journalCommitment(owner: TestOwnerDeleteJournalConfigurationV1): JsonObject {
        val bytes = owner.canonicalBytes()
        val parsed = document(bytes, "kira-complaint-journal-configuration")
        require(
            (!owner.adminDelete || owner.registeredAdminDelete) && parsed.getValue("profile").jsonPrimitive.content == owner.profile &&
                parsed.getValue("dataScopeKind").jsonPrimitive.content == "TEST" &&
                parsed.getValue("dataScopeId").jsonPrimitive.content == owner.scope.id.toString(),
        ) { INVALID_TEST_PROCESS_CONFIGURATION }
        return commitment(parsed, bytes)
    }

    private fun document(bytes: ByteArray, kind: String): JsonObject {
        val parsed = CanonicalJson.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(
            parsed.getValue("kind").jsonPrimitive.content == kind && parsed.getValue("schemaVersion").jsonPrimitive.content == "1" &&
                parsed.getValue("canonicalizerId").jsonPrimitive.content == "kcj-1",
        ) { INVALID_TEST_PROCESS_CONFIGURATION }
        return parsed
    }

    private fun commitment(document: JsonObject, bytes: ByteArray): JsonObject = buildJsonObject {
        put("kind", document.getValue("kind"))
        put("schemaVersion", document.getValue("schemaVersion"))
        put("canonicalizerId", document.getValue("canonicalizerId"))
        put("sha256", Sha256.hex(bytes))
    }

    private fun poolDescriptors(owner: VersionBoundTestNamespaceProcessV1): List<VersionBoundPersistencePoolDescriptor> {
        val descriptors = owner.pools.descriptors()
        require(descriptors.map { it.role } == POOL_ROLES) { INVALID_TEST_PROCESS_CONFIGURATION }
        require(descriptors.first().hikari.sizing.maximumPoolSize > 1) { INVALID_TEST_PROCESS_CONFIGURATION }
        val password = descriptors.first().authenticationPassword
        require(
            password.family == SecretMaterialFamily.DATABASE && password.purpose == SecretMaterialPurpose.AUTHENTICATION_PASSWORD &&
                descriptors.all { it.authenticationPassword === password } && owner.consumers.descriptors().none { it.version == password.version },
        ) { INVALID_TEST_PROCESS_CONFIGURATION }
        return descriptors
    }

    private fun persistence(descriptors: List<VersionBoundPersistencePoolDescriptor>): JsonObject = buildJsonObject {
        put("profileVersion", 1)
        put(
            "admission",
            buildJsonObject {
                put("ordinaryOwnerLimitRule", "MIN_4_POOL_MINUS_ONE")
                put("ordinaryOwnerLimit", minOf(4, descriptors.first().hikari.sizing.maximumPoolSize - 1))
                put("deletionTotalOwners", 4)
                put("deletionRoutineOwners", 3)
                put("catalogOwners", 1)
            },
        )
        put("pools", JsonArray(descriptors.map(ComplaintEffectiveConfigurationV1::pool)))
    }

    private fun publicationLanes(journal: TestOwnerDeleteJournalConfigurationV1): JsonObject = buildJsonObject {
        val capacity = journal.declaration().limits.capacity
        put("profileVersion", 1)
        put("profile", "SHARED_JOURNAL_PUBLICATION_LANES")
        put("maximumPublicationLanes", capacity.maximumPublicationLanes)
        put("routinePublicationLanes", capacity.routinePublicationLanes)
    }

    private fun consumers(owner: VersionBoundTestComplaintConsumerConfigurationV1): JsonObject = buildJsonObject {
        val bindings = owner.descriptors().sortedWith(compareBy({ it.family.name }, { it.logicalKeyId }))
        put("secretBindings", JsonArray(bindings.map(ComplaintEffectiveConfigurationV1::secret)))
        put(
            "userJwt",
            ComplaintEffectiveConsumerConfigurationV1.userJwt(requireNotNull(owner.jwt.boundUserKeyProvider) { INVALID_TEST_PROCESS_CONFIGURATION }),
        )
        put("installationJwt", ComplaintEffectiveConsumerConfigurationV1.installationJwt(owner.jwt))
        put("admission", admission(owner))
        put("ownerCursor", ComplaintEffectiveConsumerConfigurationV1.cursor(owner.ownerCursorCodec))
    }

    private fun admission(owner: VersionBoundTestComplaintConsumerConfigurationV1): JsonObject = buildJsonObject {
        val policy = owner.admissionPolicy
        put("protocolVersion", 1)
        put("coordinationMode", owner.coordinationMode)
        put("declaredInstances", owner.declaredInstances)
        put("currentKeyId", owner.admissionCurrentKeyId)
        put("previousKeyIds", strings(listOfNotNull(owner.admissionPreviousKeyId)))
        put("rotationAllowed", false)
        put("retirementAllowed", false)
        put("previousRetentionNanos", ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS)
        put("admissionLifetimeNanos", ComplaintAdmissionPolicy.ADMISSION_LIFETIME_NANOS)
        put("concurrentLimit", policy.concurrentLimit)
        put("trustedIp", trustedIp(owner))
        put(
            "ingress",
            ComplaintEffectiveConsumerConfigurationV1.window(
                policy.ingressBucketLimit, policy.ingressBucketLimit * policy.ingressPerMinute, policy.pruneBatch, true,
            ),
        )
        put("ingressPerMinute", policy.ingressPerMinute)
        put(
            "semantics",
            ComplaintEffectiveConsumerConfigurationV1.window(policy.semanticBucketLimit, policy.semanticEventLimit, policy.pruneBatch, false),
        )
        put("ownerReads", ComplaintEffectiveConsumerConfigurationV1.ownerReads(policy))
        put("quotas", quotas(owner))
        put(
            "mutationMembers",
            buildJsonObject {
                put("operations", strings(listOf("OWNER_CREATE", "OWNER_REPLY", "OWNER_EDIT", "OWNER_DELETE") +
                    (if (owner.journalConfiguration.ownerDeleteAll) listOf("OWNER_DELETE_ALL") else emptyList()) +
                    (if (owner.journalConfiguration.registeredAdminDelete) listOf("ADMIN_DELETE") else emptyList()) +
                    (if (owner.journalConfiguration.registeredAdminBatchDelete) listOf("ADMIN_BATCH_DELETE") else emptyList())))
                put("memberLimit", owner.ownerCreatePolicy.memberLimit)
                put("pruneBatch", owner.ownerCreatePolicy.pruneBatch)
                put("retentionNanos", ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS)
            },
        )
    }

    private fun trustedIp(owner: VersionBoundTestComplaintConsumerConfigurationV1): JsonObject = buildJsonObject {
        put("protocolVersion", 1)
        put("trustForwardedHeaders", owner.trustForwardedHeaders)
        put("trustedProxies", strings(owner.trustedProxies().sorted()))
        put("maximumForwardedHeaderBytes", 1024)
        put("selection", "RIGHTMOST_UNTRUSTED")
        put("headerPrecedence", strings(listOf("X-Forwarded-For", "Forwarded")))
        put("invalidChain", "REMOTE_ADDRESS")
    }

    /** Frozen ingress protocol literals; only the two global hourly limits are independent validated settings. */
    private fun quotas(owner: VersionBoundTestComplaintConsumerConfigurationV1): JsonObject = buildJsonObject {
        put("bootstrapIpPerHour", ComplaintAdmissionPolicy.BOOTSTRAP_IP_LIMIT)
        put("sessionActorPerHour", ComplaintAdmissionPolicy.SESSION_ACTOR_LIMIT)
        put("sessionIpPerHour", ComplaintAdmissionPolicy.SESSION_IP_LIMIT)
        put("enrollmentEnabled", true)
        put("enrollmentIpPerHour", ComplaintAdmissionPolicy.ENROLLMENT_IP_LIMIT)
        put("enrollmentGlobalPerHour", owner.enrollmentPolicy.globalPerHour)
        put("ownerCreateEnabled", true)
        put("ownerCreateActorPerHour", 10)
        put("ownerCreateGlobalPerHour", owner.ownerCreatePolicy.globalPerHour)
        put("ownerReplyEnabled", true)
        put("ownerEditEnabled", true)
        put("ownerDeleteEnabled", true)
        put("ownerEditDeleteActorPerHour", 60)
        put("ownerDeleteAllEnabled", owner.journalConfiguration.ownerDeleteAll)
        put("ownerDeleteAllActorPerDay", 5)
        put("ownerDeleteAllIpPerHour", 20)
        // Absent for old profiles: do not rewrite any previously committed D preimage.
        if (owner.journalConfiguration.registeredAdminDelete) {
            val admin = owner.adminDeletePolicy as? me.manga.kira.backend.security.ComplaintAdminDeleteAdmissionPolicy.Bounded
                ?: error(INVALID_TEST_PROCESS_CONFIGURATION)
            put("adminDeleteEnabled", true)
            put("adminDeleteActorPerHour", admin.perHour)
        }
        if (owner.journalConfiguration.registeredAdminBatchDelete) {
            val batch = owner.adminBatchDeletePolicy as? me.manga.kira.backend.security.ComplaintAdminBatchDeleteAdmissionPolicy.Bounded
                ?: error(INVALID_TEST_PROCESS_CONFIGURATION)
            put("adminBatchDeleteEnabled", true)
            put("adminBatchDeleteActorPerHour", batch.perHour)
            put("adminBatchDeleteMaximumTargets", 50)
            put("adminBatchDeleteMaximumOwners", 50)
            put("adminBatchDeleteMaximumFamilyVersions", 4)
            put("adminBatchDeleteRecoveryAccounting", "OWNER_SET+TARGET_SET+REMOVALS+EVENT_SUMMARY_PER_VERSION")
        }
    }

    private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

    private val POOL_ROLES = listOf(
        PersistenceJdbcParticipantRole.ORDINARY,
        PersistenceJdbcParticipantRole.DELETION,
        PersistenceJdbcParticipantRole.CATALOG_COORDINATOR,
    )
}
