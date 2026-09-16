package me.manga.kira.backend.common.infrastructure.persistence

internal class NativePathCase(val label: String, val style: PersistencePathStyle, val reference: String, val accepted: Boolean) {
    override fun toString(): String = label
}

internal object NativePathCases {
    private val posix = listOf(
        Triple("empty", "", false),
        Triple("relative", "synthetic/file.crt", false),
        Triple("root-only-is-syntax", "/", true),
        Triple("absolute", "/synthetic-unopened/file.crt", true),
        Triple("double-slash-is-not-locality-proof", "//synthetic/file.crt", true),
        Triple("dot-components-not-resolved", "/synthetic/../file.crt", true),
        Triple("dot-root-not-resolved", "/.", true),
        Triple("unicode", "/synthetic/ملف.crt", true),
        Triple("leading-space", " /synthetic/file.crt", false),
        Triple("newline-not-opened", "/synthetic/file\n.crt", true),
        Triple("nul-start", "\u0000/synthetic/file.crt", false),
        Triple("nul-interior", "/synthetic/\u0000file.crt", false),
        Triple("windows-drive", "C:/synthetic/file.crt", false),
        Triple("backslash-root", "\\synthetic\\file.crt", false),
    ).map { (label, reference, accepted) -> NativePathCase("posix-$label", PersistencePathStyle.POSIX, reference, accepted) }

    private val windows = listOf(
        Triple("drive-slash", "C:/synthetic-unopened/file.crt", true),
        Triple("drive-backslash", "c:\\synthetic-unopened\\file.crt", true),
        Triple("mixed-separators", "Z:/synthetic-unopened\\file.crt", true),
        Triple("unicode", "D:\\synthetic-unopened\\ملف.crt", true),
        Triple("space-inside", "C:/synthetic folder/file name.crt", true),
        Triple("leading-dot-component", "C:/.synthetic/file.crt", true),
        Triple("nondevice-com-zero", "C:/COM0.crt", true),
        Triple("nondevice-com-ten", "C:/COM10.crt", true),
        Triple("nondevice-lpt-zero", "C:/LPT0.crt", true),
        Triple("nondevice-lpt-ten", "C:/LPT10.crt", true),
        Triple("device-prefix-not-name", "C:/CONSOLE.crt", true),
        Triple("device-suffix-not-name", "C:/xNUL.crt", true),
        Triple("empty", "", false),
        Triple("drive-only", "C:", false),
        Triple("root-only", "C:/", false),
        Triple("backslash-root-only", "C:\\", false),
        Triple("drive-relative", "C:synthetic.crt", false),
        Triple("root-relative", "\\synthetic\\file.crt", false),
        Triple("posix-root", "/synthetic/file.crt", false),
        Triple("unc", "\\\\synthetic-server\\share\\file.crt", false),
        Triple("slash-unc", "//synthetic-server/share/file.crt", false),
        Triple("extended-namespace", "\\\\?\\C:\\synthetic\\file.crt", false),
        Triple("device-namespace", "\\\\.\\C:\\synthetic\\file.crt", false),
        Triple("nonletter-drive", "1:/synthetic/file.crt", false),
        Triple("unicode-drive", "Ç:/synthetic/file.crt", false),
        Triple("double-slash", "C:/synthetic//file.crt", false),
        Triple("double-backslash", "C:\\synthetic\\\\file.crt", false),
        Triple("mixed-empty-component", "C:/synthetic/\\file.crt", false),
        Triple("trailing-separator", "C:/synthetic/file.crt/", false),
        Triple("dot", "C:/./file.crt", false),
        Triple("dot-dot", "C:/synthetic/../file.crt", false),
        Triple("trailing-dot", "C:/synthetic/file.crt.", false),
        Triple("trailing-space", "C:/synthetic/file.crt ", false),
        Triple("directory-dot", "C:/synthetic./file.crt", false),
        Triple("directory-space", "C:/synthetic /file.crt", false),
        Triple("alternate-stream", "C:/synthetic/file.crt:stream", false),
        Triple("directory-colon", "C:/synthetic:stream/file.crt", false),
        Triple("less-than", "C:/synthetic/<file.crt", false),
        Triple("greater-than", "C:/synthetic/>file.crt", false),
        Triple("quote", "C:/synthetic/\"file.crt", false),
        Triple("pipe", "C:/synthetic/|file.crt", false),
        Triple("question", "C:/synthetic/?file.crt", false),
        Triple("star", "C:/synthetic/*file.crt", false),
        Triple("newline", "C:/synthetic/\nfile.crt", false),
        Triple("unit-separator", "C:/synthetic/\u001ffile.crt", false),
        Triple("nul", "C:/synthetic/\u0000file.crt", false),
    ).map { (label, reference, accepted) -> NativePathCase("windows-$label", PersistencePathStyle.LOCAL_WINDOWS, reference, accepted) }

    val references = posix + windows
    val devices = listOf(
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9", "COM¹", "COM²", "COM³", "LPT¹", "LPT²", "LPT³",
    )

    val deviceAliases = devices + listOf("CONIN$", "CONOUT$")
    val nonDeviceSpacedNames = listOf(
        "CONSOLE .pk8", "xNUL .pk8", "CONIN .pk8", "CONOUT .pk8", "COM0 .pk8", "COM10 .pk8",
        "LPT0 .pk8", "LPT10 .pk8", "NU L .pk8", "NUL\u00a0.pk8",
    )
}
