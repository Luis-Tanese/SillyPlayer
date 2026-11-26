-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class dev.sillyplayer.media.NativeMedia { *; }

-keepclassmembers class * implements dev.sillyplayer.media.NativeMedia$Progress { boolean update(long, long); }

# Interpreter-only Android usage; optional JVM adapters are not called.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Protobuf Lite reflects on message fields in R8 minified builds
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keep class org.schabi.newpipe.extractor.services.youtube.protos.** { *; }
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}
