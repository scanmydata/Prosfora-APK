# Keep Kotlin metadata for reflection-based libraries.
-keepattributes Signature
-keepattributes *Annotation*

# Tink (μέσω androidx.security-crypto) αναφέρεται σε annotations που δεν
# υπάρχουν στο runtime του Android — απλά προειδοποιήσεις compile-time.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**

# JavaMail: φορτώνει providers/handlers μέσω reflection από τα META-INF
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.mail.** { *; }
-dontwarn javax.mail.**
-dontwarn javax.activation.**
-dontwarn com.sun.mail.**
