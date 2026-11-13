package com.ambitsyncmodern.usb;

/*
 * Thin Java front for the native watch functions in jni_bridge.cpp (from Sommet, GPLv3).
 * The package and class name must stay exactly as they are: the native functions are found
 * by their JNI names (Java_com_ambitsyncmodern_usb_AmbitUsbModule_...).
 *
 * Only reading is exposed here. PlaninaAPP never writes settings or firmware to the watch.
 */
public final class AmbitUsbModule {
    static {
        System.loadLibrary("planinaambit");
    }

    public native String nativeAmbitGetDeviceInfo();

    /** Reads every move not listed in knownDates ("YYYYMMDDTHHMMSS") into a native cache. -1 = not connected. */
    public native int nativeAmbitGetLogCount(String[] knownDates);

    /** {moves walked, moves on the watch, unused} while nativeAmbitGetLogCount runs. Safe from any thread. */
    public native int[] nativeAmbitLogProgress();

    /** GPX of move #index from the cache filled by nativeAmbitGetLogCount. */
    public native String nativeAmbitGetLogAsGpx(int index);

    /** Takes (true) or releases (false) the watch's "Syncing" lock, as the Suunto app does over Bluetooth. */
    public native boolean nativeAmbitSyncDisplay(boolean on);

    public native void nativeAmbitDisconnect();
}
