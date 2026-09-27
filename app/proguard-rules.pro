# Unused until isMinifyEnabled=true (see app/build.gradle.kts). Kept so the release build has rules ready.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn java.lang.invoke.**
