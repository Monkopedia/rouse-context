package com.rousecontext.app.token

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rousecontext.mcp.core.UnknownClientLabeler

/**
 * [UnknownClientLabeler] backed by DataStore preferences.
 *
 * Layout:
 * - One [stringPreferencesKey] per known client, of the form `client_<clientId>`,
 *   whose value is the already-assigned label (e.g. `Unknown (#3)`). Storing the
 *   full label (rather than just the number) makes the mapping self-describing
 *   in on-device dumps and trivially readable without any schema knowledge.
 * - One [intPreferencesKey] named `next_index`, holding the next integer to
 *   assign on the first call for a new [clientId]. Starts at 1.
 *
 * The [DataStore.edit] block provides atomic read-modify-write semantics
 * across concurrent callers within a process and persists the result before
 * returning, so cold-start restarts continue the sequence (see issue #345).
 * Once a `clientId → N` mapping is written it is never rewritten, so the
 * counter is monotonic and stable even across token revocations.
 */
class DataStoreUnknownClientLabeler(private val dataStore: DataStore<Preferences>) :
    UnknownClientLabeler {

    override suspend fun labelFor(clientId: String): String {
        val key = clientKey(clientId)
        var result = ""
        dataStore.edit { prefs ->
            val existing = prefs[key]
            if (existing != null) {
                result = existing
                return@edit
            }
            val nextIndex = prefs[NEXT_INDEX_KEY] ?: 1
            val label = formatLabel(nextIndex)
            prefs[key] = label
            prefs[NEXT_INDEX_KEY] = nextIndex + 1
            result = label
        }
        return result
    }

    companion object {
        internal val NEXT_INDEX_KEY = intPreferencesKey("next_index")

        internal fun clientKey(clientId: String) = stringPreferencesKey("client_$clientId")

        internal fun formatLabel(index: Int): String = "Unknown (#$index)"
    }
}

/**
 * Context-scoped DataStore backing the unknown-client labeler. Split into
 * this file so the `preferencesDataStore` singleton-per-name contract is
 * obvious and the on-disk file name (`unknown_client_labels`) is easy to
 * locate.
 */
private val Context.unknownClientLabelsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "unknown_client_labels")

/**
 * Production factory for [DataStoreUnknownClientLabeler] bound to the app-wide
 * DataStore file named `unknown_client_labels.preferences_pb` under the app's
 * files dir. Koin calls this. Defined as a plain top-level function (not a
 * companion extension) so it doesn't collide with similarly-named factories
 * from other modules that AppModule imports.
 */
fun createUnknownClientLabeler(context: Context): DataStoreUnknownClientLabeler =
    DataStoreUnknownClientLabeler(context.unknownClientLabelsDataStore)
