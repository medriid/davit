# Optional dependencies that daVIT never uses; R8 only needs to know they're absent.
-dontwarn com.google.re2j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization: the caches (home feeds, campus records, course pages) are read
# back through generated serializers, which R8 must keep with their companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class dev.medriid.davit.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.medriid.davit.**$$serializer { *; }
-keepclassmembers class dev.medriid.davit.** {
    *** Companion;
}
