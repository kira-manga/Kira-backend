package me.manga.kira.backend.sourceconfig.api

import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.sourceconfig.domain.HistoryWindow
import org.springframework.util.MultiValueMap

/** Inspect multiplicity before conversion: an empty or repeated recognized parameter is not a default. */
internal data class AdminHistoryParameters(val size: Int, val beforeRevision: Long?) {
    companion object {
        const val NEXT_BEFORE_HEADER = "X-Kira-History-Next-Before"

        fun parse(parameters: MultiValueMap<String, String>, maxRevision: Long): AdminHistoryParameters = AdminHistoryParameters(
            size = positiveValue(parameters, "size", HistoryWindow.MAX_SIZE.toLong())?.toInt() ?: HistoryWindow.DEFAULT_SIZE,
            beforeRevision = positiveValue(parameters, "beforeRevision", maxRevision),
        )

        private fun positiveValue(parameters: MultiValueMap<String, String>, name: String, maximum: Long): Long? {
            val values = parameters[name] ?: return null
            if (values.size != 1) invalid()
            val raw = values.single()
            if (raw.isEmpty() || raw.any { it !in '0'..'9' }) invalid()
            // ASCII leading zeros are accepted numerically; overflow never clamps or wraps.
            val value = raw.toLongOrNull() ?: invalid()
            if (value !in 1..maximum) invalid()
            return value
        }

        private fun invalid(): Nothing = throw BadRequestException("Invalid history pagination parameters.", code = "INVALID_HISTORY_PAGE")
    }
}
