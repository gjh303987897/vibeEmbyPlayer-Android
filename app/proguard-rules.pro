# Add project specific ProGuard rules here.
# Uncomment rules as needed for libraries.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.vibeplayer.app.**$$serializer { *; }
-keepclassmembers class com.vibeplayer.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.vibeplayer.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Constructors and native method names are resolved from the bundled IJK JNI library.
-keep class com.vibeplayer.app.player.BitmapSubtitleRegion { *; }
-keep class com.vibeplayer.app.player.BitmapSubtitleFrame { *; }
-keep class com.vibeplayer.app.player.NativeBitmapSubtitles { *; }

# The upstream Java AAR has no consumer rules. Native J4A bindings resolve
# player fields, callbacks and data-source interfaces by their original names.
-keep class tv.danmaku.ijk.media.player.** { *; }
