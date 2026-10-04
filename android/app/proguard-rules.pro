# kotlinx.serialization, Retrofit, Room, Hilt and WorkManager ship their own consumer rules.
#
# The network layer is matched by reflection (Retrofit proxies, serializer lookup by the JSON converter).
# It is a handful of tiny classes, so keep it whole rather than chase obfuscation bugs on a phone.
-keep class dev.dietapp.data.net.** { *; }

# The diagnostics journal (settings → Журнал) records stack traces: keep them readable.
# Line numbers stay, and so do the names of the app's exceptions; method names are still shrunk
# (retrace them with app/build/outputs/mapping/release/mapping.txt of the same build).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepnames class dev.dietapp.** extends java.lang.Throwable
