# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep TunnelNative class for JNI RegisterNatives
-keep class net.mirage.vpn.TunnelNative { *; }

# Keep config classes
-keep class net.mirage.vpn.ServerConfig { *; }
