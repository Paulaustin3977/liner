package dev.liner.launcher.store

interface DocumentStore {
    suspend fun read(): String?
    suspend fun write(json: String)
}

/** An in-memory [DocumentStore]. Used by tests and by the import preview flow. */
class InMemoryDocumentStore(initial: String? = null) : DocumentStore {
    @Volatile
    private var content: String? = initial

    override suspend fun read(): String? = content

    override suspend fun write(json: String) {
        content = json
    }
}
