# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep config classes
-keep class net.mirage.vpn.ServerConfig { *; }
