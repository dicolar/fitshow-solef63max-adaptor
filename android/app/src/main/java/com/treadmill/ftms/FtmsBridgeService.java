package com.treadmill.ftms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.*;
import android.util.Log;

import java.util.Arrays;
import java.util.UUID;

/**
 * FTMS BLE Bridge Service
 *
 * Binds to com.seewo.thardwareservice via raw Binder transact calls
 * (reverse-engineered from ITreadmillService AIDL), then exposes
 * the treadmill as a BLE Fitness Machine Service (FTMS) peripheral
 * so apps like FitShow can connect.
 *
 * Protocol reference (from decompiled SoleBrushlessThardwareService.apk):
 *   - Service descriptor: "com.seewo.libthardware.ITreadmillService"
 *   - transact(4, [double speed]) → setSpeed  (speed in km/h)
 *   - transact(5, [int slope])   → setSlope   (0-15)
 *   - transact(10, [binder])     → addRunningObserver
 *   - transact(11, [binder])     → removeRunningObserver
 *
 *   IRunningObserver callbacks (descriptor: "com.seewo.libthardware.module.treadmill.IRunningObserver"):
 *   - transact(1, TreadmillData) → onDataUpdate
 *   - transact(2, double speed)  → onSpeedChanged
 *   - transact(3, int slope)     → onSlopeChanged
 *   - transact(4)                → onStarted
 *   - transact(5)                → onStopped
 */
public class FtmsBridgeService extends Service {

    private static final String TAG = "FtmsBridge";
    private static final String CHANNEL_ID = "ftms_bridge";

    // Device name — must start with "TR" to pass FitShow's name filter.
    private static final String DEVICE_NAME = "TR1200-T";

    // FitShow manufacturer Company ID (reverse-engineered from real TR1200 advertising packet)
    private static final int FITSHOW_COMPANY_ID = 0x0419;

