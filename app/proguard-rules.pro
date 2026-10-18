# LiteRT-LM packages native JNI bindings; its AAR also supplies consumer rules.
-keep class com.google.ai.edge.litertlm.** { *; }

# ML Kit discovers these manifest-declared registrars with
# Class.forName(name).getDeclaredConstructor().newInstance(). The transitive
# firebase-components 16.1.0 rule keeps only their classes; R8 full mode also
# needs the no-argument constructors to initialize OCR in release builds.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
