package com.example.uchuvatwinapp

import org.json.JSONObject
import org.tensorflow.lite.support.metadata.MetadataExtractor
import java.io.ByteArrayInputStream
import java.nio.MappedByteBuffer
import java.util.zip.ZipInputStream

object MetaData {

    fun extractNamesFromMetadata(model: MappedByteBuffer): List<String> {
        extractNamesFromAppendedMetadata(model).let {
            if (it.isNotEmpty()) return it
        }

        try {
            val metadataExtractor = MetadataExtractor(model)
            val inputStream = metadataExtractor.getAssociatedFile("temp_meta.txt")
            val metadata = inputStream?.bufferedReader()?.use { it.readText() } ?: return emptyList()

            val regex = Regex("'names': \\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL)

            val match = regex.find(metadata)
            val namesContent = match?.groups?.get(1)?.value ?: return emptyList()

            val regex2 = Regex("\"([^\"]*)\"|'([^']*)'")
            val match2 = regex2.findAll(namesContent)
            val list = match2.map { it.groupValues[1].ifEmpty { it.groupValues[2] }}.toList()

            return list
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private fun extractNamesFromAppendedMetadata(model: MappedByteBuffer): List<String> {
        return try {
            val bytes = ByteArray(model.capacity())
            model.duplicate().apply {
                position(0)
                get(bytes)
            }

            val zipOffset = findLastZipHeader(bytes)
            if (zipOffset < 0) return emptyList()

            ZipInputStream(ByteArrayInputStream(bytes, zipOffset, bytes.size - zipOffset)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && (entry.name == "metadata.json" ||
                                entry.name == "TFLITE_ULTRALYTICS_METADATA.json")) {
                        val json = JSONObject(zip.bufferedReader().use { it.readText() })
                        if (!json.has("names")) return emptyList()

                        val names = json.getJSONObject("names")
                        return names.keys().asSequence()
                            .toList()
                            .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                            .map { names.getString(it) }
                    }
                    entry = zip.nextEntry
                }
            }

            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findLastZipHeader(bytes: ByteArray): Int {
        for (index in bytes.size - 4 downTo 0) {
            if (bytes[index] == 0x50.toByte() &&
                bytes[index + 1] == 0x4B.toByte() &&
                bytes[index + 2] == 0x03.toByte() &&
                bytes[index + 3] == 0x04.toByte()) {
                return index
            }
        }
        return -1
    }

    val TEMP_CLASSES = List(1000) { "class${it + 1}" }
}