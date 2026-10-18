# The consumer rules travel with the published AAR and apply to any dependent app.
# Room needs its generated implementations by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**
