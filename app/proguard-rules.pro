# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class es.moxlite.** {
    *** Companion;
}
-keepclasseswithmembers class es.moxlite.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class es.moxlite.**$$serializer { *; }

# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Puente JS del WebView
-keepclassmembers class es.moxlite.data.session.** {
    @android.webkit.JavascriptInterface <methods>;
}

# Diagnostic log: keep the app's own class and method names, file names and line numbers so the
# stack traces in an exported report can be read without the mapping file (personal app).
-keepnames class es.moxlite.** { *; }
-keepattributes SourceFile, LineNumberTable
