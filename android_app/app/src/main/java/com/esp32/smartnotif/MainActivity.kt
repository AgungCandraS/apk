package com.esp32.smartnotif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.esp32.smartnotif.ble.BleManager
import com.esp32.smartnotif.databinding.ActivityMainBinding
import com.esp32.smartnotif.model.BleDeviceItem
import com.esp32.smartnotif.model.NotifLogItem
import com.esp32.smartnotif.service.BleForegroundService
import com.esp32.smartnotif.service.NotificationReceiverService
import com.esp32.smartnotif.service.WeatherWorker
import com.esp32.smartnotif.ui.BleDeviceAdapter
import com.esp32.smartnotif.ui.LogAdapter
import com.esp32.smartnotif.ui.GlassSurfaceDrawable
import com.esp32.smartnotif.utils.AppFilterManager
import com.esp32.smartnotif.utils.DeviceSyncHelper
import com.esp32.smartnotif.utils.PermissionHelper
import com.esp32.smartnotif.utils.WeatherManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), BleManager.BleStateListener, BleManager.BleDiscoveryListener {

    companion object {
        private const val TAB_MENU = 0
        private const val TAB_TOOLS = 1
        private const val TAB_SETTING = 2
        private const val TAB_ACTIVITY = 3
        private const val SWIPE_THRESHOLD_DP = 48
        private const val SWIPE_VELOCITY_THRESHOLD_DP = 140
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var appFilterManager: AppFilterManager
    private val logAdapter = LogAdapter()
    private lateinit var bleDeviceAdapter: BleDeviceAdapter

    private var currentTab = TAB_MENU
    private var updatingNavigation = false
    private val activityBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = switchToMenu()
    }
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var startServiceAfterPermission = false
    private var startScanAfterPermission = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Izin Bluetooth aktif", Toast.LENGTH_SHORT).show()
            if (startServiceAfterPermission || BleForegroundService.isEnabled(this)) {
                startBackgroundService()
            }
            if (startScanAfterPermission) {
                bleManager.startDiscovery(binding.switchFilterEspOnly.isChecked)
            }
        } else {
            Toast.makeText(this, "Izin diperlukan untuk menghubungkan ke ESP32", Toast.LENGTH_LONG).show()
        }
        startServiceAfterPermission = false
        startScanAfterPermission = false
        updateServiceControls()
    }

    // Receiver untuk menerima update log notifikasi secara langsung dari service
    private val logBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BleForegroundService.ACTION_STATE_CHANGED) {
                updateServiceControls()
                return
            }
            if (intent?.action == NotificationReceiverService.ACTION_NEW_NOTIF_LOG) {
                val appCode = intent.getStringExtra(NotificationReceiverService.EXTRA_APP_CODE) ?: "NOTIF"
                val sender = intent.getStringExtra(NotificationReceiverService.EXTRA_SENDER) ?: ""
                val message = intent.getStringExtra(NotificationReceiverService.EXTRA_MESSAGE) ?: ""
                val timeStr = intent.getStringExtra(NotificationReceiverService.EXTRA_TIME) ?: ""

                val item = NotifLogItem(appCode, sender, message, timeStr)
                addLogItem(item)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        GlassSurfaceDrawable.install(binding.bottomNav, 22 * resources.displayMetrics.density, tone = 3)
        startServiceAfterPermission = savedInstanceState?.getBoolean("start_service_after_permission") == true
        startScanAfterPermission = savedInstanceState?.getBoolean("start_scan_after_permission") == true

        bleManager = BleManager.getInstance(this)
        appFilterManager = AppFilterManager(this)

        setupUI()
        setupListeners()
        setupExpandablePanels(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, activityBack)
        switchTab(savedInstanceState?.getInt("selected_tab") ?: TAB_MENU, animated = false)
        checkAndRequestPermissions()

        // Jadwalkan update cuaca berkala setiap 30-45 menit via WorkManager
        WeatherWorker.schedulePeriodicWork(this)

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("selected_tab", currentTab)
        outState.putBoolean("start_service_after_permission", startServiceAfterPermission)
        outState.putBoolean("start_scan_after_permission", startScanAfterPermission)
        listOf(binding.detailsTime, binding.detailsWeather, binding.detailsNav, binding.detailsHelp).forEach { panel ->
            outState.putBoolean("panel_${panel.id}", panel.visibility == View.VISIBLE)
        }
    }

    private fun setupExpandablePanels(savedState: Bundle?) {
        listOf(
            Triple(binding.headerTime, binding.detailsTime, binding.chevronTime),
            Triple(binding.headerWeather, binding.detailsWeather, binding.chevronWeather),
            Triple(binding.headerNav, binding.detailsNav, binding.chevronNav),
            Triple(binding.headerHelp, binding.detailsHelp, binding.chevronHelp)
        ).forEach { (header, details, chevron) ->
            fun updatePanel(expanded: Boolean) {
                details.visibility = if (expanded) View.VISIBLE else View.GONE
                chevron.rotation = if (expanded) 180f else 0f
                ViewCompat.setStateDescription(header, getString(
                    if (expanded) R.string.panel_expanded else R.string.panel_collapsed
                ))
                ViewCompat.replaceAccessibilityAction(
                    header,
                    androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    getString(if (expanded) R.string.btn_collapse_panel else R.string.btn_expand_panel)
                ) { _, _ -> header.performClick() }
            }
            updatePanel(savedState?.getBoolean("panel_${details.id}") == true)
            header.setOnClickListener { updatePanel(details.visibility != View.VISIBLE) }
        }
    }

    private fun animationsEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            android.provider.Settings.Global.getFloat(
                contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) != 0f
        }

    override fun onResume() {
        super.onResume()
        bleManager.addListener(this)
        bleManager.addDiscoveryListener(this)
        checkNotificationAccess()
        syncSettingSwitches()
        updateSettingBleDeviceCard()
        updateTestPreviews()

        val filter = IntentFilter(NotificationReceiverService.ACTION_NEW_NOTIF_LOG).apply {
            addAction(BleForegroundService.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, logBroadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (BleForegroundService.isEnabled(this) && PermissionHelper.hasAllRuntimePermissions(this)) {
            BleForegroundService.startService(this)
        }
        updateServiceControls()
    }

    override fun onPause() {
        super.onPause()
        bleManager.removeListener(this)
        bleManager.removeDiscoveryListener(this)
        try {
            unregisterReceiver(logBroadcastReceiver)
        } catch (_: Exception) {}
    }

    private fun setupUI() {
        if (resources.configuration.fontScale >= 1.5f) {
            binding.homeDeviceIcon.visibility = View.GONE
            val margins = binding.homeDeviceIdentity.layoutParams as android.view.ViewGroup.MarginLayoutParams
            margins.marginStart = 0
            binding.homeDeviceIdentity.layoutParams = margins
        }
        binding.rvNotifLogs.layoutManager = LinearLayoutManager(this)
        binding.rvNotifLogs.adapter = logAdapter
        updateEmptyLogsView()

        bleDeviceAdapter = BleDeviceAdapter { selectedDeviceItem ->
            onBleDeviceSelected(selectedDeviceItem)
        }
        binding.rvBleDevices.layoutManager = LinearLayoutManager(this)
        binding.rvBleDevices.adapter = bleDeviceAdapter

        binding.bottomNav.addOnLayoutChangeListener { navigation, _, _, _, _, _, _, _, _ ->
            val clearance = navigation.height + (40 * resources.displayMetrics.density).toInt()
            tabViews().forEach { scroll ->
                if (scroll.paddingBottom != clearance) {
                    scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, clearance)
                }
            }
        }

        // Sinkronisasi awal saklar setting & kartu BLE
        syncSettingSwitches()
        updateSettingBleDeviceCard()
        updateTestPreviews()
    }

    private fun setupListeners() {
        binding.btnHomeConnect.setOnClickListener {
            switchToSetting(animated = false)
            binding.layoutSetting.post {
                binding.layoutSetting.smoothScrollTo(0, binding.panelBluetooth.top)
            }
            if (bleManager.currentState == BleManager.ConnectionState.DISCONNECTED && !bleManager.isDiscovering) {
                binding.btnMenuRescanBle.performClick()
            }
        }
        binding.btnViewLogs.setOnClickListener { switchTab(TAB_ACTIVITY) }
        binding.btnBackActivity.setOnClickListener { switchToMenu() }

        binding.btnToggleService.setOnClickListener {
            if (BleForegroundService.isRunning) {
                BleForegroundService.stopService(this)
                updateServiceControls()
            } else {
                startBackgroundService()
            }
        }
        // Navigasi tab menu dan setting
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (updatingNavigation) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_home -> {
                    if (currentTab == TAB_MENU) {
                        binding.layoutMenu.smoothScrollTo(0, 0)
                    } else {
                        switchToMenu(animated = true)
                    }
                    true
                }
                R.id.nav_tools -> {
                    if (currentTab == TAB_TOOLS) binding.layoutTools.smoothScrollTo(0, 0)
                    else switchTab(TAB_TOOLS)
                    true
                }
                R.id.nav_settings -> {
                    if (currentTab == TAB_SETTING) {
                        binding.layoutSetting.smoothScrollTo(0, 0)
                    } else {
                        switchToSetting(animated = true)
                    }
                    true
                }
                else -> false
            }
        }

        // Panel pengujian fitur
        binding.btnSendTest.setOnClickListener {
            val inputSender = binding.etTestSender.text?.toString()?.trim()
            val sender = if (!inputSender.isNullOrEmpty()) inputSender else getString(R.string.sample_sender)
            val msg = binding.etTestMessage.text?.toString()?.trim() ?: ""

            if (msg.isEmpty()) {
                binding.etTestMessage.error = getString(R.string.send_message_required)
                binding.etTestMessage.requestFocus()
                return@setOnClickListener
            }

            binding.etTestMessage.error = null
            val payload = "[WA] $sender: $msg"
            queueTest(payload, "WA", sender, msg)


        }

        // Sinkronisasi jam dan baterai
        binding.btnTestSyncTime.setOnClickListener {
            val payload = DeviceSyncHelper.buildTimeBatteryPayload(this)
            binding.tvTimeBatteryPreview.text = payload
            queueTest(payload, "TIME", getString(R.string.panel_title_test_time), payload)
        }

        // Prakiraan cuaca live GPS
        binding.btnTestSyncWeather.setOnClickListener {
            binding.btnTestSyncWeather.isEnabled = false
            binding.btnTestSyncWeather.setText(R.string.weather_loading)
            binding.tvSendFeedback.setText(R.string.weather_loading)
            lifecycleScope.launch {
                val payload = WeatherManager.fetchWeatherPayload(this@MainActivity)
                if (payload != null) {
                    binding.tvWeatherPreview.text = payload
                    queueTest(payload, "WEATHER", getString(R.string.panel_title_test_weather), payload)
                } else {
                    binding.tvSendFeedback.setText(R.string.weather_failed)
                }
                binding.btnTestSyncWeather.isEnabled = true
                binding.btnTestSyncWeather.setText(R.string.btn_send_weather_live)
            }
        }

        // Prakiraan cuaca sampel offline
        binding.btnTestSampleWeather.setOnClickListener {
            val sampleWeather = "[WEATHER] Jakarta|30|1|33|24|6|1012"
            binding.tvWeatherPreview.text = sampleWeather
            queueTest(sampleWeather, "WEATHER", getString(R.string.weather_example), sampleWeather)
        }

        // Uji navigasi Google Maps
        binding.etNavInstruction.doAfterTextChanged { updateNavPreview() }
        binding.etNavDistance.doAfterTextChanged { updateNavPreview() }
        binding.etNavEta.doAfterTextChanged { updateNavPreview() }

        binding.btnTestNav.setOnClickListener {
            val navPayload = updateNavPreview()
            queueTest(navPayload, "NAV", getString(R.string.panel_title_test_nav), navPayload)
        }

        binding.btnTestNavStop.setOnClickListener {
            val stopPayload = "[NAV] STOP"
            binding.tvNavPreview.text = stopPayload
            queueTest(stopPayload, "NAV", getString(R.string.btn_stop_nav), stopPayload)
        }

        // Hapus log notifikasi
        binding.btnClearLogs.setOnClickListener {
            logAdapter.clear()
            updateEmptyLogsView()
        }

        // Akses setelan notifikasi sistem
        binding.btnMenuNotifAccess.setOnClickListener {
            PermissionHelper.openNotificationAccessSettings(this)
        }

        // Setelan info aplikasi
        binding.btnMenuAppInfo.setOnClickListener {
            PermissionHelper.openAppDetailsSettings(this)
            Toast.makeText(this, "Jika titik tiga tidak muncul, buka Pengaturan HP -> Aplikasi -> Lihat semua", Toast.LENGTH_LONG).show()
        }

        // C. Saklar Filter Aplikasi (ada di Menu Utama)
        binding.switchWA.setOnCheckedChangeListener { _, isChecked ->
            appFilterManager.isWhatsAppEnabled = isChecked
            updateFilterSummary()
        }
        binding.switchTelegram.setOnCheckedChangeListener { _, isChecked ->
            appFilterManager.isTelegramEnabled = isChecked
            updateFilterSummary()
        }
        binding.switchSMS.setOnCheckedChangeListener { _, isChecked ->
            appFilterManager.isSmsEnabled = isChecked
            updateFilterSummary()
        }
        binding.switchIG.setOnCheckedChangeListener { _, isChecked ->
            appFilterManager.isInstagramEnabled = isChecked
            updateFilterSummary()
        }

        // D. Tombol Mulai / Hentikan Pindai Bluetooth ESP32
        binding.btnMenuRescanBle.setOnClickListener {
            if (!PermissionHelper.hasAllRuntimePermissions(this)) {
                startScanAfterPermission = true
                requestPermissionLauncher.launch(PermissionHelper.getRequiredPermissions())
                return@setOnClickListener
            }

            if (bleManager.isDiscovering) {
                bleManager.stopDiscovery()
            } else {
                val filterEsp = binding.switchFilterEspOnly.isChecked
                bleManager.startDiscovery(filterEsp)
            }
        }

        // E. Saklar Filter Khusus ESP32
        binding.switchFilterEspOnly.setOnCheckedChangeListener { _, isChecked ->
            if (bleManager.isDiscovering) {
                bleManager.startDiscovery(isChecked)
            }
        }

        // F. Tombol Putuskan / Batalkan Koneksi Bluetooth
        binding.btnDisconnectBle.setOnClickListener {
            val devName = bleManager.connectedDeviceName ?: bleManager.savedDeviceName ?: getString(R.string.device_target_name)
            bleManager.disconnect()
            Toast.makeText(this, "Koneksi Bluetooth ke $devName telah diputus", Toast.LENGTH_SHORT).show()
            updateSettingBleDeviceCard()
        }

        // G. Tombol Hubungkan Kembali ke Perangkat Tersimpan
        binding.btnReconnectSavedBle.setOnClickListener {
            if (!PermissionHelper.hasAllRuntimePermissions(this)) {
                requestPermissionLauncher.launch(PermissionHelper.getRequiredPermissions())
                return@setOnClickListener
            }
            Toast.makeText(this, "Menghubungkan kembali ke ESP32...", Toast.LENGTH_SHORT).show()
            BleForegroundService.startService(this)
            bleManager.startScanAndConnect()
            updateSettingBleDeviceCard()
        }

        // H. Tombol Lupakan Perangkat Tersimpan
        binding.btnForgetSavedBle.setOnClickListener {
            bleManager.savedDeviceAddress = null
            bleManager.savedDeviceName = null
            Toast.makeText(this, "Perangkat tersimpan telah dilupakan", Toast.LENGTH_SHORT).show()
            updateSettingBleDeviceCard()
        }
    }

    private fun tabViews() = listOf(
        binding.layoutMenu, binding.layoutTools, binding.layoutSetting, binding.layoutActivity
    )

    private fun switchToMenu(animated: Boolean = true) = switchTab(TAB_MENU, animated)
    private fun switchToSetting(animated: Boolean = true) = switchTab(TAB_SETTING, animated)

    private fun switchTab(requestedTab: Int, animated: Boolean = true) {
        val target = requestedTab.coerceIn(TAB_MENU, TAB_ACTIVITY)
        val previous = currentTab
        currentTab = target
        activityBack.isEnabled = target == TAB_ACTIVITY
        updatingNavigation = true
        binding.bottomNav.selectedItemId = when (target) {
            TAB_TOOLS -> R.id.nav_tools
            TAB_SETTING -> R.id.nav_settings
            else -> R.id.nav_home
        }
        updatingNavigation = false
        tabViews().forEachIndexed { index, scroll ->
            scroll.animate().cancel()
            scroll.translationX = 0f
            scroll.alpha = 1f
            scroll.visibility = if (index == target) View.VISIBLE else View.GONE
        }
        val visible = tabViews()[target]
        if (animated && previous != target && animationsEnabled()) {
            visible.translationX = (if (target > previous) 12f else -12f) * resources.displayMetrics.density
            visible.alpha = .65f
            visible.animate().translationX(0f).alpha(1f).setDuration(180)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        if (target == TAB_SETTING) {
            syncSettingSwitches()
            updateSettingBleDeviceCard()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val dragThreshold = SWIPE_THRESHOLD_DP * density

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTouchX = ev.rawX
                startTouchY = ev.rawY
            }
            MotionEvent.ACTION_UP -> {
                val deltaX = ev.rawX - startTouchX
                val deltaY = ev.rawY - startTouchY

                val isInsideActiveInput = isTouchInsideActiveInput(ev.rawX, ev.rawY)

                if (!isInsideActiveInput &&
                    Math.abs(deltaX) > Math.abs(deltaY) * 1.35f &&
                    Math.abs(deltaX) > dragThreshold
                ) {
                    if (currentTab != TAB_ACTIVITY) {
                        val next = (currentTab + if (deltaX < 0) 1 else -1).coerceIn(TAB_MENU, TAB_SETTING)
                        switchTab(next)
                    }
                }
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun isTouchInsideActiveInput(rawX: Float, rawY: Float): Boolean {
        val focused = currentFocus ?: return false
        if (focused is EditText) {
            val loc = IntArray(2)
            focused.getLocationOnScreen(loc)
            return rawX >= loc[0] && rawX <= loc[0] + focused.width &&
                   rawY >= loc[1] && rawY <= loc[1] + focused.height
        }
        return false
    }

    private fun onBleDeviceSelected(item: BleDeviceItem) {
        if (item.address.equals(bleManager.connectedDeviceAddress, ignoreCase = true) &&
            bleManager.currentState == BleManager.ConnectionState.CONNECTED) {
            val devName = item.name.ifBlank { "ESP32" }
            bleManager.disconnect()
            Toast.makeText(this, "Koneksi Bluetooth ke $devName telah diputus", Toast.LENGTH_SHORT).show()
            updateSettingBleDeviceCard()
            return
        }

        Toast.makeText(this, "Menghubungkan ke ${item.name}...", Toast.LENGTH_SHORT).show()
        BleForegroundService.startService(this)
        bleDeviceAdapter.updateConnectionStatus(null, item.address)
        bleManager.connectToDevice(item.device)
        updateSettingBleDeviceCard()
    }

    private fun syncSettingSwitches() {
        binding.switchWA.isChecked = appFilterManager.isWhatsAppEnabled
        binding.switchTelegram.isChecked = appFilterManager.isTelegramEnabled
        binding.switchSMS.isChecked = appFilterManager.isSmsEnabled
        binding.switchIG.isChecked = appFilterManager.isInstagramEnabled
        updateFilterSummary()
    }

    private fun updateFilterSummary() {
        val enabled = listOf(binding.switchWA, binding.switchTelegram, binding.switchSMS, binding.switchIG).count { it.isChecked }
        binding.tvFilterSummary.text = getString(R.string.filter_summary_format, enabled)
    }

    private fun updateTestPreviews() {
        // 1. Preview Waktu & Baterai
        val timePayload = DeviceSyncHelper.buildTimeBatteryPayload(this)
        binding.tvTimeBatteryPreview.text = timePayload

        // 2. Preview Cuaca (dari cache atau default)
        val savedWeather = WeatherManager.getLastSavedPayload(this)
        binding.tvWeatherPreview.text = savedWeather ?: "[WEATHER] Jakarta|30|1|33|24|6|1012"

        // 3. Preview Navigasi
        updateNavPreview()
    }

    private fun updateNavPreview(): String {
        val instruction = binding.etNavInstruction.text?.toString()?.trim().let {
            if (it.isNullOrEmpty()) "Belok Kanan Jl. Sudirman" else it.replace("|", "-")
        }
        val distance = binding.etNavDistance.text?.toString()?.trim().let {
            if (it.isNullOrEmpty()) "200 m" else it.replace("|", "-")
        }
        val eta = binding.etNavEta.text?.toString()?.trim().let {
            if (it.isNullOrEmpty()) "15 mnt" else it.replace("|", "-")
        }
        val payload = "[NAV] $instruction|$distance|$eta|1"
        binding.tvNavPreview.text = payload
        return payload
    }

    private fun checkAndRequestPermissions() {
        if (!PermissionHelper.hasAllRuntimePermissions(this)) {
            requestPermissionLauncher.launch(PermissionHelper.getRequiredPermissions())
        } else if (BleForegroundService.isEnabled(this)) {
            BleForegroundService.startService(this)
        }
    }

    private fun startBackgroundService() {
        if (!PermissionHelper.hasAllRuntimePermissions(this)) {
            startServiceAfterPermission = true
            requestPermissionLauncher.launch(PermissionHelper.getRequiredPermissions())
            return
        }
        if (!BleForegroundService.startService(this)) {
            Toast.makeText(this, R.string.service_start_failed, Toast.LENGTH_LONG).show()
        }
        updateServiceControls()
    }

    private fun updateServiceControls() {
        val running = BleForegroundService.isRunning
        binding.tvServiceStatus.setText(if (running) R.string.service_active else R.string.service_inactive)
        binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this,
            if (running) R.color.status_connected else R.color.text_secondary))
        binding.btnToggleService.setText(if (running) R.string.btn_stop_service else R.string.btn_start_service)
        binding.btnToggleService.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        updateHomeReadiness()
    }

    private fun checkNotificationAccess() {
        updateHomeReadiness()
        val hasAccess = PermissionHelper.isNotificationAccessGranted(this)
        if (hasAccess) {
            binding.tvNotifStatusBadge.text = getString(R.string.status_notif_active)
            binding.tvNotifStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
        } else {
            binding.tvNotifStatusBadge.text = getString(R.string.status_notif_inactive)
            binding.tvNotifStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_connecting))
        }
    }

    private fun queueTest(payload: String, code: String, sender: String, message: String) {
        val status = getString(if (bleManager.currentState == BleManager.ConnectionState.CONNECTED)
            R.string.send_sending else R.string.send_queued)
        addLogItem(NotifLogItem(code, sender, message,
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), status, payload))
        binding.tvSendFeedback.text = status
        binding.tvSendFeedback.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        bleManager.sendData(payload)
    }

    private fun updateHomeReadiness() {
        binding.tvHomeReadiness.text = getString(R.string.home_readiness_format,
            getString(if (BleForegroundService.isRunning) R.string.state_active else R.string.state_inactive),
            getString(if (PermissionHelper.isNotificationAccessGranted(this)) R.string.state_active else R.string.state_inactive))
    }

    private fun addLogItem(item: NotifLogItem) {
        logAdapter.addItem(item)
        updateEmptyLogsView()
    }

    private fun updateEmptyLogsView() {
        val isEmpty = logAdapter.itemCount == 0
        binding.tvEmptyLogs.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvNotifLogs.visibility = if (isEmpty) View.GONE else View.VISIBLE
        val latest = logAdapter.latestItem
        binding.tvRecentMessage.text = latest?.let { "${it.sender}\n${it.message}" } ?: getString(R.string.logs_empty)
        binding.tvRecentMeta.text = latest?.let { listOf(it.appCode, it.timestamp, it.deliveryStatus).filter { part -> part.isNotBlank() }.joinToString(" · ") } ?: ""
        binding.tvRecentMeta.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateSettingBleDeviceCard() {
        val isConnected = bleManager.currentState == BleManager.ConnectionState.CONNECTED
        val isConnecting = bleManager.currentState == BleManager.ConnectionState.CONNECTING
        val devName = bleManager.connectedDeviceName ?: bleManager.savedDeviceName ?: getString(R.string.device_target_name)
        val devAddress = bleManager.connectedDeviceAddress ?: bleManager.savedDeviceAddress

        when {
            isConnected -> {
                binding.viewSettingBleDot.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connected))
                binding.tvSettingDeviceName.text = devName
                binding.tvSettingDeviceAddress.text = "MAC: ${devAddress ?: "Tersambung"} • Terhubung"
                binding.tvSettingBleBadge.text = getString(R.string.badge_connected)
                binding.tvSettingBleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_connected))

                binding.btnDisconnectBle.visibility = View.VISIBLE
                binding.btnDisconnectBle.text = getString(R.string.btn_disconnect_ble)
                binding.btnDisconnectBle.setIconResource(R.drawable.ic_bluetooth_disabled)

                binding.layoutSavedDeviceActions.visibility = View.GONE
            }
            isConnecting -> {
                binding.viewSettingBleDot.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connecting))
                binding.tvSettingDeviceName.text = devName
                binding.tvSettingDeviceAddress.text = "Menghubungkan ke ${devAddress ?: "perangkat"}..."
                binding.tvSettingBleBadge.text = getString(R.string.badge_connecting)
                binding.tvSettingBleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_connecting))

                binding.btnDisconnectBle.visibility = View.VISIBLE
                binding.btnDisconnectBle.text = getString(R.string.btn_cancel_connecting)
                binding.btnDisconnectBle.setIconResource(R.drawable.ic_stop)

                binding.layoutSavedDeviceActions.visibility = View.GONE
            }
            else -> {
                binding.viewSettingBleDot.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_disconnected))
                binding.tvSettingDeviceName.text = if (devAddress != null) "Terputus dari $devName" else getString(R.string.status_no_device)
                binding.tvSettingDeviceAddress.text = if (devAddress != null) "MAC: $devAddress (Siap dihubungkan)" else getString(R.string.desc_start_scan_prompt)
                binding.tvSettingBleBadge.text = getString(R.string.badge_disconnected)
                binding.tvSettingBleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))

                binding.btnDisconnectBle.visibility = View.GONE

                if (devAddress != null) {
                    binding.layoutSavedDeviceActions.visibility = View.VISIBLE
                } else {
                    binding.layoutSavedDeviceActions.visibility = View.GONE
                }
            }
        }

        bleDeviceAdapter.updateConnectionStatus(
            bleManager.connectedDeviceAddress,
            if (isConnecting) bleManager.lastConnectedDevice?.address else null
        )
    }

    // Callback status koneksi Bluetooth
    override fun onStateChanged(state: BleManager.ConnectionState, message: String) {
        runOnUiThread {
            val devName = bleManager.connectedDeviceName ?: bleManager.savedDeviceName ?: getString(R.string.device_target_name)
            when (state) {
                BleManager.ConnectionState.CONNECTED -> {
                    binding.tvBleStatus.text = getString(R.string.status_connected)
                    binding.tvBleDetail.text = "$devName Aktif - Siap Menerima Chat"
                    binding.viewStatusDot.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connected))
                }
                BleManager.ConnectionState.CONNECTING -> {
                    binding.tvBleStatus.text = getString(R.string.status_connecting)
                    binding.tvBleDetail.text = "Menghubungkan ke $devName..."
                    binding.viewStatusDot.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connecting))
                }
                BleManager.ConnectionState.SCANNING -> {
                    binding.tvBleStatus.text = getString(R.string.status_scanning)
                    binding.tvBleDetail.text = "Mencari perangkat $devName di sekitar..."
                    binding.viewStatusDot.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connecting))
                }
                BleManager.ConnectionState.DISCONNECTED -> {
                    binding.tvBleStatus.text = getString(R.string.status_disconnected)
                    binding.tvBleDetail.text = if (message.isNotEmpty()) message else "Koneksi Bluetooth terputus"
                    binding.viewStatusDot.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_disconnected))
                }
            }
            binding.tvHomeDeviceName.text = bleManager.connectedDeviceName ?: bleManager.savedDeviceName
                ?: getString(R.string.home_connect_title)
            binding.tvHomeDeviceAddress.text = bleManager.connectedDeviceAddress ?: bleManager.savedDeviceAddress
                ?: getString(R.string.home_ble_hint)
            binding.btnHomeConnect.setText(if (state == BleManager.ConnectionState.DISCONNECTED)
                R.string.home_find_device else R.string.home_manage_device)
            binding.btnHomeConnect.setIconResource(if (state == BleManager.ConnectionState.DISCONNECTED)
                R.drawable.ic_search else R.drawable.ic_nav_settings)
            updateSettingBleDeviceCard()
        }
    }

    override fun onDataReceived(data: String) {
        runOnUiThread {
            if (data == "ACK_OK") {
                Toast.makeText(this, "ESP32: Pesan ditampilkan di OLED!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDataSent(data: String, success: Boolean) {
        runOnUiThread {
            val label = getString(if (success) R.string.send_sent else R.string.send_failed)
            if (logAdapter.updateDelivery(data, label)) {
                binding.tvSendFeedback.text = label
                binding.tvSendFeedback.setTextColor(ContextCompat.getColor(this,
                    if (success) R.color.status_connected else R.color.status_disconnected))
                updateEmptyLogsView()
            }
        }
    }

    // Callback pemindaian perangkat Bluetooth
    override fun onDiscoveryStarted() {
        runOnUiThread {
            binding.btnMenuRescanBle.text = getString(R.string.btn_stop_scan)
            binding.pbScanLoading.visibility = View.VISIBLE
            binding.tvScanStatusInfo.visibility = View.VISIBLE
            val filterEsp = binding.switchFilterEspOnly.isChecked
            binding.tvScanStatusInfo.text = if (filterEsp) {
                "Memindai modul ESP32-C3 di sekitar..."
            } else {
                "Memindai semua perangkat Bluetooth BLE di sekitar..."
            }
        }
    }

    override fun onDiscoveryFinished() {
        runOnUiThread {
            binding.btnMenuRescanBle.text = getString(R.string.btn_start_scan)
            binding.pbScanLoading.visibility = View.GONE
            binding.tvScanStatusInfo.visibility = View.GONE
        }
    }

    override fun onDeviceFound(devices: List<BleDeviceItem>) {
        runOnUiThread {
            bleDeviceAdapter.setDevices(
                devices,
                bleManager.connectedDeviceAddress,
                if (bleManager.currentState == BleManager.ConnectionState.CONNECTING) bleManager.lastConnectedDevice?.address else null
            )
            val isEmpty = devices.isEmpty()
            binding.layoutEmptyBleDevices.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.rvBleDevices.visibility = if (isEmpty) View.GONE else View.VISIBLE
            if (isEmpty) {
                val filterEsp = binding.switchFilterEspOnly.isChecked
                binding.tvEmptyBleDevicesMsg.text = if (filterEsp) {
                    "Belum ada ESP32 terdeteksi.\nPastikan ESP32-C3 menyala lalu tekan Mulai Pindai."
                } else {
                    "Belum ada perangkat BLE terdeteksi.\nPastikan Bluetooth HP menyala."
                }
            }
        }
    }
}

