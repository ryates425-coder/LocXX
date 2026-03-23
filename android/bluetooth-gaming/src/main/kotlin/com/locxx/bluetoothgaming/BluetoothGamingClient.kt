package com.locxx.bluetoothgaming

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE central: scans for [LocXXBleUuids.SERVICE], connects, subscribes to host notifications,
 * writes frames to the host.
 */
class BluetoothGamingClient(
    private val context: Context,
    private val listener: BluetoothGamingListener
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private val gattRef = AtomicReference<BluetoothGatt?>(null)
    private var hostToClient: BluetoothGattCharacteristic? = null
    private var clientToHost: BluetoothGattCharacteristic? = null
    private var connectedAddress: String? = null
    private val helloSent = AtomicBoolean(false)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            @Suppress("DEPRECATION")
            val advName = result.scanRecord?.deviceName
            val displayName = advName ?: device.name
            listener.onBleScanCandidate(device.address, displayName, result.rssi)
            if (!isLocXXAdvertisement(result)) return
            stopScanInternal()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onError(scanFailureMessage(errorCode))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onError("GATT connect failed ($status)")
                    return
                }
                gatt.discoverServices()
                return
            }
            if (newState != BluetoothProfile.STATE_DISCONNECTED) return
            // Ignore disconnect from a GATT we already replaced (e.g. manual pick after auto-connect);
            // otherwise we'd clear state for the new device when the old link tears down.
            if (gatt != gattRef.get()) {
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
                return
            }
            val addr = connectedAddress
            connectedAddress = null
            hostToClient = null
            clientToHost = null
            gattRef.set(null)
            if (addr != null) listener.onPeerDisconnected(addr)
            try {
                gatt.close()
            } catch (_: Exception) {
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("service discovery failed")
                return
            }
            val svc = gatt.getService(LocXXBleUuids.SERVICE) ?: run {
                listener.onError("service missing")
                return
            }
            val h2c = svc.getCharacteristic(LocXXBleUuids.HOST_TO_CLIENT)
            val c2h = svc.getCharacteristic(LocXXBleUuids.CLIENT_TO_HOST)
            if (h2c == null || c2h == null) {
                listener.onError("characteristics missing")
                return
            }
            hostToClient = h2c
            clientToHost = c2h
            val cccd = h2c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (cccd == null) {
                listener.onError("CCCD missing")
                return
            }
            gatt.setCharacteristicNotification(h2c, true)
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("descriptor write failed")
                return
            }
            if (!helloSent.compareAndSet(false, true)) return
            val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val hello = ProtocolCodec.buildClientHello(WIRE_VERSION, displayName, nonce)
            writeToHost(hello)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncoming(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                handleIncoming(characteristic.value ?: return)
            }
        }

        private fun handleIncoming(value: ByteArray) {
            val decoded = ProtocolCodec.decodeFrame(value).getOrElse {
                listener.onError(it.message ?: "decode")
                return
            }
            when (decoded.messageType) {
                WireMessageType.HOST_WELCOME -> {
                    val w = ProtocolCodec.parseHostWelcome(decoded.payload).getOrElse {
                        listener.onError(it.message ?: "welcome")
                        return
                    }
                    val addr = connectedAddress ?: return
                    listener.onPeerConnected(addr, w.playerId.toInt() and 0xFF, displayName)
                }
                WireMessageType.PING -> {
                    writeToHost(ProtocolCodec.encodeFrame(WireMessageType.PONG))
                }
                else -> {
                    val addr = connectedAddress ?: return
                    listener.onMessageReceived(addr, decoded.messageType, decoded.payload)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("write failed")
            }
        }
    }

    private var displayName: String = "Player"

    fun setDisplayName(name: String) {
        displayName = name.ifBlank { "Player" }
    }

    fun startScan(): Boolean {
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("Bluetooth off")
            return false
        }
        scanner = adapter.bluetoothLeScanner ?: run {
            listener.onError("BLE scan unavailable")
            return false
        }
        // Empty filter + software match: hardware UUID filters often miss 128-bit UUIDs.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                }
            }
            .build()
        try {
            scanner?.startScan(emptyList(), settings, scanCallback)
        } catch (e: SecurityException) {
            listener.onError(e.message ?: "scan permission")
            return false
        }
        return true
    }

    fun stopScan() {
        stopScanInternal()
    }

    private fun stopScanInternal() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
    }

    fun connect(device: BluetoothDevice) {
        stopScanInternal()
        helloSent.set(false)
        val previous = gattRef.get()
        connectedAddress = device.address
        try {
            val gatt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, gattCallback)
            }
            gattRef.set(gatt)
        } catch (e: SecurityException) {
            connectedAddress = null
            listener.onError(e.message ?: "connect")
            return
        }
        try {
            previous?.disconnect()
        } catch (_: Exception) {
        }
    }

    fun sendFrame(frame: ByteArray) {
        writeToHost(frame)
    }

    fun disconnect() {
        stopScanInternal()
        try {
            gattRef.get()?.disconnect()
        } catch (_: SecurityException) {
        }
        // connectedAddress / gattRef cleared in onConnectionStateChange for the active GATT
    }

    private fun writeToHost(frame: ByteArray) {
        val c2h = clientToHost ?: return
        val gatt = gattRef.get() ?: return
        c2h.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = if (android.os.Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(c2h, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            c2h.value = frame
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(c2h)
        }
        if (!ok) listener.onError("write queue failed")
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun isLocXXAdvertisement(result: ScanResult): Boolean {
            val record = result.scanRecord ?: return false
            val list = record.serviceUuids
            if (list != null && list.any { it.uuid == LocXXBleUuids.SERVICE }) return true
            val raw = rawAdvertisementBytes(record) ?: return false
            val target = LocXXBleUuids.SERVICE
            if (raw.indexOfSubArray(uuidToLittleEndianBytes(target)) >= 0) return true
            if (raw.indexOfSubArray(uuidToBigEndianBytes(target)) >= 0) return true
            return false
        }

        @Suppress("DEPRECATION")
        private fun rawAdvertisementBytes(record: ScanRecord): ByteArray? = record.bytes

        private fun uuidToLittleEndianBytes(uuid: UUID): ByteArray =
            ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
                putLong(uuid.mostSignificantBits)
                putLong(uuid.leastSignificantBits)
            }.array()

        private fun uuidToBigEndianBytes(uuid: UUID): ByteArray =
            ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
                putLong(uuid.mostSignificantBits)
                putLong(uuid.leastSignificantBits)
            }.array()

        private fun ByteArray.indexOfSubArray(needle: ByteArray): Int {
            if (needle.isEmpty() || needle.size > size) return -1
            for (i in 0..size - needle.size) {
                var match = true
                for (j in needle.indices) {
                    if (this[i + j] != needle[j]) {
                        match = false
                        break
                    }
                }
                if (match) return i
            }
            return -1
        }

        private fun scanFailureMessage(code: Int): String {
            val detail = when (code) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "scan already running"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                    "too many apps scanning (or registration failed); try again"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scan not supported on this device"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth internal error"
                ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "out of hardware scan resources"
                ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "scanning too frequently"
                else -> "unknown error"
            }
            return "scan failed ($code): $detail"
        }
    }
}
