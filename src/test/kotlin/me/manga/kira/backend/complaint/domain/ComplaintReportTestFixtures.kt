package me.manga.kira.backend.complaint.domain

internal object ComplaintReportTestFixtures {
    const val ID = "11111111-1111-4111-8111-111111111111"
    const val KEY = "22222222-2222-4222-8222-222222222222"
    const val TEST_SCOPE = "33333333-3333-4333-8333-333333333333"
    const val OTHER_ID = "44444444-4444-4444-8444-444444444444"
    const val OTHER_KEY = "55555555-5555-4555-8555-555555555555"
    const val LIVE = "00000000-0000-0000-0000-000000000000"

    fun identity(id: String = ID, key: String = KEY, scope: String = LIVE): ComplaintReportIdentity =
        checkNotNull(ComplaintReportIdentity.checked(id, key, scope))

    fun metadata(appVersion: String? = null, osVersion: String = "", manufacturer: String = "", deviceModel: String = ""): ComplaintReportMetadataInput =
        ComplaintReportMetadataInput(appVersion, osVersion, manufacturer, deviceModel)

    fun result(
        identity: ComplaintReportIdentity = identity(),
        type: ComplaintType = ComplaintType.TECHNICAL,
        subject: String = "S",
        body: String = "abcde",
        metadata: ComplaintReportMetadataInput = metadata(),
    ): ComplaintReportRequestResult = ComplaintReportRequest.normalize(identity, type, subject, body, metadata)

    fun request(
        identity: ComplaintReportIdentity = identity(),
        type: ComplaintType = ComplaintType.TECHNICAL,
        subject: String = "S",
        body: String = "abcde",
        metadata: ComplaintReportMetadataInput = metadata(),
    ): ComplaintReportRequest = accepted(result(identity, type, subject, body, metadata))

    fun accepted(result: ComplaintReportRequestResult): ComplaintReportRequest =
        checkNotNull(result as? ComplaintReportRequestResult.Accepted) { "Synthetic request rejected." }.request

    fun rejected(result: ComplaintReportRequestResult): ComplaintReportRequestResult.Rejected =
        checkNotNull(result as? ComplaintReportRequestResult.Rejected) { "Expected bounded rejection." }

    fun withField(field: ComplaintReportField, value: String): ComplaintReportRequestResult = result(
        subject = if (field == ComplaintReportField.SUBJECT) value else "S",
        body = if (field == ComplaintReportField.BODY) value else "abcde",
        metadata = metadata(
            appVersion = if (field == ComplaintReportField.APP_VERSION) value else null,
            osVersion = if (field == ComplaintReportField.OS_VERSION) value else "",
            manufacturer = if (field == ComplaintReportField.MANUFACTURER) value else "",
            deviceModel = if (field == ComplaintReportField.DEVICE_MODEL) value else "",
        ),
    )

    fun text(request: ComplaintReportRequest, field: ComplaintReportField): String? = when (field) {
        ComplaintReportField.SUBJECT -> request.subject
        ComplaintReportField.BODY -> request.body
        ComplaintReportField.APP_VERSION -> request.metadata.appVersion
        ComplaintReportField.OS_VERSION -> request.metadata.osVersion
        ComplaintReportField.MANUFACTURER -> request.metadata.manufacturer
        ComplaintReportField.DEVICE_MODEL -> request.metadata.deviceModel
    }

    fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    fun unicodeRequest(subject: String = "Aé"): ComplaintReportRequest = request(
        identity = identity(scope = TEST_SCOPE),
        type = ComplaintType.CUSTOM,
        subject = subject,
        body = "A\n😀bc",
        metadata = metadata("1.0", "", "M", "D"),
    )

    fun goldens(): List<Golden> = listOf(
        Golden(
            request(),
            G1_HEX,
            219,
            "f91eed8f82ac0f4273d94619e4d5bd6766a883a42873d1a6dd4ad49a917d0099",
            "-R7tj4KsD0Jz2UYZ5NW9Z2aog6Qoc9Gm3UrUmpF9AJk",
        ),
        Golden(
            request(metadata = metadata(appVersion = "")),
            G1_HEX.replaceRange(398, 406, "00000000"),
            219,
            "b8bb136cc9152d437f8d3a7feb1369eeac7ae07e42c354064038c77415dd62d9",
            "uLsTbMkVLUN_jTp_6xNp7qx64H5Cw1QGQDjHdBXdYtk",
        ),
        Golden(
            unicodeRequest(),
            G3_HEX,
            226,
            "319bf59e78a07ae8bbc8a0e07df85065d03d88d3861c4234294a531266843013",
            "MZv1nnigeui7yKDgffhQZdA9iNOGHEI0KUpTEmaEMBM",
        ),
        Golden(
            unicodeRequest("Ae\u0301"),
            G3_HEX.replace("0000000341c3a9", "000000044165cc81"),
            227,
            "7bd05c02d3a50ab2845f876f4f0f50a0e9a99c8849db8553cf158879891db381",
            "e9BcAtOlCrKEX4dvTw9QoOmpnIhJ24VTzxWIeYkds4E",
        ),
    )

    class Golden(val request: ComplaintReportRequest, val frameHex: String, val frameBytes: Int, val digestHex: String, val encoded: String)

    // Exact independent protocol bytes, not an expected value calculated by the production frame writer.
    private const val PREFIX =
        "000000226b6972612d636f6d706c61696e742d726571756573742d66696e6765727072696e74" +
            "0000000100000004504f5354000000122f6170692f76312f636f6d706c61696e7473" +
            "0000000c4f574e45525f43524541544500000024"
    private const val TARGET =
        "000000010000002431313131313131312d313131312d343131312d383131312d313131313131313131313131"
    private const val G1_HEX = PREFIX +
        "30303030303030302d303030302d303030302d303030302d303030303030303030303030" + TARGET +
        "00000009544543484e4943414c0000000153000000056162636465ffffffff000000000000000000000000ffffffff"
    private const val G3_HEX = PREFIX +
        "33333333333333332d333333332d343333332d383333332d333333333333333333333333" + TARGET +
        "00000006435553544f4d0000000341c3a900000008410af09f9880626300000003312e30" +
        "00000000000000014d0000000144ffffffff"
}
