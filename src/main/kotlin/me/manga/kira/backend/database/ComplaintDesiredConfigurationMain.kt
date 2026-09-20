package me.manga.kira.backend.database

import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentJsonV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationFailureV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationOperatorV1
import me.manga.kira.backend.complaint.infrastructure.admission.boundedDesiredInstallationFailure
import me.manga.kira.backend.complaint.infrastructure.admission.requireDesiredInstallation
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.system.exitProcess

/** Explicit non-web entry only. No Spring application, beans, runner, HTTP routes, grants, migrations or deployment. */
internal object ComplaintDesiredConfigurationMain {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    /** Emits only a bounded historical result AFTER provider/phase/root/trust cleanup, never a current-authority receipt. */
    @Suppress("TooGenericExceptionCaught")
    internal fun run(args: Array<String>): Int {
        val operator = ComplaintDesiredInstallationOperatorV1.begin() // Original 60s starts before arguments and file acquisition.
        return try {
            val command = parseArguments(args)
            val inputs = readInputs(command.path)
            operator.requireRunning()
            val secrets = credentials("KIRA_DESIRED_SECRETS")
            val sealer = if (inputs.sealerMapping == null) null else credentials("KIRA_DESIRED_SEAL_BOOTSTRAP")
            val result = if (command.expectedGeneration == null) {
                operator.bootstrap(inputs, secrets, sealer)
            } else {
                operator.supersede(inputs, command.expectedGeneration, secrets, sealer)
            }
            println("desired-configuration ${result.transition.name} generation=${result.desiredGeneration}; historical-only")
            0
        } catch (problem: Throwable) {
            val cleanup = runCatching(operator::close).exceptionOrNull()
            val failure = boundedDesiredInstallationFailure(cleanup ?: problem)
            System.err.println("desired-configuration refused: ${failure.code.name}")
            1
        }
    }

    internal fun parseArguments(args: Array<String>): DesiredConfigurationCommandV1 {
        requireDesiredInstallation(args.size == 3 || args.size == 5, ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        val expected = when (args[0]) {
            "bootstrap" -> {
                requireDesiredInstallation(args.size == 3 && args[1] == "--manifest", ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
                null
            }

            "supersede" -> {
                requireDesiredInstallation(
                    args.size == 5 && args[1] == "--expected-generation" && args[3] == "--manifest",
                    ComplaintDesiredInstallationFailureV1.INPUT_REFUSED,
                )
                val number = args[2]
                requireDesiredInstallation(
                    number.length in 1..19 && number.first() in '1'..'9' && number.all {
                        it in '0'..'9'
                    },
                    ComplaintDesiredInstallationFailureV1.INPUT_REFUSED,
                )
                number.toLongOrNull()?.takeIf { it in 1 until Long.MAX_VALUE }
                    ?: throw ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
            }

            else -> throw ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        }
        val rawPath = args.last()
        requireDesiredInstallation(rawPath.length in 1..4096, ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        val path = try {
            Path.of(rawPath)
        } catch (_: InvalidPathException) {
            throw ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        }
        requireDesiredInstallation(path.isAbsolute && path.normalize() == path, ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        return DesiredConfigurationCommandV1(path, expected)
    }

    private fun readInputs(path: Path): ComplaintDesiredDeploymentInputsV1 {
        requireDesiredInstallation(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(ComplaintDesiredDeploymentJsonV1.MAX_BYTES + 1) }
        return ComplaintDesiredDeploymentJsonV1.parse(bytes)
    }

    private fun credentials(prefix: String): AwsSessionCredentials = AwsSessionCredentials.create(
        credential("${prefix}_ACCESS_KEY_ID", 128),
        credential("${prefix}_SECRET_ACCESS_KEY", 256),
        credential("${prefix}_SESSION_TOKEN", 16_384),
    )

    private fun credential(name: String, maximum: Int): String {
        val value = System.getenv(name) ?: throw ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        requireDesiredInstallation(value.length in 1..maximum && value.all { it in '!'..'~' }, ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        return value
    }
}

internal class DesiredConfigurationCommandV1(val path: Path, val expectedGeneration: Long?) {
    override fun toString(): String = "DesiredConfigurationCommandV1(redacted)"
}
