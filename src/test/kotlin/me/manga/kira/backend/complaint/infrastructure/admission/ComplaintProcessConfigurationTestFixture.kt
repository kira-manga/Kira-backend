package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcDriverRoot
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustRelease
import me.manga.kira.backend.common.infrastructure.persistence.SystemPersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceTestInputs
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundEpochSealAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.VersionBoundLiveJournalCoverageV1
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import me.manga.kira.backend.security.JwtKeyProvider
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import me.manga.kira.backend.security.VersionBoundComplaintConsumerSettings
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import me.manga.kira.backend.security.VersionBoundInstallationJwtConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Path
import java.util.UUID

/**
 * Real cold configuration/root/all-three-pool custody, with explicit optional nonpooled rotation.
 * No provider, trust-file write, connection or pool/rotation start.
 */
internal class ComplaintProcessPoolFixture(
    password: AcquiredVersionedSecret = VersionBoundPersistenceTestInputs.acquired(),
    host: String = "db.invalid",
    port: Int = 5432,
    database: String = "fixture_db",
    username: String = "fixture_user",
    capacity: Int = 2,
    trust: ByteArray = VersionBoundPersistenceTestInputs.pem(),
    parent: Path = Path.of("/deliberately-not-created/complaint-process-test"),
    private val retained: Boolean = false,
    epochRotation: Boolean = false,
    private val testActivation: Boolean = false,
) : AutoCloseable {
    val configuration = VersionBoundPersistenceConfiguration.fromAcquired(
        password,
        host,
        port,
        database,
        username,
        capacity,
        trust,
        parent,
    )
    val owner = when {
        testActivation -> configuration.bindCatalogTestRunActivationOwner()
        epochRotation -> configuration.bindLifecycleOwnerWithEpochRotation()
        else -> configuration.bindLifecycleOwner()
    }
    val root: PersistenceJdbcDriverRoot = poolTestField(owner, "root")

    init {
        check(!testActivation || !epochRotation)
    }

    fun bind(): VersionBoundPersistencePools =
        if (testActivation) owner.bindCatalogTestRunActivationPools(SystemPersistenceNanoClock) else owner.bindVersionBoundPools()

    override fun close() {
        checkNotNull(owner.versionBoundPools).close()
        owner.requestShutdown()
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, owner.observeShutdown())
        assertEquals(
            if (retained) PersistencePublicTrustRelease.RETAINED else PersistencePublicTrustRelease.RELEASED,
            owner.releasePublicTrustAfterShutdown(),
        )
    }
}

/** Test inputs feed the existing actual consumer factory, never the process encoder or a fake descriptor. */
internal data class ComplaintProcessAdmissionInputs(
    val concurrent: Int = 2,
    val ingressBuckets: Int = 64,
    val ingressRate: Int = 120,
    val semanticBuckets: Int = 128,
    val semanticEvents: Int = 4096,
    val prune: Int = 8,
    val enrollmentGlobal: Int = 2,
    val createGlobal: Int = 2,
    val members: Int = 64,
    val memberPrune: Int = 8,
    val forwarded: Boolean = false,
    val proxies: List<String> = emptyList(),
    val coordination: String = "memory",
    val instances: Int = 1,
) {
    fun settings(): VersionBoundComplaintConsumerSettings = VersionBoundComplaintConsumerSettings(
        coordination, instances, concurrent, ingressBuckets, ingressRate, semanticBuckets, semanticEvents, prune,
        enrollmentGlobal, createGlobal, members, memberPrune, forwarded, proxies,
    )
}

internal fun processConfiguration(
    consumers: VersionBoundComplaintConsumerConfiguration,
    pools: VersionBoundPersistencePools,
    schema: Int = 1,
    generation: Long = 7,
    database: UUID = UUID.fromString(consumers.journalConfiguration.declaration().writer.databaseIdentity),
    restore: UUID = UUID.fromString(consumers.journalConfiguration.declaration().writer.restoreIdentity),
): VersionBoundComplaintProcessConfiguration = VersionBoundComplaintProcessConfiguration.fromRetained(consumers, pools, schema, generation, database, restore)

