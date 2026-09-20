package me.manga.kira.backend.database

import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentJsonV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationFailureV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationOperatorV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.boundedDesiredInstallationFailure
import me.manga.kira.backend.complaint.infrastructure.admission.requireDesiredInstallation
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.system.exitProcess

/** Dedicated non-web signed-PREPARED-G1 first-D command. No Spring launch, Sign, PUT, finalizer, history writer or grant change. */
internal object ComplaintSignedGenesisFirstDMain {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun run(args: Array<String>): Int {
        val operator = ComplaintDesiredInstallationOperatorV1.begin() // One original budget before ANY file or credential acquisition.
        return try {
            val command = parseArguments(args)
            val inputs = ComplaintDesiredDeploymentJsonV1.parse(read(command.manifest, ComplaintDesiredDeploymentJsonV1.MAX_BYTES, operator))
            val release = ComplaintSignedGenesisFirstDInputsV1.fromRaw(
                read(command.intent, CatalogGenesisCapacity.MAX_DOCUMENT_BYTES, operator),
                read(command.initialTrust, OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES, operator),
                read(command.currentTrust, OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES, operator),
                read(command.envelope, CatalogGenesisCapacity.MAX_DOCUMENT_BYTES, operator),
                read(command.pin, 64, operator).also { valid(it.size == 64 && it.all { byte -> byte.toInt().toChar() in "0123456789abcdef" }) }
                    .toString(Charsets.US_ASCII),
            )
            operator.requireRunning()
            val secrets = credentials("KIRA_DESIRED_SECRETS")
            val sealer = if (inputs.sealerMapping == null) null else credentials("KIRA_DESIRED_SEAL_BOOTSTRAP")
            val result = operator.selectSignedGenesisFirst(inputs, release, secrets, sealer)
            println("signed-genesis-first-d ${result.transition.name} generation=${result.desiredGeneration}; historical-only")
            0
        } catch (problem: Throwable) {
            val cleanup = runCatching(operator::close).exceptionOrNull()
            System.err.println("signed-genesis-first-d refused: ${boundedDesiredInstallationFailure(cleanup ?: problem).code.name}")
            1
        }
    }

    internal fun parseArguments(args: Array<String>): SignedGenesisFirstDCommandV1 {
        valid(args.size == 13 && args[0] == "select")
        val options = listOf("--manifest", "--intent", "--initial-trust", "--current-trust", "--signed-envelope", "--genesis-pin")
        valid(options.indices.all { args[it * 2 + 1] == options[it] })
        val paths = options.indices.map { index ->
            val raw = args[index * 2 + 2]
            valid(raw.length in 1..4096)
            val path = try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                throw ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
            }
            valid(path.isAbsolute && path.normalize() == path)
            path
        }
        return SignedGenesisFirstDCommandV1(paths[0], paths[1], paths[2], paths[3], paths[4], paths[5])
    }

    private fun read(path: Path, maximum: Int, operator: ComplaintDesiredInstallationOperatorV1): ByteArray {
        operator.requireRunning()
        valid(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maximum + 1) }
        valid(bytes.size in 1..maximum)
        operator.requireRunning()
        return bytes
    }

    private fun credentials(prefix: String): AwsSessionCredentials = AwsSessionCredentials.create(
        credential("${prefix}_ACCESS_KEY_ID", 128),
        credential("${prefix}_SECRET_ACCESS_KEY", 256),
        credential("${prefix}_SESSION_TOKEN", 16_384),
    )

    private fun credential(name: String, maximum: Int): String {
        val value = System.getenv(name) ?: throw ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        valid(value.length in 1..maximum && value.all { it in '!'..'~' })
        return value
    }

    private fun valid(condition: Boolean) = requireDesiredInstallation(condition, ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
}

internal class SignedGenesisFirstDCommandV1(
    val manifest: Path,
    val intent: Path,
    val initialTrust: Path,
    val currentTrust: Path,
    val envelope: Path,
    val pin: Path,
) {
    override fun toString(): String = "SignedGenesisFirstDCommandV1(redacted)"
}
