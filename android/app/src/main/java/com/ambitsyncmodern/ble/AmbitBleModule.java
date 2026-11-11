package com.ambitsyncmodern.ble;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
 * Bluetooth link to a Suunto Ambit3 / Traverse / Kailash.
 *
 * A Java port of Sommet's AmbitBleModule.kt (André Faria, GPLv3, github.com/skinnie/sommet),
 * without React Native and without the standing background reconnect. Licensed GPLv3.
 *
 * The Ambit3 protocol is inverted: the PHONE hosts a GATT server with Suunto's NSP service and
 * the watch connects in as a client after it advertises (solicits) that service, which it does
 * when "Sync now" or "Pair MobileApp" is chosen on the watch. Data from the watch arrives as
 * writes to c6339440; data to the watch is sent as notifications on d0fd6b80. The frame codec
 * lives in protocol_ble.c, which calls bleWriteChunk() / bleSyncRequested() back over JNI, so
 * the package, class and those two method names must not change.
 */
public final class AmbitBleModule {
    static {
        System.loadLibrary("planinaambit");
    }

    private static final String TAG = "PlaninaBle";
    private static final UUID NSP_SERVICE_UUID = UUID.fromString("98ae7120-e62e-11e3-badd-0002a5d5c51b");
    private static final UUID NSP_WRITE_CHAR_UUID = UUID.fromString("c6339440-e62e-11e3-a5b3-0002a5d5c51b");
    private static final UUID NSP_NOTIFY_CHAR_UUID = UUID.fromString("d0fd6b80-e62e-11e3-a2e9-0002a5d5c51b");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String[] NAME_PREFIXES = {"Ambit3", "Traverse", "Suunto NSP"};
    private static final long SCAN_TIMEOUT_MS = 30_000L;
    private static final long SUBSCRIBE_TIMEOUT_MS = 30_000L;
    private static final long BOND_TIMEOUT_MS = 60_000L;
    private static final int SUUNTO_VID = 0x1493;

    /** What the app hears back. All calls arrive on the main thread. */
    public interface Listener {
        void onStatus(String code);                 // "scanning", "linking", "pairing", "handshake"
        void onConnected(String name);
        void onFailed(String code, String message);
        void onDisconnected();
        void onSyncRequested();                     // "Sync now" pressed on the watch while linked
    }

    // Native, in jni_bridge.cpp / protocol_ble.c
    private native boolean nativeAmbitBleInit(int vid, int pid);
    private native void nativeAmbitBleOnNotify(byte[] chunk);
    private native void nativeAmbitBleResetRx();

    private final Activity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic notifyChar;
    private volatile BluetoothDevice connectedDevice;
    private volatile boolean nativeInitStarted = false;
    private volatile boolean connecting = false;
    private volatile boolean live = false;
    private ScanCallback scanCallback;
    private Runnable scanTimeout, subscribeTimeout, bondTimeout;
    private BroadcastReceiver bondReceiver;
    private volatile boolean bondingInProgress = false;

    private final Object chunkLock = new Object();
    private final ArrayDeque<byte[]> pendingChunks = new ArrayDeque<>();
    private boolean chunkInFlight = false;

