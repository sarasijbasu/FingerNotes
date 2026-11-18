# SQLCipher
-keep class net.zetetic.database.** { *; }
# WebRTC (JNI)
-keep class org.webrtc.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.fingernotes.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.fingernotes.**$$serializer { *; }
# Zip4j / FastExcel / OkHttp
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.xml.stream.**
-keep class org.dhatim.fastexcel.** { *; }

# Optional/compile-only references from Zip4j, FastExcel (opczip), OkHttp, WebRTC and ZXing
# that are never loaded on Android. R8 treats missing classes as errors in AGP 8.
-dontwarn javax.annotation.**
-dontwarn javax.xml.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.slf4j.**
-dontwarn org.osgi.**
-dontwarn aQute.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.github.rzymek.opczip.**
-dontwarn org.dhatim.fastexcel.**
-dontwarn net.lingala.zip4j.**
# Last-resort: report any other unknown reference as a warning instead of failing the release build.
-ignorewarnings
-keep class net.lingala.zip4j.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
