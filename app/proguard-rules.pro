# CameraX and ML Kit ship consumer rules. Keep only app JNI entry points if added later.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
