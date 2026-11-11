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
     * mistaken for a broken file. This matches that contract and catches
     * Exception only, so an Error inside the decoder surfaces instead of
     * being reported to the user as an unreadable file.
     */
    private fun decode(raw: String): LayoutDocument? = try {
        DocumentCodec.decode(raw)
    } catch (_: Exception) {
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
     * document holds — the name the user gave an app and the style set on
     * it are part of the document too, and are reported by their own
     * comparison.
     *
     * The sections are compared by their membership and their titles, not by
     * their number alone: the same apps in another section, in another
     * order, or under another title is a change the user has to be told
     * about, because the import rearranges the home screen.
     */
    private fun summarise(current: LayoutDocument, incoming: LayoutDocument): List<String> {
        val changes = mutableListOf<String>()

        val currentPackages = current.sections.flatMap { it.items }.map { it.packageName }.toSet()
        val incomingPackages = incoming.sections.flatMap { it.items }.map { it.packageName }.toSet()

        val added = incomingPackages - currentPackages
        val removed = currentPackages - incomingPackages
        if (added.isNotEmpty()) changes += "Adds apps: ${added.joinToString()}"
        if (removed.isNotEmpty()) changes += "Removes apps: ${removed.joinToString()}"

        changes += itemChanges(current, incoming)
        changes += sectionChanges(current, incoming)

        if (current.sections.size != incoming.sections.size) {
            changes += "Sections: ${current.sections.size} becomes ${incoming.sections.size}"
        }
        if (current.theme != incoming.theme) changes += "Replaces the theme"

        if (changes.isEmpty()) changes += "No visible changes"
        return changes
    }

    /**
     * The changes to the apps that are in both documents.
     *
     * The apps are matched by their package. The name the user gave an app
     * and the style set on it are both part of the document and both
     * visible on the home screen, so a payload that changes either of them
     * changes what the user sees and has to be told about.
     */
    private fun itemChanges(current: LayoutDocument, incoming: LayoutDocument): List<String> {
        val changes = mutableListOf<String>()
        val currentItems = current.sections.flatMap { it.items }.associateBy { it.packageName }
        val incomingItems = incoming.sections.flatMap { it.items }.associateBy { it.packageName }

        for ((packageName, after) in incomingItems) {
            val before = currentItems[packageName] ?: continue
            if (before.label != after.label) {
                changes += "Renames $packageName to ${describeName(after.label)}"
            }
            if (before.style != after.style) {
                changes += "Restyles $packageName"
            }
        }

        return changes
    }

    /**
     * How the name of an app is named in the summary: the name the user
     * gave it, or the name the app itself carries when the import takes
     * the custom name away.
     */
    private fun describeName(label: String?): String =
        label?.let { "\"$it\"" } ?: "the app's own name"

    /**
     * The changes between the two documents' sections.
     *
     * A section is recognised by its id, so the same section under another
     * title, with another membership or in another order is a *changed*
     * section — and a section that is only in one of the two documents is an
     * added or a removed one.
     */
    private fun sectionChanges(current: LayoutDocument, incoming: LayoutDocument): List<String> {
        val changes = mutableListOf<String>()
        val currentById = current.sections.associateBy { it.id }

        if (sectionsReordered(current, incoming)) changes += "Reorders the sections"

        for (section in incoming.sections) {
            val other = currentById[section.id]
            if (other == null) {
                changes += "Adds section \"${describe(section)}\""
            } else if (section.title != other.title) {
                changes += "Renames section \"${describe(other)}\" to \"${describe(section)}\""
            }
            changes += membershipChanges(other, section)
            changes += orderChanges(other, section)
        }

        for (section in current.sections) {
            if (incoming.sections.none { it.id == section.id }) {
                changes += "Removes section \"${describe(section)}\""
            }
        }

        return changes
    }

    /**
     * Whether the sections the two documents share are in another order.
     *
     * Only the sections both documents hold are compared: a section that is
     * only in one of them is reported as added or removed, and it says
     * nothing about the order of the others.
     */
    private fun sectionsReordered(current: LayoutDocument, incoming: LayoutDocument): Boolean {
        val currentIds = current.sections.map { it.id }
        val incomingIds = incoming.sections.map { it.id }

        val sharedThere = currentIds.filter { it in incomingIds }
        val sharedHere = incomingIds.filter { it in currentIds }

        return sharedThere != sharedHere
    }

    /**
     * The apps that left a section and the ones that joined it.
     *
     * An app that only moved to another section is not added or removed —
     * it is somewhere else now, and that is what the user has to see.
     */
    private fun membershipChanges(before: Section?, after: Section): List<String> {
        val there = before?.items?.map { it.packageName } ?: emptyList()
        val here = after.items.map { it.packageName }
        val lost = there - here.toSet()
        val gained = here - there.toSet()

        return buildList {
            if (lost.isNotEmpty()) add("Section \"${describe(after)}\" loses ${lost.joinToString()}")
            if (gained.isNotEmpty()) add("Section \"${describe(after)}\" gains ${gained.joinToString()}")
        }
    }

    /**
     * Whether the apps a section holds are in another order.
     *
     * Only the apps both sections hold are compared: the ones that moved
     * away are reported by [membershipChanges].
     */
    private fun orderChanges(before: Section?, after: Section): List<String> {
        val there = before?.items?.map { it.packageName } ?: emptyList()
        val here = after.items.map { it.packageName }
        val shared = there.filter { it in here }
        val sharedHere = here.filter { it in there }

        return if (shared != sharedHere) listOf("Reorders section \"${describe(after)}\"") else emptyList()
    }

    /**
     * How a section is named in the summary: its title, or its id when it
     * has no title of its own.
     */
    private fun describe(section: Section): String =
        section.title?.takeIf { it.isNotBlank() } ?: section.id
}
