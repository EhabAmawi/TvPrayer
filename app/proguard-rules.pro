# Coil, OkHttp and kotlinx-coroutines ship their own consumer rules, so the defaults from
# proguard-android-optimize.txt are sufficient.

# Keep the DreamService entry point: it is instantiated by name from the manifest and
# from Settings.Secure.screensaver_components, which R8 cannot see.
-keep class com.example.customtvscreensaver.CustomDreamService { <init>(); }
