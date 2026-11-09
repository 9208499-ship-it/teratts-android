# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep our native bridge
-keep class com.brahmadeo.supertonic.tts.SupertonicTTS { *; }

# Keep AIDL interfaces and their stubs
-keep interface com.brahmadeo.supertonic.tts.service.IPlaybackService { *; }
-keep interface com.brahmadeo.supertonic.tts.service.IPlaybackListener { *; }
-keep class com.brahmadeo.supertonic.tts.service.IPlaybackService$Stub { *; }
-keep class com.brahmadeo.supertonic.tts.service.IPlaybackListener$Stub { *; }

# Keep models/data classes that might be used for serialization/reflection
# If you have any data classes used with Gson/JSON, add them here.
-keep class com.brahmadeo.supertonic.tts.utils.LexiconManager$** { *; }

# Fix: Missing classes detected while running R8 (Missing JP2Decoder from Readium/PDFium)
-dontwarn com.gemalto.jp2.JP2Decoder

# ONNX Runtime (Java API): its JNI code looks classes up by name (ai/onnxruntime/TensorInfo …) — R8 must not rename or drop them
-keep class ai.onnxruntime.** { *; }
