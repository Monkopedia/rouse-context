# Add project specific ProGuard rules here.

# Ktor references JMX classes not available on Android
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
