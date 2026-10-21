# -----------------------------------------------------------------------------
# SpeedCam R8 / ProGuard Optimization Rules (Phase 9.4 Release Hardening)
# -----------------------------------------------------------------------------

# 1. Room Database, DAOs & Entities
# Room generates database and DAO implementations reflectively referenced by name.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class * extends androidx.room.EntityInsertionAdapter { *; }
-keep class * extends androidx.room.SharedSQLiteStatement { *; }
-keep class * extends androidx.room.RoomSQLiteQuery { *; }
-keep class **.*_Impl { *; }
-dontwarn androidx.room.paging.**

# Keep Room entity definitions, DAOs and database models so cursor mapping succeeds.
-keep class com.speedcam.core.data.** { *; }
-keep interface com.speedcam.core.data.** { *; }

# 2. Enum Preservation
# Enum ordinals are persisted as ints in SharedPreferences and SQLite tables:
# - AlertProfile / QuickSetting / TransportProfile / CameraKind / CameraSource (core:detect)
# - OutputMode (core:media)
# - OverrideAction / HazardReportStatus (core:data)
# - SyncOutcome (app:sync)
# - CameraRegion (app:cameras)
# Renaming, reordering, or removing enum constants silently corrupts stored settings and SQLite rows.
-keepclassmembers enum com.speedcam.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static ** entries;
    public ** value;
}

# 3. WorkManager Background Workers
# WorkManager instantiates Workers via reflection in WorkerFactory (Context, WorkerParameters).
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class androidx.work.WorkerParameters { *; }

# 4. JSON Serialization & Parsing (Gson)
# Keep attributes for generic types and annotations required by JSON parsers.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-dontwarn com.google.gson.**
-keep class com.google.gson.** { *; }
-keepclassmembers class com.speedcam.app.sync.** { <fields>; }
-keep class com.speedcam.app.sync.DatabaseManifest { *; }

# 5. Core Domain Models & State
# Ensure domain records passed across coroutines, flows and Compose retain signatures.
-keep class com.speedcam.core.detect.Camera { *; }
-keep class com.speedcam.core.detect.Hazard { *; }
-keep class com.speedcam.core.detect.Fix { *; }
-keep class com.speedcam.core.detect.AlertDecision { *; }
-keep class com.speedcam.core.detect.SectionRecord { *; }
-keep class com.speedcam.core.detect.SectionEvent { *; }
-keep class com.speedcam.core.detect.AlertProfile { *; }
-keep class com.speedcam.service.DetectionUiState { *; }
-keep class com.speedcam.service.HazardView { *; }
-keep class com.speedcam.service.SectionUiState { *; }

# 6. Coroutines & Android Runtime
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# 7. Release Logging Optimization
# Strip verbose and debug logs in release builds to eliminate dead strings and log call overhead.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