/** Actual complete D6 cold graph only; no supplied D, descriptor, catalog result, clock observation or installed flag. */
internal fun liveProcessConfiguration(
    consumers: VersionBoundComplaintConsumerConfiguration,
    pools: VersionBoundPersistencePools,
    reader: VersionBoundCatalogReadbackConfigurationV1,
    lanes: JournalPublicationLanesV1,
    sealer: VersionBoundEpochSealAcquisitionV1,
    coverage: VersionBoundLiveJournalCoverageV1,
): VersionBoundComplaintProcessConfiguration {
    val writer = consumers.journalConfiguration.declaration().writer
    return VersionBoundComplaintProcessConfiguration.fromRetainedWithLiveCoverage(
        consumers, pools, 1, 7, UUID.fromString(writer.databaseIdentity), UUID.fromString(writer.restoreIdentity), reader, lanes, sealer, coverage,
    )
}

/** Change a real acquired version in every HMAC family, including retained-only verifiers. No descriptor is substituted after construction. */
internal fun replacedProcessConsumerVersions(fixture: BoundComplaintConsumerFixture): List<VersionBoundComplaintConsumerConfiguration> {
    val originals = listOf(
        fixture.userSecret,
        fixture.installationSecrets.last(),
        fixture.previous,
        fixture.cursorSecrets.last(),
        fixture.journalSecrets.first(),
    )
    return originals.mapIndexed { index, original ->
        val binding = original.descriptor
        val replacement = fixture.acquired(binding.family, binding.logicalKeyId, 151 + index)
        when (binding.family) {
            SecretMaterialFamily.USER_ADMIN_JWT -> fixture.configuration(
                jwt = VersionBoundInstallationJwtConfiguration.fromAcquired(
                    "installation-z",
                    fixture.installationSecrets,
                    JwtKeyProvider.fromAcquired(replacement, KiraSecurityProperties()),
                ),
            )

            SecretMaterialFamily.INSTALLATION_JWT -> fixture.configuration(
                jwt = VersionBoundInstallationJwtConfiguration.fromAcquired(
                    "installation-z",
                    fixture.installationSecrets.map { if (it === original) replacement else it },
                    fixture.user,
                ),
            )

            SecretMaterialFamily.COMPLAINT_ADMISSION -> fixture.configuration(keys = fixture.inputs(previous = replacement))

            SecretMaterialFamily.COMPLAINT_CURSOR -> fixture.configuration(
                keys = fixture.inputs(cursors = fixture.cursorSecrets.map { if (it === original) replacement else it }),
            )

            SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING -> {
                val previous = fixture.journal.declaration()
                val changed = ComplaintJournalConfigurationV1.of(
                    previous.copy(
                        routing = previous.routing.copy(
                            keys = previous.routing.keys.map {
                                if (it.keyId == binding.logicalKeyId) it.copy(secret = replacement.descriptor.version) else it
                            },
                        ),
                    ),
                )
                val routing = VersionBoundComplaintJournalRouting.fromAcquired(
                    changed,
                    fixture.journalSecrets.map { if (it === original) replacement else it },
                )
                fixture.configuration(keys = fixture.inputs(routing = routing), journal = changed)
            }

            else -> error("Unexpected test HMAC family")
        }
    }
}

/**
 * Independent FULL D6 expected bytes: the frozen D2 resource plus literal reviewed resource/policy
 * inventories below, never a production D/reader/sealer/policy encoder or a live owner's descriptor.
 * Both hashes were derived from these public synthetic literals with sorted ASCII JSON keys.
 */
internal object ComplaintLivePolicyGolden {
    const val G1_SHA256 = "4935ce2be237f34e241a54adea005c70143c21bee670aefa5c2fc2a7398c32f2"
    const val PROJECTED_SHA256 = "00f238d1ac708c6adefd8cd5d9c8369568ea3b73d19388f6850289d46f991bd8"

