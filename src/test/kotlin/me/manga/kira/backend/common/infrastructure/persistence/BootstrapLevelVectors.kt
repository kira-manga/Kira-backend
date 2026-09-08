package me.manga.kira.backend.common.infrastructure.persistence

class BootstrapLevelVector(val id: String, val raw: String, val allowed: Boolean) {
    override fun toString(): String = id
}

internal object BootstrapLevelVectors {
    val all = listOf(
        BootstrapLevelVector("stock-info", "INFO", true),
        BootstrapLevelVector("stock-warning", "WARNING", true),
        BootstrapLevelVector("stock-severe", "SEVERE", true),
        BootstrapLevelVector("stock-off", "OFF", true),
        BootstrapLevelVector("stock-config", "CONFIG", true),
        BootstrapLevelVector("stock-fine", "FINE", false),
        BootstrapLevelVector("stock-finer", "FINER", false),
        BootstrapLevelVector("stock-finest", "FINEST", false),
        BootstrapLevelVector("stock-all", "ALL", false),
        BootstrapLevelVector("positive-signed", "+800", true),
        BootstrapLevelVector("leading-zeroes", "000800", true),
        BootstrapLevelVector("above-fine-boundary", "501", true),
        BootstrapLevelVector("fine-boundary", "500", false),
        BootstrapLevelVector("int-max", "2147483647", true),
        BootstrapLevelVector("int-min", "-2147483648", false),
        BootstrapLevelVector("negative-zero", "-0", false),
        BootstrapLevelVector("negative-leading-zeroes", "-001", false),
        BootstrapLevelVector("positive-signed-max", "+2147483647", true),
        BootstrapLevelVector("positive-overflow", "2147483648", false),
        BootstrapLevelVector("negative-overflow", "-2147483649", false),
        BootstrapLevelVector("java-whitespace-trim", " \tINFO\r\n", true),
        BootstrapLevelVector("java-control-trim", "\u0000INFO\u001f", true),
        BootstrapLevelVector("empty", "", false),
        BootstrapLevelVector("whitespace-only", " \t", false),
        BootstrapLevelVector("wrong-case", "Info", false),
        BootstrapLevelVector("signed-name", "+INFO", false),
        BootstrapLevelVector("double-sign", "+-800", false),
        BootstrapLevelVector("bare-sign", "+", false),
        BootstrapLevelVector("double-minus", "--1", false),
        BootstrapLevelVector("fraction", "1.0", false),
        BootstrapLevelVector("non-java-whitespace", "\u00a0INFO\u00a0", false),
        BootstrapLevelVector("fullwidth-digits", "１２３", false),
        BootstrapLevelVector("arabic-digits", "١٨٠٠", false),
        BootstrapLevelVector("hex", "0x320", false),
        BootstrapLevelVector("stock-lookalike", "INFOextra", false),
        BootstrapLevelVector("unsupported-custom", "KIRA_CUSTOM", false),
    )
}