    // FTMS BLE UUIDs
    private static final UUID UUID_FITNESS_MACHINE_SERVICE  = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_TREADMILL_DATA           = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_FITNESS_MACHINE_FEATURE  = UUID.fromString("00002acc-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_CONTROL_POINT            = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_FITNESS_MACHINE_STATUS   = UUID.fromString("00002ada-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_CCCD                     = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // FTMS Supported Range characteristics (read-only) — real TR1200 exposes all of these.
    // FitShow / Zwift typically read Supported Speed Range before enabling treadmill control,
    // so their absence is a common reason an app refuses to recognise the device.
    private static final UUID UUID_SUPPORTED_SPEED_RANGE       = UUID.fromString("00002ad4-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_SUPPORTED_INCLINATION_RANGE = UUID.fromString("00002ad5-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_SUPPORTED_RESISTANCE_RANGE  = UUID.fromString("00002ad6-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_SUPPORTED_POWER_RANGE       = UUID.fromString("00002ad8-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_SUPPORTED_HR_RANGE          = UUID.fromString("00002ad7-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_TRAINING_STATUS             = UUID.fromString("00002ad3-0000-1000-8000-00805f9b34fb");

    // FitShow private service UUID — must appear in advertising alongside 0x1826
    private static final UUID UUID_FFF0                     = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_FFF1                     = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_FFF2                     = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");

    // Device Information Service (0x180A) — the real TR1200 BLE module identifies itself
    // as a FitShow "FS-BT-D2". Cloning these lets FitShow recognise it as a genuine module.
    private static final UUID UUID_DIS                      = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_MANUFACTURER_NAME        = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_MODEL_NUMBER             = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_SERIAL_NUMBER            = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_HARDWARE_REVISION        = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_SOFTWARE_REVISION        = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb");

    // Fitness Machine Feature value, decoded byte-for-byte from the real TR1200:
    //   Fitness Machine Features = 0x000056DC  (Total Distance, Inclination, Elevation Gain,
    //     Step Count, Resistance Level, Expended Energy, Heart Rate, Elapsed Time, Power)
    //   Target Setting Features  = 0x0000000F  (Speed, Inclination, Resistance, Power targets)
    private static final byte[] FMS_FEATURE_VALUE =
            new byte[]{(byte) 0xDC, 0x56, 0x00, 0x00, 0x0F, 0x00, 0x00, 0x00};

    // =========================================================
    // FitShow ("运动秀") treadmill private protocol over FFF0
    // (spec: FitShow_Protocol_Reference.txt, 跑步机协议 v1.1)
    // Frame: 0x02 [CMD] [DATA...] [FCS] 0x03 ; FCS = XOR(CMD..DATA) ; little-endian.
    // =========================================================
    private static final int FS_STX = 0x02, FS_ETX = 0x03;
    private static final int CMD_SYS_INFO = 0x50, CMD_SYS_STATUS = 0x51,
                             CMD_SYS_DATA = 0x52, CMD_SYS_CONTROL = 0x53;
    // SYS_INFO subcommands
    private static final int INFO_MODEL = 0, INFO_SPEED = 2, INFO_INCLINE = 3, INFO_TOTAL = 4;
    // SYS_STATUS values
    private static final int ST_NORMAL = 0, ST_END = 1, ST_START = 2, ST_RUNNING = 3,
                             ST_STOPPING = 4, ST_ERROR = 5, ST_DISABLE = 6,
                             ST_READY = 9, ST_PAUSED = 0x0A;
    // SYS_CONTROL subcommands
    private static final int CTL_USER = 0, CTL_READY = 1, CTL_TARGET = 2, CTL_STOP = 3,
                             CTL_SPEED = 4, CTL_INCLINE = 5, CTL_START = 9, CTL_PAUSE = 0x0A;
    // SYS_DATA subcommands
    private static final int DAT_SPORT = 0, DAT_INFO = 1, DAT_SPEED = 2, DAT_INCLINE = 3;

    // Brand + model code returned by INFO_MODEL. FitShow identifies the treadmill by this
    // (spec: "设备型号是APP识别的依据...否则运动秀将无法识别").
    // ⚠️ PLACEHOLDER — capture the real value and replace:
    //   nRF: subscribe FFF1, write 02 50 00 02 03 04 55 03 to FFF2, read FFF1 → 50 00 [brandLE] [modelLE] ...
    //   or read it from FitShow logcat ("FFF2 RX: 02 50 ..." then the brand/model FitShow expects).
    private static final int FS_BRAND_CODE = 0x0001;
    private static final int FS_MODEL_CODE = 0x0001;
    // Device limits in FS units: speed 0.1 km/h (160=16.0, 10=1.0), incline raw level.
    private static final int FS_SPEED_MAX = 160, FS_SPEED_MIN = 10;
    private static final int FS_INCLINE_MAX = 15, FS_INCLINE_MIN = 0;
    private static final int FS_INCLINE_CONFIG = 0x02; // bit0=0 metric, bit1=1 pause supported

    // Workout counters for SYS_STATUS / SYS_DATA telemetry.
    private volatile long workoutStartMs = 0;
    private volatile int workoutDistanceM = 0;
    private volatile double workoutKcal = 0;
    private volatile int workoutSteps = 0;

    // ITreadmillService transaction codes (from b.java Stub onTransact)
    private static final int TXN_SET_SPEED    = 4;   // a(double d) → setSpeed
    private static final int TXN_SET_SLOPE    = 5;   // a(int i) → setSlope
    private static final int TXN_ADD_OBSERVER    = 10;  // a(IRunningObserver) → addListener
    private static final int TXN_REMOVE_OBSERVER = 11;  // b(IRunningObserver) → removeListener
    private static final String ITREADMILL_DESCRIPTOR = "com.seewo.libthardware.ITreadmillService";
    private static final String IOBSERVER_DESCRIPTOR  = "com.seewo.libthardware.module.treadmill.IRunningObserver";

    // State
    private IBinder treadmillServiceBinder;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothDevice connectedDevice;
    private BluetoothGattCharacteristic treadmillDataChar;
    private BluetoothGattCharacteristic statusChar;
    private BluetoothGattCharacteristic fff1Char;

    // GATT services must be added one at a time, waiting for onServiceAdded between
    // each — adding them back-to-back is unreliable on this RK3 BLE stack.
    private final java.util.ArrayDeque<BluetoothGattService> pendingServices = new java.util.ArrayDeque<>();

    private volatile double currentSpeedKmh = 0.0;
    private volatile int currentSlope = 0;
    private volatile boolean treadmillRunning = false;

    // Internal state: stopped / running / paused
    // Used to decide whether 0x07 should call start or resume.
    private enum TmState { STOPPED, RUNNING, PAUSED }
    private volatile TmState tmState = TmState.STOPPED;

    // Periodic notification every 1s so Zwift/FitShow don't time out
    private final Handler notifyHandler = new Handler(Looper.getMainLooper());
    private final Runnable notifyRunnable = new Runnable() {
        @Override public void run() {
            tickWorkout();
            notifyTreadmillData();
            notifyHandler.postDelayed(this, 1000);
        }
    };

    // Delayed real-start, so the belt waits out FitShow's 3-2-1 countdown.
    private final Runnable delayedStartRunnable = new Runnable() {
        @Override public void run() {
            if (tmState != TmState.RUNNING) treadmillStart();
        }
    };

    // =========================================================
    // IRunningObserver Binder implementation
    // Receives callbacks from thardwareservice
    // =========================================================
    private final Binder observerBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case 1: // onDataUpdate(TreadmillData)
                    data.enforceInterface(IOBSERVER_DESCRIPTOR);
                    // TreadmillData is a Parcelable; we skip full parsing and
                    // rely on the separate speed/slope callbacks below.
                    return true;
                case 2: // onSpeedChanged(double speedKmh)
                    data.enforceInterface(IOBSERVER_DESCRIPTOR);
                    double speed = data.readDouble();
                    currentSpeedKmh = speed;
                    if (speed > 0.05 && tmState != TmState.RUNNING) {
                        if (tmState == TmState.STOPPED) resetWorkout();
                        tmState = TmState.RUNNING;
                        treadmillRunning = true;
                        notifyMachineStatus((byte) 0x04);
                    }
                    Log.d(TAG, "Speed: " + speed + " km/h state=" + tmState);
                    notifyTreadmillData();
                    return true;
                case 3: // onSlopeChanged(int slope)
                    data.enforceInterface(IOBSERVER_DESCRIPTOR);
                    currentSlope = data.readInt();
                    Log.d(TAG, "Slope: " + currentSlope);
                    notifyTreadmillData();
                    return true;
                case 4: // onStarted()
                    data.enforceInterface(IOBSERVER_DESCRIPTOR);
                    if (tmState != TmState.RUNNING) resetWorkout();
                    tmState = TmState.RUNNING;
                    treadmillRunning = true;
                    Log.d(TAG, "Treadmill started");
                    notifyMachineStatus((byte) 0x04);
                    return true;
                case 5: // onStopped()
                    data.enforceInterface(IOBSERVER_DESCRIPTOR);
                    tmState = TmState.STOPPED;
                    treadmillRunning = false;
                    currentSpeedKmh = 0;
                    Log.d(TAG, "Treadmill stopped");
                    notifyMachineStatus((byte) 0x02);
                    notifyTreadmillData();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        @Override
        public String getInterfaceDescriptor() {
            return IOBSERVER_DESCRIPTOR;
        }
    };

    // =========================================================
    // Service connection to thardwareservice
    // =========================================================
    private final ServiceConnection treadmillConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Connected to thardwareservice");
            treadmillServiceBinder = service;
            registerObserver();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "Disconnected from thardwareservice");
            treadmillServiceBinder = null;
        }
    };

