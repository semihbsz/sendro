# R8 is disabled for the release build (see app/build.gradle.kts), but these
# rules are kept so turning it on is a one-line change rather than a debugging
# session.

# kotlinx.serialization keeps generated serializers via companion objects.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.sendro.android.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.sendro.android.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sendro.android.core.**$$serializer { *; }

# OkHttp ships its own consumer rules; these silence the optional deps it
# references reflectively and never uses on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
