package com.uniplay.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.app.Activity
import android.content.pm.PackageManager
import android.animation.ObjectAnimator
import android.graphics.Color
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.app.AlertDialog
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.uniplay.core.*

/**
 * Universal projection activity.
 * Hosts a single SurfaceView for hardware-accelerated rendering,
 * displays an interactive first-boot wireless connection guide,
 * and coordinates the projection session with the Android lifecycle.
 */
class ProjectionActivity : Activity() {

    companion object {
        private const val TAG = "ProjectionActivity"
        private const val PERMISSION_REQ_CODE = 101
        private const val VIDEO_WIDTH = VideoLayout.CANVAS_WIDTH.toFloat()
        private const val VIDEO_HEIGHT = VideoLayout.CANVAS_HEIGHT.toFloat()
        private const val ACTION_USB_PERMISSION = "com.uniplay.app.USB_PERMISSION"
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var guideOverlay: View
    private lateinit var badgeWifiIp: TextView
    private lateinit var badgeBluetooth: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private var dotPulse: ObjectAnimator? = null
    private lateinit var btnConnectPhone: Button

    private lateinit var mediaRenderer: ProjectionMediaRenderer
    private lateinit var sessionController: ProjectionSessionController
    private lateinit var memoryGuard: MemoryGuard

    private var hasStreamStarted = false
    private var permissionsPending = false
    private var hasPromptedDiscoverable = false
    private var stage = 0
    private lateinit var pulse: PulseView
    private lateinit var stepBadges: List<TextView>
    private lateinit var stepTitles: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_projection)

