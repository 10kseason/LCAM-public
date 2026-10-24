package dev.lunacam.domain.capability

class CapabilityReportRenderer {
    fun render(report: CapabilityReport): CapabilityDocuments = CapabilityDocuments(
        json = JsonRenderer.render(report.toJsonValue()),
        markdown = renderMarkdown(report)
    )

    private fun CapabilityReport.toJsonValue(): JsonValue = JsonValue.ObjectValue(
        listOf(
            "schemaVersion" to JsonValue.NumberValue(schemaVersion.toString()),
            "createdAtUtc" to JsonValue.StringValue(createdAtUtc),
            "cameras" to JsonValue.ArrayValue(cameras.map { it.toJsonValue() })
        )
    )

    private fun CameraCapabilityProfile.toJsonValue(): JsonValue = JsonValue.ObjectValue(
        listOf(
            "cameraAlias" to JsonValue.StringValue(cameraAlias),
            "physicalCameraAliases" to physicalCameraAliases.toJsonArray(),
            "role" to JsonValue.StringValue(roleAssignment.role.name),
            "roleScore" to JsonValue.NumberValue(roleAssignment.score.toString()),
            "roleConfidence" to JsonValue.StringValue(roleAssignment.confidence.name),
            "roleReasons" to roleAssignment.reasons.toJsonArray(),
            "lensFacing" to JsonValue.StringValue(roleEvidence.lensFacing.name),
            "hardwareLevel" to roleEvidence.hardwareLevel.toNullableJson(),
            "focalLengthsMm" to
                JsonValue.ArrayValue(
                    roleEvidence.focalLengthsMm.map {
                        JsonValue.NumberValue(it.toString())
                    }
                ),
            "sensorWidthMm" to roleEvidence.sensorWidthMm.toNullableJson(),
            "sensorHeightMm" to roleEvidence.sensorHeightMm.toNullableJson(),
            "hasRawCapability" to JsonValue.BooleanValue(roleEvidence.hasRawCapability),
            "isLogicalMultiCamera" to JsonValue.BooleanValue(roleEvidence.isLogicalMultiCamera),
            "capabilities" to capabilities.toJsonArray(),
            "streams" to JsonValue.ArrayValue(streams.map { it.toJsonValue() }),
            "recommendedStreams" to
                JsonValue.ArrayValue(recommendedStreams.map { it.toJsonValue() }),
            "mandatoryStreamCombinations" to
                JsonValue.ArrayValue(mandatoryStreamCombinations.map { it.toJsonValue() }),
            "availableCharacteristicKeys" to availableCharacteristicKeys.toJsonArray(),
            "availableRequestKeys" to availableRequestKeys.toJsonArray(),
            "availableResultKeys" to availableResultKeys.toJsonArray(),
            "availablePhysicalRequestKeys" to availablePhysicalRequestKeys.toJsonArray(),
            "characteristics" to
                JsonValue.ArrayValue(
                    characteristics.map { entry ->
                        JsonValue.ObjectValue(
                            listOf(
                                "name" to JsonValue.StringValue(entry.name),
                                "value" to entry.value.toNullableJson()
                            )
                        )
                    }
                ),
            "missingKeys" to missingKeys.toJsonArray(),
            "readErrors" to readErrors.toJsonArray()
        )
    )

    private fun StreamFormatProfile.toJsonValue(): JsonValue = JsonValue.ObjectValue(
        listOf(
            "formatName" to JsonValue.StringValue(formatName),
            "formatValue" to JsonValue.NumberValue(formatValue.toString()),
            "sizes" to
                JsonValue.ArrayValue(
                    sizes.map { size ->
                        JsonValue.ObjectValue(
                            listOf(
                                "width" to JsonValue.NumberValue(size.width.toString()),
                                "height" to JsonValue.NumberValue(size.height.toString()),
                                "minFrameDurationNs" to size.minFrameDurationNs.toNullableJson(),
                                "stallDurationNs" to size.stallDurationNs.toNullableJson()
                            )
                        )
                    }
                )
        )
    )

    private fun RecommendedStreamProfile.toJsonValue(): JsonValue = JsonValue.ObjectValue(
        listOf(
            "useCaseName" to JsonValue.StringValue(useCaseName),
            "useCaseValue" to JsonValue.NumberValue(useCaseValue.toString()),
            "outputFormats" to JsonValue.ArrayValue(outputFormats.map { it.toJsonValue() })
        )
    )

    private fun MandatoryStreamCombinationProfile.toJsonValue(): JsonValue = JsonValue.ObjectValue(
        listOf(
            "category" to JsonValue.StringValue(category),
            "description" to JsonValue.StringValue(description),
            "isReprocessable" to JsonValue.BooleanValue(isReprocessable),
            "streams" to
                JsonValue.ArrayValue(
                    streams.map { stream ->
                        JsonValue.ObjectValue(
                            listOf(
                                "formatName" to JsonValue.StringValue(stream.formatName),
                                "formatValue" to
                                    JsonValue.NumberValue(stream.formatValue.toString()),
                                "availableSizes" to stream.availableSizes.toJsonArray(),
                                "isInput" to JsonValue.BooleanValue(stream.isInput),
                                "isMaximumSize" to JsonValue.BooleanValue(stream.isMaximumSize),
                                "isUltraHighResolution" to
                                    JsonValue.BooleanValue(stream.isUltraHighResolution),
                                "isTenBitCapable" to
                                    JsonValue.BooleanValue(stream.isTenBitCapable),
                                "streamUseCase" to
                                    JsonValue.NumberValue(stream.streamUseCase.toString())
                            )
                        )
                    }
                )
        )
    )

