# Release R8 optimizations: strip verbose/debug logs & aggressively inline/repackage
-allowaccessmodification
-repackageclasses 'o'
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep kotlinx.serialization descriptors for WebApiModels & ToolHubPreferences
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.**