    // =========================================================
    // BLE GATT Server callbacks
    // =========================================================
    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            Log.d(TAG, "onServiceAdded: " + service.getUuid() + " status=" + status);
            // Add the next queued service once this one is registered.
            addNextService();
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "BLE client connected: " + device.getAddress());
                connectedDevice = device;
                // Stop advertising while connected (optional, saves power)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "BLE client disconnected, restarting advertising");
                connectedDevice = null;
                // Android stops advertising when a connection is made;
                // restart it so FitShow can find the device again.
                stopAdvertising();
                startAdvertising();
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattCharacteristic characteristic) {
            if (UUID_FITNESS_MACHINE_FEATURE.equals(characteristic.getUuid())) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, FMS_FEATURE_VALUE);
            } else {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {

            if (UUID_CONTROL_POINT.equals(characteristic.getUuid())) {
                handleControlPoint(value);
                if (responseNeeded) {
                    // Response: op code 0x80 (Response Code), request op code, result 0x01 (Success)
                    byte[] response = new byte[]{(byte) 0x80, value[0], 0x01};
                    characteristic.setValue(response);
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, response);
                }
            } else if (UUID_FFF2.equals(characteristic.getUuid())) {
                // FitShow private-protocol downlink. We don't yet know the full command
                // set, so log every frame (capture with: adb logcat -s FtmsBridge:D) so it
                // can be decoded next iteration. Still ack so the write doesn't error out.
                handleFff2Write(value);
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value);
                }
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattDescriptor descriptor, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            descriptor.setValue(value);
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value);
            }
        }
    };

    // =========================================================
    // Lifecycle
    // =========================================================
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, buildNotification());
        bindToTreadmillService();
        setupBleGattServer();
        startAdvertising();
        notifyHandler.postDelayed(notifyRunnable, 1000);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        notifyHandler.removeCallbacks(notifyRunnable);
        unregisterObserver();
        unbindService(treadmillConnection);
        stopAdvertising();
        if (gattServer != null) gattServer.close();
        Log.d(TAG, "FTMS Bridge stopped");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // =========================================================
    // Bind to thardwareservice
    // =========================================================
    private void bindToTreadmillService() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.seewo.thardwareservice",
                "com.seewo.thardwareservice.TreadmillService"));
        boolean bound = bindService(intent, treadmillConnection, Context.BIND_AUTO_CREATE);
        Log.d(TAG, "bindToTreadmillService: " + bound);
    }

    private void registerObserver() {
        if (treadmillServiceBinder == null) return;
        try {
            Parcel data = Parcel.obtain();
            data.writeInterfaceToken(ITREADMILL_DESCRIPTOR);
            data.writeStrongBinder(observerBinder);
            treadmillServiceBinder.transact(TXN_ADD_OBSERVER, data, null, IBinder.FLAG_ONEWAY);
            data.recycle();
            Log.d(TAG, "Observer registered");
        } catch (RemoteException e) {
            Log.e(TAG, "registerObserver failed", e);
        }
    }

    private void unregisterObserver() {
        if (treadmillServiceBinder == null) return;
        try {
            Parcel data = Parcel.obtain();
            data.writeInterfaceToken(ITREADMILL_DESCRIPTOR);
            data.writeStrongBinder(observerBinder);
            treadmillServiceBinder.transact(TXN_REMOVE_OBSERVER, data, null, IBinder.FLAG_ONEWAY);
            data.recycle();
        } catch (RemoteException e) {
            Log.e(TAG, "unregisterObserver failed", e);
        }
    }

    // =========================================================
    // Handle FTMS Control Point commands from FitShow
    // =========================================================
    // ITreadmillService transaction codes for start/stop/pause
    // Deduced from Stub onTransact switch order in b.java:
    //   case 22 → l() → start
    //   case 23 → m() → stop
    //   case 24 → c(int) → pause (param: 1=stop, 2=pause)
    private static final int TXN_START = 22;
    private static final int TXN_STOP  = 23;
    private static final int TXN_PAUSE = 24;

    private void handleControlPoint(byte[] value) {
        if (value == null || value.length == 0) return;
        int opCode = value[0] & 0xFF;
        Log.d(TAG, "Control Point opCode: " + opCode);

        switch (opCode) {
            case 0x02: // Set Target Speed (0.01 km/h units, little-endian)
                if (value.length >= 3) {
                    int rawSpeed = ((value[2] & 0xFF) << 8) | (value[1] & 0xFF);
                    setTreadmillSpeed(rawSpeed / 100.0);
                }
                break;
            case 0x07: // Start or Resume
                if (tmState == TmState.PAUSED) {
                    treadmillResume();
                } else if (tmState == TmState.RUNNING) {
                    // Belt already running (user pressed Start on the panel). FitShow always
                    // sends Start when it takes control — just acknowledge. Do NOT call
                    // transact(22): that triggers SLOPE CALIBRATION (incline ramps up, setSpeed
                    // returns -6 for ~30s), confirmed via logcat. Speed/slope commands that
                    // follow will apply normally.
                    Log.d(TAG, "Start received, already RUNNING — no-op (will accept speed/slope)");
                } else {
                    // STOPPED: FitShow shows a 3-2-1 countdown after Start and expects the belt
                    // to wait for it. Delay the real start ~3s so the belt doesn't move during
                    // the countdown. (Cancelled if a Stop arrives first.)
                    Log.d(TAG, "Start while STOPPED — scheduling real start in 3s (match FitShow countdown)");
                    notifyHandler.removeCallbacks(delayedStartRunnable);
                    notifyHandler.postDelayed(delayedStartRunnable, 3000);
                }
                break;
            case 0x08: // Stop or Pause
                if (value.length >= 2 && value[1] == 0x02) {
                    treadmillPause();
                } else {
                    treadmillStop();
                }
                break;
            case 0x03: // Set Target Inclination (0.1% units, little-endian)
                if (value.length >= 3) {
                    int rawInclination = ((value[2] & 0xFF) << 8) | (value[1] & 0xFF);
                    setTreadmillSlope(Math.min(15, Math.max(0, rawInclination / 10)));
                }
                break;
        }
    }

    // Broadcast action recognised by thardwareservice's TrunningReceiver (k.java class c):
    //   "pause"         → k.z()  → saves speed, decelerates to 0
    //   "resume_to_run" → k.A()  → restores saved speed, resumes running
    private static final String TRUNNING_ACTION = "com.seewo.trunning.to_thardwareservice";

    // ITreadmillService additional transaction codes (verified from b.java Stub + f.java Running state):
    //   transact(22) → k.l() → Halted.l() → transitions to Running state (f.java)
    //   transact(23) → k.m() → Running.m() → transitions to Halting → Halted
    private static final int TXN_START_WORKOUT = 22;
    private static final int TXN_STOP_WORKOUT  = 23;

    // Real software start. Confirmed by decompiling the hardware service + trunning panel app:
    //   transact(1) → ITreadmillService.a() → TreadmillControllerImpl.l(): resets the workout
    //   counters, sets the running flag, and transitions the state machine Halted → Running.
    //   This is the SAME start primitive the panel's "快速启动 / Quick Start" ultimately uses.
    //   (transact(22) we used before was controller.a() = SLOPE CALIBRATION — wrong.)
    // ⚠️ This starts the belt remotely, bypassing the physical Start button. Make sure nobody
    //    is standing on the belt unexpectedly when FitShow sends Start.
    private static final int TXN_REAL_START = 1;

    private void treadmillStart() {
        if (treadmillServiceBinder == null) return;
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken(ITREADMILL_DESCRIPTOR);
            treadmillServiceBinder.transact(TXN_REAL_START, data, reply, 0);
            reply.readException();
            int result = reply.readInt();
            data.recycle(); reply.recycle();
            tmState = TmState.RUNNING;
            treadmillRunning = true;
            resetWorkout();
            Log.d(TAG, "treadmillStart → transact(1) real start (Quick-Start path), result=" + result);
        } catch (RemoteException e) {
            Log.e(TAG, "treadmillStart transact(1) failed", e);
        }
    }

    /** Retries setSpeed(speed) every 500 ms until the AIDL returns 0 (not -6). */
    private void retrySetSpeed(double speed, int remaining) {
        if (remaining <= 0 || tmState != TmState.RUNNING) {
            Log.w(TAG, "retrySetSpeed: gave up remaining=" + remaining + " state=" + tmState);
            return;
        }
        int result = trySetSpeedForResult(speed);
        Log.d(TAG, "retrySetSpeed result=" + result + " remaining=" + remaining);
        if (result != 0) {
            // Still blocked (calibration in progress), try again in 500 ms
            notifyHandler.postDelayed(() -> retrySetSpeed(speed, remaining - 1), 500);
        } else {
            Log.d(TAG, "Speed accepted — belt should be starting");
        }
    }

    /** Like setTreadmillSpeed but returns the raw AIDL result code. */
    private int trySetSpeedForResult(double speedKmh) {
        if (treadmillServiceBinder == null) return -1;
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken(ITREADMILL_DESCRIPTOR);
            data.writeDouble(speedKmh);
            treadmillServiceBinder.transact(TXN_SET_SPEED, data, reply, 0);
            reply.readException();
            int result = reply.readInt();
            data.recycle(); reply.recycle();
            return result;
        } catch (RemoteException e) {
            Log.e(TAG, "trySetSpeedForResult failed", e);
            return -1;
        }
    }

    private void treadmillStop() {
        // Cancel any pending delayed start (e.g. user/app stopped during the countdown).
        notifyHandler.removeCallbacks(delayedStartRunnable);
        // k.m() transitions: Running.m() → h state → Halting → Halted
        Log.d(TAG, "treadmillStop → transact(23)");
        if (treadmillServiceBinder == null) return;
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken(ITREADMILL_DESCRIPTOR);
            treadmillServiceBinder.transact(TXN_STOP_WORKOUT, data, reply, 0);
            reply.readException();
            data.recycle(); reply.recycle();
            tmState = TmState.STOPPED;
            treadmillRunning = false;
            currentSpeedKmh = 0;
            Log.d(TAG, "treadmillStop OK");
        } catch (RemoteException e) { Log.e(TAG, "stop failed", e); }
    }

    private void treadmillPause() {
        Log.d(TAG, "treadmillPause → broadcast pause");
        Intent intent = new Intent(TRUNNING_ACTION);
        intent.putExtra("action", "pause");
        sendBroadcast(intent);
        tmState = TmState.PAUSED;
    }

    private void treadmillResume() {
        Log.d(TAG, "treadmillResume → broadcast resume_to_run");
        Intent intent = new Intent(TRUNNING_ACTION);
        intent.putExtra("action", "resume_to_run");
        sendBroadcast(intent);
        tmState = TmState.RUNNING;
    }

    private void setTreadmillSpeed(double speedKmh) {
        if (treadmillServiceBinder == null) return;
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken(ITREADMILL_DESCRIPTOR);
            data.writeDouble(speedKmh);
            treadmillServiceBinder.transact(TXN_SET_SPEED, data, reply, 0);
            reply.readException();
            int result = reply.readInt();
            data.recycle();
            reply.recycle();
            Log.d(TAG, "Set speed: " + speedKmh + " km/h → result=" + result);
        } catch (RemoteException e) {
            Log.e(TAG, "setSpeed failed", e);
        }
    }

    private void setTreadmillSlope(int slope) {
        if (treadmillServiceBinder == null) return;
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken(ITREADMILL_DESCRIPTOR);
            data.writeInt(slope);
            treadmillServiceBinder.transact(TXN_SET_SLOPE, data, reply, 0);
            reply.readException();
            int result = reply.readInt();
            data.recycle();
            reply.recycle();
            Log.d(TAG, "Set slope: " + slope + " → result=" + result);
        } catch (RemoteException e) {
            Log.e(TAG, "setSlope failed", e);
        }
    }

    private void sendTreadmillCommand(byte[] payload) {
        Log.d(TAG, "sendTreadmillCommand (unused stub): " + Arrays.toString(payload));
    }

    /** Get first 3 bytes of the Bluetooth MAC address. Falls back to a fixed value. */
    private byte[] getMacBytes(BluetoothAdapter adapter) {
        try {
            String addr = adapter.getAddress(); // e.g. "22:22:F5:55:06:00"
            if (addr != null && !addr.equals("02:00:00:00:00:00")) {
                String[] parts = addr.split(":");
                return new byte[]{
                        (byte) Integer.parseInt(parts[0], 16),
                        (byte) Integer.parseInt(parts[1], 16),
                        (byte) Integer.parseInt(parts[2], 16)
                };
            }
        } catch (Exception ignored) {}
        // Fallback: known MAC prefix for this device
        return new byte[]{0x22, 0x22, (byte) 0xF5};
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02X", v));
        return sb.toString();
    }

    // =========================================================
    // BLE FTMS setup
    // =========================================================
    private void setupBleGattServer() {
        BluetoothManager btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (btManager == null) return;

        gattServer = btManager.openGattServer(this, gattCallback);
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server");
            return;
        }

        BluetoothGattService fmsService = new BluetoothGattService(
                UUID_FITNESS_MACHINE_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Fitness Machine Feature (read) — 8 bytes per FTMS spec
        // Bytes 0-3: Fitness Machine Features
        //   Bit 0: Avg Speed supported
        //   Bit 2: Total Distance supported
        //   Bit 3: Inclination supported
        //   Bit 5: Pace supported
        //   Bit 6: Step Count supported
        //   Bit 12: Elapsed Time supported
        //   = 0x006D + 0x1000 = 0x106D
        // Bytes 4-7: Target Setting Features
        //   Bit 0: Speed Target Setting supported
        //   Bit 1: Inclination Target Setting supported
        //   = 0x0003
        BluetoothGattCharacteristic featureChar = new BluetoothGattCharacteristic(
                UUID_FITNESS_MACHINE_FEATURE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        featureChar.setValue(FMS_FEATURE_VALUE);
        fmsService.addCharacteristic(featureChar);

        // Treadmill Data (notify)
        treadmillDataChar = new BluetoothGattCharacteristic(
                UUID_TREADMILL_DATA,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0);
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                UUID_CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        treadmillDataChar.addDescriptor(cccd);
        fmsService.addCharacteristic(treadmillDataChar);

        // Fitness Machine Status (notify)
        statusChar = new BluetoothGattCharacteristic(
                UUID_FITNESS_MACHINE_STATUS,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0);
        BluetoothGattDescriptor statusCccd = new BluetoothGattDescriptor(
                UUID_CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        statusCccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        statusChar.addDescriptor(statusCccd);
        fmsService.addCharacteristic(statusChar);

        // Fitness Machine Control Point (write + indicate)
        BluetoothGattCharacteristic controlChar = new BluetoothGattCharacteristic(
                UUID_CONTROL_POINT,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        fmsService.addCharacteristic(controlChar);

        // ----- Supported Range characteristics (read-only constants) -----
        // These mirror the real TR1200 GATT profile. The read handler already
        // returns characteristic.getValue() for any characteristic that isn't the
        // Feature char, so simply setting a fixed value here is enough.

        // All Supported Range values below are decoded byte-for-byte from the real TR1200.

        // Supported Speed Range (0x2AD4): min(uint16,0.01km/h), max(uint16), minIncrement(uint16)
        //   1.0 km/h .. 16.0 km/h, step 0.1 km/h  → 100, 1600, 10
        fmsService.addCharacteristic(makeReadConst(UUID_SUPPORTED_SPEED_RANGE,
                new byte[]{0x64, 0x00, (byte) 0x40, 0x06, 0x0A, 0x00}));

        // Supported Inclination Range (0x2AD5): min(sint16,0.1%), max(sint16), minIncrement(uint16)
        //   0.0% .. 15.0%, step 1.0%  → 0, 150, 10
        fmsService.addCharacteristic(makeReadConst(UUID_SUPPORTED_INCLINATION_RANGE,
                new byte[]{0x00, 0x00, (byte) 0x96, 0x00, 0x0A, 0x00}));

        // Supported Resistance Level Range (0x2AD6): min(sint16,0.1), max(sint16), minIncrement(uint16)
        //   0.0 .. 25.5, step 0.1  → 0, 255, 1
        fmsService.addCharacteristic(makeReadConst(UUID_SUPPORTED_RESISTANCE_RANGE,
                new byte[]{0x00, 0x00, (byte) 0xFF, 0x00, 0x01, 0x00}));

        // Supported Power Range (0x2AD8): min(sint16,W), max(sint16), minIncrement(uint16)
        //   10 W .. 9999 W, step 10 W  → 10, 9999, 10
        fmsService.addCharacteristic(makeReadConst(UUID_SUPPORTED_POWER_RANGE,
                new byte[]{0x0A, 0x00, (byte) 0x0F, 0x27, 0x0A, 0x00}));

        // Supported Heart Rate Range (0x2AD7): min(uint8), max(uint8), minIncrement(uint8)
        //   0 .. 250 bpm, step 1
        fmsService.addCharacteristic(makeReadConst(UUID_SUPPORTED_HR_RANGE,
                new byte[]{0x00, (byte) 0xFA, 0x01}));

        // Training Status (0x2AD3): NOTIFY + READ. Flags(uint8) + Training Status(uint8).
        //   0x01 = Idle initially.
        BluetoothGattCharacteristic trainingChar = new BluetoothGattCharacteristic(
                UUID_TRAINING_STATUS,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        trainingChar.setValue(new byte[]{0x00, 0x01});
        BluetoothGattDescriptor trainingCccd = new BluetoothGattDescriptor(
                UUID_CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        trainingCccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        trainingChar.addDescriptor(trainingCccd);
        fmsService.addCharacteristic(trainingChar);

        // FitShow's preferred path is the private 0xFFF0 service. On the real TR1200:
        //   FFF1 — NOTIFY (uplink to app), initial value 02 51 00 51 03
        //   FFF2 — WRITE NO RESPONSE (downlink from app)
        // The previous build was missing FFF2 entirely, so FitShow had no channel to
        // send commands on. We now expose both, mirroring the real device.
        BluetoothGattService fff0Service = new BluetoothGattService(
                UUID_FFF0, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // FFF1 — NOTIFY only (matches real device). Seed with the real device's initial frame.
        fff1Char = new BluetoothGattCharacteristic(
                UUID_FFF1,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0);
        fff1Char.setValue(new byte[]{0x02, 0x51, 0x00, 0x51, 0x03});
        BluetoothGattDescriptor fff1Cccd = new BluetoothGattDescriptor(
                UUID_CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        fff1Cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        fff1Char.addDescriptor(fff1Cccd);
        fff0Service.addCharacteristic(fff1Char);

        // FFF2 — WRITE NO RESPONSE (+ WRITE for tolerance). FitShow writes its private
        // protocol frames here; we log every frame so the exact command set can be
        // captured via logcat for the next iteration.
        BluetoothGattCharacteristic fff2Char = new BluetoothGattCharacteristic(
                UUID_FFF2,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        fff0Service.addCharacteristic(fff2Char);

        // ----- Device Information Service (0x180A) -----
        // Mirror the real TR1200 FS-BT-D2 module so FitShow recognises a genuine module.
        BluetoothGattService disService = new BluetoothGattService(
                UUID_DIS, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        disService.addCharacteristic(makeReadString(UUID_MANUFACTURER_NAME, "FITSHOW"));
        disService.addCharacteristic(makeReadString(UUID_MODEL_NUMBER,      "FS-BT-D2"));
        disService.addCharacteristic(makeReadString(UUID_SERIAL_NUMBER,     "FS231221001"));
        disService.addCharacteristic(makeReadString(UUID_HARDWARE_REVISION, "1.0"));
        disService.addCharacteristic(makeReadString(UUID_SOFTWARE_REVISION, "1.3.3"));

        // Register the three services sequentially (see pendingServices comment).
        pendingServices.add(fmsService);
        pendingServices.add(fff0Service);
        pendingServices.add(disService);
        addNextService();

        Log.d(TAG, "GATT services queued: FTMS (+ranges) + FFF0 (FFF1/FFF2) + DIS");
    }

    /** Adds the next queued GATT service; onServiceAdded triggers the following one. */
    private void addNextService() {
        if (gattServer == null) return;
        BluetoothGattService next = pendingServices.poll();
        if (next != null) gattServer.addService(next);
    }

    /** Builds a read-only string characteristic (UTF-8). */
    private BluetoothGattCharacteristic makeReadString(UUID uuid, String value) {
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        c.setValue(value);
        return c;
    }

    /** Builds a read-only characteristic that always returns a fixed value. */
    private BluetoothGattCharacteristic makeReadConst(UUID uuid, byte[] value) {
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        c.setValue(value);
        return c;
    }

    private void startAdvertising() {
        BluetoothManager btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (btManager == null) return;
        BluetoothAdapter adapter = btManager.getAdapter();
        if (adapter == null) return;

        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.e(TAG, "LE advertising not supported");
            return;
        }

        // Set name and wait briefly for it to propagate
        adapter.setName(DEVICE_NAME);
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        android.os.ParcelUuid ftmsUuid = new android.os.ParcelUuid(UUID_FITNESS_MACHINE_SERVICE);
        android.os.ParcelUuid fff0Uuid = new android.os.ParcelUuid(UUID_FFF0);

        // Get first 3 bytes of our MAC address for the manufacturer data payload.
        // Format observed from a real TR1200: 6D 00 [mac0 mac1 mac2] 3B 00 00 00 07
        byte[] mac = getMacBytes(adapter);
        byte[] mfData = new byte[]{
                0x6D, 0x00,
                mac[0], mac[1], mac[2],
                0x3B, 0x00, 0x00, 0x00, 0x07
        };

        // Primary advertising packet — must exactly match TR1200 format:
        //   02 01 06                          Flags (auto)
        //   0D FF 19 04 [mfData]              Manufacturer Data, Company ID 0x0419
        //   05 03 F0 FF 26 18                 Complete 16-bit UUIDs: 0xFFF0 + 0x1826
        //   06 16 26 18 01 00 01              FTMS Service Data (treadmill type + flags)
        // Total ≈ 30 bytes (within 31-byte limit)
        AdvertiseData advData = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(FITSHOW_COMPANY_ID, mfData)
                .addServiceUuid(fff0Uuid)
                .addServiceUuid(ftmsUuid)
                .addServiceData(ftmsUuid, new byte[]{0x01, 0x00, 0x01})
                .build();

        // Scan response: device name starting with "TR" (required by FitShow name filter)
        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .build();

        advertiser.startAdvertising(settings, advData, scanResponse, advertiseCallback);
        Log.d(TAG, "BLE advertising: name=" + DEVICE_NAME
                + " companyId=0x0419 UUIDs=FFF0+1826 mac=" + bytesToHex(mac));
    }

    private void stopAdvertising() {
        if (advertiser != null) {
            advertiser.stopAdvertising(advertiseCallback);
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.d(TAG, "Advertising started OK");
        }
        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "Advertising failed: " + errorCode);
        }
    };

    // =========================================================
    // Notify BLE clients with treadmill data
    // =========================================================
    private void notifyTreadmillData() {
        if (gattServer == null || treadmillDataChar == null || connectedDevice == null) return;

        // FTMS Treadmill Data characteristic format (Bluetooth SIG spec):
        // Flags (2 bytes) + Instantaneous Speed (2 bytes, unit 0.01 km/h)
        // + optional: Average Speed, Total Distance, Inclination, Ramp Angle,
        //             Positive/Negative Elevation, Steps/Strides/Cadence, etc.
        //
        // We send: flags=0x0000 (only mandatory speed), speed in 0.01 km/h

        // Round (don't truncate): the hardware reports doubles like 4.8999999, and
        // (int)(4.8999999*100)=489 would display as 4.8 — a systematic 0.1 km/h shortfall.
        int speedRaw = (int) Math.round(currentSpeedKmh * 100); // 0.01 km/h units
        int inclineRaw = (int) Math.round(currentSlope * 10.0); // 0.1% units (slope level ≈ N.0%)

        // FTMS Treadmill Data flags (uint16):
        //   bit 0 (More Data) = 0  → Instantaneous Speed present
        //   bit 3            = 1  → Inclination + Ramp Angle Setting present (4 bytes total)
        // Field order follows bit order: [flags][inst speed][inclination][ramp angle].
        int flags = 0x0008; // always report inclination so FitShow can display it

        byte[] payload = new byte[8];
        payload[0] = (byte) (flags & 0xFF);
        payload[1] = (byte) ((flags >> 8) & 0xFF);
        payload[2] = (byte) (speedRaw & 0xFF);            // Instantaneous Speed (uint16, 0.01 km/h)
        payload[3] = (byte) ((speedRaw >> 8) & 0xFF);
        payload[4] = (byte) (inclineRaw & 0xFF);          // Inclination (sint16, 0.1 %)
        payload[5] = (byte) ((inclineRaw >> 8) & 0xFF);
        payload[6] = 0;                                   // Ramp Angle Setting (sint16, 0.1 deg)
        payload[7] = 0;

        treadmillDataChar.setValue(payload);
        gattServer.notifyCharacteristicChanged(connectedDevice, treadmillDataChar, false);
    }

    private void notifyMachineStatus(byte statusCode) {
        if (gattServer == null || statusChar == null || connectedDevice == null) return;
        statusChar.setValue(new byte[]{statusCode});
        gattServer.notifyCharacteristicChanged(connectedDevice, statusChar, false);
    }

    // =========================================================
    // FitShow private protocol (0xFFF0 service)
    // =========================================================
    // The real TR1200 uses an FS/iConsole-style framing on FFF1/FFF2:
    //   0x02 [CMD] [DATA...] [XOR-checksum of CMD..DATA] 0x03
    // (Observed FFF1 notify value 02 51 00 51 03 fits this: cmd=0x51, data=0x00,
    //  checksum 0x51^0x00 = 0x51.)
    //
    // Parses an inbound FitShow frame and dispatches to the right responder.
    private void handleFff2Write(byte[] value) {
        if (value == null || value.length < 4) {
            Log.d(TAG, "FFF2 RX (too short): " + bytesToHex(value));
            return;
        }
        Log.d(TAG, "FFF2 RX: " + bytesToHex(value));
        if ((value[0] & 0xFF) != FS_STX || (value[value.length - 1] & 0xFF) != FS_ETX) {
            Log.w(TAG, "FFF2: bad frame markers, ignoring");
            return;
        }
        int cmd = value[1] & 0xFF;
        // DATA = bytes between CMD and FCS (strip STX, CMD, FCS, ETX).
        byte[] dataBytes = new byte[value.length - 4];
        System.arraycopy(value, 2, dataBytes, 0, dataBytes.length);

        switch (cmd) {
            case CMD_SYS_INFO:    respondSysInfo(dataBytes);   break;
            case CMD_SYS_STATUS:  respondSysStatus();          break;
            case CMD_SYS_CONTROL: handleSysControl(dataBytes); break;
            case CMD_SYS_DATA:    respondSysData(dataBytes);   break;
            default:
                // Spec: unknown but well-formed command → echo the command with no data.
                notifyFff1(buildFrame(cmd, new byte[0]));
                break;
        }
    }

    // SYS_INFO (0x50): reply with the parameters the app asked for (or all if none specified).
    private void respondSysInfo(byte[] req) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] ids = (req.length > 0) ? req
                : new byte[]{INFO_MODEL, INFO_SPEED, INFO_INCLINE, INFO_TOTAL};
        for (byte idB : ids) {
            int id = idB & 0xFF;
            out.write(id);
            switch (id) {
                case INFO_MODEL:
                    writeW(out, FS_BRAND_CODE);   // 品牌 (W)
                    writeW(out, FS_MODEL_CODE);   // 机型 (W)
                    break;
                case INFO_SPEED:
                    out.write(FS_SPEED_MAX);      // 最高速 (B), 0.1 km/h
                    out.write(FS_SPEED_MIN);      // 最低速 (B)
                    break;
                case INFO_INCLINE:
                    out.write(FS_INCLINE_MAX);    // 最高坡度 (B)
                    out.write(FS_INCLINE_MIN);    // 最低坡度 (B)
                    out.write(FS_INCLINE_CONFIG); // 配置 (B)
                    break;
                case INFO_TOTAL:
                    writeL(out, 0);               // 累计里程 (L), 0.1 km
                    break;
                default:
                    break;
            }
        }
        notifyFff1(buildFrame(CMD_SYS_INFO, out.toByteArray()));
    }

    // SYS_STATUS (0x51): reply with current run state + telemetry (polled ~3x/s by the app).
    private void respondSysStatus() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int status;
        switch (tmState) {
            case RUNNING: status = ST_RUNNING; break;
            case PAUSED:  status = ST_PAUSED;  break;
            default:      status = ST_NORMAL;  break;
        }
        out.write(status);
        if (status == ST_RUNNING) {
            int spd = (int) Math.round(currentSpeedKmh * 10);   // 0.1 km/h units
            out.write(Math.max(0, Math.min(255, spd)));         // 当前速度 (B)
            out.write(currentSlope & 0xFF);                     // 当前坡度 (B)
            writeW(out, workoutElapsedSec());                   // 正计时间 (W) s
            writeW(out, workoutDistanceM);                      // 正计距离 (W) m
            writeW(out, (int) workoutKcal);                     // 正计热量 (W)
            writeW(out, workoutSteps);                          // 正计步数 (W)
            out.write(0);                                       // 当前心率 (B)
            out.write(0);                                       // 当前程式段数 (B)
        }
        notifyFff1(buildFrame(CMD_SYS_STATUS, out.toByteArray()));
    }

    // SYS_CONTROL (0x53): drive the treadmill, then echo per spec.
    private void handleSysControl(byte[] data) {
        if (data.length < 1) { notifyFff1(buildFrame(CMD_SYS_CONTROL, new byte[0])); return; }
        int sub = data[0] & 0xFF;
        switch (sub) {
            case CTL_READY:
                resetWorkout();
                notifyFff1(buildFrame(CMD_SYS_CONTROL, new byte[]{(byte) CTL_READY, 0x00})); // 启动秒数=0
                break;
            case CTL_START:
                if (tmState == TmState.PAUSED) treadmillResume();
                else { resetWorkout(); treadmillStart(); }
                notifyFff1(buildFrame(CMD_SYS_CONTROL, new byte[]{(byte) CTL_START}));
                break;
            case CTL_PAUSE:
                treadmillPause();
                notifyFff1(buildFrame(CMD_SYS_CONTROL, new byte[]{(byte) CTL_PAUSE}));
                break;
            case CTL_STOP:
                treadmillStop();
                notifyFff1(buildFrame(CMD_SYS_CONTROL, new byte[]{(byte) CTL_STOP}));
                break;
            case CTL_TARGET: {
                int tSpeed   = data.length > 1 ? (data[1] & 0xFF) : -1; // 0.1 km/h
                int tIncline = data.length > 2 ? (data[2] & 0xFF) : -1; // raw level
                if (tSpeed >= 0)   setTreadmillSpeed(tSpeed / 10.0);
                if (tIncline >= 0) setTreadmillSlope(Math.max(0, Math.min(15, tIncline)));
                notifyFff1(buildFrame(CMD_SYS_CONTROL, new byte[]{
                        (byte) CTL_TARGET,
                        (byte) (tSpeed   >= 0 ? tSpeed   : 0),   // 实际目标速度
                        (byte) (tIncline >= 0 ? tIncline : 0)})); // 实际目标坡度
                break;
            }
            case CTL_USER:
            case CTL_SPEED:
            case CTL_INCLINE:
            default:
                notifyFff1(buildFrame(CMD_SYS_CONTROL, new byte[]{(byte) sub}));
                break;
        }
    }

    // SYS_DATA (0x52): reply with sport / info data.
    private void respondSysData(byte[] data) {
        int sub = data.length > 0 ? (data[0] & 0xFF) : DAT_SPORT;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(sub);
        switch (sub) {
            case DAT_SPORT:
                writeW(out, workoutElapsedSec()); // 正计时间 (W)
                writeW(out, workoutDistanceM);    // 正计距离 (W)
                writeW(out, (int) workoutKcal);   // 正计热量 (W)
                writeW(out, workoutSteps);        // 正计步数 (W)
                break;
            case DAT_INFO:
                writeL(out, 0); // 用户ID (L)
                writeL(out, 0); // 运动ID (L)
                out.write(0);   // 模式
                out.write(0);   // 程序段数
                writeW(out, 0); // 倒计值 (W)
                break;
            default:
                break;
        }
        notifyFff1(buildFrame(CMD_SYS_DATA, out.toByteArray()));
    }

    // ---- Workout counter helpers ----
    private void resetWorkout() {
        workoutStartMs = System.currentTimeMillis();
        workoutDistanceM = 0;
        workoutKcal = 0;
        workoutSteps = 0;
    }

    private int workoutElapsedSec() {
        if (workoutStartMs == 0) return 0;
        return (int) ((System.currentTimeMillis() - workoutStartMs) / 1000);
    }

    /** Called once per second; integrates distance/calories while running. */
    private void tickWorkout() {
        if (tmState == TmState.RUNNING && currentSpeedKmh > 0.05) {
            workoutDistanceM += (int) Math.round(currentSpeedKmh * 1000.0 / 3600.0); // m this second
            workoutKcal += currentSpeedKmh * 0.015; // rough kcal/s estimate
        }
    }

    // ---- FitShow frame helpers ----
    /** Sends a frame to the app over the FFF1 notify characteristic. */
    private void notifyFff1(byte[] frame) {
        if (gattServer == null || fff1Char == null || connectedDevice == null) return;
        fff1Char.setValue(frame);
        gattServer.notifyCharacteristicChanged(connectedDevice, fff1Char, false);
        Log.d(TAG, "FFF1 TX: " + bytesToHex(frame));
    }

    /** Wraps CMD+DATA into a full 0x02 ... FCS 0x03 frame. */
    private static byte[] buildFrame(int cmd, byte[] data) {
        byte[] body = new byte[1 + data.length];
        body[0] = (byte) cmd;
        System.arraycopy(data, 0, body, 1, data.length);
        byte fcs = fsChecksum(body);
        byte[] frame = new byte[body.length + 3];
        frame[0] = (byte) FS_STX;
        System.arraycopy(body, 0, frame, 1, body.length);
        frame[1 + body.length] = fcs;
        frame[2 + body.length] = (byte) FS_ETX;
        return frame;
    }

    /** FS-protocol XOR checksum over the command + data bytes. */
    private static byte fsChecksum(byte[] cmdAndData) {
        int x = 0;
        for (byte b : cmdAndData) x ^= (b & 0xFF);
        return (byte) x;
    }

    private static void writeW(java.io.ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void writeL(java.io.ByteArrayOutputStream o, long v) {
        o.write((int) (v & 0xFF));
        o.write((int) ((v >> 8) & 0xFF));
        o.write((int) ((v >> 16) & 0xFF));
        o.write((int) ((v >> 24) & 0xFF));
    }

    // =========================================================
    // Foreground notification
    // =========================================================
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "FTMS Bridge", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("FTMS Bridge Running")
                .setContentText("Treadmill visible as 'SoleF63Max' via BLE")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }
}
