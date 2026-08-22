# Debug builds in this project disable minification (see app/build.gradle.kts).
# These rules only take effect for a release build with isMinifyEnabled = true.

# org.json classes are used for hand-rolled JSON parsing of the print-jobs payload --
# keep them untouched if minification is ever turned on.
-keep class org.json.** { *; }
