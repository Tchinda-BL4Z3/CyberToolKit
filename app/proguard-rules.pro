# ProGuard / R8 rules for the release build.
#
# CyberToolkit has no reflection, no serialization framework and no JNI, so the
# default optimized ruleset is sufficient. These entries only protect the things
# R8 cannot infer.

# Keep line numbers so release crash reports stay readable, but hide the original
# file names so the source layout is not leaked.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Coroutines internals referenced only reflectively by kotlinx-coroutines-test.
-dontwarn kotlinx.coroutines.debug.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Jetpack Compose relies on these being present on the classpath.
-dontwarn androidx.compose.**

# Keep enum valueOf/values, used by the persisted preference round-trips.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
