package dev.liner.launcher.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

class DataStoreDocumentStore(context: Context) : DocumentStore {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile(FILE_NAME) },
    )

    override suspend fun read(): String? = dataStore.data.first()[KEY]

    override suspend fun write(json: String) {
        dataStore.edit { preferences -> preferences[KEY] = json }
    }

    private companion object {
        val KEY = stringPreferencesKey("layout_document")
        const val FILE_NAME = "liner_layout"
    }
}
