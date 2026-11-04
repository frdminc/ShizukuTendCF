# Consumer ProGuard rules for database module
# These rules are included when the module is consumed by other projects

# Keep Room entities
-keep class af.shizuku.manager.database.ActivityLogRoom { *; }

# Keep DAO
-keep class af.shizuku.manager.database.ActivityLogDao { *; }

# Keep Database
-keep class af.shizuku.manager.database.ActivityLogDatabase { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# kotlinx.serialization: the app database (assets/app_context_db.json) is decoded with generated
# serializers; without these an R8 build parses it to nothing and Apps / Root compatibility lose
# every description and SU-path hint.
-keepattributes InnerClasses,EnclosingMethod
-keep,includedescriptorclasses class af.shizuku.manager.database.**$$serializer { *; }
-keepclassmembers class af.shizuku.manager.database.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