        // Make true fullscreen (immersive mode)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = findViewById(R.id.projection_surface)
        // The phone draws a fixed 16:9 picture. Fit it inside the screen instead of
        // stretching it, which turns circles into ovals on any other aspect ratio.
        (surfaceView.parent as View).addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            fitSurface(v.width, v.height)
        }
        guideOverlay = findViewById(R.id.guide_overlay)
        badgeWifiIp = findViewById(R.id.badge_wifi_ip)
        badgeBluetooth = findViewById(R.id.badge_bluetooth)
        statusText = findViewById(R.id.text_status)
        statusDot = findViewById(R.id.status_dot)
        startDotPulse()
        btnConnectPhone = findViewById(R.id.btn_connect_phone)
        pulse = findViewById(R.id.pulse)
        stepBadges = listOf(R.id.step1_badge, R.id.step2_badge, R.id.step3_badge).map { findViewById<TextView>(it) }
        stepTitles = listOf(R.id.step1_title, R.id.step2_title, R.id.step3_title).map { findViewById<TextView>(it) }
        setStage(1, animate = false)
        playEntrance()
        val displayMode = findViewById<Button>(R.id.btn_display_mode)
        fun showDisplayMode() {
            displayMode.text = if (VideoLayout.isFitScreen(this)) "Display: Fit screen" else "Display: Wide 16:9"
        }
        showDisplayMode()
        displayMode.setOnClickListener {
            // Takes effect on the next connection, when the phone is told the shape again.
            VideoLayout.setFitScreen(this, !VideoLayout.isFitScreen(this))
            showDisplayMode()
            (surfaceView.parent as View).let { fitSurface(it.width, it.height) }
        }
        listOf(btnConnectPhone, findViewById<View>(R.id.btn_hotspot_settings), displayMode).forEach(::addPressEffect)

        mediaRenderer = ProjectionMediaRenderer(
            isForceMediaStream = { AudioSettings.isForceMediaStream(this) },
            onFirstFrameRendered = { width, height ->
                Log.i(TAG, "First video frame rendered (${width}x$height). Projection is live.")
                runOnUiThread { hideGuideOverlay() }
            },
            onSessionEnded = ::onSessionTerminated
        )
        surfaceView.holder.addCallback(mediaRenderer)

        memoryGuard = MemoryGuard(this)
        sessionController = ProjectionSessionController(this, mediaRenderer) { status, color ->
            runOnUiThread {
                statusText.text = status
                statusDot.backgroundTintList = ColorStateList.valueOf(color)
                setStage(stageFor(status))
            }
        }
        sessionController.onHotspotSetupNeeded = {
            // While the permission dialog is up the handoff is retried when it closes.
            runOnUiThread { if (!permissionsPending) showHotspotDialog() }
        }
        sessionController.onResolvedIpChanged = { ip ->
            runOnUiThread { badgeWifiIp.text = "Wi-Fi  $ip:${WirelessServer.PORT_MAIN}" }
        }

        // Populate live network info into guide badges
        val localIp = NetworkUtils.getLocalIpAddress(this)
        val btName = sessionController.getBluetoothDeviceName()
        badgeWifiIp.text = "Wi-Fi  $localIp:${WirelessServer.PORT_MAIN}"
        badgeBluetooth.text = "Bluetooth  $btName"

        checkAndRequestPermissions()
        sessionController.startWirelessServices(localIp, btName)
        if (!permissionsPending) maybePromptDiscoverableIfUnpaired()
        findViewById<View>(R.id.btn_hotspot_settings).setOnClickListener { showHotspotDialog() }

        // Manual fallback: connect straight to a phone that runs its own head unit server
        btnConnectPhone.setOnClickListener {
            val currentGatewayIp = NetworkUtils.getGatewayIp(this)
            sessionController.connectDirectToPhone(
                manual = true,
                gatewayIp = currentGatewayIp,
                hasStreamStarted = hasStreamStarted
            )
        }

        registerUsbPermissionReceiver()
        handleUsbIntent(intent)

        Log.i(TAG, "ProjectionActivity initialized. ${memoryGuard.dumpMemoryStats()}")
    }

    override fun onResume() {
        super.onResume()
        if (!permissionsPending && !hasStreamStarted) {
            sessionController.ensureHotspotActive()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) return
            usbDeviceFrom(intent)?.let { sessionController.onUsbDeviceAttached(it) }
        }
    }

    private fun registerUsbPermissionReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    @Suppress("DEPRECATION")
    private fun usbDeviceFrom(intent: Intent): UsbDevice? =
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    /** A phone plugged in: use it if we may, otherwise ask Android for permission first. */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = usbDeviceFrom(intent) ?: return
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usb.hasPermission(device)) {
            sessionController.onUsbDeviceAttached(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
            )
            usb.requestPermission(device, pending)
        }
    }

    /**
     * Sizes the video so the part of the phone's canvas that holds its UI fills the
     * screen exactly. The phone was told to leave the rest empty (see VideoLayout), so
     * on any screen shape nothing is stretched, squashed or letterboxed.
     */
    private fun fitSurface(parentW: Int, parentH: Int) {
        if (parentW <= 0 || parentH <= 0) return
        val (w, h) = VideoLayout.surfaceSize(
            parentW, parentH, VideoLayout.marginsFor(this), letterbox = !VideoLayout.isFitScreen(this)
        )
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width == w && lp.height == h && lp.gravity == Gravity.CENTER) return
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp
    }

    /** Maps what the controller reports onto the three on-screen steps. */
    private fun stageFor(status: String): Int = when {
        status.contains("Bluetooth handshake", true) -> 2
        status.contains("Connect", true) && !status.contains("Disconnected", true) ||
            status.contains("Starting Android Auto", true) ||
            status.contains("Switching", true) -> 3
        else -> 1
    }

    /** Done steps get a tick, the current one lights up, later ones stay dim. */
    private fun setStage(newStage: Int, animate: Boolean = true) {
        val changed = newStage != stage
        stage = newStage
        stepBadges.forEachIndexed { i, badge ->
            val step = i + 1
            when {
                step < stage -> {
                    badge.setBackgroundResource(R.drawable.bg_step_done)
                    badge.setTextColor(Color.parseColor("#5FD0A5"))
                    badge.text = "✓"
                }
                step == stage -> {
                    badge.setBackgroundResource(R.drawable.bg_step_active)
                    badge.setTextColor(Color.parseColor("#0B0E14"))
                    badge.text = step.toString()
                }
                else -> {
                    badge.setBackgroundResource(R.drawable.bg_step_number)
                    badge.setTextColor(Color.parseColor("#8FB2FF"))
                    badge.text = step.toString()
                }
            }
            stepTitles[i].alpha = if (step <= stage) 1f else 0.5f
            if (animate && changed && step == stage) popIn(badge)
        }
        pulse.setBusy(stage >= 2)
        pulse.setAccent(Color.parseColor(if (stage >= 3) "#5FD0A5" else "#8FB2FF"))
    }

    private fun popIn(view: View) {
        view.scaleX = 0.6f
        view.scaleY = 0.6f
        view.animate().scaleX(1f).scaleY(1f).setDuration(280)
            .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
    }

    /** The panels slide up and fade in one after the other when the screen opens. */
    private fun playEntrance() {
        val offset = 24 * resources.displayMetrics.density
        listOf(R.id.panel_state, R.id.card_steps).forEachIndexed { i, id ->
            findViewById<View>(id).apply {
                alpha = 0f
                translationY = offset
                animate().alpha(1f).translationY(0f).setStartDelay(120L * i + 100).setDuration(450)
                    .setInterpolator(AccelerateDecelerateInterpolator()).start()
            }
        }
    }

    /** Buttons dip slightly while pressed. */
    @Suppress("ClickableViewAccessibility")
    private fun addPressEffect(view: View) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }

    /** Slow breathing on the status dot: the screen is alive, not frozen. */
    private fun startDotPulse() {
        dotPulse = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.3f).apply {
            duration = 1400
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
            wanted += Manifest.permission.BLUETOOTH_SCAN
            wanted += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.NEARBY_WIFI_DEVICES
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            permissionsPending = true
            requestPermissions(missing.toTypedArray(), PERMISSION_REQ_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_CODE) {
            permissionsPending = false
            badgeBluetooth.text = "Bluetooth  ${sessionController.getBluetoothDeviceName()}"
            // Bluetooth just became usable, so the handoff can start for real now.
            sessionController.startAndroidAutoHandoff(NetworkUtils.getLocalIpAddress(this))
            maybePromptDiscoverableIfUnpaired()
        }
    }

    /** Makes an unpaired device visible over Bluetooth once so the user does not have to hunt in settings. */
    private fun maybePromptDiscoverableIfUnpaired() {
        if (hasPromptedDiscoverable) return
        if (sessionController.hotspot.mode == HotspotMode.CLIENT_GATEWAY) return
        if (!sessionController.isBluetoothEnabled() || sessionController.hasBondedBluetoothDevices()) return
        hasPromptedDiscoverable = true
        sessionController.requestBluetoothDiscoverable(this)
    }

    /** Configures hotspot mode, phone-to-phone Bluetooth options, and audio stream routing. */
    private fun showHotspotDialog() {
        val pad = (18 * resources.displayMetrics.density).toInt()
        val currentMode = sessionController.hotspot.mode

        val modeLabel = TextView(this).apply {
            text = "Wi-Fi hotspot mode"
            setPadding(0, 0, 0, pad / 4)
        }
        val radioAuto = RadioButton(this).apply {
            text = "Auto (Head unit local hotspot)"
            tag = HotspotMode.AUTO
        }
        val radioPhoneHotspot = RadioButton(this).apply {
            text = "Phone hotspot (This phone hosts hotspot)"
            tag = HotspotMode.PHONE_HOTSPOT
        }
        val radioClientGateway = RadioButton(this).apply {
            text = "Join phone's hotspot (Connect to gateway)"
            tag = HotspotMode.CLIENT_GATEWAY
        }
        val modeGroup = RadioGroup(this).apply {
            addView(radioAuto)
            addView(radioPhoneHotspot)
            addView(radioClientGateway)
            when (currentMode) {
                HotspotMode.AUTO -> check(radioAuto.id)
                HotspotMode.PHONE_HOTSPOT -> check(radioPhoneHotspot.id)
                HotspotMode.CLIENT_GATEWAY -> check(radioClientGateway.id)
            }
        }

        val ssid = EditText(this).apply {
            hint = "Hotspot name (SSID)"
            setText(sessionController.hotspot.ssid)
            setSingleLine()
        }
        val psk = EditText(this).apply {
            hint = "Hotspot password (8+ characters)"
            setText(sessionController.hotspot.psk)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val bssid = EditText(this).apply {
            hint = "Hotspot BSSID (optional, AA:BB:CC:DD:EE:FF)"
            setText(sessionController.hotspot.bssid)
            setSingleLine()
        }
        val btnSystemHotspot = Button(this).apply {
            text = "Open system hotspot settings"
            isAllCaps = false
            setOnClickListener { openSystemHotspotSettings() }
        }

        val btCheck = CheckBox(this).apply {
            text = "Aggressive Bluetooth RFCOMM (phone-to-phone)"
            isChecked = sessionController.hotspot.aggressiveBluetooth
        }
        val btnBtDiscoverable = Button(this).apply {
            text = "Make Bluetooth discoverable (5 min)"
            isAllCaps = false
            setOnClickListener { sessionController.requestBluetoothDiscoverable(this@ProjectionActivity) }
        }

        val audioCheck = CheckBox(this).apply {
            text = "Force media stream (STREAM_MUSIC) for all audio"
            isChecked = AudioSettings.isForceMediaStream(this@ProjectionActivity)
        }

        fun updateCredentialVisibility(mode: HotspotMode) {
            val showCreds = mode != HotspotMode.CLIENT_GATEWAY
            ssid.isEnabled = showCreds
            psk.isEnabled = showCreds
            bssid.isEnabled = showCreds
            btnSystemHotspot.visibility = if (mode == HotspotMode.PHONE_HOTSPOT) View.VISIBLE else View.GONE
        }
        updateCredentialVisibility(currentMode)
        modeGroup.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<RadioButton>(checkedId)?.tag as? HotspotMode ?: HotspotMode.AUTO
            updateCredentialVisibility(selected)
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(modeLabel)
            addView(modeGroup)
            addView(ssid)
            addView(psk)
            addView(bssid)
            addView(btnSystemHotspot)
            addView(btCheck)
            addView(btnBtDiscoverable)
            addView(audioCheck)
        }
        val container = ScrollView(this).apply { addView(form) }

        AlertDialog.Builder(this)
            .setTitle("Connection & audio settings")
            .setMessage("Use Auto for car head units. For phone-to-phone, turn on this phone's hotspot, enter its SSID & password, and enable STREAM_MUSIC if audio plays through the earpiece.")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset to Auto") { _, _ ->
                sessionController.hotspot.save(HotspotMode.AUTO, "", "", false)
                AudioSettings.setForceMediaStream(this, false)
                sessionController.startAndroidAutoHandoff(NetworkUtils.getLocalIpAddress(this))
            }
            .setPositiveButton("Save") { _, _ ->
                val selectedMode = modeGroup.findViewById<RadioButton>(modeGroup.checkedRadioButtonId)
                    ?.tag as? HotspotMode ?: HotspotMode.AUTO
                sessionController.hotspot.save(
                    mode = selectedMode,
                    ssid = ssid.text.toString(),
                    psk = psk.text.toString(),
                    aggressiveBluetooth = btCheck.isChecked,
                    bssid = bssid.text.toString()
                )
                AudioSettings.setForceMediaStream(this, audioCheck.isChecked)
                sessionController.startAndroidAutoHandoff(NetworkUtils.getLocalIpAddress(this))
            }
            .show()
    }

    private fun openSystemHotspotSettings() {
        val candidates = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun hideGuideOverlay() {
        if (guideOverlay.visibility != View.GONE) {
            guideOverlay.animate().alpha(0f).setDuration(250).withEndAction {
                guideOverlay.visibility = View.GONE
                dotPulse?.cancel()
            }.start()
            hasStreamStarted = true
            Log.i(TAG, "First video frame rendered. Connection guide overlay hidden.")
        }
    }

    private fun onSessionTerminated(reason: String) {
        runOnUiThread {
            hasStreamStarted = false
            sessionController.onSessionTerminated()
            guideOverlay.animate().cancel()
            guideOverlay.alpha = 1f
            guideOverlay.visibility = View.VISIBLE
            if (dotPulse?.isRunning != true) startDotPulse()
            statusText.text = "Disconnected: $reason. Waiting for your phone"
            setStage(1)
            statusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F07C7C"))
        }
    }

    // Intercept at dispatch time so the SurfaceView can never swallow the gesture.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!hasStreamStarted) {
            return super.dispatchTouchEvent(event)
        }

        val viewW = surfaceView.width.toFloat()
        val viewH = surfaceView.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) {
            return super.dispatchTouchEvent(event)
        }

        // Only single-finger input is mapped; a second finger would send the phone a
        // pointer-down it has no pointer data for.
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_UP
        ) return true

        // The picture may be letterboxed, so measure from the surface's own corner.
        val origin = IntArray(2)
        surfaceView.getLocationInWindow(origin)
        val videoX = ((event.rawX - origin[0]) / viewW * VIDEO_WIDTH).coerceIn(0f, VIDEO_WIDTH - 1f)
        val videoY = ((event.rawY - origin[1]) / viewH * VIDEO_HEIGHT).coerceIn(0f, VIDEO_HEIGHT - 1f)

        sessionController.sendTouchEvent(event.actionMasked, videoX, videoY)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (hasStreamStarted && event?.repeatCount == 0) sessionController.sendKeyEvent(keyCode, true)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (hasStreamStarted) sessionController.sendKeyEvent(keyCode, false)
        return super.onKeyUp(keyCode, event)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "System low memory signal received!")
        memoryGuard.checkAndCompactMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        dotPulse?.cancel()
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (_: IllegalArgumentException) {
        }
        sessionController.stop()
        mediaRenderer.stop()
    }
}
