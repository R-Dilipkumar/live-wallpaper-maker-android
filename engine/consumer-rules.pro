# Scene files are read with kotlinx.serialization; keep the generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class io.github.rdilipkumar.livewallpapermaker.engine.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.rdilipkumar.livewallpapermaker.engine.model.**$$serializer { *; }
