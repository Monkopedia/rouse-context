package com.rousecontext.notifications

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Context-scoped DataStore that backs the per-tool-call notification id
 * counter. Split into its own file so the [preferencesDataStore] delegate's
 * singleton-per-name contract is obvious and the DataStore file name
 * (`per_tool_call_counter`) is easy to locate.
 */
private val Context.perToolCallCounterDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "per_tool_call_counter")

/**
 * Production factory for [NotificationIdCounter]. Koin calls this to obtain
 * the app-wide counter bound to the DataStore file named
 * `per_tool_call_counter.preferences_pb` under the app's files dir.
 */
fun NotificationIdCounter.Companion.create(context: Context): NotificationIdCounter =
    NotificationIdCounter(context.perToolCallCounterDataStore)
