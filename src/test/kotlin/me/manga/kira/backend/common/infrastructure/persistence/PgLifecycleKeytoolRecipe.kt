package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path

/** Eight closed synthetic recipes, not an arbitrary external-command seam. No shell or ambient keystore. */
internal enum class PgLifecycleKeytoolStep {
    ROOT_KEY,
    WRONG_KEY,
    SERVER_KEY,
    SERVER_REQUEST,
    MATCHED_CERTIFICATE,
    WRONG_HOST_CERTIFICATE,
    ROOT_CERTIFICATE,
    WRONG_CERTIFICATE,
}

internal object PgLifecycleKeytoolRecipe {
    fun command(step: PgLifecycleKeytoolStep, files: PgLifecycleTlsFiles): List<String> {
        check(Runtime.version().feature() == 21) { "Synthetic TLS generation requires the selected JDK21." }
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool")
        check(keytool.isAbsolute && Files.isRegularFile(keytool) && Files.isExecutable(keytool))
        val options = listOf(
            keytool.toString(), "-J-Xms16m", "-J-Xmx64m", "-J-XX:MaxMetaspaceSize=64m", "-J-XX:-UsePerfData",
            "-J-XX:+DisableAttachMechanism", "-J-XX:-HeapDumpOnOutOfMemoryError", "-J-XX:ErrorFile=/dev/null",
            "-J-Duser.home=${files.root.resolve("home")}", "-J-Djava.io.tmpdir=${files.root.resolve("tmp")}",
            "-J-Duser.name=pg-lifecycle-synthetic", "-J-Duser.timezone=UTC", "-J-Duser.language=en", "-J-Duser.country=US",
        )
        return options + when (step) {
            PgLifecycleKeytoolStep.ROOT_KEY -> authority(files, wrong = false)

            PgLifecycleKeytoolStep.WRONG_KEY -> authority(files, wrong = true)

            PgLifecycleKeytoolStep.SERVER_KEY -> listOf("-genkeypair") + keyPair() + validity() +
                store(files, PgLifecycleTlsAsset.SERVER_STORE, PgLifecycleTlsFiles.SERVER_ALIAS, privateKey = true) +
                listOf("-dname", SERVER_NAME, "-ext", "BC:critical=ca:false")

            PgLifecycleKeytoolStep.SERVER_REQUEST -> listOf("-certreq", "-sigalg", "SHA256withRSA") +
                store(files, PgLifecycleTlsAsset.SERVER_STORE, PgLifecycleTlsFiles.SERVER_ALIAS, privateKey = true) +
                listOf("-file", files.path(PgLifecycleTlsAsset.REQUEST).toString())

            PgLifecycleKeytoolStep.MATCHED_CERTIFICATE -> leaf(files, wrongHost = false)

            PgLifecycleKeytoolStep.WRONG_HOST_CERTIFICATE -> leaf(files, wrongHost = true)

            PgLifecycleKeytoolStep.ROOT_CERTIFICATE -> export(files, wrong = false)

            PgLifecycleKeytoolStep.WRONG_CERTIFICATE -> export(files, wrong = true)
        }
    }

    private fun authority(files: PgLifecycleTlsFiles, wrong: Boolean): List<String> {
        val asset = if (wrong) PgLifecycleTlsAsset.WRONG_STORE else PgLifecycleTlsAsset.ROOT_STORE
        val name = if (wrong) "CN=Wrong Synthetic W03 CA,O=Kira Synthetic Tests" else "CN=Synthetic W03 CA,O=Kira Synthetic Tests"
        return listOf("-genkeypair") + keyPair() + validity() + store(files, asset, PgLifecycleTlsFiles.ROOT_ALIAS, privateKey = true) +
            listOf("-dname", name, "-ext", "BC:critical=ca:true,pathlen:0", "-ext", "KU:critical=keyCertSign,cRLSign")
    }

    private fun leaf(files: PgLifecycleTlsFiles, wrongHost: Boolean): List<String> {
        val asset = if (wrongHost) PgLifecycleTlsAsset.WRONG_HOST else PgLifecycleTlsAsset.SERVER
        val name = if (wrongHost) "CN=wrong-host.synthetic.invalid,O=Kira Synthetic Tests" else SERVER_NAME
        val san = if (wrongHost) "SAN=IP:127.0.0.2,DNS:wrong-host.synthetic.invalid" else "SAN=IP:127.0.0.1,DNS:localhost"
        return listOf("-gencert", "-rfc", "-sigalg", "SHA256withRSA") + validity() +
            store(files, PgLifecycleTlsAsset.ROOT_STORE, PgLifecycleTlsFiles.ROOT_ALIAS, privateKey = true) + listOf(
                "-infile", files.path(PgLifecycleTlsAsset.REQUEST).toString(), "-outfile", files.path(asset).toString(), "-dname", name,
                "-ext", "BC:critical=ca:false", "-ext", "KU:critical=digitalSignature,keyEncipherment", "-ext", "EKU=serverAuth", "-ext", san,
            )
    }

    private fun export(files: PgLifecycleTlsFiles, wrong: Boolean): List<String> = listOf("-exportcert", "-rfc") +
        store(files, if (wrong) PgLifecycleTlsAsset.WRONG_STORE else PgLifecycleTlsAsset.ROOT_STORE, PgLifecycleTlsFiles.ROOT_ALIAS, privateKey = false) +
        listOf("-file", files.path(if (wrong) PgLifecycleTlsAsset.WRONG_CA else PgLifecycleTlsAsset.ROOT_CA).toString())

    private fun keyPair(): List<String> = listOf("-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA")

    private fun validity(): List<String> = listOf("-startdate", "2025/01/01 00:00:00", "-validity", "10957")

    private fun store(files: PgLifecycleTlsFiles, asset: PgLifecycleTlsAsset, alias: String, privateKey: Boolean): List<String> =
        listOf("-storetype", "JKS", "-keystore", files.path(asset).toString(), "-alias", alias, "-storepass", PgLifecycleTlsFiles.STORE_PASSWORD) +
            if (privateKey) listOf("-keypass", PgLifecycleTlsFiles.STORE_PASSWORD) else emptyList()

    private const val SERVER_NAME = "CN=localhost,O=Kira Synthetic Tests"
}
