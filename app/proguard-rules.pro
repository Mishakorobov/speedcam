# Room generates implementations reflectively referenced by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# Entities are read by column name via generated code; keep field names stable.
-keepclassmembers class com.speedcam.core.data.** { *; }

# Enum ordinals are persisted as ints (Plan.md §4). Renaming or reordering an enum
# silently corrupts stored data, so keep them.
-keepclassmembers enum com.speedcam.core.detect.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static ** entries;
}
