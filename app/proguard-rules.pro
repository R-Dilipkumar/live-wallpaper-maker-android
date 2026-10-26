# Scene/project files use kotlinx.serialization (rules for the engine model ship with :engine).
-keepclassmembers @kotlinx.serialization.Serializable class io.github.rdilipkumar.livewallpapermaker.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.rdilipkumar.livewallpapermaker.**$$serializer { *; }
