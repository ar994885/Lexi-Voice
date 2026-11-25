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

enum class ReaderFontFamily(val label: String) {
    ORIGINAL("Original Book Font"),
    SERIF("Serif"),
    SANS_SERIF("Sans-Serif"),
    MONOSPACE("Monospace"),
    DYSLEXIA("Dyslexia-Friendly"),
    CUSTOM("Custom Imported Font")
}

enum class ReaderTextAlign(val label: String) {
    PUBLISHER("Publisher Default"),
    LEFT("Left"),
    JUSTIFY("Justify")
}

enum class ReaderMode(val label: String) {
    SCROLL("Scroll"),
    PAGED("Paged")
}

data class ReaderSettings(
    val fontSizeSp: Float = 19f,
    val lineSpacing: Float = 1.45f,
    val themeMode: ReaderThemeMode = ReaderThemeMode.WARM_SEPIA,
    val horizontalMarginDp: Int = 20,
    val useSerifFont: Boolean = true,
    val audiobookRewindSeconds: Int = 10,
    val audiobookForwardSeconds: Int = 30,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.ORIGINAL,
    val usePublisherStyling: Boolean = true,
    val paragraphSpacingEm: Float = 0.6f,
    val textAlign: ReaderTextAlign = ReaderTextAlign.PUBLISHER,
    val brightnessLevel: Float = -1f,
    val readingMode: ReaderMode = ReaderMode.SCROLL,
    val customCss: String = "",
    val volumeKeysTurnPages: Boolean = false,
    val showLibraryFolders: Boolean = true,
    val customThemeBgHex: String = "#FFFDF9",
    val customThemeTextHex: String = "#171A21",
    val customThemeLinkHex: String = "#1D60A6",
    val customFontName: String = "",
    val showSystemStatusBar: Boolean = true,
    val keepScreenAwake: Boolean = false
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

enum class FloatingAppearanceMode {
    DEFAULT_THEME,
    CUSTOM
}

data class FloatingOverlaySettings(
    val masterEnabled: Boolean = true,
    val appearanceMode: FloatingAppearanceMode = FloatingAppearanceMode.DEFAULT_THEME,
    val backgroundColorArgb: Int = 0xE61E1E24.toInt(),
    val borderColorArgb: Int = 0x33FFFFFF.toInt(),
    val borderWidthDp: Int = 1
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val READER_FONT_SIZE = floatPreferencesKey("reader_font_size")
        val READER_LINE_SPACING = floatPreferencesKey("reader_line_spacing")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val READER_MARGIN = intPreferencesKey("reader_margin")
        val READER_SERIF = booleanPreferencesKey("reader_serif")
        val READER_FONT_FAMILY = stringPreferencesKey("reader_font_family")
        val READER_USE_PUBLISHER_STYLING = booleanPreferencesKey("reader_use_publisher_styling")
        val READER_PARAGRAPH_SPACING = floatPreferencesKey("reader_paragraph_spacing")
        val READER_TEXT_ALIGN = stringPreferencesKey("reader_text_align")
        val READER_BRIGHTNESS = floatPreferencesKey("reader_brightness")
        val READER_MODE = stringPreferencesKey("reader_reading_mode")
        val READER_CUSTOM_CSS = stringPreferencesKey("reader_custom_css")
        val VOLUME_KEYS_TURN_PAGES = booleanPreferencesKey("volume_keys_turn_pages")
        val SHOW_LIBRARY_FOLDERS = booleanPreferencesKey("show_library_folders")
        val CUSTOM_THEME_BG = stringPreferencesKey("custom_theme_bg")
        val CUSTOM_THEME_TEXT = stringPreferencesKey("custom_theme_text")
        val CUSTOM_THEME_LINK = stringPreferencesKey("custom_theme_link")
        val CUSTOM_FONT_NAME = stringPreferencesKey("custom_font_name")
        val READER_SHOW_STATUS_BAR = booleanPreferencesKey("reader_show_status_bar")
        val READER_KEEP_AWAKE = booleanPreferencesKey("reader_keep_awake")

        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_LOCALE = stringPreferencesKey("tts_locale")
        val TTS_RATE = floatPreferencesKey("tts_rate")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")

        val LIBRARY_GRID_VIEW = booleanPreferencesKey("library_grid_view")
        val LIBRARY_GRID_COLUMNS = intPreferencesKey("library_grid_columns")
        val SAVED_EXPORT_FOLDERS = stringPreferencesKey("saved_export_folders_json")
        val PASTE_SCRATCHPAD_TEXT = stringPreferencesKey("paste_scratchpad_text")
        val PASTE_SCRATCHPAD_TITLE = stringPreferencesKey("paste_scratchpad_title")

        val OVERLAY_MASTER_ENABLED = booleanPreferencesKey("overlay_master_enabled")
        val OVERLAY_APPEARANCE_MODE = stringPreferencesKey("overlay_appearance_mode")
        val OVERLAY_BG_ARGB = intPreferencesKey("overlay_bg_argb")
        val OVERLAY_BORDER_ARGB = intPreferencesKey("overlay_border_argb")
        val OVERLAY_BORDER_WIDTH_DP = intPreferencesKey("overlay_border_width_dp")

        val AUDIOBOOK_REWIND_SEC = intPreferencesKey("audiobook_rewind_sec")
        val AUDIOBOOK_FORWARD_SEC = intPreferencesKey("audiobook_forward_sec")
    }

    private fun decodeReaderSettings(prefs: Preferences): ReaderSettings {
        val theme = ReaderThemeMode.fromStoredName(prefs[Keys.READER_THEME])
        val legacySerif = prefs[Keys.READER_SERIF] ?: true
        val fontFamily = prefs[Keys.READER_FONT_FAMILY]?.let { raw ->
            runCatching { ReaderFontFamily.valueOf(raw) }.getOrNull()
        } ?: if (legacySerif) ReaderFontFamily.ORIGINAL else ReaderFontFamily.SANS_SERIF
        val textAlign = prefs[Keys.READER_TEXT_ALIGN]?.let { raw ->
            runCatching { ReaderTextAlign.valueOf(raw) }.getOrNull()
        } ?: ReaderTextAlign.PUBLISHER
        val readingMode = prefs[Keys.READER_MODE]?.let { raw ->
            runCatching { ReaderMode.valueOf(raw) }.getOrNull()
        } ?: ReaderMode.SCROLL
        val rawBrightness = prefs[Keys.READER_BRIGHTNESS] ?: -1f
        val brightness = if (rawBrightness < 0f) -1f else rawBrightness.coerceIn(0.05f, 1.0f)

        return ReaderSettings(
            fontSizeSp = (prefs[Keys.READER_FONT_SIZE] ?: 19f).coerceIn(12f, 36f),
            lineSpacing = (prefs[Keys.READER_LINE_SPACING] ?: 1.45f).coerceIn(1.0f, 2.2f),
            themeMode = theme,
            horizontalMarginDp = (prefs[Keys.READER_MARGIN] ?: 20).coerceIn(8, 48),
            useSerifFont = fontFamily == ReaderFontFamily.SERIF || fontFamily == ReaderFontFamily.ORIGINAL,
            audiobookRewindSeconds = (prefs[Keys.AUDIOBOOK_REWIND_SEC] ?: 10).coerceIn(1, 300),
            audiobookForwardSeconds = (prefs[Keys.AUDIOBOOK_FORWARD_SEC] ?: 30).coerceIn(1, 300),
            fontFamily = fontFamily,
            usePublisherStyling = prefs[Keys.READER_USE_PUBLISHER_STYLING] ?: true,
            paragraphSpacingEm = (prefs[Keys.READER_PARAGRAPH_SPACING] ?: 0.6f).coerceIn(0f, 2.0f),
            textAlign = textAlign,
            brightnessLevel = brightness,
            readingMode = readingMode,
            customCss = prefs[Keys.READER_CUSTOM_CSS] ?: "",
            volumeKeysTurnPages = prefs[Keys.VOLUME_KEYS_TURN_PAGES] ?: false,
            showLibraryFolders = prefs[Keys.SHOW_LIBRARY_FOLDERS] ?: true,
            customThemeBgHex = prefs[Keys.CUSTOM_THEME_BG] ?: "#FFFDF9",
            customThemeTextHex = prefs[Keys.CUSTOM_THEME_TEXT] ?: "#171A21",
            customThemeLinkHex = prefs[Keys.CUSTOM_THEME_LINK] ?: "#1D60A6",
            customFontName = prefs[Keys.CUSTOM_FONT_NAME] ?: "",
            showSystemStatusBar = prefs[Keys.READER_SHOW_STATUS_BAR] ?: true,
            keepScreenAwake = prefs[Keys.READER_KEEP_AWAKE] ?: false
        )
    }

    val readerSettings: Flow<ReaderSettings> = context.dataStore.data.map { prefs ->
        decodeReaderSettings(prefs)
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
    val libraryGridColumns: Flow<Int> = context.dataStore.data.map { (it[Keys.LIBRARY_GRID_COLUMNS] ?: 3).coerceIn(2, 5) }

    val savedExportFolders: Flow<List<SavedExportFolder>> = context.dataStore.data.map { prefs ->
        parseSavedFolders(prefs[Keys.SAVED_EXPORT_FOLDERS].orEmpty())
    }

    val scratchpadText: Flow<String> = context.dataStore.data.map { it[Keys.PASTE_SCRATCHPAD_TEXT] ?: "" }
    val scratchpadTitle: Flow<String> = context.dataStore.data.map { it[Keys.PASTE_SCRATCHPAD_TITLE] ?: "Quick Read" }

    val floatingOverlaySettings: Flow<FloatingOverlaySettings> = context.dataStore.data.map { prefs ->
        val mode = runCatching {
            FloatingAppearanceMode.valueOf(prefs[Keys.OVERLAY_APPEARANCE_MODE] ?: FloatingAppearanceMode.DEFAULT_THEME.name)
        }.getOrDefault(FloatingAppearanceMode.DEFAULT_THEME)
        FloatingOverlaySettings(
            masterEnabled = prefs[Keys.OVERLAY_MASTER_ENABLED] ?: true,
            appearanceMode = mode,
            backgroundColorArgb = prefs[Keys.OVERLAY_BG_ARGB] ?: 0xE61E1E24.toInt(),
            borderColorArgb = prefs[Keys.OVERLAY_BORDER_ARGB] ?: 0x33FFFFFF.toInt(),
            borderWidthDp = (prefs[Keys.OVERLAY_BORDER_WIDTH_DP] ?: 1).coerceIn(0, 2)
        )
    }

    suspend fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings) {
        context.dataStore.edit { prefs ->
            val current = decodeReaderSettings(prefs)
            val updated = transform(current)
            val rawBrightness = updated.brightnessLevel
            val safeBrightness = if (rawBrightness < 0f) -1f else rawBrightness.coerceIn(0.05f, 1.0f)

            prefs[Keys.READER_FONT_SIZE] = updated.fontSizeSp.coerceIn(12f, 36f)
            prefs[Keys.READER_LINE_SPACING] = updated.lineSpacing.coerceIn(1.0f, 2.2f)
            prefs[Keys.READER_THEME] = updated.themeMode.name
            prefs[Keys.READER_MARGIN] = updated.horizontalMarginDp.coerceIn(8, 48)
            prefs[Keys.READER_SERIF] = updated.fontFamily == ReaderFontFamily.SERIF || updated.fontFamily == ReaderFontFamily.ORIGINAL
            prefs[Keys.AUDIOBOOK_REWIND_SEC] = updated.audiobookRewindSeconds.coerceIn(1, 300)
            prefs[Keys.AUDIOBOOK_FORWARD_SEC] = updated.audiobookForwardSeconds.coerceIn(1, 300)
            prefs[Keys.READER_FONT_FAMILY] = updated.fontFamily.name
            prefs[Keys.READER_USE_PUBLISHER_STYLING] = updated.usePublisherStyling
            prefs[Keys.READER_PARAGRAPH_SPACING] = updated.paragraphSpacingEm.coerceIn(0f, 2.0f)
            prefs[Keys.READER_TEXT_ALIGN] = updated.textAlign.name
            prefs[Keys.READER_BRIGHTNESS] = safeBrightness
            prefs[Keys.READER_MODE] = updated.readingMode.name
            prefs[Keys.READER_CUSTOM_CSS] = updated.customCss
            prefs[Keys.VOLUME_KEYS_TURN_PAGES] = updated.volumeKeysTurnPages
            prefs[Keys.SHOW_LIBRARY_FOLDERS] = updated.showLibraryFolders
            prefs[Keys.CUSTOM_THEME_BG] = updated.customThemeBgHex
            prefs[Keys.CUSTOM_THEME_TEXT] = updated.customThemeTextHex
            prefs[Keys.CUSTOM_THEME_LINK] = updated.customThemeLinkHex
            prefs[Keys.CUSTOM_FONT_NAME] = updated.customFontName
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

    suspend fun setGridColumns(cols: Int) {
        context.dataStore.edit { it[Keys.LIBRARY_GRID_COLUMNS] = cols.coerceIn(2, 5) }
    }

    suspend fun saveScratchpad(title: String, text: String) {
        context.dataStore.edit {
            it[Keys.PASTE_SCRATCHPAD_TITLE] = title
            it[Keys.PASTE_SCRATCHPAD_TEXT] = text
        }
    }

    suspend fun updateFloatingOverlaySettings(transform: (FloatingOverlaySettings) -> FloatingOverlaySettings) {
        context.dataStore.edit { prefs ->
            val currentMode = runCatching {
                FloatingAppearanceMode.valueOf(prefs[Keys.OVERLAY_APPEARANCE_MODE] ?: FloatingAppearanceMode.DEFAULT_THEME.name)
            }.getOrDefault(FloatingAppearanceMode.DEFAULT_THEME)
            val current = FloatingOverlaySettings(
                masterEnabled = prefs[Keys.OVERLAY_MASTER_ENABLED] ?: true,
                appearanceMode = currentMode,
                backgroundColorArgb = prefs[Keys.OVERLAY_BG_ARGB] ?: 0xE61E1E24.toInt(),
                borderColorArgb = prefs[Keys.OVERLAY_BORDER_ARGB] ?: 0x33FFFFFF.toInt(),
                borderWidthDp = (prefs[Keys.OVERLAY_BORDER_WIDTH_DP] ?: 1).coerceIn(0, 2)
            )
            val updated = transform(current)
            prefs[Keys.OVERLAY_MASTER_ENABLED] = updated.masterEnabled
            prefs[Keys.OVERLAY_APPEARANCE_MODE] = updated.appearanceMode.name
            prefs[Keys.OVERLAY_BG_ARGB] = updated.backgroundColorArgb
            prefs[Keys.OVERLAY_BORDER_ARGB] = updated.borderColorArgb
            prefs[Keys.OVERLAY_BORDER_WIDTH_DP] = updated.borderWidthDp.coerceIn(0, 2)
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
