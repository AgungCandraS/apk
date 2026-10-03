package com.esp32.smartnotif.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.esp32.smartnotif.MainActivity
import com.esp32.smartnotif.R
import com.esp32.smartnotif.ble.BleManager
import com.esp32.smartnotif.utils.DeviceSyncHelper
import com.esp32.smartnotif.utils.PermissionHelper
import com.esp32.smartnotif.utils.WeatherManager

class BleForegroundService : Service(), BleManager.BleStateListener {
    companion object {
        private const val TAG = "BleForegroundService"
        private const val PREFS = "ble_service_preferences"
        private const val ENABLED = "enabled"
        const val CHANNEL_ID = "esp32_smartnotif_service_channel"
        const val NOTIF_ID = 1001
        const val ACTION_DISCONNECT = "com.esp32.smartnotif.ACTION_DISCONNECT"
        const val ACTION_STATE_CHANGED = "com.esp32.smartnotif.ACTION_SERVICE_STATE_CHANGED"

        @Volatile
        var isRunning = false
            private set

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

        private fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(ENABLED, enabled).apply()
        }

        fun startService(context: Context): Boolean {
            if (!PermissionHelper.hasAllRuntimePermissions(context)) return false
            if (isRunning) return true
            return try {
                val intent = Intent(context, BleForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                setEnabled(context, true)
                true
            } catch (error: Exception) {
                Log.e(TAG, "Tidak dapat memulai layanan BLE", error)
                false
            }
        }

        fun stopService(context: Context) {
            setEnabled(context, false)
            BleManager.getInstance(context).disconnect()
            context.stopService(Intent(context, BleForegroundService::class.java))
        }
    }

    private lateinit var bleManager: BleManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastBatteryLevel = -1
    private var isBatteryReceiverRegistered = false
    private var connectedActionsApplied = false

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val percentage = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            if (percentage >= 0 && percentage != lastBatteryLevel) {
                lastBatteryLevel = percentage
                sendTimeAndBatterySync(percentage)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager.getInstance(this)
        createNotificationChannel()
        // Promote before registering listeners, which immediately report the current state.
        val notification = buildForegroundNotification(serviceDescription(bleManager.currentState))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        isRunning = true
        notifyServiceState()
        bleManager.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT || !isEnabled(this)) {
            setEnabled(this, false)
            bleManager.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!PermissionHelper.hasAllRuntimePermissions(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bleManager.currentState == BleManager.ConnectionState.DISCONNECTED) {
            bleManager.startScanAndConnect()
        }
        return START_STICKY
    }

    override fun onStateChanged(state: BleManager.ConnectionState, message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildForegroundNotification(serviceDescription(state)))
        if (state == BleManager.ConnectionState.CONNECTED) {
            if (!connectedActionsApplied) {
                connectedActionsApplied = true
                acquireWakeLock()
                lastBatteryLevel = DeviceSyncHelper.getBatteryPercentage(this)
                sendTimeAndBatterySync(lastBatteryLevel)
                registerBatteryReceiver()
                WeatherManager.getLastSavedPayload(this)?.takeIf { it.isNotEmpty() }?.let(bleManager::sendData)
                WeatherWorker.triggerImmediateSync(this)
            }
        } else {
            connectedActionsApplied = false
            unregisterBatteryReceiver()
            releaseWakeLock()
        }
        // A disconnected device is still a valid running service state.
        notifyServiceState()
    }

    private fun serviceDescription(state: BleManager.ConnectionState): String = when (state) {
        BleManager.ConnectionState.CONNECTED -> getString(
            R.string.service_connected,
            bleManager.connectedDeviceName ?: bleManager.savedDeviceName ?: getString(R.string.device_target_name)
        )
        BleManager.ConnectionState.CONNECTING -> getString(R.string.service_connecting)
        BleManager.ConnectionState.SCANNING -> getString(R.string.service_scanning)
        BleManager.ConnectionState.DISCONNECTED -> getString(R.string.service_waiting)
    }

    private fun notifyServiceState() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun registerBatteryReceiver() {
        if (isBatteryReceiverRegistered) return
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        isBatteryReceiverRegistered = true
    }

    private fun unregisterBatteryReceiver() {
        if (isBatteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver)
            isBatteryReceiverRegistered = false
        }
    }

    private fun sendTimeAndBatterySync(level: Int) {
        if (bleManager.currentState == BleManager.ConnectionState.CONNECTED) {
            bleManager.sendData(DeviceSyncHelper.buildTimeBatteryPayload(this, level))
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ESP32SmartNotif:BleWakeLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.service_channel_description)
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildForegroundNotification(content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BleForegroundService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_stop, getString(R.string.btn_stop_service), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        bleManager.removeListener(this)
        unregisterBatteryReceiver()
        releaseWakeLock()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyServiceState()
        super.onDestroy()
    }

    override fun onDataReceived(data: String) {}
    override fun onDataSent(data: String, success: Boolean) {}
    override fun onBind(intent: Intent?): IBinder? = null
}
