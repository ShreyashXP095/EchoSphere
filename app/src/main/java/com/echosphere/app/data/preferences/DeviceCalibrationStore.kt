package com.echosphere.app.data.preferences

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "echosphere_calibration")

class DeviceCalibrationStore(private val context: Context) {

    private val deviceHardwareKey: Preferences.Key<Long> = longPreferencesKey(
        "nudge_${Build.MANUFACTURER}_${Build.MODEL}".replace(" ", "_")
    )

    val calibrationOffsetMsFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[deviceHardwareKey] ?: 0L
    }

    suspend fun saveCalibrationOffsetMs(offsetMs: Long) {
        context.dataStore.edit { preferences ->
            preferences[deviceHardwareKey] = offsetMs
        }
    }
}
