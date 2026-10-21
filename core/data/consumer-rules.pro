# The consumer rules travel with the published AAR and apply to any dependent app.
# Room needs its generated implementations by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.room.EntityInsertionAdapter { *; }
-keep class * extends androidx.room.SharedSQLiteStatement { *; }
-keep class * extends androidx.room.RoomSQLiteQuery { *; }
-keep class **.*_Impl { *; }
-dontwarn androidx.room.paging.**

# Keep Room entity definitions and columns intact
-keep class com.speedcam.core.data.** { *; }
