# gomobile-generated bindings are accessed via JNI by name
-keep class go.** { *; }
-keep class com.dpidetector.core.** { *; }
-keepclassmembers class * implements com.dpidetector.core.dpich.EventListener { *; }
