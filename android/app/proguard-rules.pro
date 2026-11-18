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
