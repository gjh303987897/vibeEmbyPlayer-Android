# VibePlayer additions to upstream's consumer-proguard-rules.txt.
#
# Media3 instantiates the FFmpeg renderer by reflection:
# DefaultRenderersFactory does Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
# followed by getConstructor(Handler, AudioRendererEventListener, AudioSink). R8 sees no static
# reference to that class, so without these rules a minified release APK silently loses
# on-device AC-3 / E-AC-3 / DTS / DTS-HD / TrueHD decoding and goes back to silent video.
# app/src/test/.../FfmpegExtensionWiringTest.kt pins the same name + signature.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    public <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}

# AudioDecodeSupport probes these two static methods reflectively as well.
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary {
    public static boolean isAvailable();
    public static boolean supportsFormat(java.lang.String);
    public static java.lang.String getVersion();
}
