# kotlinx.serialization: модели очереди/волны сериализуются в Room (JSON).
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ru.bodro.music.**$$serializer { *; }
-keepclassmembers class ru.bodro.music.** {
    *** Companion;
}

# Tink (протобаф-классы ключей).
-keep class com.google.crypto.tink.proto.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
