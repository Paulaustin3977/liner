package dev.liner.launcher.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What an import attempt made of a payload.
 *
 * [document] is the layout the payload describes, or null when the payload
 * cannot be used. [error] is the explanation of why it cannot be used, or
 * null when it can. [summary] is what the import would change.
 */
data class ImportResult(
    val document: LayoutDocument?,
    val error: String?,
    val summary: List<String>,
)

object ImportValidator {
    /**
     * Parses [raw] and describes the difference from [current].
     * Returns a null document with an error message when the payload cannot
     * be used; returns a document plus a human readable summary otherwise.
     */
    fun validate(raw: String, current: LayoutDocument = LayoutDocument()): ImportResult {
        val decoded = decode(raw) ?: return ImportResult(
            document = null,
            error = explain(raw),
            summary = emptyList(),
        )

        return ImportResult(document = decoded, error = null, summary = summarise(current, decoded))
    }

    /**
     * The document a payload describes, or null when it cannot be read.
     *
     * The codec lets Errors through on purpose — a bug in it must not be
     * mistaken for a broken file. A deeply nested payload can raise a
     * `StackOverflowError` though, and an import must never crash the
     * launcher: the failure is turned into the explanation instead.
     */
    private fun decode(raw: String): LayoutDocument? = try {
        DocumentCodec.decode(raw)
    } catch (_: Throwable) {
        null
    }

    /**
     * The explanation for a payload the codec refused.
     *
     * The codec refuses two different things: a payload it cannot read, and
     * a payload from a newer version of Liner. They need different
     * explanations — one is a broken file, the other is a file this build
     * must not overwrite its own layout with.
     */
    private fun explain(raw: String): String {
        val version = declaredVersion(raw)
        return if (version != null && version > LayoutDocument.CURRENT_VERSION) {
            "That layout was made by a newer version of Liner, so this version cannot read it."
        } else {
            "That file is not readable as a Liner layout."
        }
    }

    /**
     * The version a payload declares, or null when it declares none.
     *
     * Only the version is read out of the raw payload, and only when the
     * payload is JSON at all — the codec has already refused everything else.
     */
    private fun declaredVersion(raw: String): Int? = try {
        val element = Json.parseToJsonElement(raw)
        (element as? JsonObject)?.get("version")?.jsonPrimitive?.intOrNull
    } catch (_: Exception) {
        // A payload that is not JSON has no version: the codec already
        // refused it, and the explanation is the readable-file one.
        null
    }

    /**
     * What the imported layout would change about the current one.
     *
     * The apps are named by their package, because that is what a layout
     * document holds — the name the user gave an app is not part of the
     * document.
     */
    private fun summarise(current: LayoutDocument, incoming: LayoutDocument): List<String> {
        val changes = mutableListOf<String>()

        val currentPackages = current.sections.flatMap { it.items }.map { it.packageName }.toSet()
        val incomingPackages = incoming.sections.flatMap { it.items }.map { it.packageName }.toSet()

        val added = incomingPackages - currentPackages
        val removed = currentPackages - incomingPackages
        if (added.isNotEmpty()) changes += "Adds apps: ${added.joinToString()}"
        if (removed.isNotEmpty()) changes += "Removes apps: ${removed.joinToString()}"
        if (current.sections.size != incoming.sections.size) {
            changes += "Sections: ${current.sections.size} becomes ${incoming.sections.size}"
        }
        if (current.theme != incoming.theme) changes += "Replaces the theme"

        if (changes.isEmpty()) changes += "No visible changes"
        return changes
    }
}
