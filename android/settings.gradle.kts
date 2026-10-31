// JVM-only build for the platform-independent parts (MIDI parsing, onset detection, latency
// statistics), so they can be unit tested without the Android SDK. The files under
// android-only/ need the Android SDK and are copied into the Stomp Box app instead.
rootProject.name = "stompbox-leg-sensor"