    fun bytes(projected: Boolean): ByteArray {
        val frozen = checkNotNull(javaClass.getResourceAsStream("/fixtures/complaint-effective-configuration-v2/initial-live-memory-g1.json"))
            .use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
        val g1Reader = frozen.getValue("catalogReadback").jsonObject
        val reader = if (projected) {
            JsonObject(
                g1Reader + mapOf(
                    "profileVersion" to JsonPrimitive(2),
                    "profile" to JsonPrimitive("ALREADY_PROJECTED_CURRENT_HEAD"),
                    "retention" to JsonObject(g1Reader.getValue("retention").jsonObject + ("creationAnchor" to JsonPrimitive("SIGNED_CURRENT_HEAD_CREATION"))),
                ),
            )
        } else {
            g1Reader
        }
        val expected = JsonObject(
            frozen + Json.parseToJsonElement(EXTENSIONS).jsonObject + mapOf(
                "schemaVersion" to JsonPrimitive(6),
                "profile" to JsonPrimitive("INITIAL_LIVE_MEMORY_SINGLE_INSTANCE_LIVE_COVERAGE"),
                "catalogReadback" to reader,
            ),
        )
        return CanonicalJson.canonicalize(expected).toByteArray(Charsets.UTF_8)
    }

    private val EXTENSIONS = """
        {
          "epochRotation": {
            "authenticationPassword": {
              "family": "DATABASE",
              "logicalKeyId": "fixture-db-password",
              "purpose": "AUTHENTICATION_PASSWORD",
              "resourceArn": "arn:aws:secretsmanager:eu-west-1:123456789012:secret:fixture-db-Ab12Cd",
              "versionId": "550e8400-e29b-41d4-a716-446655440000"
            },
            "capacity": 1,
            "controlLockMillis": 100,
            "effectiveRotationMillis": 10000,
            "maximumRotationMillis": 10000,
            "opening": {
              "driverUrl": "jdbc:postgresql://",
              "evidencePolicy": "TRACKED_CONJUNCTION",
              "loginBudgetMillis": 2000,
              "publicDriverProperties": {
                "PGDBNAME": "fixture_db",
                "PGHOST": "db.invalid",
                "PGPORT": "5432",
                "cancelSignalTimeout": "1",
                "channelBinding": "prefer",
                "connectTimeout": "1",
                "gssEncMode": "disable",
                "loginTimeout": "0",
                "requireAuth": "password,scram-sha-256",
                "scramMaxIterations": "100000",
                "socketFactory": "me.manga.kira.backend.common.infrastructure.persistence.TrackedPgSocketFactory",
                "socketTimeout": "2",
                "sslcert": "",
                "sslfactory": "org.postgresql.ssl.LibPQFactory",
                "sslhostnameverifier": "org.postgresql.ssl.PGjdbcHostnameVerifier",
                "sslkey": "",
                "sslmode": "verify-full",
                "user": "fixture_user"
              },
              "recipe": "TRACKED_STANDARD",
              "transportRoute": "APPROVED_DIRECT"
            },
            "pooled": false,
            "protocolVersion": 1,
            "publicTrust": {
              "byteCount": 1939,
              "certificateCount": 1,
              "sha256": "22b557a27055b33606b6559f37703928d3e4ad79f110b407d04986e1843543d1"
            },
            "requestPhaseMillis": 2000,
            "role": "EPOCH_ROTATION",
            "sessionPolicy": "FRESH_TERMINAL_ONLY",
            "statementMillis": 1000
          },
          "epochSealAcquisition": {
            "deployment": {
              "bootstrap": {
                "credentialReferenceId": "bootstrap-credential",
                "originId": "bootstrap-origin",
                "principal": {
                  "accountId": "123456789012",
                  "arn": "arn:aws:iam::123456789012:role/bootstrap-source",
                  "kind": "ROLE",
                  "stableId": "AROAFFFFFFFFFFFFFFFFF"
                },
                "version": 1
              },
              "catalogPut": {
                "policy": {
                  "policyId": "catalog-put-policy",
                  "sha256": "e72dd45f30255599010c3ff93843b0f201c7bbdca827176a12fd1dc49fb260d2",
                  "version": 1
                },
                "principal": {
                  "accountId": "123456789012",
                  "arn": "arn:aws:iam::123456789012:role/catalog-put",
                  "kind": "ROLE",
                  "stableId": "AROADDDDDDDDDDDDDDDDD"
                },
                "principalId": "catalog-put-role"
              },
              "catalogSign": {
                "policy": {
                  "policyId": "catalog-sign-policy",
                  "sha256": "40eca2f6d863a8cd856c798f3eee692e294d4521281861ee81041e6a439526dc",
                  "version": 1
                },
                "principal": {
                  "accountId": "123456789012",
                  "arn": "arn:aws:iam::123456789012:role/catalog-sign",
                  "kind": "ROLE",
                  "stableId": "AROAEEEEEEEEEEEEEEEEE"
                },
                "principalId": "catalog-sign-role"
              },
              "installedPolicyBundle": {
                "policyId": "installed-bundle",
                "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "version": 1
              },
              "ordinary": {
                "credentialId": "ordinary-credentials",
                "policy": {
                  "policyId": "ordinary-policy",
                  "sha256": "ebbd2008ed0b406373f30866ea41a770c51366361b32f371ce2e460305e73881",
                  "version": 1
                },
                "principal": {
                  "accountId": "123456789012",
                  "arn": "arn:aws:iam::123456789012:role/ordinary",
                  "kind": "ROLE",
                  "stableId": "AROAAAAAAAAAAAAAAAAAA"
                },
                "roleId": "ordinary-role"
              },
              "recovery": {
                "credentialId": "recovery-credentials",
                "policy": {
                  "policyId": "recovery-policy",
                  "sha256": "0726e41ade85d7fd03dd3fbc6945c36be4020455c30ab86445b799d34d0e664f",
                  "version": 1
                },
                "principal": {
                  "accountId": "123456789012",
                  "arn": "arn:aws:iam::123456789012:role/recovery",
                  "kind": "ROLE",
                  "stableId": "AROACCCCCCCCCCCCCCCCC"
                },
                "roleId": "recovery-role"
              },
              "sealTerminal": {
                "credentialId": "seal-credentials",
                "policy": {
                  "policyId": "seal-policy",
                  "sha256": "d7aa4abb9961295ca25b841472cb653d3f07d5ba4b497bb5f485dd8a93ad0bb1",
                  "version": 1
                },
                "principal": {
                  "accountId": "123456789012",
                  "arn": "arn:aws:iam::123456789012:role/epoch-sealer",
                  "kind": "ROLE",
                  "stableId": "AROABBBBBBBBBBBBBBBBB"
                },
                "roleId": "seal-role"
              }
            },
            "journalConfigurationSha256": "e1562c3f07c5983ee40f2212c601782dc3bfdc003ee1bf7f7c01b10951ad64f0",
            "profile": "AWS_STS_EXACT_SEAL_KEY_V1",
            "profileVersion": 1,
            "region": "us-east-1",
            "sdk": {
              "apiVersion": "2011-06-15",
              "clockUncertaintyMillis": 1000,
              "connectTimeoutMillis": 1000,
              "credentialRefresh": "NONE",
              "credentialSelection": "EXPLICIT_BOOTSTRAP_SESSION",
              "defaultsMode": "STANDARD",
              "endpoint": "https://sts.us-east-1.amazonaws.com",
              "endpointMode": "COMMERCIAL_REGIONAL_ONLY",
              "maxRequestBytes": 12288,
              "maxResponseBytes": 32768,
              "maximumAcquisitionMillis": 9000,
              "maximumAttemptsPerCall": 1,
              "profileSelection": "EMPTY_FIXED_PROFILE",
              "protocolVersion": 1,
              "proxy": "NONE",
              "readTimeoutMillis": 1000,
              "redirects": "REFUSE",
              "requestTimeoutMillis": 2500,
              "sequence": [
                "SOURCE_GET_CALLER_IDENTITY",
                "ASSUME_ROLE",
                "TARGET_GET_CALLER_IDENTITY"
              ],
              "sessionDurationSeconds": 900,
              "sessionExpiryFloor": "ORIGINAL_SEAL_ATTEMPT_END_PLUS_CLOCK_UNCERTAINTY",
              "sourceIdentity": "EXACT_ACCOUNT_ARN_USER_ID",
              "targetIdentity": "EXACT_ACCOUNT_ROLE_ARN_STABLE_ROLE_ID_SESSION",
              "wireProtocol": "QUERY_XML_BOUNDED_PREFLIGHT"
            },
            "sessionPolicy": {
              "bucketActions": [
                "s3:ListBucketVersions"
              ],
              "identityAction": "sts:GetCallerIdentity",
              "kmsActions": [
                "kms:GenerateDataKey",
                "kms:Decrypt"
              ],
              "kmsContext": "EXISTING_OPAQUE_VALUE_NO_CONTEXT_CONDITION",
              "listPrefix": "EXACT_COMMITTED_SEAL_KEY",
              "maximumPolicyChars": 2048,
              "objectActions": [
                "s3:PutObject",
                "s3:PutObjectRetention",
                "s3:GetObjectVersion",
                "s3:GetObjectRetention"
              ],
              "overflow": "REFUSE_NEVER_WIDEN",
              "profile": "EXACT_SEAL_OBJECT_LIST_PREFIX_AND_DECLARED_KMS_KEY",
              "protocolVersion": 1
            }
          },
          "liveCoverage": {
            "acceptedRequestLateArrival": {
              "maximumMillis": 120000,
              "policy": {
                "policyId": "s3-late-arrival",
                "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "version": 1
              },
              "profileId": "s3-accepted-request-bound-v1"
            },
            "copyAgeAnchor": "IMMUTABLE_SOURCE_RESTORE_POINT",
            "copyPolicies": [
              {
                "accountId": "111111111111",
                "bucket": "backup-primary",
                "locationClass": "OFFSITE",
                "maximumAgeSeconds": 31536000,
                "policy": {
                  "policyId": "logical-offsite-age",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "version": 1
                },
                "prefix": "logical/",
                "region": "us-east-1",
                "sourceKind": "KIRA_BACKUP_BUNDLE_V1"
              },
              {
                "accountId": "111111111111",
                "bucket": "backup-primary",
                "locationClass": "OPERATOR",
                "maximumAgeSeconds": 31536000,
                "policy": {
                  "policyId": "logical-operator-age",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "version": 1
                },
                "prefix": "logical/",
                "region": "us-east-1",
                "sourceKind": "KIRA_BACKUP_BUNDLE_V1"
              },
              {
                "accountId": "111111111111",
                "bucket": "backup-primary",
                "locationClass": "PRIMARY",
                "maximumAgeSeconds": 31536000,
                "policy": {
                  "policyId": "logical-primary-age",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "version": 1
                },
                "prefix": "logical/",
                "region": "us-east-1",
                "sourceKind": "KIRA_BACKUP_BUNDLE_V1"
              },
              {
                "accountId": "222222222222",
                "bucket": "backup-replica",
                "locationClass": "REPLICA",
                "maximumAgeSeconds": 31536000,
                "policy": {
                  "policyId": "logical-replica-age",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "version": 1
                },
                "prefix": "logical/",
                "region": "eu-west-1",
                "sourceKind": "KIRA_BACKUP_BUNDLE_V1"
              }
            ],
            "environment": "synthetic-test",
            "hmacKeys": [
              {
                "keyId": "route-a",
                "resourceArn": "arn:aws:secretsmanager:us-east-1:123456789012:secret:journal-routing-a-ABC123",
                "retentionPolicy": {
                  "policyId": "hmac-route-a-retention",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "version": 1
                },
                "versionId": "44444444-4444-4444-8444-444444444444"
              },
              {
                "keyId": "route-b",
                "resourceArn": "arn:aws:secretsmanager:us-east-1:123456789012:secret:journal-routing-b-ABC123",
                "retentionPolicy": {
                  "policyId": "hmac-route-b-retention",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "version": 1
                },
                "versionId": "55555555-5555-4555-8555-555555555555"
              }
            ],
            "horizon": "ALL_EXTANT_SOURCES_PLUS_J_MAXIMUM_RESTORE_AGE",
            "journalLock": {
              "authorities": {
                "isolation": {
                  "administrationPolicy": {
                    "policyId": "administration-policy",
                    "sha256": "51a3fcbbf465b3e5316b585fecb116ea7ae10d830c722d5debc1bc65b13b4baf",
                    "version": 1
                  },
                  "applicationDatabaseFailureDomainId": "application-domain",
                  "backupFailureDomainId": "backup-domain",
                  "bucketAdministratorId": "bucket-admin",
                  "deploymentPrincipalId": "deployment",
                  "journalFailureDomainId": "journal-domain",
                  "kmsAdministratorId": "kms-admin"
                },
                "ordinary": {
                  "credentialId": "ordinary-credentials",
                  "policy": {
                    "policyId": "ordinary-policy",
                    "sha256": "ebbd2008ed0b406373f30866ea41a770c51366361b32f371ce2e460305e73881",
                    "version": 1
                  },
                  "roleId": "ordinary-role"
                },
                "recovery": {
                  "credentialId": "recovery-credentials",
                  "policy": {
                    "policyId": "recovery-policy",
                    "sha256": "0726e41ade85d7fd03dd3fbc6945c36be4020455c30ab86445b799d34d0e664f",
                    "version": 1
                  },
                  "roleId": "recovery-role"
                },
                "sealTerminal": {
                  "credentialId": "seal-credentials",
                  "policy": {
                    "policyId": "seal-policy",
                    "sha256": "d7aa4abb9961295ca25b841472cb653d3f07d5ba4b497bb5f485dd8a93ad0bb1",
                    "version": 1
                  },
                  "roleId": "seal-role"
                }
              },
              "location": {
                "accountId": "123456789012",
                "bucket": "kira-journal-fixture",
                "region": "us-east-1"
              },
              "ordinaryPrefix": "complaints/journal/v1/33333333-3333-4333-8333-333333333333/live/00000000-0000-0000-0000-000000000000/ordinary/",
              "policy": {
                "policyId": "journal-compliance-retention",
                "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "version": 1
              },
              "profile": "COMPLIANCE_ALL_VERSIONS_NO_NATIVE_EXPIRATION_OR_DELETION",
              "sealTerminalPrefix": "complaints/journal/v1/33333333-3333-4333-8333-333333333333/live/00000000-0000-0000-0000-000000000000/seal-terminal/"
            },
            "journalSafetyMarginSeconds": 2678400,
            "keyAvailability": "WHILE_ANY_RETAINED_JOURNAL_VERSION_REQUIRES_KEY",
            "kmsKeys": [
              {
                "key": {
                  "keyArn": "arn:aws:kms:us-east-1:123456789012:key/88888888-8888-4888-8888-888888888888",
                  "keyId": "dlq-kms",
                  "policy": {
                    "policyId": "dlq-kms-policy",
                    "sha256": "4bf871b7c9fb337c81306721dc7287f4a2eca58036c4f286bd0821fc95887670",
                    "version": 1
                  }
                },
                "retentionPolicy": {
                  "policyId": "kms-dlq-kms-retention",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "version": 1
                }
              },
              {
                "key": {
                  "keyArn": "arn:aws:kms:us-east-1:123456789012:key/66666666-6666-4666-8666-666666666666",
                  "keyId": "journal-kms",
                  "policy": {
                    "policyId": "journal-kms-policy",
                    "sha256": "13a5d524007fe71127ec19879816d9e16f0f208dbe113ace1a0eb17088c0e303",
                    "version": 1
                  }
                },
                "retentionPolicy": {
                  "policyId": "kms-journal-kms-retention",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "version": 1
                }
              },
              {
                "key": {
                  "keyArn": "arn:aws:kms:us-east-1:123456789012:key/77777777-7777-4777-8777-777777777777",
                  "keyId": "queue-kms",
                  "policy": {
                    "policyId": "queue-kms-policy",
                    "sha256": "42c397fec2ed37b75ab282d270a56e381a75320b423538e3c5008a96e7b9c076",
                    "version": 1
                  }
                },
                "retentionPolicy": {
                  "policyId": "kms-queue-kms-retention",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "version": 1
                }
              }
            ],
            "newObjectFloor": "UTC_PLUS_UNCERTAINTY_ORIGINAL_ATTEMPT_LATE_ARRIVAL_J_RETENTION_OR_HORIZON",
            "profile": "ALL_LOGICAL_COPIES_SOURCE_RELATIVE_MAXIMUM_AGE_V1",
            "profileVersion": 1,
            "rounding": "CEILING_WHOLE_SECOND",
            "utcUncertainty": {
              "maximumMillis": 250,
              "policy": {
                "policyId": "utc-uncertainty",
                "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "version": 1
              },
              "profileId": "independent-utc-error-v1"
            }
          }
        }
    """.trimIndent()
}
