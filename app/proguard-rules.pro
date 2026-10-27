# Scene/project files use kotlinx.serialization (rules for the engine model ship with :engine).
-keepclassmembers @kotlinx.serialization.Serializable class io.github.rdilipkumar.livewallpapermaker.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.rdilipkumar.livewallpapermaker.**$$serializer { *; }

# ML Kit finds its components by reflection; R8 full mode would otherwise drop their constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
-keep class com.google.mlkit.**Registrar { <init>(); }
