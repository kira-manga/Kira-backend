package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.Base64
import java.util.UUID
import kotlin.system.exitProcess

/** Every mutation and driver/loader/provider experiment is confined to this owned synthetic child. */
object BootstrapProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        check(args.size == 7)
        val mode = BootstrapProbeCase.valueOf(args[0])
        val nonce = args[1]
        val driverRoot = Path.of(args[2])
        check(UUID.fromString(nonce).toString() == nonce)
        val root = Path.of(args[5])
        check(System.getProperty("user.home") == root.resolve("home").toString())
        check(System.getProperty("java.io.tmpdir") == root.resolve("tmp").toString())
        check(System.getProperty("user.name") == "bootstrap-synthetic")
        if (!BootstrapProbeEnvironment.matches(root, args[6], System.getenv())) {
            println("BOOTSTRAP_ENVIRONMENT_REJECTED mode=${mode.name} nonce=$nonce")
            error("Bootstrap synthetic environment did not match.")
        }
        when {
            mode == BootstrapProbeCase.ASSERTION_FAILURE -> {
                println("BOOTSTRAP_EXPECTED_ASSERTION mode=${mode.name} nonce=$nonce")
                error("Deliberate bootstrap child assertion failure")
            }

            mode == BootstrapProbeCase.EXTRA_ENVIRONMENT -> error("Unexpected synthetic environment was accepted.")

            mode == BootstrapProbeCase.WAIT_FOR_TERMINATION || mode == BootstrapProbeCase.WRONG_READY_IDENTITY -> {
                val readyNonce = if (mode == BootstrapProbeCase.WRONG_READY_IDENTITY) UUID(0, 0).toString() else nonce
                publishReady(root, mode, readyNonce)
                Thread.sleep(Long.MAX_VALUE)
            }

            mode == BootstrapProbeCase.NOOP_EXIT -> exitProcess(0)

            mode == BootstrapProbeCase.WRONG_RECEIPT -> {
                println("BOOTSTRAP_VERIFIED mode=${mode.name} nonce=wrong")
                exitProcess(BOOTSTRAP_VERIFIED_EXIT)
            }

            mode == BootstrapProbeCase.PENDING_LEVEL -> {
                val raw = Base64.getDecoder().decode(args[3]).toString(Charsets.UTF_8)
                BootstrapJulCases.pendingLevel(driverRoot, raw, args[4].toBooleanStrict())
            }

            mode.name.startsWith("JUL_") -> BootstrapJulCases.verify(mode, driverRoot)

            mode.name.startsWith("LOGBACK_") || mode in providerCases -> BootstrapLogbackCases.verify(mode, driverRoot)

            else -> BootstrapDriverCases.verify(mode, driverRoot)
        }
        println("BOOTSTRAP_VERIFIED mode=${mode.name} nonce=$nonce")
        exitProcess(BOOTSTRAP_VERIFIED_EXIT)
    }

    private val providerCases = setOf(
        BootstrapProbeCase.UNKNOWN_FACTORY,
        BootstrapProbeCase.NOP_FACTORY,
        BootstrapProbeCase.UNKNOWN_CONTEXT,
        BootstrapProbeCase.UNFINISHED_FACTORY,
    )

    private fun publishReady(root: Path, mode: BootstrapProbeCase, nonce: String) {
        val pending = root.resolve(BOOTSTRAP_READY_PENDING_FILE)
        val ready = root.resolve(BOOTSTRAP_READY_FILE)
        check(!Files.exists(pending, NOFOLLOW_LINKS) && !Files.exists(ready, NOFOLLOW_LINKS))
        val payload = bootstrapReadyPayload(mode, nonce)
        check(payload.size <= BOOTSTRAP_READY_MAX_BYTES)
        Files.write(pending, payload, CREATE_NEW, WRITE)
        Files.move(pending, ready, ATOMIC_MOVE)
    }
}

internal fun expectBootstrapFailure(code: PersistenceBoundaryFailureCode, action: () -> Unit): PersistenceBoundaryException {
    try {
        action()
        error("Expected fixed bootstrap rejection: ${code.name}")
    } catch (failure: PersistenceBoundaryException) {
        check(failure.code == code)
        check(failure.message == "Persistence boundary rejected: ${code.name}.")
        check(failure.cause == null && failure.suppressed.isEmpty())
        check(!failure.toString().contains(BOOTSTRAP_CANARY))
        return failure
    }
}

internal fun requireSyntheticDriverCold() {
    check(System.getProperty(BOOTSTRAP_DRIVER_MARKER) == null) { "Driver initialized before admission." }
}

internal fun withBootstrapInterruptIsolation(action: () -> Unit) {
    val before = Thread.interrupted()
    try {
        action()
    } finally {
        Thread.interrupted()
        if (before) Thread.currentThread().interrupt()
    }
}
