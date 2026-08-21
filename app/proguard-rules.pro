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
