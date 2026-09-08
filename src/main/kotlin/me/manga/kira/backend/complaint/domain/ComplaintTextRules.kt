package me.manga.kira.backend.complaint.domain

/** Contract-v1 text rules. HTTP byte limits must also be enforced before constructing these strings. */
object ComplaintTextRules {
    fun subject(value: String): String = normalize(value, ComplaintField.SUBJECT, 1, 200, 800)

    fun reportBody(value: String): String = normalize(value, ComplaintField.BODY, 5, 500, 2_000)

    fun replyBody(value: String): String = normalize(value, ComplaintField.BODY, 1, 500, 2_000)

    fun editedBody(value: String): String = normalize(value, ComplaintField.BODY, 1, 1_000, 4_000)

    fun closureReason(value: String): String = normalize(value, ComplaintField.CLOSURE_REASON, 1, 500, 2_000)

    fun appVersion(value: String?): String? = value?.let { normalize(it, ComplaintField.APP_VERSION, 0, 64, 256) }

    fun osVersion(value: String): String = normalize(value, ComplaintField.OS_VERSION, 0, 128, 512)

    fun manufacturer(value: String): String = normalize(value, ComplaintField.MANUFACTURER, 0, 128, 512)

    fun deviceModel(value: String): String = normalize(value, ComplaintField.DEVICE_MODEL, 0, 128, 512)

    fun adminSearch(value: String): String = normalize(value, ComplaintField.SEARCH, 0, 100, 400)

    fun pageLimit(value: Int): Int = range(value, ComplaintField.PAGE_LIMIT)

    fun batchSize(value: Int): Int = range(value, ComplaintField.BATCH_SIZE)

    internal fun normalize(value: String, field: ComplaintField, minimum: Int, maximum: Int, maximumBytes: Int): String {
        val lineNormalized = value.replace("\r\n", "\n")
        // Trimming first would hide a forbidden control at the edge. UTF-8 encoding would replace
        // unpaired surrogates, so validate Unicode before either measuring or returning the string.
        validateCharacters(lineNormalized, field)
        val normalized = lineNormalized.trim()
        val count = normalized.codePointCount(0, normalized.length)
        if (count < minimum) {
            throw ComplaintValidationException(
                field,
                if (count == 0) ComplaintValidationReason.REQUIRED else ComplaintValidationReason.TOO_SHORT,
            )
        }
        if (count > maximum || normalized.toByteArray(Charsets.UTF_8).size > maximumBytes) {
            throw ComplaintValidationException(field, ComplaintValidationReason.TOO_LONG)
        }
        return normalized
    }

    private fun validateCharacters(value: String, field: ComplaintField) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                    throw ComplaintValidationException(field, ComplaintValidationReason.MALFORMED_UNICODE)
                }
                index += 2
            } else {
                if (Character.isLowSurrogate(character)) {
                    throw ComplaintValidationException(field, ComplaintValidationReason.MALFORMED_UNICODE)
                }
                if (Character.isISOControl(character) && character != '\t' && character != '\n') {
                    throw ComplaintValidationException(field, ComplaintValidationReason.FORBIDDEN_CONTROL)
                }
                index++
            }
        }
    }

    private fun range(value: Int, field: ComplaintField): Int {
        if (value !in 1..50) throw ComplaintValidationException(field, ComplaintValidationReason.OUT_OF_RANGE)
        return value
    }
}
