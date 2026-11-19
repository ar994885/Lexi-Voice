# ============================================================================
# Lexi-Voice — R8 / ProGuard rules for the release build.
#
# R8 is enabled for release (isMinifyEnabled + isShrinkResources). These rules
# keep only what is reached reflectively — Room, the WebView JavaScript bridge,
# Media3, Coil and WorkManager — so everything else, including the ~10k unused
# Material icon classes, is removed.
# ============================================================================

# Keep line numbers and source file names so release stack traces stay readable
# in Play Console, while renaming the source file to hide the original name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Annotations and generic signatures are read reflectively by Room, Gson-style
# serializers and Kotlin metadata.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, Exceptions

# Kotlin metadata (needed for kotlin-reflect / KSP-generated code to keep working
# if any path uses reflection on Kotlin types).
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }

# ---------------------------------------------------------------------------
# Room — entities, DAOs and the database are instantiated reflectively by the
# generated *_Impl classes and the Room runtime.
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
-keep class androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# Room TypeConverters and enums persisted by name.
-keep class com.example.data.db.** { *; }
-keepclassmembers enum com.example.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------
# WebView JavaScript bridge.
#
# addJavascriptInterface() exposes only @JavascriptInterface-annotated methods,
# and those are invoked by name from JavaScript — R8 cannot see that edge, so
# the annotated members must be kept explicitly. Without this the bridge
# silently stops working in release builds.
# ---------------------------------------------------------------------------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.example.ui.components.LexiWebJsBridge { *; }
-keepclassmembers class com.example.ui.components.LexiWebJsBridge {
    public *;
}

# ---------------------------------------------------------------------------
# Media3 / ExoPlayer
# ---------------------------------------------------------------------------
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ---------------------------------------------------------------------------
# Coil — the image loader resolves fetchers and decoders through its component
# registry and instantiates them by class.
# ---------------------------------------------------------------------------
-keep class coil.** { *; }
-dontwarn coil.**

# ---------------------------------------------------------------------------
# WorkManager — workers are constructed reflectively from their class name.
# ---------------------------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class * extends androidx.work.CoroutineWorker { <init>(...); }
-keep class com.example.library.FolderSyncWorker { *; }
-dontwarn androidx.work.**

# ---------------------------------------------------------------------------
# Android components declared in the manifest are instantiated by name.
# ---------------------------------------------------------------------------
-keep class com.example.LexiVoiceApplication { *; }
-keep class com.example.MainActivity { *; }
-keep class com.example.export.ExportChaptersActivity { *; }
-keep class com.example.tts.TtsPlaybackService { *; }
-keep class com.example.audiobook.M4bPlaybackService { *; }
-keep class com.example.export.AudioExportService { *; }
-keep class com.example.export.M4bCompileService { *; }
-keep class com.example.overlay.FloatingOverlayService { *; }

# ---------------------------------------------------------------------------
# MP3 encoder (jump3r) is driven through a Java bridge; keep it intact.
# ---------------------------------------------------------------------------
-keep class de.sciss.jump3r.** { *; }
-keep class com.example.export.Mp3AudioEncoder { *; }
-dontwarn de.sciss.jump3r.**

# ---------------------------------------------------------------------------
# HTML parsing / EPUB pipeline
# ---------------------------------------------------------------------------
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ---------------------------------------------------------------------------
# Kotlin coroutines — the internal machinery is referenced reflectively when
# debug probes are enabled.
# ---------------------------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ---------------------------------------------------------------------------
# Keep the models that are serialised to and from JSON by hand
# (SyncAlignmentSerializer, chapterFilesJson, chaptersJson). Field names matter
# because the JSON keys are derived from them.
# ---------------------------------------------------------------------------
-keep class com.example.audiobook.** { *; }
-keep class com.example.export.ExportModelsKt { *; }
-keepclassmembers class com.example.export.** {
    <fields>;
}
-keep class com.example.epub.EpubModelsKt { *; }

# ---------------------------------------------------------------------------
# Misc. warnings for optional dependencies that are not bundled.
# ---------------------------------------------------------------------------
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
