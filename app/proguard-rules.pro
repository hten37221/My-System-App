# Android Gradle Plugin supplies manifest component, Compose, Hilt, and baseline-profile rules.
# Keep runtime metadata used by dependency injection and framework callback inspection.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
-keepattributes Signature,InnerClasses,EnclosingMethod

# The app invokes hidden framework APIs by their stable platform method names. R8 cannot rename
# framework classes (they are compileOnly/platform inputs), so no broad first-party keep rule is
# required here. Keep this file deliberately narrow so release shrinking remains effective.

# The privileged app compiles against platform/system-server stubs. These classes are supplied by
# the device boot class path but are intentionally absent from the public SDK seen by R8.
-dontwarn android.car.builtin.view.TouchableInsetsProvider
-dontwarn android.provider.DeviceConfig$Properties
-dontwarn dalvik.system.BlockGuard$VmPolicy
-dontwarn dalvik.system.CloseGuard
-dontwarn libcore.net.http.Dns
-dontwarn libcore.net.http.HttpURLConnectionFactory
-dontwarn libcore.util.NativeAllocationRegistry
-dontwarn sun.misc.Cleaner
