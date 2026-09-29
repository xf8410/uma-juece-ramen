package com.umaai.assistant.service;

/** Only EngineService loads this class. No network fallback and no invented state. */
public final class UmaNativeBridge {
    public static final int DEFAULT_SEARCH_N = 8192;
    private static final boolean loaded;
    static {
        boolean found;
        try { System.loadLibrary("uma_jni"); found = true; }
        catch (UnsatisfiedLinkError error) { found = false; }
        loaded = found;
    }
    public interface NativeEventListener { void onNativeEvent(String json); }
    public static boolean isLoaded() { return loaded; }
    public static native String nativeInit(String dataDirectory, String optionsJson);
    public static native String nativeEvaluate(String snapshotPath, String optionsJson, String requestId, NativeEventListener listener);
    public static native void nativeCancel(String requestId);
    public static native String nativeVersion();
    public static native String nativeReview(String runDirectory, String outputDirectory);
    private UmaNativeBridge() {}
}
