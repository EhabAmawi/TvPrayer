# Coil, kotlinx-coroutines and play-services-location ship their own consumer rules,
# and adhan is plain Java with no reflection, so the defaults from
# proguard-android-optimize.txt are sufficient.

# Keep the DreamService entry point: it is instantiated by name from the manifest and
# from Settings.Secure.screensaver_components, which R8 cannot see.
-keep class com.example.customtvscreensaver.CustomDreamService { <init>(); }
