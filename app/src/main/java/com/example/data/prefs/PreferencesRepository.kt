package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.ReaderThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lexivoice_prefs")

data class ReaderSettings(
    val fontSizeSp: Float = 19f,
    val lineSpacing: Float = 1.45f,
    val themeMode: ReaderThemeMode = ReaderThemeMode.SEPIA,
    val horizontalMarginDp: Int = 20,
    val useSerifFont: Boolean = true
)

data class TtsSettings(
    val enginePackage: String = "",
    val voiceName: String = "",
    val localeTag: String = "",
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f
)

data class SavedExportFolder(
    val uri: String,
    val displayName: String,
    val lastUsedAt: Long
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val READER_FONT_SIZE = floatPreferencesKey("reader_font_size")
        val READER_LINE_SPACING = floatPreferencesKey("reader_line_spacing")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val READER_MARGIN = intPreferencesKey("reader_margin")
        val READER_SERIF = booleanPreferencesKey("reader_serif")

        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_LOCALE = stringPreferencesKey("tts_locale")
        val TTS_RATE = floatPreferencesKey("tts_rate")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")

        val LIBRARY_GRID_VIEW = booleanPreferencesKey("library_grid_view")
        val SAVED_EXPORT_FOLDERS = stringPreferencesKey("saved_export_folders_json")
        val PASTE_SCRATCHPAD_TEXT = stringPreferencesKey("paste_scratchpad_text")
        val PASTE_SCRATCHPAD_TITLE = stringPreferencesKey("paste_scratchpad_title")
    }

    val readerSettings: Flow<ReaderSettings> = context.dataStore.data.map { prefs ->
        val themeName = prefs[Keys.READER_THEME] ?: ReaderThemeMode.SEPIA.name
        val theme = runCatching { ReaderThemeMode.valueOf(themeName) }.getOrDefault(ReaderThemeMode.SEPIA)
        ReaderSettings(
            fontSizeSp = prefs[Keys.READER_FONT_SIZE] ?: 19f,
            lineSpacing = prefs[Keys.READER_LINE_SPACING] ?: 1.45f,
            themeMode = theme,
            horizontalMarginDp = prefs[Keys.READER_MARGIN] ?: 20,
            useSerifFont = prefs[Keys.READER_SERIF] ?: true
        )
    }

    val ttsSettings: Flow<TtsSettings> = context.dataStore.data.map { prefs ->
        TtsSettings(
            enginePackage = prefs[Keys.TTS_ENGINE] ?: "",
            voiceName = prefs[Keys.TTS_VOICE] ?: "",
            localeTag = prefs[Keys.TTS_LOCALE] ?: "",
            speechRate = prefs[Keys.TTS_RATE] ?: 1.0f,
            pitch = prefs[Keys.TTS_PITCH] ?: 1.0f
        )
    }

    val isGridView: Flow<Boolean> = context.dataStore.data.map { it[Keys.LIBRARY_GRID_VIEW] ?: true }

    val savedExportFolders: Flow<List<SavedExportFolder>> = context.dataStore.data.map { prefs ->
        parseSavedFolders(prefs[Keys.SAVED_EXPORT_FOLDERS].orEmpty())
    }

    val scratchpadText: Flow<String> = context.dataStore.data.map { it[Keys.PASTE_SCRATCHPAD_TEXT] ?: "" }
    val scratchpadTitle: Flow<String> = context.dataStore.data.map { it[Keys.PASTE_SCRATCHPAD_TITLE] ?: "Quick Read" }

    suspend fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings) {
        context.dataStore.edit { prefs ->
            val currentTheme = runCatching {
                ReaderThemeMode.valueOf(prefs[Keys.READER_THEME] ?: ReaderThemeMode.SEPIA.name)
            }.getOrDefault(ReaderThemeMode.SEPIA)
            val current = ReaderSettings(
                fontSizeSp = prefs[Keys.READER_FONT_SIZE] ?: 19f,
                lineSpacing = prefs[Keys.READER_LINE_SPACING] ?: 1.45f,
                themeMode = currentTheme,
                horizontalMarginDp = prefs[Keys.READER_MARGIN] ?: 20,
                useSerifFont = prefs[Keys.READER_SERIF] ?: true
            )
            val updated = transform(current)
            prefs[Keys.READER_FONT_SIZE] = updated.fontSizeSp
            prefs[Keys.READER_LINE_SPACING] = updated.lineSpacing
            prefs[Keys.READER_THEME] = updated.themeMode.name
            prefs[Keys.READER_MARGIN] = updated.horizontalMarginDp
            prefs[Keys.READER_SERIF] = updated.useSerifFont
        }
    }

    suspend fun updateTtsSettings(transform: (TtsSettings) -> TtsSettings) {
        context.dataStore.edit { prefs ->
            val current = TtsSettings(
                enginePackage = prefs[Keys.TTS_ENGINE] ?: "",
                voiceName = prefs[Keys.TTS_VOICE] ?: "",
                localeTag = prefs[Keys.TTS_LOCALE] ?: "",
                speechRate = prefs[Keys.TTS_RATE] ?: 1.0f,
                pitch = prefs[Keys.TTS_PITCH] ?: 1.0f
            )
            val updated = transform(current)
            prefs[Keys.TTS_ENGINE] = updated.enginePackage
            prefs[Keys.TTS_VOICE] = updated.voiceName
            prefs[Keys.TTS_LOCALE] = updated.localeTag
            prefs[Keys.TTS_RATE] = updated.speechRate
            prefs[Keys.TTS_PITCH] = updated.pitch
        }
    }

    suspend fun setGridView(grid: Boolean) {
        context.dataStore.edit { it[Keys.LIBRARY_GRID_VIEW] = grid }
    }

    suspend fun saveScratchpad(title: String, text: String) {
        context.dataStore.edit {
            it[Keys.PASTE_SCRATCHPAD_TITLE] = title
            it[Keys.PASTE_SCRATCHPAD_TEXT] = text
        }
    }

    suspend fun addSavedExportFolder(uri: String, displayName: String) {
        context.dataStore.edit { prefs ->
            val list = parseSavedFolders(prefs[Keys.SAVED_EXPORT_FOLDERS].orEmpty())
                .filterNot { it.uri == uri }
                .toMutableList()
            list.add(0, SavedExportFolder(uri = uri, displayName = displayName, lastUsedAt = System.currentTimeMillis()))
            val trimmed = list.take(10)
            val jsonArray = JSONArray()
            trimmed.forEach { folder ->
                jsonArray.put(
                    JSONObject().apply {
                        put("uri", folder.uri)
                        put("displayName", folder.displayName)
                        put("lastUsedAt", folder.lastUsedAt)
                    }
                )
            }
            prefs[Keys.SAVED_EXPORT_FOLDERS] = jsonArray.toString()
        }
    }

    suspend fun removeSavedExportFolder(uri: String) {
        context.dataStore.edit { prefs ->
            val list = parseSavedFolders(prefs[Keys.SAVED_EXPORT_FOLDERS].orEmpty())
                .filterNot { it.uri == uri }
            val jsonArray = JSONArray()
            list.forEach { folder ->
                jsonArray.put(
                    JSONObject().apply {
                        put("uri", folder.uri)
                        put("displayName", folder.displayName)
                        put("lastUsedAt", folder.lastUsedAt)
                    }
                )
            }
            prefs[Keys.SAVED_EXPORT_FOLDERS] = jsonArray.toString()
        }
    }

    private fun parseSavedFolders(raw: String): List<SavedExportFolder> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val uri = obj.optString("uri")
                val name = obj.optString("displayName")
                if (uri.isBlank()) null
                else SavedExportFolder(uri = uri, displayName = name.ifBlank { uri }, lastUsedAt = obj.optLong("lastUsedAt", 0L))
            }
        }.getOrDefault(emptyList())
    }
}
