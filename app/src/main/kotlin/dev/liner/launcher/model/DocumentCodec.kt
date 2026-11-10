package dev.liner.launcher.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object DocumentCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        classDiscriminator = "type"
    }

    fun encode(document: LayoutDocument): String = json.encodeToString(document)

    /**
     * Returns null when the payload is unreadable or newer than this build
     * understands. The caller is responsible for falling back to defaults.
     */
    fun decode(raw: String): LayoutDocument? = try {
        val decoded = json.decodeFromString<LayoutDocument>(raw)
        if (decoded.version > LayoutDocument.CURRENT_VERSION) null else migrate(decoded)
    } catch (_: Exception) {
        // Data errors only: malformed JSON, missing fields, an unknown
        // discriminator. Deliberately not Throwable — an OutOfMemoryError or a
        // bug inside migrate() must surface, not silently reset the user's
        // layout to defaults.
        null
    }

    /** Forward-migrates an older document. Version 1 is the first version, so no-op today. */
    private fun migrate(document: LayoutDocument): LayoutDocument = document
}
