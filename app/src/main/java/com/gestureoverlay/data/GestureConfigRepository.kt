package com.gestureoverlay.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GestureConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow<List<GestureProfile>>(emptyList())
    val profiles: StateFlow<List<GestureProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<GestureProfile?>(null)
    val activeProfile: StateFlow<GestureProfile?> = _activeProfile.asStateFlow()

    init {
        loadAll()
    }

    private fun loadAll() {
        val json = prefs.getString(KEY_PROFILES, null)
        if (json != null) {
            val type = object : TypeToken<List<GestureProfile>>() {}.type
            val loaded: List<GestureProfile> = gson.fromJson(json, type) ?: emptyList()
            _profiles.value = loaded
        }

        val activeId = prefs.getString(KEY_ACTIVE_PROFILE, null)
        _activeProfile.value = _profiles.value.firstOrNull { it.id == activeId }
            ?: _profiles.value.firstOrNull()
    }

    suspend fun saveProfile(profile: GestureProfile) = withContext(Dispatchers.IO) {
        val current = _profiles.value.toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) current[idx] = profile else current.add(profile)
        _profiles.value = current
        persistProfiles(current)
    }

    suspend fun deleteProfile(profileId: String) = withContext(Dispatchers.IO) {
        val updated = _profiles.value.filter { it.id != profileId }
        _profiles.value = updated
        persistProfiles(updated)
        if (_activeProfile.value?.id == profileId) {
            setActiveProfile(updated.firstOrNull()?.id)
        }
    }

    suspend fun setActiveProfile(profileId: String?) = withContext(Dispatchers.IO) {
        _activeProfile.value = _profiles.value.firstOrNull { it.id == profileId }
        prefs.edit().putString(KEY_ACTIVE_PROFILE, profileId).apply()
    }

    suspend fun updateGestureInActiveProfile(gesture: GestureConfig) = withContext(Dispatchers.IO) {
        val active = _activeProfile.value ?: createDefaultProfile()
        val updatedGestures = active.gestures.toMutableList()
        val idx = updatedGestures.indexOfFirst { it.id == gesture.id }
        if (idx >= 0) updatedGestures[idx] = gesture else updatedGestures.add(gesture)
        val updated = active.copy(gestures = updatedGestures)
        saveProfile(updated)
        _activeProfile.value = updated
    }

    suspend fun removeGestureFromActiveProfile(gestureId: String) = withContext(Dispatchers.IO) {
        val active = _activeProfile.value ?: return@withContext
        val updated = active.copy(gestures = active.gestures.filter { it.id != gestureId })
        saveProfile(updated)
        _activeProfile.value = updated
    }

    fun exportProfileJson(profile: GestureProfile): String = gson.toJson(profile)

    fun importProfileFromJson(json: String): GestureProfile? = runCatching {
        gson.fromJson(json, GestureProfile::class.java)
    }.getOrNull()

    private fun persistProfiles(profiles: List<GestureProfile>) {
        prefs.edit().putString(KEY_PROFILES, gson.toJson(profiles)).apply()
    }

    private suspend fun createDefaultProfile(): GestureProfile {
        val profile = GestureProfile(name = "Default")
        saveProfile(profile)
        setActiveProfile(profile.id)
        return profile
    }

    companion object {
        private const val PREFS_NAME = "gesture_overlay_prefs"
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"
    }
}
