# Add project specific ProGuard rules here.
-keep class org.tensorflow.** { *; }
-keep class com.github.mlc_ai.** { *; }
-keep class org.eclipse.jgit.** { *; }
-keep class org.kohsuke.github.** { *; }
-dontwarn org.tensorflow.**
-dontwarn com.github.mlc_ai.**
-dontwarn org.eclipse.jgit.**