    private fun renderMarkdown(report: CapabilityReport): String = buildString {
        appendLine("# LunaCam Capability Report")
        appendLine()
        appendLine("- Schema: ${report.schemaVersion}")
        appendLine("- Created UTC: ${report.createdAtUtc}")
        appendLine("- Camera candidates: ${report.cameras.size}")
        appendLine()
        appendLine(
            "> Privacy: this export contains no Android ID, account, location, file path, or image pixels."
        )
        report.cameras.forEach { camera ->
            appendLine()
            appendLine("## ${camera.cameraAlias} — ${camera.roleAssignment.role.name}")
            appendLine()
            appendLine("- Role confidence: ${camera.roleAssignment.confidence.name}")
            appendLine("- Role score: ${camera.roleAssignment.score}")
            appendLine("- Lens facing: ${camera.roleEvidence.lensFacing.name}")
            appendLine("- Hardware level: ${camera.roleEvidence.hardwareLevel ?: "missing"}")
            appendLine("- RAW capability: ${camera.roleEvidence.hasRawCapability}")
            appendLine("- Logical multi-camera: ${camera.roleEvidence.isLogicalMultiCamera}")
            appendLine(
                "- Physical aliases: ${camera.physicalCameraAliases.ifEmpty {
                    listOf("none")
                }.joinToString()}"
            )
            appendLine(
                "- Focal lengths (mm): ${camera.roleEvidence.focalLengthsMm.ifEmpty {
                    listOf("missing")
                }.joinToString()}"
            )
            appendLine("- Missing expected keys: ${camera.missingKeys.size}")
            appendLine("- Read errors: ${camera.readErrors.size}")
            appendLine()
            appendLine("### Stream formats")
            camera.streams.forEach { stream ->
                appendLine(
                    "- ${stream.formatName}: ${stream.sizes.joinToString {
                        "${it.width}x${it.height}"
                    }}"
                )
            }
            if (camera.missingKeys.isNotEmpty()) {
                appendLine()
                appendLine("### Missing expected keys")
                camera.missingKeys.forEach { appendLine("- `$it`") }
            }
            if (camera.readErrors.isNotEmpty()) {
                appendLine()
                appendLine("### Read errors")
                camera.readErrors.forEach { appendLine("- $it") }
            }
        }
    }.trimEnd() + "\n"

    private fun List<String>.toJsonArray(): JsonValue = JsonValue.ArrayValue(
        map {
            JsonValue.StringValue(it)
        }
    )

    private fun String?.toNullableJson(): JsonValue = if (this ==
        null
    ) {
        JsonValue.NullValue
    } else {
        JsonValue.StringValue(this)
    }

    private fun Double?.toNullableJson(): JsonValue = if (this ==
        null
    ) {
        JsonValue.NullValue
    } else {
        JsonValue.NumberValue(toString())
    }

    private fun Long?.toNullableJson(): JsonValue = if (this ==
        null
    ) {
        JsonValue.NullValue
    } else {
        JsonValue.NumberValue(toString())
    }

    private sealed interface JsonValue {
        data class ObjectValue(val fields: List<Pair<String, JsonValue>>) : JsonValue

        data class ArrayValue(val values: List<JsonValue>) : JsonValue

        data class StringValue(val value: String) : JsonValue

        data class NumberValue(val value: String) : JsonValue

        data class BooleanValue(val value: Boolean) : JsonValue

        data object NullValue : JsonValue
    }

    private object JsonRenderer {
        fun render(value: JsonValue): String = buildString { appendValue(value, 0) } + "\n"

        private fun StringBuilder.appendValue(value: JsonValue, depth: Int) {
            when (value) {
                is JsonValue.ObjectValue -> appendObject(value, depth)
                is JsonValue.ArrayValue -> appendArray(value, depth)
                is JsonValue.StringValue -> appendQuoted(value.value)
                is JsonValue.NumberValue -> append(value.value)
                is JsonValue.BooleanValue -> append(value.value)
                JsonValue.NullValue -> append("null")
            }
        }

        private fun StringBuilder.appendObject(value: JsonValue.ObjectValue, depth: Int) {
            if (value.fields.isEmpty()) {
                append("{}")
                return
            }
            appendLine("{")
            value.fields.forEachIndexed { index, (name, fieldValue) ->
                append("  ".repeat(depth + 1))
                appendQuoted(name)
                append(": ")
                appendValue(fieldValue, depth + 1)
                if (index != value.fields.lastIndex) append(',')
                appendLine()
            }
            append("  ".repeat(depth)).append('}')
        }

        private fun StringBuilder.appendArray(value: JsonValue.ArrayValue, depth: Int) {
            if (value.values.isEmpty()) {
                append("[]")
                return
            }
            appendLine("[")
            value.values.forEachIndexed { index, item ->
                append("  ".repeat(depth + 1))
                appendValue(item, depth + 1)
                if (index != value.values.lastIndex) append(',')
                appendLine()
            }
            append("  ".repeat(depth)).append(']')
        }

        private fun StringBuilder.appendQuoted(value: String) {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (character.code < 0x20) {
                            append("\\u${character.code.toString(16).padStart(4, '0')}")
                        } else {
                            append(character)
                        }
                    }
                }
            }
            append('"')
        }
    }
}