    public AmbitBleModule(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public boolean isLive() { return live; }
    public boolean isBusy() { return connecting || live; }

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    /* ---------------- scan ---------------- */

    public void scanAndConnect() {
        if (connecting) return;
        if (live) { listener.onConnected(nameOf(connectedDevice)); return; }
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) { listener.onFailed("BLUETOOTH_OFF", "Bluetooth je izklopljen."); return; }
        BluetoothLeScanner scanner = a.getBluetoothLeScanner();
        if (scanner == null) { listener.onFailed("BLE_UNAVAILABLE", "Ta telefon ne podpira Bluetooth LE."); return; }

        connecting = true;
        nativeInitStarted = false;
        nativeAmbitBleResetRx();   // the watch's first bytes wait for the handshake instead of being dropped
        listener.onStatus("scanning");

        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        final ParcelUuid nsp = new ParcelUuid(NSP_SERVICE_UUID);
        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                ScanRecord rec = result.getScanRecord();
                boolean advertisesNsp = rec != null && rec.getServiceUuids() != null && rec.getServiceUuids().contains(nsp);
                if (!advertisesNsp && rec != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        && rec.getServiceSolicitationUuids() != null) {
                    advertisesNsp = rec.getServiceSolicitationUuids().contains(nsp);
                }
                String name = safeName(result.getDevice());
                if (name == null && rec != null) name = rec.getDeviceName();
                boolean nameMatches = false;
                if (name != null) for (String p : NAME_PREFIXES) if (name.startsWith(p)) nameMatches = true;
                if (!advertisesNsp && !nameMatches) return;
                Log.d(TAG, "scan match " + name);
                stopScan();
                connectToDevice(result.getDevice());
            }
            @Override public void onScanFailed(int errorCode) {
                stopScan();
                fail("SCAN_FAILED", "Iskanje ure ni uspelo (koda " + errorCode + ").");
            }
        };
        scanTimeout = () -> {
            stopScan();
            fail("SCAN_TIMEOUT", "Ure nisem našel. Na uri izberi »Sync now« tik preden pritisneš Poveži.");
        };
        main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        try {
            scanner.startScan(Collections.emptyList(), settings, scanCallback);
        } catch (SecurityException e) {
            stopScan();
            fail("PERMISSION_DENIED", "Aplikacija nima dovoljenja za Bluetooth.");
        }
    }

    private void stopScan() {
        if (scanTimeout != null) { main.removeCallbacks(scanTimeout); scanTimeout = null; }
        BluetoothAdapter a = adapter();
        if (scanCallback != null && a != null && a.getBluetoothLeScanner() != null) {
            try { a.getBluetoothLeScanner().stopScan(scanCallback); } catch (Exception ignored) { }
        }
        scanCallback = null;
    }

    /* ---------------- GATT server ---------------- */

    private void connectToDevice(BluetoothDevice device) {
        listener.onStatus("linking");
        armSubscribeTimeout();
        registerBondReceiver(device);
        openServerAndConnect(device);
    }

    private void openServerAndConnect(BluetoothDevice device) {
        // One server per app run: a new one changes the GATT database and the watch's discovery
        // can race the Service Changed indication (Sommet, btsnoop 2026-10-25).
        if (gattServer != null && notifyChar != null) {
            synchronized (chunkLock) { pendingChunks.clear(); chunkInFlight = false; }
            connectedDevice = device;
            try { gattServer.connect(device, false); } catch (SecurityException e) { fail("PERMISSION_DENIED", e.getMessage()); }
            return;
        }
        BluetoothManager m = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        if (m == null) { fail("BLUETOOTH_OFF", "Bluetooth ni na voljo."); return; }
        try {
            BluetoothGattServer server = m.openGattServer(activity, serverCallback);
            if (server == null) { fail("SERVER_OPEN_FAILED", "Bluetooth strežnika ni bilo mogoče odpreti."); return; }
            gattServer = server;
            BluetoothGattService service = new BluetoothGattService(NSP_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            BluetoothGattCharacteristic notify = new BluetoothGattCharacteristic(NSP_NOTIFY_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
            notify.addDescriptor(new BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE));
            service.addCharacteristic(notify);
            notifyChar = notify;
            service.addCharacteristic(new BluetoothGattCharacteristic(NSP_WRITE_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE));
            connectedDevice = device;   // before addService: onServiceAdded may come back first
            server.addService(service);
        } catch (SecurityException e) {
            fail("PERMISSION_DENIED", e.getMessage());
        }
    }

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int status, BluetoothGattService service) {
            BluetoothDevice d = connectedDevice;
            if (d == null || gattServer == null) return;
            // Direct connect: links inside the watch's short advertising window.
            try { gattServer.connect(d, false); } catch (SecurityException e) { fail("PERMISSION_DENIED", e.getMessage()); }
        }

        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            Log.d(TAG, "connection state " + newState + " status " + status);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device;
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!nativeInitStarted && connecting) {
                    if (bondingInProgress) {
                        bondingInProgress = false;
                        clearBondTimeout();
                        fail("DISCONNECTED_DURING_BONDING", "Ura je prekinila povezavo med seznanjanjem. Poskusi znova.");
                    } else {
                        fail("DISCONNECTED", "Ura je prekinila povezavo, preden se je sinhronizacija začela.");
                    }
                } else if (nativeInitStarted) {
                    nativeInitStarted = false;
                    live = false;
                    connectedDevice = null;
                    synchronized (chunkLock) { pendingChunks.clear(); chunkInFlight = false; }
                    main.post(listener::onDisconnected);
                }
            }
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded,
                int offset, byte[] value) {
            if (NSP_WRITE_CHAR_UUID.equals(characteristic.getUuid())) {
                nativeAmbitBleOnNotify(value);   // stashed natively until the handshake is armed
                // A bonded watch may skip rewriting the CCCD and go straight to its opener.
                if (!nativeInitStarted) {
                    nativeInitStarted = true;
                    connectedDevice = device;
                    main.post(AmbitBleModule.this::clearSubscribeTimeout);
                    proceedToNativeInit(device);
                }
            }
            if (responseNeeded) {
                try { gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null); } catch (Exception ignored) { }
            }
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                int offset, byte[] value) {
            boolean isNotifyCccd = CCCD_UUID.equals(descriptor.getUuid()) && descriptor.getCharacteristic() != null
                    && NSP_NOTIFY_CHAR_UUID.equals(descriptor.getCharacteristic().getUuid());
            boolean enabling = value != null && value.length > 0 && value[0] != 0;
            if (responseNeeded) {
                try { gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value); } catch (Exception ignored) { }
            }
            if (isNotifyCccd && enabling && !nativeInitStarted) {
                nativeInitStarted = true;
                connectedDevice = device;
                main.post(AmbitBleModule.this::clearSubscribeTimeout);
                proceedToNativeInit(device);
            }
        }

        @Override public void onNotificationSent(BluetoothDevice device, int status) {
            synchronized (chunkLock) { chunkInFlight = false; }
            drainChunks();
        }
    };

    private void proceedToNativeInit(final BluetoothDevice device) {
        main.post(() -> listener.onStatus("handshake"));
        executor.execute(() -> {
            final boolean ok = nativeAmbitBleInit(SUUNTO_VID, guessProductId(safeName(device)));
            main.post(() -> {
                if (ok) {
                    connecting = false;
                    live = true;
                    unregisterBondReceiver();
                    listener.onConnected(nameOf(device));
                } else {
                    nativeInitStarted = false;
                    fail("BLE_INIT_FAILED", "Ura se je povezala, vendar ni odgovorila na prvo vprašanje. Poskusi znova.");
                }
            });
        });
    }

    /* ---------------- called from protocol_ble.c over JNI ---------------- */

    @SuppressWarnings("unused")
    public void bleWriteChunk(byte[] chunk) {
        synchronized (chunkLock) { pendingChunks.addLast(chunk); }
        drainChunks();
    }

    @SuppressWarnings("unused")
    public void bleSyncRequested() {
        main.post(listener::onSyncRequested);
    }

    @SuppressWarnings("deprecation")
    private void drainChunks() {
        BluetoothGattServer server = gattServer;
        BluetoothGattCharacteristic ch = notifyChar;
        BluetoothDevice device = connectedDevice;
        if (server == null || ch == null || device == null) return;
        byte[] chunk;
        synchronized (chunkLock) {
            if (chunkInFlight || pendingChunks.isEmpty()) return;
            chunk = pendingChunks.removeFirst();
            chunkInFlight = true;
        }
        try {
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33) {
                ok = server.notifyCharacteristicChanged(device, ch, false, chunk) == 0;
            } else {
                ch.setValue(chunk);
                ok = server.notifyCharacteristicChanged(device, ch, false);
            }
            if (!ok) synchronized (chunkLock) { chunkInFlight = false; }
        } catch (SecurityException e) {
            synchronized (chunkLock) { chunkInFlight = false; }
        }
    }

    /* ---------------- teardown ---------------- */

    public void disconnect() {
        stopScan();
        unregisterBondReceiver();
        clearSubscribeTimeout();
        try {
            if (connectedDevice != null && gattServer != null) gattServer.cancelConnection(connectedDevice);
            if (gattServer != null) gattServer.close();
        } catch (Exception ignored) { }
        gattServer = null;
        notifyChar = null;
        connectedDevice = null;
        nativeInitStarted = false;
        connecting = false;
        live = false;
        synchronized (chunkLock) { pendingChunks.clear(); chunkInFlight = false; }
    }

    private void fail(final String code, final String message) {
        Log.e(TAG, "fail " + code + ": " + message);
        try { if (connectedDevice != null && gattServer != null) gattServer.cancelConnection(connectedDevice); } catch (Exception ignored) { }
        connectedDevice = null;
        synchronized (chunkLock) { pendingChunks.clear(); chunkInFlight = false; }
        main.post(() -> {
            clearSubscribeTimeout();
            unregisterBondReceiver();
            boolean wasConnecting = connecting;
            connecting = false;
            if (wasConnecting) listener.onFailed(code, message);
        });
    }

    private void armSubscribeTimeout() {
        clearSubscribeTimeout();
        subscribeTimeout = () -> {
            subscribeTimeout = null;
            if (nativeInitStarted || !connecting) return;
            if (bondingInProgress) { armSubscribeTimeout(); return; }
            fail("NO_SESSION", "Ura se je povezala, a ni začela sinhronizacije. Na uri izberi »Sync now« in poskusi znova.");
        };
        main.postDelayed(subscribeTimeout, SUBSCRIBE_TIMEOUT_MS);
    }

    private void clearSubscribeTimeout() {
        if (subscribeTimeout != null) { main.removeCallbacks(subscribeTimeout); subscribeTimeout = null; }
    }

    /* ---------------- pairing ---------------- */

    private void clearBondTimeout() {
        if (bondTimeout != null) { main.removeCallbacks(bondTimeout); bondTimeout = null; }
    }

    @SuppressWarnings("deprecation")
    private void registerBondReceiver(final BluetoothDevice device) {
        unregisterBondReceiver();
        bondReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                BluetoothDevice target = Build.VERSION.SDK_INT >= 33
                        ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                        : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (target == null || !target.getAddress().equals(device.getAddress())) return;
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                    if (state == BluetoothDevice.BOND_BONDING) {
                        bondingInProgress = true;
                        listener.onStatus("pairing");
                        clearBondTimeout();
                        bondTimeout = () -> {
                            bondTimeout = null;
                            if (bondingInProgress) {
                                bondingInProgress = false;
                                fail("BONDING_TIMEOUT", "Seznanjanje se ni končalo v 60 sekundah.");
                            }
                        };
                        main.postDelayed(bondTimeout, BOND_TIMEOUT_MS);
                    } else {
                        bondingInProgress = false;
                        clearBondTimeout();
                    }
                } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                    int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                    // PIN (0), PASSKEY (1), PIN_16_DIGITS (7): the watch shows a code to type here.
                    // Confirmation variants are left to Android's own system dialog.
                    if (variant == 0 || variant == 1 || variant == 7) {
                        abortBroadcast();
                        promptForPin(device);
                    }
                }
            }
        };
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        // Exported: the system Bluetooth process must be able to deliver these.
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(bondReceiver, f, Context.RECEIVER_EXPORTED);
        else activity.registerReceiver(bondReceiver, f);
    }

    private void unregisterBondReceiver() {
        if (bondReceiver != null) {
            try { activity.unregisterReceiver(bondReceiver); } catch (Exception ignored) { }
        }
        bondReceiver = null;
        bondingInProgress = false;
        clearBondTimeout();
    }

    private void promptForPin(final BluetoothDevice device) {
        main.post(() -> {
            final EditText input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setHint("Koda");
            new AlertDialog.Builder(activity)
                    .setTitle("Seznanjanje z uro")
                    .setMessage("Vpiši kodo, ki jo kaže ura.")
                    .setView(input)
                    .setCancelable(false)
                    .setPositiveButton("V redu", (d, w) -> {
                        byte[] pin = input.getText().toString().getBytes(StandardCharsets.UTF_8);
                        if (pin.length > 0 && pin.length <= 16) {
                            try { device.setPin(pin); } catch (SecurityException ignored) { }
                        }
                    })
                    .setNegativeButton("Prekliči", null)
                    .show();
        });
    }

    /* ---------------- helpers ---------------- */

    private static String safeName(BluetoothDevice d) {
        if (d == null) return null;
        try { return d.getName(); } catch (SecurityException e) { return null; }
    }

    private static String nameOf(BluetoothDevice d) {
        String n = safeName(d);
        return n != null ? n : "Suunto";
    }

    /** VID is Suunto's; the PID only picks libambit's driver row. Every Ambit3-family watch uses device_driver_ambit3.c. */
    private static int guessProductId(String name) {
        if (name != null && name.startsWith("Traverse Alpha")) return 0x002d;
        if (name != null && name.startsWith("Traverse")) return 0x002b;
        return 0x001c;
    }
}
