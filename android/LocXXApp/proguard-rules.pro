# Nakama Java / gRPC / Protobuf (enable when isMinifyEnabled = true)
-keep class com.heroiclabs.nakama.** { *; }
-keepclassmembers class com.heroiclabs.nakama.WebSocketClient {
    private com.google.common.util.concurrent.ListenableFuture send(com.heroiclabs.nakama.WebSocketEnvelope);
}
-keep class com.google.protobuf.** { *; }
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.**
