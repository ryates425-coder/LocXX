package com.locxx.bluetoothgaming

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLE peripheral hosting a star-typed session: advertises [LocXXBleUuids.SERVICE], accepts writes
 * on [LocXXBleUuids.CLIENT_TO_HOST] and pushes notifications on [LocXXBleUuids.HOST_TO_CLIENT].
 */
class BluetoothGamingHost(
    private val context: Context,
    private val listener: BluetoothGamingListener
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var hostToClientChar: BluetoothGattCharacteristic? = null

    private val sessionNonce: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
    private val nextPlayerId = AtomicInteger(1)
    private val addressToPeer = ConcurrentHashMap<String, PeerRecord>()

    data class PeerRecord(val playerId: Int, val displayName: String)

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {}
        override fun onStartFailure(errorCode: Int) {
            listener.onError(advertiseFailureMessage(errorCode))
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState != BluetoothGatt.STATE_CONNECTED) {
                addressToPeer.remove(device.address)
                listener.onPeerDisconnected(device.address)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val server = gattServer ?: return
            if (characteristic.uuid != LocXXBleUuids.CLIENT_TO_HOST) {
                if (responseNeeded) {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
                return
            }
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            handleClientFrame(device, value)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            val server = gattServer ?: return
            if (descriptor.uuid == CLIENT_CONFIG_UUID) {
                val v = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v)
            } else {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val server = gattServer ?: return
            if (descriptor.uuid == CLIENT_CONFIG_UUID) {
                descriptor.value = value
                if (responseNeeded) {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } else if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }
    }

    private fun handleClientFrame(device: BluetoothDevice, value: ByteArray) {
        val decoded = ProtocolCodec.decodeFrame(value).getOrElse {
            listener.onError(it.message ?: "decode")
            return
        }
        when (decoded.messageType) {
            WireMessageType.CLIENT_HELLO -> {
                val hello = ProtocolCodec.parseClientHello(decoded.payload).getOrElse {
                    listener.onError(it.message ?: "hello")
                    return
                }
                if (hello.protocolVersion != WIRE_VERSION) {
                    listener.onError("protocol mismatch")
                    return
                }
                val pid = nextPlayerId.getAndIncrement()
                if (pid > 8) {
                    nextPlayerId.decrementAndGet()
                    listener.onError("room full")
                    return
                }
                addressToPeer[device.address] = PeerRecord(pid, hello.displayName)
                val welcome = ProtocolCodec.buildHostWelcome(WIRE_VERSION, pid.toByte(), sessionNonce)
                sendRawToDevice(device, welcome)
                listener.onPeerConnected(device.address, pid, hello.displayName)
            }
            WireMessageType.PING -> {
                sendRawToDevice(device, ProtocolCodec.encodeFrame(WireMessageType.PONG))
            }
            else -> {
                listener.onMessageReceived(device.address, decoded.messageType, decoded.payload)
            }
        }
    }

    fun sessionNonceBytes(): ByteArray = sessionNonce.copyOf()

    fun start(): Boolean {
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("Bluetooth off or unavailable")
            return false
        }
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            listener.onError("BLE advertise not supported")
            return false
        }
        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        val service = buildService()
        val ok = gattServer?.addService(service) == true
        if (!ok) {
            listener.onError("addService failed")
            return false
        }
        hostToClientChar = service.getCharacteristic(LocXXBleUuids.HOST_TO_CLIENT)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Legacy AD is limited to 31 bytes. Device name + 128-bit UUID often exceeds it → error 1
        // (ADVERTISE_FAILED_DATA_TOO_LARGE). Clients discover us by service UUID; name can go in scan response.
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(LocXXBleUuids.SERVICE))
            .build()
        // Duplicate UUID in scan response: some radios only merge SR into ScanRecord.serviceUuids.
        // Avoid name here (keeps packet small; long BT device names break the 31-byte SR limit).
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(LocXXBleUuids.SERVICE))
            .build()
        try {
            advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        } catch (e: SecurityException) {
            listener.onError(e.message ?: "permission")
            return false
        }
        return true
    }

    fun stop() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: SecurityException) {
        }
        advertiser = null
        addressToPeer.clear()
        nextPlayerId.set(1)
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        hostToClientChar = null
    }

    fun sendToPeer(deviceAddress: String, frame: ByteArray) {
        val server = gattServer ?: return
        val dev = adapter.getRemoteDevice(deviceAddress)
        sendRawToDevice(dev, frame)
    }

    fun broadcast(frame: ByteArray) {
        for (addr in addressToPeer.keys) {
            sendToPeer(addr, frame)
        }
    }

    private fun sendRawToDevice(device: BluetoothDevice, frame: ByteArray) {
        val ch = hostToClientChar ?: return
        val server = gattServer ?: return
        ch.value = frame
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                server.notifyCharacteristicChanged(device, ch, false, frame)
            } else {
                ch.value = frame
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, ch, false)
            }
        } catch (e: SecurityException) {
            listener.onError(e.message ?: "notify")
        }
    }

    private fun buildService(): BluetoothGattService {
        val svc = BluetoothGattService(LocXXBleUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val c2h = BluetoothGattCharacteristic(
            LocXXBleUuids.CLIENT_TO_HOST,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val h2c = BluetoothGattCharacteristic(
            LocXXBleUuids.HOST_TO_CLIENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            CLIENT_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        h2c.addDescriptor(cccd)
        svc.addCharacteristic(c2h)
        svc.addCharacteristic(h2c)
        return svc
    }

    companion object {
        private val CLIENT_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun advertiseFailureMessage(code: Int): String {
            val detail = when (code) {
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
                    "advertisement payload too large (31-byte limit); try shorter Bluetooth device name"
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                    "too many concurrent advertisers on this device"
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED ->
                    "advertising already started"
                AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Bluetooth stack internal error"
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                    "advertising not supported with these settings on this device"
                else -> "unknown error"
            }
            return "advertise failed ($code): $detail"
        }
    }
}
