package com.toclient.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import com.toclient.android.compat.AndroidCompatibility
import com.toclient.android.compat.CompatibilityIssue
import com.toclient.android.engine.XrayEngineAdapter
import com.toclient.android.persistence.AndroidProfileRepository
import com.toclient.android.subscription.AndroidSubscriptionRepository
import com.toclient.android.subscription.HttpsSubscriptionFetcher
import com.toclient.android.subscription.SubscriptionScheduler
import com.toclient.android.ui.ConnectionOrbView
import com.toclient.android.ui.OrbState
import com.toclient.android.ui.ToIcon
import com.toclient.android.ui.ToIconView
import com.toclient.android.ui.ToPalette
import com.toclient.android.ui.SubscriptionUsageView
import com.toclient.android.ui.add
import com.toclient.android.ui.dp
import com.toclient.android.ui.label
import com.toclient.android.ui.margin
import com.toclient.android.ui.marginRelative
import com.toclient.android.ui.roundedBackground
import com.toclient.android.ui.vertical
import com.toclient.android.vpn.VpnRuntime
import com.toclient.core.backup.BackupPayload
import com.toclient.core.backup.BackupProfile
import com.toclient.core.backup.BackupSubscription
import com.toclient.core.backup.TocBackupCodec
import com.toclient.core.connection.ConnectionState
import com.toclient.core.connection.XrayConfigPlanner
import com.toclient.core.contract.EngineStartRequest
import com.toclient.core.contract.SocksEndpoint
import com.toclient.core.importer.ConfigImporter
import com.toclient.core.importer.ImportedConfig
import com.toclient.core.model.DnsPolicy
import com.toclient.core.model.LatencyTestStatus
import com.toclient.core.model.PerAppMode
import com.toclient.core.model.PerAppPolicy
import com.toclient.core.model.ProfileId
import com.toclient.core.model.ProfileSource
import com.toclient.core.model.ReconnectPolicy
import com.toclient.core.model.RoutingAction
import com.toclient.core.model.RoutingPolicy
import com.toclient.core.model.RoutingRule
import com.toclient.core.model.SensitiveProfileConfig
import com.toclient.core.model.TrafficSnapshot
import com.toclient.core.model.VpnProfile
import com.toclient.core.routing.PolicyValidator
import com.toclient.core.subscription.Subscription
import com.toclient.core.subscription.SubscriptionId
import com.toclient.core.subscription.SubscriptionUpdateResult
import com.toclient.core.subscription.SubscriptionUpdater
import com.google.zxing.BinaryBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.integration.android.IntentIntegrator
import com.toclient.core.subscription.SubscriptionParser
import java.security.MessageDigest
import java.net.InetSocketAddress
import java.net.Proxy
import javax.net.ssl.HttpsURLConnection
import java.util.Locale
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

class MainActivity : Activity() {
    private data class ExitInfo(val ip: String, val location: String, val isp: String)
    private data class InstalledAppChoice(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable,
    )

    private enum class Screen {
        HOME, PROFILES, DIAGNOSTICS, SETTINGS, PROFILE_DETAIL, DNS, ROUTING,
        NETWORK, SUBSCRIPTIONS, BACKUP, ABOUT,
    }

    private enum class PendingDocument { IMPORT, QR_IMAGE, EXPORT_BACKUP, RESTORE_BACKUP }
    private enum class ConfigurationSort { DEFAULT, LATENCY, NAME, FAVORITES }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var profilesRepo: AndroidProfileRepository
    private lateinit var subscriptionsRepo: AndroidSubscriptionRepository
    private val importer = ConfigImporter()
    private val backupCodec = TocBackupCodec()
    private val screens = ArrayDeque<Screen>()
    private var screen = Screen.HOME
    private var profiles = emptyList<VpnProfile>()
    private var subscriptions = emptyList<Subscription>()
    private var selectedProfileId: ProfileId? = null
    private var selectedSubscriptionId: SubscriptionId? = null
    private val expandedSubscriptionIds = mutableSetOf<SubscriptionId>()
    private var pendingConnectId: ProfileId? = null
    private var pendingDocument: PendingDocument? = null
    private var pendingBackupBytes: ByteArray? = null
    private var pendingRestoreBytes: ByteArray? = null
    private var connectionState: ConnectionState = ConnectionState.Idle
    private var traffic: TrafficSnapshot? = null
    private var previousTraffic: TrafficSnapshot? = null
    private var downloadRateBytes = 0L
    private var uploadRateBytes = 0L
    private var lightTheme = false
    private var persian = false
    private var showNotificationTraffic = false
    private var downloadValue: TextView? = null
    private var uploadValue: TextView? = null
    private var trafficCaption: TextView? = null
    private var durationValue: TextView? = null
    private var totalDownloadValue: TextView? = null
    private var totalUploadValue: TextView? = null
    private var quickPingValue: TextView? = null
    private var quickPingBolt: View? = null
    private var quickPingRunning = false
    private var quickPingResult: String? = null
    private var latencyTesting = false
    private var latencyTestCompleted = 0
    private var latencyTestTotal = 0
    private var latencyTestingProfileId: ProfileId? = null
    private var latencyCancelRequested = false
    private var latencyTestJob: Job? = null
    private var configurationSearch = ""
    private var configurationSort = ConfigurationSort.DEFAULT
    private var exitInfo: ExitInfo? = null
    private var exitInfoLoading = false
    private var exitInfoSessionKey: String? = null
    private var loading = true
    private val palette: ToPalette get() = if (lightTheme) ToPalette.Light else ToPalette.Dark

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profilesRepo = AndroidProfileRepository(this)
        subscriptionsRepo = AndroidSubscriptionRepository(this)
        getSharedPreferences(PREFS, MODE_PRIVATE).let {
            lightTheme = it.getBoolean(KEY_LIGHT, false)
            persian = it.getBoolean(KEY_PERSIAN, false)
            showNotificationTraffic = it.getBoolean(KEY_SHOW_NOTIFICATION_TRAFFIC, false)
            it.getString(KEY_SELECTED_PROFILE, null)?.let { raw ->
                runCatching { ProfileId(raw) }.getOrNull()?.let { id -> selectedProfileId = id }
            }
            it.getString(KEY_SELECTED_SUBSCRIPTION, null)?.let { raw ->
                runCatching { SubscriptionId(raw) }.getOrNull()
                    ?.let { id -> selectedSubscriptionId = id }
            }
        }
        configureWindow()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { handleBack() }
        }
        render()
        scope.launch {
            VpnRuntime.state.collectLatest { state ->
                connectionState = state
                state.profileIdOrNull()?.let { activeId ->
                    selectedProfileId = activeId
                    profiles.firstOrNull { it.id == activeId }?.let { activeProfile ->
                        selectedSubscriptionId = (activeProfile.source as? ProfileSource.Subscription)
                            ?.subscriptionId?.let(::SubscriptionId)
                    }
                    persistSelection()
                }
                when (state) {
                    is ConnectionState.Connected -> loadExitInfoFor(state)
                    ConnectionState.Idle, is ConnectionState.Failed -> {
                        exitInfo = null
                        exitInfoLoading = false
                        exitInfoSessionKey = null
                        quickPingResult = null
                        quickPingRunning = false
                    }
                    else -> Unit
                }
                if (screen == Screen.HOME || screen == Screen.DIAGNOSTICS) render()
            }
        }
        scope.launch {
            VpnRuntime.traffic.collectLatest { snapshot ->
                val previous = previousTraffic
                if (snapshot != null && previous != null) {
                    val elapsed = (snapshot.capturedAtMillis - previous.capturedAtMillis).coerceAtLeast(1)
                    downloadRateBytes = ((snapshot.engineTotal.downloadedBytes -
                        previous.engineTotal.downloadedBytes).coerceAtLeast(0) * 1_000L) / elapsed
                    uploadRateBytes = ((snapshot.engineTotal.uploadedBytes -
                        previous.engineTotal.uploadedBytes).coerceAtLeast(0) * 1_000L) / elapsed
                } else {
                    downloadRateBytes = 0
                    uploadRateBytes = 0
                }
                previousTraffic = snapshot
                traffic = snapshot
                updateTrafficViews()
            }
        }
        reloadData { handleSharedIntent(intent) }
    }

    override fun onResume() {
        super.onResume()
        reloadData()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        pendingBackupBytes?.fill(0)
        pendingRestoreBytes?.fill(0)
        super.onDestroy()
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Used as the API 24-32 compatibility back entry point")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        if (screens.isNotEmpty()) {
            screen = screens.removeLast()
            render()
        } else if (screen != Screen.HOME) {
            screen = Screen.HOME
            render()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Android API; retained for API 24 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null) {
            scanResult.contents?.let(::handleDecodedQr)
                ?: toast(tr("QR scan cancelled", "اسکن QR لغو شد"))
            return
        }
        if (requestCode == REQUEST_VPN_CONSENT) {
            if (resultCode == RESULT_OK) pendingConnectId?.let(::startVpn)
            else toast(tr("VPN permission was not granted", "اجازه VPN داده نشد"))
            pendingConnectId = null
            return
        }
        if (requestCode != REQUEST_DOCUMENT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (pendingDocument) {
            PendingDocument.IMPORT -> importDocument(uri)
            PendingDocument.QR_IMAGE -> importQrImage(uri)
            PendingDocument.EXPORT_BACKUP -> writeBackup(uri)
            PendingDocument.RESTORE_BACKUP -> readBackup(uri)
            null -> Unit
        }
        pendingDocument = null
    }

    private fun configureWindow() {
        val decorView = window.decorView
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        if (Build.VERSION.SDK_INT >= 30) {
            decorView.windowInsetsController?.setSystemBarsAppearance(
                if (lightTheme) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = when {
                !lightTheme -> 0
                Build.VERSION.SDK_INT >= 26 ->
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                else -> View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
        decorView.layoutDirection =
            if (persian) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    private fun reloadData(after: (() -> Unit)? = null) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                profilesRepo.list() to subscriptionsRepo.list()
            }
            profiles = loaded.first
            subscriptions = loaded.second
            val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
            val inferredSubscription = (selectedProfile?.source as? ProfileSource.Subscription)
                ?.subscriptionId?.let(::SubscriptionId)
            if (selectedSubscriptionId == null && inferredSubscription != null) {
                selectedSubscriptionId = inferredSubscription
            }
            if (selectedSubscriptionId == null && profiles.none { it.source is ProfileSource.Manual }) {
                selectedSubscriptionId = subscriptions.firstOrNull()?.id
            }
            if (selectedSubscriptionId != null && subscriptions.none { it.id == selectedSubscriptionId }) {
                selectedSubscriptionId = subscriptions.firstOrNull()?.id
            }
            val candidates = profilesFor(selectedSubscriptionId)
            if (selectedProfile == null || selectedProfile !in candidates) {
                selectedProfileId = candidates.firstOrNull()?.id
                    ?: profiles.firstOrNull { it.source is ProfileSource.Manual }?.id
            }
            persistSelection()
            loading = false
            render()
            after?.invoke()
        }
    }

    private fun render() {
        configureWindow()
        downloadValue = null
        uploadValue = null
        trafficCaption = null
        durationValue = null
        totalDownloadValue = null
        totalUploadValue = null
        quickPingValue = null
        quickPingBolt = null
        val root = FrameLayout(this).apply { setBackgroundColor(palette.background) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(98))
        }
        val content = vertical().apply {
            setPadding(dp(20), dp(18), dp(20), dp(32))
            layoutDirection = if (persian) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        when (screen) {
            Screen.HOME -> buildHome(content)
            Screen.PROFILES -> buildProfiles(content)
            Screen.DIAGNOSTICS -> buildDiagnostics(content)
            Screen.SETTINGS -> buildSettings(content)
            Screen.PROFILE_DETAIL -> buildProfileDetail(content)
            Screen.DNS -> buildDns(content)
            Screen.ROUTING -> buildRouting(content)
            Screen.NETWORK -> buildNetwork(content)
            Screen.SUBSCRIPTIONS -> buildSubscriptions(content)
            Screen.BACKUP -> buildBackup(content)
            Screen.ABOUT -> buildAbout(content)
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(scroll)
        root.addView(
            buildBottomNav(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(72),
                Gravity.BOTTOM,
            ).apply { setMargins(dp(15), 0, dp(15), dp(14)) },
        )
        setContentView(root)
        updateTrafficViews()
    }

    private fun buildHome(parent: LinearLayout) {
        parent.add(headerBrand())
        val state = connectionState
        val profile = selectedProfile()
        val subscription = selectedSubscription()
        val connected = state is ConnectionState.Connected
        val failed = state is ConnectionState.Failed

        parent.add(selectionRow(
            ToIcon.SUBSCRIPTION,
            tr("Subscription", "اشتراک"),
            subscription?.name ?: if (profiles.any { it.source is ProfileSource.Manual }) {
                tr("Local configurations", "پیکربندی‌های محلی")
            } else tr("No subscription", "بدون اشتراک"),
        ) { navigateRoot(Screen.PROFILES) }).margin(top = 20)
        parent.add(selectionRow(
            ToIcon.GLOBE,
            tr("Configuration", "پیکربندی"),
            profile?.let { p ->
                listOfNotNull(
                    p.name,
                    p.protocolLabel.uppercase(Locale.ROOT),
                    p.transportLabel?.uppercase(Locale.ROOT),
                    p.securityLabel?.uppercase(Locale.ROOT),
                    latencyLabel(p).takeIf {
                        p.lastTestedMillis != null || p.latencyMillis != null
                    },
                ).joinToString(" · ")
            } ?: tr("Select a configuration", "یک پیکربندی انتخاب کنید"),
        ) { navigateRoot(Screen.PROFILES) }).margin(top = 8)

        val orbState = when (state) {
            is ConnectionState.Connected -> OrbState.CONNECTED
            is ConnectionState.Reconnecting -> OrbState.RECONNECTING
            is ConnectionState.Failed -> OrbState.ERROR
            ConnectionState.Idle -> OrbState.DISCONNECTED
            else -> OrbState.CONNECTING
        }
        parent.add(ConnectionOrbView(this).apply {
            palette = this@MainActivity.palette
            this.state = orbState
            contentDescription = "${connectionActionLabel(state)}. ${connectionHeadline(state, profile)}"
            setOnClickListener { connectOrDisconnect() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, dp(244)).margin(top = 4)

        parent.add(label(connectionHeadline(state, profile), palette.text, 21f, Typeface.BOLD, Gravity.CENTER))
        parent.add(label(connectionSupportingText(state, profile), if (failed) palette.error else palette.textSecondary,
            12f, gravityValue = Gravity.CENTER).apply {
            setPadding(dp(18), dp(5), dp(18), 0)
        })
        parent.add(quickPingControl(connected && profile != null)).margin(top = 12, left = 54, right = 54)
        parent.add(statisticsCard(tr("Live session", "نشست زنده")) {
            val metrics = LinearLayout(this@MainActivity).apply { weightSum = 2f }
            metrics.add(metricBlock("↓", tr("Download", "دریافت"), true), 0, dp(52), 1f)
            metrics.add(metricBlock("↑", tr("Upload", "ارسال"), false), 0, dp(52), 1f)
            add(metrics)
            trafficCaption = label(
                if (connected) tr("Current transfer speed", "سرعت لحظه‌ای انتقال")
                else tr("Traffic appears after a connection starts", "ترافیک پس از شروع اتصال نمایش داده می‌شود"),
                palette.muted, 11f, gravityValue = Gravity.CENTER,
            ).apply { setPadding(0, dp(6), 0, 0) }
            add(trafficCaption!!)
        }).margin(top = 18)

        parent.add(statisticsCard(ls(R.string.total_overall)) {
            val totals = LinearLayout(this@MainActivity).apply { weightSum = 2f }
            totalDownloadValue = label("—", palette.text, 14f, Typeface.BOLD, Gravity.CENTER)
            totalUploadValue = label("—", palette.text, 14f, Typeface.BOLD, Gravity.CENTER)
            totals.add(trafficTotalBlock(totalDownloadValue!!, tr("Downloaded", "دریافت‌شده")),
                0, dp(52), 1f)
            totals.add(trafficTotalBlock(totalUploadValue!!, tr("Uploaded", "ارسال‌شده")),
                0, dp(52), 1f)
            add(totals)
        }).margin(top = 8)

        parent.add(statisticsCard(tr("Connection", "اتصال")) {
            val info = LinearLayout(this@MainActivity).apply { weightSum = 3f }
            info.add(liveDurationMetric(
                if (state is ConnectionState.Connected) formatDuration(System.currentTimeMillis() - state.connectedAtMillis) else "—",
                tr("Duration", "مدت"),
            ), 0, dp(56), 1f)
            info.add(compactMetric(profile?.let(::latencyLabel) ?: "—", tr("Latency", "تأخیر")),
                0, dp(56), 1f).marginRelative(start = 8)
            info.add(compactMetric(profile?.protocolLabel?.uppercase(Locale.ROOT) ?: "—", tr("Protocol", "پروتکل")),
                0, dp(56), 1f).marginRelative(start = 8)
            add(info)
        }).margin(top = 8)

        if (connected) {
            val unavailable = tr("Unavailable", "در دسترس نیست")
            val loadingValue = tr("Loading…", "در حال بارگذاری…")
            detailGroup(parent, tr("Exit network", "شبکه خروجی"), listOf(
                tr("Location", "موقعیت") to (exitInfo?.location
                    ?: if (exitInfoLoading) loadingValue else unavailable),
                tr("Exit IP", "آی‌پی خروجی") to (exitInfo?.ip
                    ?: if (exitInfoLoading) loadingValue else unavailable),
                "ISP" to (exitInfo?.isp
                    ?: if (exitInfoLoading) loadingValue else unavailable),
            ))
        }

        when {
            subscriptions.isEmpty() && profiles.isEmpty() -> emptyCard(
                parent,
                tr("Start with a trusted source", "با یک منبع معتبر شروع کنید"),
                tr("Add a subscription or import one configuration.", "یک اشتراک اضافه کنید یا یک پیکربندی وارد کنید."),
                tr("Add source", "افزودن منبع"),
            ) { showImportChoices() }
            subscription != null && profilesFor(subscription.id).isEmpty() ->
                parent.add(messageCard(
                    tr("Subscription has no configurations", "اشتراک پیکربندی ندارد"),
                    tr("Refresh it or inspect the last update error.", "آن را به‌روز کنید یا خطای آخر را بررسی کنید."),
                    palette.warning,
                )).margin(top = 18)
            failed -> parent.add(messageCard(
                (state as ConnectionState.Failed).reason.name,
                state.message,
                palette.error,
            )).margin(top = 18)
        }
    }

    private fun buildProfiles(parent: LinearLayout) {
        parent.add(pageHeader(
            tr("Subscription → configuration", "اشتراک ← پیکربندی"),
            tr("Configurations", "پیکربندی‌ها"),
            tr("Choose a source, then choose its server", "ابتدا منبع و سپس سرور آن را انتخاب کنید"),
            ToIcon.PLUS,
        ) { showImportChoices() })
        if (loading) {
            loadingCard(parent, tr("Loading encrypted configurations…", "در حال بارگذاری پیکربندی‌های رمزگذاری‌شده…"))
            return
        }
        sectionTitle(parent, tr("Sources", "منابع"), "${subscriptions.size + if (profiles.any { it.source is ProfileSource.Manual }) 1 else 0}")
        subscriptions.forEach { subscription ->
            parent.add(sourceSelectorRow(subscription)).margin(top = 7)
        }
        if (profiles.any { it.source is ProfileSource.Manual }) {
            parent.add(sourceSelectorRow(null)).margin(top = 7)
        }
        if (subscriptions.isEmpty() && profiles.none { it.source is ProfileSource.Manual }) {
            emptyCard(
                parent,
                tr("No sources yet", "هنوز منبعی وجود ندارد"),
                tr("Subscriptions stay separate and keep ownership of their configurations.",
                    "اشتراک‌ها جدا می‌مانند و مالکیت پیکربندی‌های خود را حفظ می‌کنند."),
                tr("Add source", "افزودن منبع"),
            ) { showImportChoices() }
            return
        }

        val sourceName = selectedSubscription()?.name ?: tr("Local configurations", "پیکربندی‌های محلی")
        val allCandidates = profilesFor(selectedSubscriptionId)
        val candidates = visibleConfigurations(allCandidates)
        sectionTitle(parent, sourceName, "${candidates.size}/${allCandidates.size} ${tr("configs", "پیکربندی")}")
        selectedSubscription()?.let { subscription ->
            val subscriptionActions = LinearLayout(this).apply { weightSum = 2f }
            subscriptionActions.add(secondaryButton(ls(R.string.subscription_update)) {
                refreshSubscription(subscription)
            }, 0, dp(48), 1f)
            subscriptionActions.add(secondaryButton(
                ls(R.string.subscription_delete),
                danger = true,
            ) {
                confirmDeleteSubscription(subscription)
            }, 0, dp(48), 1f).marginRelative(start = 8)
            parent.add(subscriptionActions).margin(bottom = 10)
        }
        if (allCandidates.isNotEmpty()) {
            val controls = LinearLayout(this).apply { weightSum = if (configurationSearch.isEmpty()) 2f else 3f }
            controls.add(compactAction(
                if (configurationSearch.isEmpty()) tr("Search", "جستجو")
                else tr("Search: $configurationSearch", "جستجو: $configurationSearch"),
            ) { showConfigurationSearch() }, 0, dp(44), 1f)
            controls.add(compactAction(
                tr("Sort: ${configurationSortLabel()}", "مرتب‌سازی: ${configurationSortLabel()}"),
            ) { showConfigurationSort() }, 0, dp(44), 1f).marginRelative(start = 7)
            if (configurationSearch.isNotEmpty()) {
                controls.add(compactAction(tr("Clear", "پاک کردن")) {
                    configurationSearch = ""
                    render()
                }, 0, dp(44), 1f).marginRelative(start = 7)
            }
            parent.add(controls).margin(bottom = 8)
        }
        if (allCandidates.isEmpty()) {
            emptyCard(
                parent,
                tr("No configurations", "پیکربندی وجود ندارد"),
                tr("Refresh this subscription or import a local configuration.",
                    "این اشتراک را به‌روز کنید یا یک پیکربندی محلی وارد کنید."),
                if (selectedSubscriptionId != null) tr("Refresh", "به‌روزرسانی") else tr("Import", "درون‌ریزی"),
            ) {
                selectedSubscription()?.let(::refreshSubscription) ?: showImportChoices()
            }
        } else if (candidates.isEmpty()) {
            emptyCard(
                parent,
                tr("No matching configurations", "پیکربندی منطبق پیدا نشد"),
                tr("Change or clear the current search.", "جستجوی فعلی را تغییر دهید یا پاک کنید."),
                tr("Clear search", "پاک کردن جستجو"),
            ) {
                configurationSearch = ""
                render()
            }
        } else {
            parent.add(secondaryButton(
                if (latencyTesting) {
                    tr("Cancel after current test · $latencyTestCompleted/$latencyTestTotal",
                        "لغو پس از آزمایش فعلی · $latencyTestCompleted/$latencyTestTotal")
                } else tr("Test ping for all configurations", "آزمایش پینگ همه پیکربندی‌ها"),
            ) {
                if (latencyTesting) {
                    latencyCancelRequested = true
                    render()
                } else {
                    testAllLatencies(allCandidates)
                }
            }.apply {
                isEnabled = !latencyCancelRequested
                alpha = if (latencyCancelRequested) .62f else 1f
            }).margin(top = 4, bottom = 8)
            val failedTests = allCandidates.filter {
                it.latencyStatus == LatencyTestStatus.TIMEOUT ||
                    it.latencyStatus == LatencyTestStatus.FAILED
            }
            if (!latencyTesting && failedTests.isNotEmpty()) {
                parent.add(secondaryButton(
                    tr("Retry failed · ${failedTests.size}", "تلاش دوباره ناموفق‌ها · ${failedTests.size}"),
                ) { testAllLatencies(failedTests) }).margin(bottom = 8)
            }
            val measured = allCandidates.filter {
                it.latencyMillis != null && it.latencyStatus == LatencyTestStatus.SUCCESS
            }
            if (measured.isNotEmpty()) {
                parent.add(secondaryButton(tr("Select fastest measured", "انتخاب سریع‌ترین اندازه‌گیری‌شده")) {
                    selectProfile(measured.minBy { it.latencyMillis ?: Long.MAX_VALUE })
                }).margin(top = 4, bottom = 6)
            }
            if (!latencyTesting) {
                parent.add(secondaryButton(
                    tr("Test all, then select fastest", "آزمایش همه، سپس انتخاب سریع‌ترین"),
                ) { testAllLatencies(allCandidates, selectFastest = true) }).margin(bottom = 8)
            }
            candidates.forEach { profile ->
                parent.add(configurationRow(profile) {
                    selectProfile(profile)
                    navigate(Screen.PROFILE_DETAIL)
                }).margin(top = 7)
            }
        }
    }

    private fun buildProfileDetail(parent: LinearLayout) {
        val profile = selectedProfile()
        if (profile == null) {
            parent.add(backHeader(tr("Profile unavailable", "پروفایل در دسترس نیست"), ""))
            emptyCard(parent, tr("This profile no longer exists", "این پروفایل دیگر وجود ندارد"), "", tr("Back", "بازگشت")) {
                navigateRoot(Screen.PROFILES)
            }
            return
        }
        parent.add(backHeader(profile.name, tr("Network identity", "هویت شبکه")))
        val hero = vertical().apply {
            background = roundedBackground(palette.glass, dp(22).toFloat(), palette.primary, dp(1))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.add(iconTile(ToIcon.GLOBE))
        row.add(vertical().apply {
            setPaddingRelative(dp(12), 0, 0, 0)
            add(label(profile.name, palette.text, 19f, Typeface.BOLD))
            add(label(profile.protocolLabel.uppercase(Locale.ROOT), palette.textSecondary, 11f, Typeface.BOLD))
        }, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.add(statusBadge(
            if (profile.id == selectedProfileId) tr("SELECTED", "انتخاب‌شده") else tr("READY", "آماده"),
            palette.primary,
        ))
        hero.add(row)
        profile.endpointLabel?.let {
            hero.add(technicalLabel(it).apply { setPadding(0, dp(16), 0, 0) })
        }
        val actions = LinearLayout(this).apply {
            setPadding(0, dp(18), 0, 0)
        }
        actions.add(primaryButton(
            if (isProfileConnected(profile.id)) tr("Disconnect", "قطع اتصال") else tr("Connect", "اتصال"),
        ) {
            selectProfile(profile)
            connectProfile(profile.id)
        }, 0, dp(48), 1f)
        actions.add(secondaryButton(tr("Rename", "تغییر نام")) { showRenameProfile(profile) },
            0, dp(48), 1f).marginRelative(start = 10)
        actions.add(iconButton(ToIcon.CLOSE, tr("Delete configuration", "حذف پیکربندی")) {
            confirmDeleteProfile(profile)
        }, dp(48), dp(48)).marginRelative(start = 10)
        hero.add(actions)
        parent.add(hero).margin(top = 8)
        detailGroup(parent, tr("Identity", "هویت"), buildList {
            add(tr("Engine", "موتور") to profile.engine.name)
            add(tr("Transport", "انتقال") to (profile.transportLabel ?: "—"))
            add(tr("Security", "امنیت") to (profile.securityLabel ?: "—"))
            add(tr("Latency", "تأخیر") to latencyLabel(profile))
            if (profile.latencyMillis != null &&
                profile.latencyStatus in setOf(LatencyTestStatus.TIMEOUT, LatencyTestStatus.FAILED)
            ) {
                add(tr("Last successful latency", "آخرین تأخیر موفق") to "${profile.latencyMillis} ms")
            }
            profile.latencyError?.let {
                add(tr("Test detail", "جزئیات آزمایش") to it)
            }
            add(tr("Source", "منبع") to when (val source = profile.source) {
                ProfileSource.Manual -> tr("Manual import", "درون‌ریزی دستی")
                is ProfileSource.Subscription -> subscriptions.firstOrNull {
                    it.id.value == source.subscriptionId
                }?.name ?: tr("Subscription", "اشتراک")
            })
            add(tr("Fingerprint", "اثر انگشت") to (profile.fingerprint?.take(16) ?: "—"))
        })
        detailGroup(parent, tr("Network policy", "سیاست شبکه"), listOf(
            "DNS" to profile.dns.servers.joinToString(", "),
            tr("Routing rules", "قوانین مسیریابی") to profile.routing.rules.size.toString(),
            tr("Applications", "برنامه‌ها") to profile.perApp.mode.name,
            "MTU" to profile.mtu.toString(),
            "IPv6" to if (profile.ipv6Enabled) tr("Enabled", "فعال") else tr("Disabled", "غیرفعال"),
        ))
        if (connectionState is ConnectionState.Connected && isProfileConnected(profile.id)) {
            detailGroup(parent, tr("Exit information", "اطلاعات خروج"), listOf(
                tr("Location", "موقعیت") to (exitInfo?.location ?: tr("Unavailable", "در دسترس نیست")),
                tr("Exit IP", "آی‌پی خروجی") to (exitInfo?.ip ?: tr("Unavailable", "در دسترس نیست")),
                "ISP" to (exitInfo?.isp ?: tr("Unavailable", "در دسترس نیست")),
            ))
        }
        (profile.source as? ProfileSource.Subscription)?.let { source ->
            subscriptions.firstOrNull { it.id.value == source.subscriptionId }?.let { subscription ->
                sectionTitle(parent, tr("Subscription", "اشتراک"))
                parent.add(subscriptionUsagePanel(subscription))
                val subscriptionActions = LinearLayout(this).apply { weightSum = 2f }
                subscriptionActions.add(secondaryButton(ls(R.string.subscription_update)) {
                    refreshSubscription(subscription)
                }, 0, dp(48), 1f)
                subscriptionActions.add(secondaryButton(
                    ls(R.string.subscription_delete),
                    danger = true,
                ) {
                    confirmDeleteSubscription(subscription)
                }, 0, dp(48), 1f).marginRelative(start = 8)
                parent.add(subscriptionActions).margin(top = 10)
            }
        }
        parent.add(secondaryButton(
            if (profile.isFavorite) tr("Remove from favorites", "حذف از علاقه‌مندی‌ها")
            else tr("Add to favorites", "افزودن به علاقه‌مندی‌ها"),
        ) { updateProfile(profile.copy(isFavorite = !profile.isFavorite)) }).margin(top = 18)
        parent.add(secondaryButton(tr("View / edit Xray JSON", "مشاهده / ویرایش JSON ایکس‌ری")) {
            showXrayJsonEditor(profile)
        }).margin(top = 10)
        parent.add(secondaryButton(tr("Copy configuration", "کپی پیکربندی")) { copyProfile(profile) }).margin(top = 10)
        parent.add(secondaryButton(tr("Share configuration", "اشتراک‌گذاری پیکربندی")) {
            shareProfile(profile)
        }).margin(top = 10)
        parent.add(secondaryButton(tr("Show configuration QR", "نمایش QR پیکربندی")) {
            showProfileQr(profile)
        }).margin(top = 10)
        parent.add(secondaryButton(tr("Delete configuration", "حذف پیکربندی"), danger = true) {
            confirmDeleteProfile(profile)
        }).margin(top = 10)
    }

    private fun buildDiagnostics(parent: LinearLayout) {
        parent.add(pageHeader(
            tr("Network visibility", "دید شبکه"),
            tr("Diagnostics", "عیب‌یابی"),
            tr("Truthful live checks from the running core", "بررسی‌های واقعی از هسته در حال اجرا"),
            ToIcon.ACTIVITY,
        ) { runLatencyDiagnostic() })
        val connected = connectionState is ConnectionState.Connected
        val summary = vertical()
        summary.add(summaryCard(
            if (connectionState is ConnectionState.Failed) tr("Error", "خطا")
            else if (connected) tr("Healthy", "سالم") else tr("Idle", "آماده"),
            tr("Current status", "وضعیت فعلی"),
            if (connectionState is ConnectionState.Failed) palette.error else palette.primary,
        ), ViewGroup.LayoutParams.MATCH_PARENT, dp(82))
        summary.add(summaryCard(
            traffic?.let { formatBytes(it.engineTotal.downloadedBytes + it.engineTotal.uploadedBytes) } ?: "—",
            tr("Session traffic", "ترافیک نشست"),
            palette.text,
        ), ViewGroup.LayoutParams.MATCH_PARENT, dp(82)).margin(top = 8)
        parent.add(summary).margin(top = 12)
        val diagnosticProfile = selectedProfile()
        detailGroup(parent, tr("Selected context", "زمینه انتخاب‌شده"), listOf(
            tr("Subscription", "اشتراک") to (selectedSubscription()?.name
                ?: tr("Local configurations", "پیکربندی‌های محلی")),
            tr("Configuration", "پیکربندی") to (diagnosticProfile?.name ?: "—"),
            tr("Endpoint", "نقطه پایانی") to (diagnosticProfile?.endpointLabel ?: "—"),
            tr("Latency", "تأخیر") to (diagnosticProfile?.let(::latencyLabel)
                ?: tr("Not measured", "اندازه‌گیری نشده")),
            tr("Last tested", "آخرین آزمایش") to (diagnosticProfile?.lastTestedMillis?.let(::formatTimestamp) ?: "—"),
        ))
        traffic?.let { snapshot ->
            detailGroup(parent, tr("Traffic counters", "شمارنده‌های ترافیک"), buildList {
                add(tr("Engine download", "دریافت موتور") to formatBytes(snapshot.engineTotal.downloadedBytes))
                add(tr("Engine upload", "ارسال موتور") to formatBytes(snapshot.engineTotal.uploadedBytes))
                add(tr("TUN download", "دریافت TUN") to formatBytes(snapshot.tun.downloadedBytes))
                add(tr("TUN upload", "ارسال TUN") to formatBytes(snapshot.tun.uploadedBytes))
            })
            if (snapshot.engineOutbounds.isNotEmpty()) {
                detailGroup(parent, tr("Outbound counters", "شمارنده‌های خروجی"), snapshot.engineOutbounds
                    .sortedBy { it.tag }
                    .map {
                        it.tag to tr(
                            "↓ ${formatBytes(it.traffic.downloadedBytes)} · ↑ ${formatBytes(it.traffic.uploadedBytes)}",
                            "↓ ${formatBytes(it.traffic.downloadedBytes)} · ↑ ${formatBytes(it.traffic.uploadedBytes)}",
                        )
                    })
            }
        }
        sectionTitle(parent, tr("Connection pipeline", "مسیر اتصال"))
        val steps = listOf(
            tr("Profile & secrets", "پروفایل و داده محرمانه") to stageAtLeast(1),
            tr("Xray engine", "موتور Xray") to stageAtLeast(2),
            tr("Android VPN tunnel", "تونل VPN اندروید") to stageAtLeast(3),
            tr("TUN bridge", "پل TUN") to stageAtLeast(4),
        )
        steps.forEach { (name, ready) ->
            parent.add(diagnosticRow(name, if (ready) tr("READY", "آماده") else tr("WAITING", "در انتظار"), ready))
                .margin(top = 9)
        }
        if (connectionState is ConnectionState.Failed) {
            val failure = connectionState as ConnectionState.Failed
            sectionTitle(parent, tr("Last failure", "آخرین خطا"))
            parent.add(messageCard(failure.reason.name, failure.message, palette.error))
        }
        sectionTitle(parent, tr("Compatibility", "سازگاری"))
        val report = AndroidCompatibility.inspect(this)
        val issueText = if (report.issues.isEmpty()) {
            tr("API and ABI checks passed", "بررسی‌های API و ABI موفق بود")
        } else report.issues.joinToString(" · ") { localizedIssue(it) }
        parent.add(messageCard(
            "Android ${report.sdk}",
            "${report.supportedAbis.joinToString()} · $issueText",
            if (report.issues.any {
                    it == CompatibilityIssue.ANDROID_VERSION_UNSUPPORTED ||
                        it == CompatibilityIssue.NATIVE_ABI_UNSUPPORTED
                }) palette.error else palette.primary,
        ))
        parent.add(primaryButton(tr("Measure HTTPS latency", "اندازه‌گیری تأخیر HTTPS")) {
            runLatencyDiagnostic()
        }).margin(top = 18)
        parent.add(secondaryButton(tr("Share diagnostic snapshot", "اشتراک‌گذاری گزارش عیب‌یابی")) {
            shareDiagnosticSnapshot()
        }).margin(top = 10)
    }

    private fun buildSettings(parent: LinearLayout) {
        parent.add(pageHeader(
            tr("Control center", "مرکز کنترل"),
            tr("Settings", "تنظیمات"),
            tr("Connection, privacy and appearance", "اتصال، حریم خصوصی و ظاهر"),
            ToIcon.THEME,
        ) { toggleTheme() })
        val mainSettings = listOf(
            SettingItem(ToIcon.DNS, "DNS", selectedProfile()?.dns?.servers?.joinToString() ?: "—", Screen.DNS),
            SettingItem(ToIcon.ROUTE, tr("Routing", "مسیریابی"), "${selectedProfile()?.routing?.rules?.size ?: 0} ${tr("rules", "قانون")}", Screen.ROUTING),
            SettingItem(ToIcon.TUNNEL, tr("Tunnel & applications", "تونل و برنامه‌ها"), "MTU ${selectedProfile()?.mtu ?: 1500}", Screen.NETWORK),
            SettingItem(ToIcon.SUBSCRIPTION, tr("Subscriptions", "اشتراک‌ها"), "${subscriptions.size}", Screen.SUBSCRIPTIONS),
            SettingItem(ToIcon.BACKUP, tr("Encrypted backup", "پشتیبان رمزگذاری‌شده"), "TOCLIENT-BACKUP", Screen.BACKUP),
            SettingItem(ToIcon.ACTIVITY, tr("Diagnostics", "عیب‌یابی"), connectionLabel(connectionState), Screen.DIAGNOSTICS),
            SettingItem(ToIcon.INFO, tr("About TO Client", "درباره TO Client"), "v1.0.0", Screen.ABOUT),
        )
        sectionTitle(parent, ls(R.string.settings_group).uppercase())
        val unified = vertical().apply {
            background = roundedBackground(palette.glass, dp(19).toFloat(), palette.line, dp(1))
            mainSettings.forEachIndexed { index, item ->
                if (index > 0) add(divider())
                add(settingActionRow(item.icon, item.title, item.value) { navigate(item.screen) })
            }
            add(divider())
            add(settingToggleRow(
                ToIcon.ACTIVITY,
                ls(R.string.show_notification_traffic),
                ls(R.string.show_notification_traffic_summary),
                showNotificationTraffic,
            ) { enabled ->
                showNotificationTraffic = enabled
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_SHOW_NOTIFICATION_TRAFFIC, enabled)
                    .apply()
            })
        }
        parent.add(unified)
        sectionTitle(parent, tr("Appearance", "ظاهر"))
        parent.add(settingActionRow(
            ToIcon.THEME,
            tr("Theme", "پوسته"),
            if (lightTheme) tr("Light", "روشن") else tr("Dark", "تیره"),
        ) { toggleTheme() })
        parent.add(settingActionRow(
            ToIcon.GLOBE,
            tr("Language", "زبان"),
            if (persian) "فارسی" else "English",
        ) { toggleLanguage() }).margin(top = 1)
    }

    private fun buildDns(parent: LinearLayout) {
        val profile = selectedProfile()
        parent.add(backHeader("DNS", tr("Resolver policy inside the tunnel", "سیاست حل نام داخل تونل")))
        if (profile == null) {
            noProfileCard(parent)
            return
        }
        parent.add(messageCard(
            tr("Leak-safe numeric resolvers", "حل‌کننده‌های عددی امن"),
            tr(
                "Hostnames are rejected so Android never resolves the DNS server outside the tunnel.",
                "نام میزبان رد می‌شود تا اندروید DNS را خارج از تونل حل نکند.",
            ),
            palette.primary,
        ))
        val servers = styledInput(profile.dns.servers.joinToString("\n"), "1.1.1.1\n1.0.0.1").apply {
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_LTR
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        formField(parent, tr("DNS servers · one per line", "سرورهای DNS · هر خط یک مورد"), servers)
        val dnsActions = LinearLayout(this).apply { weightSum = 2f }
        dnsActions.add(secondaryButton(tr("Reset defaults", "بازنشانی پیش‌فرض‌ها")) {
            servers.setText(DnsPolicy().servers.joinToString("\n"))
            servers.error = null
        }, 0, dp(48), 1f)
        dnsActions.add(secondaryButton(tr("Clear", "پاک کردن")) {
            servers.setText("")
            servers.error = null
        }, 0, dp(48), 1f).marginRelative(start = 8)
        parent.add(dnsActions).margin(top = 10)
        parent.add(primaryButton(tr("Save DNS policy", "ذخیره سیاست DNS")) {
            runCatching {
                val values = servers.text.toString().lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .map(::normalizeNumericDns)
                    .distinct()
                    .toList()
                require(values.isNotEmpty()) {
                    tr("Add at least one numeric DNS server or reset defaults.",
                        "حداقل یک سرور DNS عددی اضافه کنید یا پیش‌فرض‌ها را بازنشانی کنید.")
                }
                values
            }.onSuccess { values ->
                servers.error = null
                updateProfile(profile.copy(dns = DnsPolicy(values))) {
                    handleBack()
                    showGlassFeedback(tr("DNS policy saved", "سیاست DNS ذخیره شد"), ToIcon.DNS)
                }
            }.onFailure {
                servers.error = it.message ?: tr("Invalid DNS server", "سرور DNS نامعتبر است")
            }
        }).margin(top = 18)
    }

    private fun buildRouting(parent: LinearLayout) {
        val profile = selectedProfile()
        parent.add(pageHeader(
            tr("Traffic policy", "سیاست ترافیک"),
            tr("Routing", "مسیریابی"),
            tr("Rules apply on the next connection", "قوانین در اتصال بعدی اعمال می‌شوند"),
            ToIcon.PLUS,
        ) { if (profile != null) showAddRule(profile) })
        if (profile == null) {
            noProfileCard(parent)
            return
        }
        parent.add(messageCard(
            tr("Full tunnel", "تونل کامل"),
            if (profile.routing.bypassLan) {
                tr("Private LAN ranges stay reachable.", "محدوده‌های شبکه محلی در دسترس می‌مانند.")
            } else tr("All routable traffic enters the tunnel.", "تمام ترافیک قابل مسیریابی وارد تونل می‌شود."),
            palette.primary,
        ))
        val lanToggle = CheckBox(this).apply {
            text = tr("Keep private LAN reachable", "شبکه محلی در دسترس بماند")
            setTextColor(palette.text)
            isChecked = profile.routing.bypassLan
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.primary)
            setPadding(dp(6), dp(12), dp(6), dp(12))
        }
        parent.add(lanToggle).margin(top = 12)
        val excluded = styledInput(
            profile.routing.excludedCidrs.joinToString("\n"),
            "198.51.100.0/24",
        ).apply {
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_LTR
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        formField(parent, tr("Excluded TUN routes · one CIDR per line", "مسیرهای مستثنی TUN · هر خط یک CIDR"), excluded)
        parent.add(secondaryButton(tr("Save base routing policy", "ذخیره سیاست پایه مسیریابی")) {
            val values = excluded.text.toString().lineSequence().map(String::trim)
                .filter(String::isNotEmpty).distinct().toList()
            updateProfile(profile.copy(routing = profile.routing.copy(
                bypassLan = lanToggle.isChecked,
                excludedCidrs = values,
            ))) {
                showGlassFeedback(
                    tr("Base routing policy saved", "سیاست پایه مسیریابی ذخیره شد"),
                    ToIcon.ROUTE,
                )
            }
        }).margin(top = 12)
        sectionTitle(parent, tr("Rules", "قوانین"), "${profile.routing.rules.size}")
        if (profile.routing.rules.isEmpty()) {
            emptyCard(
                parent,
                tr("No custom rules", "قانون سفارشی وجود ندارد"),
                tr("The active profile handles all traffic.", "پروفایل فعال تمام ترافیک را مدیریت می‌کند."),
                tr("Add rule", "افزودن قانون"),
            ) { showAddRule(profile) }
        } else {
            profile.routing.rules.forEachIndexed { index, rule ->
                parent.add(ruleCard(rule) {
                    val updated = profile.routing.rules.toMutableList().apply { removeAt(index) }
                    updateProfile(profile.copy(routing = profile.routing.copy(rules = updated))) {
                        showGlassFeedback(tr("Rule removed", "قانون حذف شد"), ToIcon.ROUTE)
                    }
                }).margin(top = 9)
            }
        }
    }

    private fun buildNetwork(parent: LinearLayout) {
        val profile = selectedProfile()
        parent.add(backHeader(
            tr("Tunnel & applications", "تونل و برنامه‌ها"),
            tr("Packet path, IPv6 and reconnect behavior", "مسیر بسته، IPv6 و رفتار اتصال مجدد"),
        ))
        if (profile == null) {
            noProfileCard(parent)
            return
        }
        val mtu = styledInput(profile.mtu.toString(), "1500").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        formField(parent, "MTU", mtu)
        val ipv6 = checkRow(tr("Enable IPv6 inside the tunnel", "فعال‌سازی IPv6 در تونل"), profile.ipv6Enabled)
        val reconnect = checkRow(tr("Reconnect after network changes", "اتصال مجدد پس از تغییر شبکه"), profile.reconnect.enabled)
        parent.add(ipv6).margin(top = 10)
        parent.add(reconnect).margin(top = 8)
        sectionTitle(parent, tr("Per-app routing", "مسیریابی برنامه‌ها"))
        var selectedMode = profile.perApp.mode
        val selectedPackages = profile.perApp.packages.toMutableSet()
        lateinit var appSelectionButton: TextView
        val modeButton = secondaryButton(localizedPerAppMode(selectedMode)) {
            selectedMode = when (selectedMode) {
                PerAppMode.ALL_APPS -> PerAppMode.ONLY_SELECTED
                PerAppMode.ONLY_SELECTED -> PerAppMode.BYPASS_SELECTED
                PerAppMode.BYPASS_SELECTED -> PerAppMode.ALL_APPS
            }
            (itFromTag(MODE_BUTTON_TAG) as? TextView)?.text = localizedPerAppMode(selectedMode)
            appSelectionButton.isEnabled = selectedMode != PerAppMode.ALL_APPS
            appSelectionButton.alpha = if (appSelectionButton.isEnabled) 1f else .55f
        }.apply { tag = MODE_BUTTON_TAG }
        appSelectionButton = secondaryButton(
            selectedAppsLabel(selectedPackages.size),
        ) {
            showAppSelection(selectedPackages) { count ->
                appSelectionButton.text = selectedAppsLabel(count)
            }
        }.apply {
            isEnabled = selectedMode != PerAppMode.ALL_APPS
            alpha = if (isEnabled) 1f else .55f
        }
        parent.add(label(tr("Mode", "حالت"), palette.textSecondary, 11f, Typeface.BOLD).apply {
            setPadding(dp(4), dp(17), dp(4), dp(8))
        })
        parent.add(modeButton)
        parent.add(label(tr("Applications", "برنامه‌ها"), palette.textSecondary, 11f, Typeface.BOLD).apply {
            setPaddingRelative(dp(4), dp(17), dp(4), dp(8))
        })
        parent.add(appSelectionButton)
        val maxAttempts = styledInput(profile.reconnect.maxAttempts.toString(), "5").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val initialDelay = styledInput(profile.reconnect.initialDelayMillis.toString(), "1000").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val maxDelay = styledInput(profile.reconnect.maxDelayMillis.toString(), "30000").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        sectionTitle(parent, tr("Reconnect limits", "محدودیت‌های اتصال مجدد"))
        formField(parent, tr("Maximum attempts", "حداکثر تلاش"), maxAttempts)
        formField(parent, tr("Initial delay · milliseconds", "تأخیر اولیه · میلی‌ثانیه"), initialDelay)
        formField(parent, tr("Maximum delay · milliseconds", "حداکثر تأخیر · میلی‌ثانیه"), maxDelay)
        parent.add(primaryButton(tr("Save tunnel policy", "ذخیره سیاست تونل")) {
            runCatching {
                val packageSet = if (selectedMode == PerAppMode.ALL_APPS) emptySet()
                else selectedPackages.toSet()
                require(selectedMode == PerAppMode.ALL_APPS || packageSet.isNotEmpty()) {
                    tr("Select at least one application", "حداقل یک برنامه را انتخاب کنید")
                }
                profile.copy(
                    mtu = mtu.text.toString().toIntOrNull() ?: -1,
                    ipv6Enabled = ipv6.isChecked,
                    reconnect = ReconnectPolicy(
                        enabled = reconnect.isChecked,
                        maxAttempts = maxAttempts.text.toString().toIntOrNull() ?: -1,
                        initialDelayMillis = initialDelay.text.toString().toLongOrNull() ?: -1,
                        maxDelayMillis = maxDelay.text.toString().toLongOrNull() ?: -1,
                    ),
                    perApp = PerAppPolicy(selectedMode, packageSet),
                )
            }.onSuccess { newProfile ->
                updateProfile(newProfile) {
                    handleBack()
                    showGlassFeedback(
                        tr("Tunnel policy saved", "سیاست تونل ذخیره شد"),
                        ToIcon.TUNNEL,
                    )
                }
            }.onFailure(::showError)
        }).margin(top = 18)
    }

    private fun buildSubscriptions(parent: LinearLayout) {
        parent.add(pageHeader(
            tr("Independent grouped sources", "منابع گروه‌بندی‌شده مستقل"),
            tr("Subscriptions", "اشتراک‌ها"),
            tr("Each source owns and refreshes only its configurations", "هر منبع فقط پیکربندی‌های خودش را نگه می‌دارد"),
            ToIcon.PLUS,
        ) { showSubscriptionDialog() })
        if (subscriptions.isEmpty()) {
            emptyCard(
                parent,
                tr("No subscriptions", "اشتراکی وجود ندارد"),
                tr("Add a trusted HTTPS source. Its configurations will remain in a separate group.",
                    "یک منبع HTTPS معتبر اضافه کنید. پیکربندی‌های آن در گروهی جدا می‌مانند."),
                tr("Add subscription", "افزودن اشتراک"),
            ) { showSubscriptionDialog() }
            return
        }
        subscriptions.forEach { subscription ->
            parent.add(subscriptionCard(subscription)).margin(top = 10)
        }
    }

    private fun subscriptionCard(subscription: Subscription): View {
        val expanded = subscription.id in expandedSubscriptionIds
        val owned = profilesFor(subscription.id)
        val selected = subscription.id == selectedSubscriptionId
        return vertical().apply {
            background = roundedBackground(
                if (selected) palette.selected else palette.glass,
                dp(16).toFloat(), if (selected) palette.primary else palette.line, dp(1),
            )
            setPadding(dp(14), dp(13), dp(14), dp(13))
            val header = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                add(ToIconView(this@MainActivity, ToIcon.SUBSCRIPTION,
                    if (selected) palette.primary else palette.textSecondary), dp(22), dp(22))
                add(vertical().apply {
                    setPaddingRelative(dp(11), 0, 0, 0)
                    add(label(subscription.name, palette.text, 15f, Typeface.BOLD))
                    add(label(listOfNotNull(
                        subscription.sourceHost,
                        "${owned.size} ${tr("configs", "پیکربندی")}",
                        subscription.lastSuccessMillis?.let { relativeTime(it) },
                    ).joinToString(" · "), palette.textSecondary, 10f).apply {
                        setPadding(0, dp(4), 0, 0)
                    })
                }, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                add(statusBadge(
                    when {
                        subscription.lastError != null -> tr("ERROR", "خطا")
                        !subscription.enabled -> tr("PAUSED", "متوقف")
                        selected -> tr("ACTIVE", "فعال")
                        else -> tr("READY", "آماده")
                    },
                    if (subscription.lastError != null) palette.error
                    else if (subscription.enabled) palette.primary else palette.muted,
                ))
            }
            add(header)
            add(subscriptionUsagePanel(subscription)).margin(top = 10)
            subscription.lastError?.let {
                add(label(it, palette.error, 11f).apply { setPadding(0, dp(8), 0, 0) })
            }
            val controls = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(11), 0, 0)
                add(compactAction(tr("${owned.size} configs", "${owned.size} پیکربندی")) {
                    if (!expandedSubscriptionIds.add(subscription.id)) expandedSubscriptionIds.remove(subscription.id)
                    render()
                }, 0, dp(42), 1f)
                add(compactAction(tr("Update", "به‌روزرسانی")) { refreshSubscription(subscription) },
                    0, dp(42), 1f).marginRelative(start = 7)
                add(iconButton(ToIcon.INFO, tr("Subscription actions", "عملیات اشتراک")) {
                    showSubscriptionActions(subscription)
                }, dp(42), dp(42)).marginRelative(start = 7)
                add(iconButton(ToIcon.CLOSE, tr("Delete subscription", "حذف اشتراک")) {
                    confirmDeleteSubscription(subscription)
                }, dp(42), dp(42)).marginRelative(start = 7)
            }
            add(controls)
            if (expanded) {
                owned.forEach { profile ->
                    add(configurationRow(profile) {
                        selectProfile(profile)
                        navigateRoot(Screen.PROFILES)
                    }).margin(top = 7)
                }
                if (owned.isEmpty()) {
                    add(label(tr("No configurations. Refresh to try again.", "پیکربندی وجود ندارد. دوباره به‌روز کنید."),
                        palette.muted, 11f).apply { setPadding(dp(4), dp(12), dp(4), 0) })
                }
            }
        }
    }

    private fun buildBackup(parent: LinearLayout) {
        parent.add(backHeader(
            tr("Encrypted backup", "پشتیبان رمزگذاری‌شده"),
            tr("Independent, password-protected TO Client format", "فرمت مستقل و رمزدار TO Client"),
        ))
        parent.add(messageCard(
            "TOCLIENT-BACKUP",
            tr(
                "Profiles, encrypted configuration content, subscriptions and routing policies are exported only after local AES-256-GCM encryption.",
                "پروفایل‌ها، پیکربندی‌ها، اشتراک‌ها و سیاست‌ها فقط پس از رمزگذاری محلی AES-256-GCM صادر می‌شوند.",
            ),
            palette.primary,
        ))
        parent.add(primaryButton(tr("Create encrypted backup", "ساخت پشتیبان رمزگذاری‌شده")) {
            promptBackupPassword(export = true)
        }).margin(top = 18)
        parent.add(secondaryButton(tr("Restore encrypted backup", "بازیابی پشتیبان رمزگذاری‌شده")) {
            pendingDocument = PendingDocument.RESTORE_BACKUP
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                },
                REQUEST_DOCUMENT,
            )
        }).margin(top = 10)
        parent.add(label(
            tr(
                "Restoring replaces matching profile IDs and subscriptions. Existing unrelated records remain.",
                "بازیابی رکوردهای هم‌نام را جایگزین می‌کند و رکوردهای نامرتبط باقی می‌مانند.",
            ),
            palette.muted,
            12f,
        ).apply { setPadding(dp(6), dp(18), dp(6), 0) })
    }

    private fun buildAbout(parent: LinearLayout) {
        parent.add(backHeader(tr("About", "درباره"), tr("The client behind the tunnel", "کلاینت پشت تونل")))
        val mark = FrameLayout(this).apply {
            background = roundedBackground(palette.selected, dp(32).toFloat(), palette.primary, dp(1))
            addView(ToIconView(this@MainActivity, ToIcon.TUNNEL, palette.primary),
                FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        }
        parent.add(mark, dp(112), dp(112)).margin(top = 30)
        (mark.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        parent.add(label("TO Client", palette.text, 28f, Typeface.BOLD, Gravity.CENTER).apply {
            setPadding(0, dp(18), 0, 0)
        })
        parent.add(label(tr("Network Engine & Client", "موتور شبکه و کلاینت"), palette.textSecondary, 14f, gravityValue = Gravity.CENTER).apply {
            setPadding(0, dp(7), 0, dp(22))
        })
        detailGroup(parent, tr("Build", "ساخت"), listOf(
            tr("Version", "نسخه") to "1.0.0",
            tr("Core", "هسته") to "Xray 26.9.9",
            tr("TUN bridge", "پل TUN") to "HevTun 2.18.0",
            tr("Minimum Android", "حداقل اندروید") to "API 24",
        ))
        sectionTitle(parent, "Developer")
        val developerGroup = vertical().apply {
            background = roundedBackground(palette.glass, dp(18).toFloat(), palette.line, dp(1))
            add(settingActionRow(ToIcon.INFO, "Developer", "@Espierz") {
                openDeveloperProfile()
            }.apply {
                contentDescription = "Developer: @Espierz"
            })
        }
        parent.add(developerGroup)
        parent.add(label(
            tr("With special thanks to HO3EIN.zmn", "با تشکر فراوان از HO3EIN.zmn"),
            palette.warning,
            13f,
            Typeface.BOLD,
            Gravity.CENTER,
        ).apply {
            setPadding(dp(12), dp(16), dp(12), dp(2))
        })
        parent.add(messageCard(
            tr("Independent implementation", "پیاده‌سازی مستقل"),
            tr(
                "TO Client uses its own architecture, profile format, visual identity and encrypted storage.",
                "TO Client از معماری، فرمت پروفایل، هویت بصری و ذخیره‌سازی رمزگذاری‌شده مستقل استفاده می‌کند.",
            ),
            palette.primary,
        )).margin(top = 18)
    }

    private fun openDeveloperProfile() {
        runCatching {
            startActivity(developerIntent())
        }.onFailure {
            Toast.makeText(this, tr("Unable to open Telegram", "امکان باز کردن تلگرام نیست"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun headerBrand(): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.add(iconTile(ToIcon.TUNNEL, compact = true))
        row.add(vertical().apply {
            setPaddingRelative(dp(11), 0, 0, 0)
            add(label("TO Client", palette.text, 17f, Typeface.BOLD))
            add(label(tr("Network engine & client", "موتور شبکه و کلاینت"), palette.muted, 11f))
        }, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.add(iconButton(ToIcon.THEME, tr("Toggle theme", "تغییر پوسته")) { toggleTheme() })
        return row
    }

    private fun pageHeader(
        eyebrow: String,
        title: String,
        subtitle: String,
        actionIcon: ToIcon,
        action: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.add(vertical().apply {
            add(label(eyebrow.uppercase(Locale.ROOT), palette.muted, 10f, Typeface.BOLD))
            add(label(title, palette.text, 29f, Typeface.BOLD).apply { setPadding(0, dp(6), 0, 0) })
            add(label(subtitle, palette.textSecondary, 12f).apply { setPadding(0, dp(5), 0, 0) })
        }, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.add(iconButton(actionIcon, title, action))
        return row
    }

    private fun backHeader(title: String, subtitle: String): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.add(iconButton(ToIcon.BACK, tr("Back", "بازگشت")) { handleBack() })
        row.add(vertical().apply {
            setPaddingRelative(dp(13), 0, 0, 0)
            add(label(title, palette.text, 22f, Typeface.BOLD))
            if (subtitle.isNotEmpty()) add(label(subtitle, palette.textSecondary, 12f).apply {
                setPadding(0, dp(5), 0, 0)
            })
        }, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return row
    }

    private fun buildBottomNav(): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = roundedBackground(palette.elevated, dp(22).toFloat(), palette.lineStrong, dp(1))
            elevation = dp(10).toFloat()
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        listOf(
            Triple(Screen.HOME, ToIcon.HOME, tr("Home", "خانه")),
            Triple(Screen.PROFILES, ToIcon.PROFILES, tr("Profiles", "پروفایل‌ها")),
            Triple(Screen.DIAGNOSTICS, ToIcon.ACTIVITY, tr("Diagnostics", "عیب‌یابی")),
            Triple(Screen.SETTINGS, ToIcon.SETTINGS, tr("Settings", "تنظیمات")),
        ).forEach { (destination, icon, title) ->
            val selected = screen == destination ||
                (destination == Screen.PROFILES && screen == Screen.PROFILE_DETAIL) ||
                (destination == Screen.SETTINGS && screen in setOf(
                    Screen.DNS, Screen.ROUTING, Screen.NETWORK, Screen.SUBSCRIPTIONS,
                    Screen.BACKUP, Screen.ABOUT,
                ))
            val item = vertical().apply {
                gravity = Gravity.CENTER
                background = if (selected) roundedBackground(palette.selected, dp(17).toFloat()) else null
                isClickable = true
                isFocusable = true
                contentDescription = title
                add(ToIconView(this@MainActivity, icon, if (selected) palette.primary else palette.muted),
                    dp(22), dp(22))
                add(label(title, if (selected) palette.primary else palette.muted, 10f, Typeface.NORMAL, Gravity.CENTER)
                    .apply { setPadding(0, dp(4), 0, 0) })
                setOnClickListener { navigateRoot(destination) }
            }
            row.add(item, 0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        return row
    }

    private fun selectionRow(icon: ToIcon, title: String, value: String, action: () -> Unit): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(palette.glass, dp(14).toFloat(), palette.line, dp(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            add(ToIconView(this@MainActivity, icon, palette.primary), dp(20), dp(20))
            add(vertical().apply {
                setPaddingRelative(dp(11), 0, 0, 0)
                add(label(title, palette.muted, 10f, Typeface.BOLD))
                add(label(value, palette.text, 13f, Typeface.BOLD).apply {
                    maxLines = 1
                    setPadding(0, dp(3), 0, 0)
                })
            }, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            add(ToIconView(this@MainActivity, ToIcon.BACK, palette.muted).apply {
                rotation = if (persian) 0f else 180f
            }, dp(17), dp(17))
            setOnClickListener { action() }
        }

    private fun sourceSelectorRow(subscription: Subscription?): View {
        val id = subscription?.id
        val selected = selectedSubscriptionId == id
        val count = profilesFor(id).size
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = if (selected) roundedBackground(palette.selected, dp(14).toFloat()) else null
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            add(ToIconView(this@MainActivity,
                if (subscription == null) ToIcon.GLOBE else ToIcon.SUBSCRIPTION,
                if (selected) palette.primary else palette.textSecondary), dp(21), dp(21))
            add(vertical().apply {
                setPaddingRelative(dp(11), 0, 0, 0)
                add(label(subscription?.name ?: tr("Local configurations", "پیکربندی‌های محلی"),
                    palette.text, 14f, Typeface.BOLD))
                add(label("$count ${tr("configurations", "پیکربندی")}", palette.muted, 11f).apply {
                    setPadding(0, dp(3), 0, 0)
                })
            }, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (selected) add(statusBadge(tr("ACTIVE", "فعال"), palette.primary))
            setOnClickListener { selectSubscription(id) }
        }
    }

    private fun configurationRow(profile: VpnProfile, action: () -> Unit): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(
                if (profile.id == selectedProfileId) palette.selected else palette.glass,
                dp(14).toFloat(),
                if (profile.id == selectedProfileId) palette.primary else palette.line,
                dp(1),
            )
            setPadding(dp(12), dp(11), dp(12), dp(11))
            isClickable = true
            isFocusable = true
            add(ToIconView(this@MainActivity, if (profile.isFavorite) ToIcon.CHECK else ToIcon.GLOBE,
                if (profile.id == selectedProfileId) palette.primary else palette.textSecondary), dp(21), dp(21))
            add(vertical().apply {
                setPaddingRelative(dp(11), 0, 0, 0)
                add(label(profile.name, palette.text, 14f, Typeface.BOLD).apply { maxLines = 1 })
                add(label(listOfNotNull(
                    profile.protocolLabel.uppercase(Locale.ROOT),
                    profile.transportLabel?.uppercase(Locale.ROOT),
                    profile.securityLabel?.uppercase(Locale.ROOT),
                    profile.endpointLabel,
                ).joinToString(" · "), palette.muted, 10f).apply {
                    maxLines = 1
                    setPadding(0, dp(4), 0, 0)
                })
            }, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            add(label(latencyLabel(profile), latencyColor(profile),
                10f, Typeface.BOLD, Gravity.END))
            setOnClickListener { action() }
        }

    private fun compactMetric(value: String, caption: String): View =
        vertical().apply {
            gravity = Gravity.CENTER
            add(label(value, palette.text, 13f, Typeface.BOLD, Gravity.CENTER).apply { maxLines = 1 })
            add(label(caption, palette.muted, 10f, gravityValue = Gravity.CENTER).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }

    private fun statisticsCard(title: String, content: LinearLayout.() -> Unit): View =
        vertical().apply {
            background = roundedBackground(palette.glass, dp(18).toFloat(), palette.line, dp(1))
            setPadding(dp(14), dp(11), dp(14), dp(11))
            add(label(title, palette.text, 12f, Typeface.BOLD))
            content()
        }

    private fun liveDurationMetric(value: String, caption: String): View =
        vertical().apply {
            gravity = Gravity.CENTER
            val liveValue = label(value, palette.text, 13f, Typeface.BOLD, Gravity.CENTER).apply {
                maxLines = 1
            }
            durationValue = liveValue
            add(liveValue)
            add(label(caption, palette.muted, 10f, gravityValue = Gravity.CENTER).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }

    private fun connectionHeadline(state: ConnectionState, profile: VpnProfile?): String = when (state) {
        ConnectionState.Idle -> if (profile == null) tr("Choose a configuration", "یک پیکربندی انتخاب کنید")
            else tr("Ready to connect", "آماده اتصال")
        is ConnectionState.Preparing -> tr("Preparing configuration", "آماده‌سازی پیکربندی")
        is ConnectionState.StartingEngine -> tr("Starting secure engine", "راه‌اندازی موتور امن")
        is ConnectionState.EstablishingTun -> tr("Establishing VPN", "برقراری VPN")
        is ConnectionState.StartingBridge -> tr("Opening packet path", "باز کردن مسیر بسته")
        is ConnectionState.Connected -> tr("Connected", "متصل")
        is ConnectionState.Reconnecting -> tr("Reconnecting", "اتصال مجدد")
        is ConnectionState.Stopping -> tr("Disconnecting", "در حال قطع")
        is ConnectionState.Failed -> tr("Connection needs attention", "اتصال نیاز به بررسی دارد")
    }

    private fun connectionSupportingText(state: ConnectionState, profile: VpnProfile?): String = when (state) {
        ConnectionState.Idle -> profile?.let {
            listOfNotNull(selectedSubscription()?.name, it.name, it.endpointLabel).joinToString(" · ")
        } ?: tr("Add a subscription or local configuration to begin.",
            "برای شروع یک اشتراک یا پیکربندی محلی اضافه کنید.")
        is ConnectionState.Connected -> profile?.endpointLabel
            ?: tr("Traffic is protected by the active tunnel.", "ترافیک توسط تونل فعال محافظت می‌شود.")
        is ConnectionState.Reconnecting -> tr("Restoring the network path after a change.",
            "بازیابی مسیر شبکه پس از تغییر.")
        is ConnectionState.Failed -> state.message
        else -> tr("TO Client is completing the connection pipeline.",
            "TO Client در حال تکمیل مسیر اتصال است.")
    }

    private fun profileCard(profile: VpnProfile, selected: Boolean, action: () -> Unit): View =
        vertical().apply {
            background = roundedBackground(
                if (selected) palette.selected else palette.glass,
                dp(18).toFloat(),
                if (selected) palette.primary else palette.line,
                dp(1),
            )
            setPadding(dp(16), dp(15), dp(16), dp(15))
            isClickable = true
            isFocusable = true
            contentDescription = "${profile.name}, ${profile.protocolLabel}"
            val top = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            top.add(iconTile(ToIcon.GLOBE))
            top.add(vertical().apply {
                setPaddingRelative(dp(11), 0, 0, 0)
                add(label(profile.name, palette.text, 16f, Typeface.BOLD))
                add(label(profile.protocolLabel, palette.textSecondary, 11f).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val connected = isProfileConnected(profile.id)
            top.add(statusBadge(
                when {
                    connected -> tr("ACTIVE", "فعال")
                    selected -> tr("SELECTED", "انتخاب‌شده")
                    else -> tr("READY", "آماده")
                },
                if (connected || selected) palette.primary else palette.muted,
            ))
            add(top)
            profile.endpointLabel?.let {
                add(technicalLabel(it).apply { setPadding(dp(47), dp(12), 0, 0) })
            }
            add(label(
                listOf(
                    if (profile.ipv6Enabled) "IPv4 + IPv6" else "IPv4",
                    "${profile.routing.rules.size} ${tr("rules", "قانون")}",
                    "MTU ${profile.mtu}",
                ).joinToString("  ·  "),
                palette.muted,
                11f,
            ).apply { setPadding(dp(47), dp(12), 0, 0) })
            setOnClickListener { action() }
        }

    private fun metricBlock(symbol: String, caption: String, download: Boolean): View =
        vertical().apply {
            gravity = Gravity.CENTER
            add(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER
                add(label(symbol, if (download) palette.primary else palette.secondary, 18f, Typeface.NORMAL))
                val value = label("—", palette.text, 16f, Typeface.BOLD).apply {
                    setPaddingRelative(dp(7), 0, 0, 0)
                }
                if (download) downloadValue = value else uploadValue = value
                add(value)
            })
            add(label(caption, palette.muted, 10f, gravityValue = Gravity.CENTER).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }

    private fun quickPingControl(connected: Boolean): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = roundedBackground(palette.glass, dp(14).toFloat(), palette.lineStrong, dp(1))
            minimumHeight = dp(42)
            setPadding(dp(13), 0, dp(13), 0)
            isClickable = true
            isFocusable = true
            contentDescription = ls(R.string.quick_ping)
            val bolt = ToIconView(this@MainActivity, ToIcon.BOLT,
                if (connected) palette.primary else palette.muted)
            quickPingBolt = bolt
            add(bolt, dp(19), dp(19))
            val value = label(
                when {
                    quickPingRunning -> "…"
                    quickPingResult != null -> quickPingResult!!
                    else -> ls(R.string.quick_ping)
                },
                if (connected) palette.text else palette.muted,
                12f,
                Typeface.BOLD,
                Gravity.CENTER,
            ).apply { setPaddingRelative(dp(8), 0, 0, 0) }
            quickPingValue = value
            add(value)
            setOnClickListener { runQuickPing() }
        }

    private fun runQuickPing() {
        if (quickPingRunning) return
        val state = connectionState as? ConnectionState.Connected
        val profile = selectedProfile()
        if (state == null || profile == null || state.profileId != profile.id) {
            showGlassFeedback(ls(R.string.ping_connect_first), ToIcon.INFO)
            return
        }
        quickPingRunning = true
        quickPingResult = null
        quickPingValue?.text = "…"
        quickPingBolt?.let { bolt ->
            bolt.animate().cancel()
            bolt.scaleX = .82f
            bolt.scaleY = .82f
            bolt.rotation = -8f
            bolt.alpha = .55f
            bolt.animate()
                .scaleX(1.25f)
                .scaleY(1.25f)
                .rotation(8f)
                .alpha(1f)
                .setDuration(130)
                .withEndAction {
                    bolt.animate().scaleX(1f).scaleY(1f).rotation(0f)
                        .setDuration(150).start()
                }
                .start()
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    VpnRuntime.measureDelay("https://www.gstatic.com/generate_204")
                }.takeIf { it >= 0 } ?: error("Timeout")
            }.onSuccess { latency ->
                quickPingResult = "$latency ms"
                quickPingValue?.text = quickPingResult
                val updated = profile.copy(
                    latencyMillis = latency,
                    lastTestedMillis = System.currentTimeMillis(),
                    latencyStatus = LatencyTestStatus.SUCCESS,
                    latencyError = null,
                )
                profiles = profiles.map { if (it.id == updated.id) updated else it }
                runCatching {
                    withContext(Dispatchers.IO) {
                        profilesRepo.upsert(updated, profilesRepo.getSensitive(profile))
                    }
                }
            }.onFailure { error ->
                quickPingResult = ls(R.string.ping_timeout)
                quickPingValue?.text = quickPingResult
                val updated = profile.copy(
                    lastTestedMillis = System.currentTimeMillis(),
                    latencyStatus = LatencyTestStatus.TIMEOUT,
                    latencyError = (error.message ?: "Timeout").take(200),
                )
                profiles = profiles.map { if (it.id == updated.id) updated else it }
                runCatching {
                    withContext(Dispatchers.IO) {
                        profilesRepo.upsert(updated, profilesRepo.getSensitive(profile))
                    }
                }
            }
            quickPingRunning = false
        }
    }

    private fun trafficTotalBlock(value: TextView, caption: String): View =
        vertical().apply {
            gravity = Gravity.CENTER
            add(value)
            add(label(caption, palette.muted, 10f, gravityValue = Gravity.CENTER).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }

    private fun healthCell(icon: ToIcon, value: String, caption: String): View =
        vertical().apply {
            gravity = Gravity.CENTER
            background = roundedBackground(palette.glass, dp(16).toFloat(), palette.line, dp(1))
            add(ToIconView(this@MainActivity, icon, palette.primary), dp(22), dp(22))
            add(label(value, palette.text, if (value.length > 12) 10f else 13f, Typeface.BOLD, Gravity.CENTER).apply {
                maxLines = 1
                setPadding(dp(4), dp(8), dp(4), 0)
            })
            add(label(caption, palette.muted, 10f, gravityValue = Gravity.CENTER).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }

    private fun summaryCard(value: String, caption: String, color: Int): View =
        vertical().apply {
            background = roundedBackground(palette.glass, dp(18).toFloat(), palette.line, dp(1))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            add(label(value, color, 18f, Typeface.BOLD))
            add(label(caption, palette.textSecondary, 11f).apply { setPadding(0, dp(7), 0, 0) })
        }

    private fun diagnosticRow(title: String, state: String, ready: Boolean): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(palette.glass, dp(16).toFloat(), palette.line, dp(1))
            setPadding(dp(15), dp(14), dp(15), dp(14))
            add(ToIconView(this@MainActivity, if (ready) ToIcon.CHECK else ToIcon.ACTIVITY,
                if (ready) palette.primary else palette.muted), dp(20), dp(20))
            add(label(title, palette.text, 14f, Typeface.BOLD).apply { setPaddingRelative(dp(11), 0, 0, 0) },
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            add(label(state, if (ready) palette.primary else palette.muted, 10f, Typeface.BOLD))
        }

    private data class SettingItem(val icon: ToIcon, val title: String, val value: String, val screen: Screen)

    private fun settingSection(parent: LinearLayout, title: String, items: List<SettingItem>) {
        sectionTitle(parent, title)
        val group = vertical().apply {
            background = roundedBackground(palette.glass, dp(19).toFloat(), palette.line, dp(1))
        }
        items.forEachIndexed { index, item ->
            if (index > 0) group.add(divider())
            group.add(settingActionRow(item.icon, item.title, item.value) { navigate(item.screen) })
        }
        parent.add(group)
    }

    private fun settingActionRow(icon: ToIcon, title: String, value: String, action: () -> Unit): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            minimumHeight = dp(62)
            isClickable = true
            isFocusable = true
            add(iconTile(icon, compact = true))
            add(label(title, palette.text, 14f, Typeface.BOLD).apply { setPaddingRelative(dp(11), 0, 0, 0) },
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            add(label(value, palette.textSecondary, 11f).apply {
                maxLines = 1
                gravity = Gravity.END
            })
            add(ToIconView(this@MainActivity, ToIcon.BACK, palette.muted).apply {
                rotation = if (persian) 0f else 180f
            }, dp(18), dp(18))
            setOnClickListener { action() }
        }

    private fun settingToggleRow(
        icon: ToIcon,
        title: String,
        summary: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13), dp(11), dp(13), dp(11))
        minimumHeight = dp(66)
        add(iconTile(icon, compact = true))
        add(vertical().apply {
            setPaddingRelative(dp(11), 0, dp(8), 0)
            add(label(title, palette.text, 14f, Typeface.BOLD))
            add(label(summary, palette.textSecondary, 10f).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val toggle = CheckBox(this@MainActivity).apply {
            isChecked = checked
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.primary)
            contentDescription = title
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        add(toggle, dp(48), dp(48))
        setOnClickListener { toggle.isChecked = !toggle.isChecked }
    }

    private fun ruleCard(rule: RoutingRule, remove: () -> Unit): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(palette.glass, dp(16).toFloat(), palette.line, dp(1))
            setPadding(dp(14), dp(13), dp(12), dp(13))
            add(statusBadge(rule.action.name, when (rule.action) {
                RoutingAction.PROXY -> palette.primary
                RoutingAction.DIRECT -> palette.textSecondary
                RoutingAction.BLOCK -> palette.error
            }))
            add(vertical().apply {
                setPaddingRelative(dp(12), 0, 0, 0)
                add(technicalLabel((rule.domains + rule.cidrs).joinToString()))
                add(label(
                    when (rule.action) {
                        RoutingAction.PROXY -> tr("Send through active profile", "ارسال از پروفایل فعال")
                        RoutingAction.DIRECT -> tr("Use direct network path", "استفاده از مسیر مستقیم")
                        RoutingAction.BLOCK -> tr("Reject matching traffic", "مسدودسازی ترافیک منطبق")
                    },
                    palette.muted,
                    11f,
                ).apply { setPadding(0, dp(5), 0, 0) })
            }, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            add(iconButton(ToIcon.CLOSE, tr("Remove rule", "حذف قانون"), remove))
        }

    private fun messageCard(title: String, message: String, accent: Int): View =
        vertical().apply {
            background = roundedBackground(palette.glass, dp(18).toFloat(), palette.line, dp(1))
            setPadding(dp(16), dp(15), dp(16), dp(15))
            add(label(title, accent, 15f, Typeface.BOLD))
            add(label(message, palette.textSecondary, 12f).apply {
                setPadding(0, dp(8), 0, 0)
            })
        }

    private fun detailGroup(parent: LinearLayout, title: String, values: List<Pair<String, String>>) {
        sectionTitle(parent, title)
        val group = vertical().apply {
            background = roundedBackground(palette.glass, dp(18).toFloat(), palette.line, dp(1))
        }
        values.forEachIndexed { index, pair ->
            if (index > 0) group.add(divider())
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(15), dp(13), dp(15), dp(13))
                add(label(pair.first, palette.textSecondary, 12f), 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                add(technicalLabel(pair.second).apply {
                    gravity = Gravity.END
                    maxLines = 2
                }, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            group.add(row)
        }
        parent.add(group)
    }

    private fun sectionTitle(parent: LinearLayout, title: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(22), 0, dp(9))
        }
        row.add(label(title, palette.text, 14f, Typeface.BOLD), 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (action != null) {
            val view = label(action, if (onAction != null) palette.primary else palette.muted, 10f, Typeface.BOLD)
            if (onAction != null) {
                view.setPadding(dp(12), dp(8), dp(4), dp(8))
                view.isClickable = true
                view.setOnClickListener { onAction() }
            }
            row.add(view)
        }
        parent.add(row)
    }

    private fun emptyCard(parent: LinearLayout, title: String, message: String, actionText: String, action: () -> Unit) {
        val card = vertical().apply {
            gravity = Gravity.CENTER
            background = roundedBackground(palette.glass, dp(22).toFloat(), palette.line, dp(1))
            setPadding(dp(24), dp(28), dp(24), dp(28))
            add(iconTile(ToIcon.TUNNEL))
            add(label(title, palette.text, 18f, Typeface.BOLD, Gravity.CENTER).apply {
                setPadding(0, dp(15), 0, 0)
            })
            if (message.isNotEmpty()) add(label(message, palette.textSecondary, 12f, gravityValue = Gravity.CENTER).apply {
                setPadding(0, dp(9), 0, 0)
            })
            add(primaryButton(actionText, action)).margin(top = 18)
        }
        parent.add(card).margin(top = 12)
    }

    private fun loadingCard(parent: LinearLayout, message: String) {
        parent.add(messageCard(tr("Loading", "در حال بارگذاری"), message, palette.primary)).margin(top = 18)
    }

    private fun noProfileCard(parent: LinearLayout) {
        emptyCard(
            parent,
            tr("Select a profile first", "ابتدا یک پروفایل انتخاب کنید"),
            tr("Network policies belong to a real stored profile.", "سیاست شبکه متعلق به یک پروفایل ذخیره‌شده واقعی است."),
            tr("Open profiles", "باز کردن پروفایل‌ها"),
        ) { navigateRoot(Screen.PROFILES) }
    }

    private fun compactAction(text: String, action: () -> Unit): TextView =
        label(text, palette.text, 11f, Typeface.BOLD, Gravity.CENTER).apply {
            background = roundedBackground(palette.glassStrong, dp(12).toFloat(), palette.line, dp(1))
            setPadding(dp(10), 0, dp(10), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun subscriptionUsagePanel(subscription: Subscription): View {
        val total = subscription.totalBytes?.takeIf { it > 0 }
        val used = (subscription.uploadedBytes ?: 0) + (subscription.downloadedBytes ?: 0)
        val remaining = total?.let { (it - used).coerceAtLeast(0) }
        val unavailable = ls(R.string.subscription_unavailable)
        return vertical().apply {
            background = roundedBackground(palette.glassStrong, dp(15).toFloat(), palette.line, dp(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (total != null) {
                add(SubscriptionUsageView(this@MainActivity).apply {
                    palette = this@MainActivity.palette
                    usedBytes = used
                    totalBytes = total
                    contentDescription = "${((used.toDouble() / total) * 100).toInt()}%"
                    layoutDirection = if (persian) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                }, ViewGroup.LayoutParams.MATCH_PARENT, dp(34))
            }
            val first = LinearLayout(this@MainActivity).apply { weightSum = 2f }
            first.add(usageMetric(
                total?.let(::formatBytes) ?: unavailable,
                ls(R.string.subscription_total),
            ), 0, dp(50), 1f)
            first.add(usageMetric(
                if (total != null) formatBytes(used) else unavailable,
                ls(R.string.subscription_used),
            ), 0, dp(50), 1f)
            add(first).margin(top = if (total != null) 3 else 0)
            val second = LinearLayout(this@MainActivity).apply { weightSum = 2f }
            second.add(usageMetric(
                remaining?.let(::formatBytes) ?: unavailable,
                ls(R.string.subscription_remaining),
            ), 0, dp(50), 1f)
            second.add(usageMetric(
                subscription.expiresAtMillis?.let(::remainingSubscriptionTime) ?: unavailable,
                ls(R.string.subscription_time),
            ), 0, dp(50), 1f)
            add(second)
        }
    }

    private fun usageMetric(value: String, caption: String): View =
        vertical().apply {
            gravity = Gravity.CENTER
            add(label(value, palette.text, 12f, Typeface.BOLD, Gravity.CENTER).apply {
                maxLines = 1
            })
            add(label(caption, palette.muted, 9f, gravityValue = Gravity.CENTER).apply {
                setPadding(0, dp(3), 0, 0)
            })
        }

    private fun remainingSubscriptionTime(expiresAtMillis: Long): String {
        val remaining = expiresAtMillis - System.currentTimeMillis()
        if (remaining <= 0) return ls(R.string.subscription_expired)
        val days = ((remaining + DAY_MILLIS - 1) / DAY_MILLIS).coerceAtLeast(1)
        return ls(R.string.subscription_days_remaining, days)
    }

    private fun primaryButton(text: String, action: () -> Unit): TextView =
        label(text, if (lightTheme) Color.WHITE else Color.rgb(4, 24, 20), 13f, Typeface.BOLD, Gravity.CENTER).apply {
            background = roundedBackground(palette.primary, dp(14).toFloat())
            minimumHeight = dp(48)
            setPadding(dp(18), 0, dp(18), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun secondaryButton(text: String, danger: Boolean = false, action: () -> Unit): TextView =
        label(text, if (danger) palette.error else palette.text, 13f, Typeface.BOLD, Gravity.CENTER).apply {
            background = roundedBackground(palette.glassStrong, dp(14).toFloat(),
                if (danger) palette.error else palette.lineStrong, dp(1))
            minimumHeight = dp(48)
            setPadding(dp(18), 0, dp(18), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun iconButton(icon: ToIcon, description: String, action: () -> Unit): FrameLayout =
        FrameLayout(this).apply {
            background = roundedBackground(palette.glass, dp(13).toFloat(), palette.lineStrong, dp(1))
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = description
            addView(ToIconView(this@MainActivity, icon, palette.textSecondary),
                FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
            setOnClickListener { action() }
        }

    private fun iconTile(icon: ToIcon, compact: Boolean = false): FrameLayout =
        FrameLayout(this).apply {
            val size = if (compact) 38 else 44
            background = roundedBackground(palette.selected, dp(if (compact) 12 else 14).toFloat(),
                Color.argb(45, Color.red(palette.primary), Color.green(palette.primary), Color.blue(palette.primary)), dp(1))
            addView(ToIconView(this@MainActivity, icon, palette.primary),
                FrameLayout.LayoutParams(dp(if (compact) 19 else 21), dp(if (compact) 19 else 21), Gravity.CENTER))
            minimumWidth = dp(size)
            minimumHeight = dp(size)
        }

    private fun statusBadge(text: String, color: Int): TextView =
        label(text.uppercase(Locale.ROOT), color, 9f, Typeface.BOLD, Gravity.CENTER).apply {
            background = roundedBackground(
                Color.argb(25, Color.red(color), Color.green(color), Color.blue(color)),
                dp(10).toFloat(),
                Color.argb(55, Color.red(color), Color.green(color), Color.blue(color)),
                dp(1),
            )
            setPadding(dp(9), dp(6), dp(9), dp(6))
        }

    private fun technicalLabel(value: String): TextView =
        label(value, palette.textSecondary, 11f).apply {
            typeface = Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_LTR
        }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(palette.line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(14)
            marginEnd = dp(14)
        }
    }

    private fun styledInput(value: String, hintValue: String): EditText =
        EditText(this).apply {
            setText(value)
            hint = hintValue
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            textSize = 14f
            background = roundedBackground(palette.glassStrong, dp(14).toFloat(), palette.lineStrong, dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minHeight = dp(50)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

    private fun formField(parent: LinearLayout, title: String, input: EditText) {
        parent.add(label(title, palette.textSecondary, 11f, Typeface.BOLD).apply {
            setPadding(dp(4), dp(17), dp(4), dp(8))
        })
        parent.add(input)
    }

    private fun checkRow(text: String, checked: Boolean): CheckBox =
        CheckBox(this).apply {
            this.text = text
            setTextColor(palette.text)
            isChecked = checked
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.primary)
            background = roundedBackground(palette.glass, dp(14).toFloat(), palette.line, dp(1))
            setPadding(dp(12), dp(9), dp(12), dp(9))
            minHeight = dp(52)
        }

    private fun connectOrDisconnect() {
        if (connectionState !is ConnectionState.Idle && connectionState !is ConnectionState.Failed) {
            AndroidCompatibility.stopVpn(this)
            return
        }
        val id = selectedProfileId ?: run {
            toast(tr("Import or select a profile first", "ابتدا یک پروفایل وارد یا انتخاب کنید"))
            navigateRoot(Screen.PROFILES)
            return
        }
        requestVpnConnect(id)
    }

    private fun connectProfile(id: ProfileId) {
        if (isProfileConnected(id)) {
            AndroidCompatibility.stopVpn(this)
        } else {
            requestVpnConnect(id)
        }
    }

    private fun requestVpnConnect(id: ProfileId) {
        val report = AndroidCompatibility.inspect(this)
        when {
            CompatibilityIssue.ANDROID_VERSION_UNSUPPORTED in report.issues ->
                toast(tr("This Android version is not supported", "این نسخه اندروید پشتیبانی نمی‌شود"))
            CompatibilityIssue.NATIVE_ABI_UNSUPPORTED in report.issues ->
                toast(tr("This device ABI is not supported", "معماری این دستگاه پشتیبانی نمی‌شود"))
            CompatibilityIssue.VPN_CONSENT_REQUIRED in report.issues -> {
                pendingConnectId = id
                @Suppress("DEPRECATION")
                startActivityForResult(AndroidCompatibility.vpnConsentIntent(this), REQUEST_VPN_CONSENT)
            }
            else -> startVpn(id)
        }
    }

    private fun startVpn(id: ProfileId) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        runCatching { AndroidCompatibility.startVpn(this, id) }
            .onFailure { toast(it.message ?: tr("Unable to start VPN", "راه‌اندازی VPN ممکن نیست")) }
    }

    private fun showImportChoices() {
        val importChoices = listOf(
            Triple(ToIcon.BACKUP, ls(R.string.import_from_clipboard)) { importClipboard() },
            Triple(ToIcon.BACKUP, ls(R.string.import_from_file)) {
                pendingDocument = PendingDocument.IMPORT
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, REQUEST_DOCUMENT)
            },
            Triple(ToIcon.BOLT, ls(R.string.scan_qr_code)) { startQrScanner() },
            Triple(ToIcon.GLOBE, ls(R.string.scan_qr_image)) {
                pendingDocument = PendingDocument.QR_IMAGE
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }, REQUEST_DOCUMENT)
            },
            Triple(ToIcon.PLUS, ls(R.string.add_manually)) {
                showConfigTextDialog(
                    title = ls(R.string.add_manually),
                    description = ls(R.string.manual_config_hint),
                )
            },
            Triple(ToIcon.INFO, ls(R.string.custom_config_json)) {
                showConfigTextDialog(
                    title = ls(R.string.custom_config_json),
                    description = ls(R.string.custom_json_hint),
                    placeholder = "{ \"outbounds\": […] }",
                )
            },
        )
        lateinit var dialog: AlertDialog
        val content = vertical().apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundedBackground(palette.glass, dp(18).toFloat(), palette.line, dp(1))
            sectionTitle(this, ls(R.string.import_menu_section).uppercase())
            importChoices.forEachIndexed { index, choice ->
                if (index > 0) add(divider())
                add(settingActionRow(choice.first, choice.second, "") {
                    dialog.dismiss()
                    choice.third()
                })
            }
            sectionTitle(this, ls(R.string.subscription_menu_section).uppercase())
            add(settingActionRow(ToIcon.SUBSCRIPTION, ls(R.string.add_subscription), "") {
                dialog.dismiss()
                showSubscriptionDialog()
            })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        dialog = styledDialogBuilder(tr("Add to TO Client", "افزودن به TO Client"))
            .setView(scroll)
            .setNegativeButton(tr("Cancel", "لغو"), null)
            .create()
        dialog.show()
    }

    private fun showConfigTextDialog(
        initial: String = "",
        title: String = tr("Configuration text", "متن پیکربندی"),
        description: String = tr(
            "Paste one supported URI or raw Xray JSON.",
            "یک URI پشتیبانی‌شده یا JSON خام Xray را وارد کنید.",
        ),
        placeholder: String = "vless://…",
    ) {
        val input = styledInput(initial, placeholder).apply {
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val container = vertical().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            add(label(
                description,
                palette.textSecondary,
                12f,
            ))
            add(input).margin(top = 12)
        }
        styledDialogBuilder(title)
            .setView(container)
            .setPositiveButton(tr("Import", "درون‌ریزی")) { _, _ -> importConfig(input.text.toString()) }
            .setNegativeButton(tr("Cancel", "لغو"), null)
            .show()
    }

    private fun importClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (value.isBlank()) toast(tr("Clipboard has no configuration text", "کلیپ‌بورد متن پیکربندی ندارد"))
        else showConfigTextDialog(value)
    }

    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        @Suppress("DEPRECATION")
        val value = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        intent.action = null
        if (value.isNotBlank()) showConfigTextDialog(value)
    }

    private fun importQrImage(uri: Uri) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                        ?: error("Unable to decode QR image")
                }
            }.onSuccess(::importQrBitmap).onFailure(::showError)
        }
    }

    private fun importQrBitmap(bitmap: Bitmap) {
        runCatching {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        }.onSuccess(::handleDecodedQr)
            .onFailure { showError(IllegalArgumentException("No supported QR code was found", it)) }
    }

    private fun startQrScanner() {
        runCatching {
            IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt(tr("Point the camera at a QR code", "دوربین را روی کد QR بگیرید"))
                .setBeepEnabled(false)
                .setBarcodeImageEnabled(false)
                .setOrientationLocked(false)
                .initiateScan()
        }.onFailure(::showError)
    }

    private fun handleDecodedQr(rawValue: String) {
        val value = rawValue.trim()
        if (value.isEmpty()) {
            toast(tr("The QR code is empty", "کد QR خالی است"))
            return
        }
        val uri = runCatching { java.net.URI(value) }.getOrNull()
        when {
            uri?.scheme.equals("https", ignoreCase = true) && !uri?.host.isNullOrBlank() ->
                showSubscriptionDialog(initialUrl = value)
            uri?.scheme.equals("http", ignoreCase = true) ->
                showError(IllegalArgumentException(tr(
                    "Subscription URLs must use HTTPS.",
                    "نشانی اشتراک باید از HTTPS استفاده کند.",
                )))
            value.startsWith("{") || "://" in value -> showConfigTextDialog(value)
            else -> showError(IllegalArgumentException(tr(
                "This QR code does not contain a supported subscription URL or configuration.",
                "این کد QR شامل نشانی اشتراک یا پیکربندی پشتیبانی‌شده نیست.",
            )))
        }
    }

    private fun importConfig(value: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    SubscriptionParser(importer).parse(value.toByteArray())
                }
            }.onSuccess { imported ->
                storeImported(imported)
            }.onFailure(::showError)
        }
    }

    private fun storeImported(imported: List<ImportedConfig>) {
        scope.launch {
            runCatching {
                val stored = withContext(Dispatchers.IO) {
                    imported.map { item ->
                        val id = ProfileId("manual-${item.fingerprint.take(32)}")
                        val profile = VpnProfile(
                            id = id,
                            name = item.name.take(120),
                            secretRef = "cfg-${item.fingerprint}",
                            fingerprint = item.fingerprint,
                            protocolLabel = item.protocol,
                            endpointLabel = item.endpoint,
                            transportLabel = item.transport,
                            securityLabel = item.security,
                        )
                        profilesRepo.upsert(profile, SensitiveProfileConfig(item.xrayJson))
                        profile
                    }
                }
                selectedSubscriptionId = null
                selectedProfileId = stored.firstOrNull()?.id
                persistSelection()
                stored.size
            }.onSuccess { count ->
                reloadData {
                    navigateRoot(Screen.PROFILES)
                    toast("$count ${tr("profiles imported securely", "پروفایل با امنیت درون‌ریزی شد")}")
                }
            }.onFailure(::showError)
        }
    }

    private fun importDocument(uri: Uri) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        require(bytes.size <= ConfigImporter.MAX_IMPORT_BYTES) { "File is too large" }
                        bytes
                    } ?: error("Unable to open file")
                }
            }.onSuccess { bytes ->
                runCatching { SubscriptionParser(importer).parse(bytes) }
                    .onSuccess(::storeImported)
                    .onFailure(::showError)
            }.onFailure(::showError)
        }
    }

    private fun showSubscriptionDialog(initialUrl: String = "") {
        val name = styledInput("", tr("My subscription", "اشتراک من"))
        val url = styledInput(initialUrl, "https://example.com/subscription")
        val box = vertical().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            add(label(tr("Name", "نام"), palette.textSecondary, 11f, Typeface.BOLD))
            add(name).margin(top = 7)
            add(label("HTTPS URL", palette.textSecondary, 11f, Typeface.BOLD).apply {
                setPadding(0, dp(14), 0, 0)
            })
            add(url).margin(top = 7)
        }
        styledDialogBuilder(tr("Add subscription", "افزودن اشتراک"))
            .setView(box)
            .setPositiveButton(tr("Save & refresh", "ذخیره و به‌روزرسانی")) { _, _ ->
                addSubscription(name.text.toString(), url.text.toString())
            }
            .setNeutralButton(tr("Scan QR code", "اسکن کد QR")) { _, _ -> startQrScanner() }
            .setNegativeButton(tr("Cancel", "لغو"), null)
            .show()
    }

    private fun addSubscription(name: String, url: String) {
        val normalizedUrl = url.trim()
        val parsedUrl = runCatching { java.net.URI(normalizedUrl) }.getOrNull()
        if (!parsedUrl?.scheme.equals("https", ignoreCase = true) || parsedUrl?.host.isNullOrBlank()) {
            showError(IllegalArgumentException(tr(
                "Enter a valid HTTPS subscription URL.",
                "یک نشانی معتبر HTTPS برای اشتراک وارد کنید.",
            )))
            return
        }
        scope.launch {
            runCatching {
                val normalizedName = name.trim().ifEmpty { tr("Subscription", "اشتراک") }
                val hash = sha256(normalizedUrl).take(32)
                val subscription = Subscription(
                    id = SubscriptionId("sub-$hash"),
                    name = normalizedName.take(120),
                    urlSecretRef = "subscription-url-$hash",
                    sourceHost = parsedUrl.host,
                )
                val result = withContext(Dispatchers.IO) {
                    require(subscriptionsRepo.get(subscription.id) == null) {
                        "This subscription has already been added"
                    }
                    subscriptionsRepo.upsert(subscription, normalizedUrl)
                    try {
                        refreshSubscriptionInternal(subscription)
                    } catch (error: Throwable) {
                        runCatching {
                            profilesRepo.replaceSubscriptionProfiles(subscription.id.value, emptyList())
                        }
                        runCatching { subscriptionsRepo.delete(subscription.id) }
                        throw IllegalArgumentException(subscriptionErrorMessage(error), error)
                    }
                }
                selectedSubscriptionId = subscription.id
                selectedProfileId = result.importedProfiles.firstOrNull()
                persistSelection()
                SubscriptionScheduler.schedule(this@MainActivity)
                result
            }.onSuccess {
                reloadData {
                    navigateRoot(Screen.SUBSCRIPTIONS)
                    showGlassFeedback(importResultMessage(it), ToIcon.SUBSCRIPTION)
                }
            }.onFailure(::showError)
        }
    }

    private fun subscriptionErrorMessage(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        val message = chain.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
            ?: tr("The subscription could not be validated.", "اعتبارسنجی اشتراک ممکن نشد.")
        return when {
            chain.any { it is java.net.SocketTimeoutException } ->
                tr("Subscription validation timed out. Check the URL or network and try again.",
                    "زمان اعتبارسنجی اشتراک تمام شد. نشانی یا شبکه را بررسی و دوباره تلاش کنید.")
            chain.any { it is java.net.UnknownHostException } ->
                tr("The subscription host could not be reached.",
                    "دسترسی به میزبان اشتراک ممکن نشد.")
            chain.any { it is javax.net.ssl.SSLException } ->
                tr("The subscription has an invalid or untrusted HTTPS certificate.",
                    "گواهی HTTPS اشتراک نامعتبر یا غیرقابل اعتماد است.")
            "no supported profiles" in message.lowercase() ->
                tr("The subscription contains no configurations supported by TO Client.",
                    "اشتراک هیچ پیکربندی پشتیبانی‌شده‌ای برای TO Client ندارد.")
            "empty" in message.lowercase() ->
                tr("The subscription is empty.", "اشتراک خالی است.")
            "http status" in message.lowercase() -> message
            else -> tr("Subscription validation failed: $message",
                "اعتبارسنجی اشتراک ناموفق بود: $message")
        }
    }

    private fun showSubscriptionActions(subscription: Subscription) {
        val actions = arrayOf(
            tr("Use this subscription", "استفاده از این اشتراک"),
            tr("Edit name", "ویرایش نام"),
            if (subscription.enabled) tr("Pause updates", "توقف به‌روزرسانی")
            else tr("Enable updates", "فعال‌سازی به‌روزرسانی"),
            tr("Delete subscription", "حذف اشتراک"),
        )
        styledDialogBuilder(subscription.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        selectSubscription(subscription.id)
                        navigateRoot(Screen.PROFILES)
                    }
                    1 -> showEditSubscription(subscription)
                    2 -> setSubscriptionEnabled(subscription, !subscription.enabled)
                    3 -> confirmDeleteSubscription(subscription)
                }
            }
            .setNegativeButton(tr("Cancel", "لغو"), null)
            .show()
    }

    private fun showEditSubscription(subscription: Subscription) {
        val name = styledInput(subscription.name, subscription.name)
        styledDialogBuilder(tr("Edit subscription", "ویرایش اشتراک"))
            .setView(vertical().apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                add(name)
            })
            .setPositiveButton(tr("Save", "ذخیره")) { _, _ ->
                scope.launch {
                    runCatching {
                        val value = name.text.toString().trim()
                        require(value.isNotEmpty()) { "Subscription name is required" }
                        withContext(Dispatchers.IO) {
                            subscriptionsRepo.upsert(subscription.copy(name = value.take(120)), null)
                        }
                    }.onSuccess { reloadData() }.onFailure(::showError)
                }
            }
            .setNegativeButton(tr("Cancel", "لغو"), null)
            .show()
    }

    private fun setSubscriptionEnabled(subscription: Subscription, enabled: Boolean) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    subscriptionsRepo.upsert(subscription.copy(enabled = enabled), null)
                }
            }.onSuccess { reloadData() }.onFailure(::showError)
        }
    }

    private fun refreshSubscription(subscription: Subscription) {
        showGlassFeedback(
            tr("Refreshing subscription…", "در حال به‌روزرسانی اشتراک…"),
            ToIcon.SUBSCRIPTION,
        )
        scope.launch {
            runCatching { refreshSubscriptionInternal(subscription) }
                .onSuccess {
                    reloadData {
                        showGlassFeedback(importResultMessage(it), ToIcon.SUBSCRIPTION)
                    }
                }.onFailure {
                    reloadData()
                    showError(it)
                }
        }
    }

    private suspend fun refreshSubscriptionInternal(subscription: Subscription): SubscriptionUpdateResult =
        withContext(Dispatchers.IO) {
            SubscriptionUpdater(
                subscriptionsRepo,
                profilesRepo,
                HttpsSubscriptionFetcher(),
            ).update(subscription.id)
        }

    private fun importResultMessage(result: SubscriptionUpdateResult): String =
        if (result.changed) {
            "${result.importedProfiles.size} ${tr("profiles imported", "پروفایل درون‌ریزی شد")}"
        } else tr("Subscription is already current", "اشتراک به‌روز است")

    private fun showRenameProfile(profile: VpnProfile) {
        val input = styledInput(profile.name, profile.name)
        styledDialogBuilder(tr("Rename profile", "تغییر نام پروفایل"))
            .setView(vertical().apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                add(input)
            })
            .setPositiveButton(tr("Save", "ذخیره")) { _, _ ->
                runCatching { profile.copy(name = input.text.toString().trim()) }
                    .onSuccess {
                        updateProfile(it) {
                            toast(tr("Profile renamed", "نام پروفایل تغییر کرد"))
                        }
                    }.onFailure(::showError)
            }
            .setNegativeButton(tr("Cancel", "لغو"), null)
            .show()
    }

    private fun copyProfile(profile: VpnProfile) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { profilesRepo.getSensitive(profile).xrayJson }
            }.onSuccess { value ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("TO Client profile", value),
                )
                toast(tr("Configuration copied", "پیکربندی کپی شد"))
            }.onFailure(::showError)
        }
    }

    private fun showXrayJsonEditor(profile: VpnProfile) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { profilesRepo.getSensitive(profile).xrayJson }
            }.onSuccess { currentJson ->
                val input = styledInput(currentJson, "{ \"outbounds\": […] }").apply {
                    minLines = 12
                    maxLines = 22
                    gravity = Gravity.TOP or Gravity.START
                    typeface = Typeface.MONOSPACE
                    textDirection = View.TEXT_DIRECTION_LTR
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                val container = vertical().apply {
                    setPadding(dp(18), dp(6), dp(18), 0)
                    add(label(
                        tr(
                            "The JSON is validated with the existing Xray planner before it is saved.",
                            "JSON پیش از ذخیره با برنامه‌ریز فعلی Xray اعتبارسنجی می‌شود.",
                        ),
                        palette.textSecondary,
                        11f,
                    ))
                    add(input).margin(top = 10)
                }
                val dialog = styledDialogBuilder(tr("Xray JSON", "JSON ایکس‌ری"))
                    .setView(container)
                    .setPositiveButton(tr("Validate & save", "اعتبارسنجی و ذخیره"), null)
                    .setNegativeButton(tr("Cancel", "لغو"), null)
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val candidate = input.text.toString().trim()
                        if (candidate.isEmpty()) {
                            input.error = tr("Configuration JSON cannot be empty", "JSON پیکربندی نمی‌تواند خالی باشد")
                            return@setOnClickListener
                        }
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.Default) {
                                    XrayConfigPlanner().prepare(
                                        candidate,
                                        SocksEndpoint(port = 11_080),
                                        profile,
                                    )
                                }
                                withContext(Dispatchers.IO) {
                                    profilesRepo.upsert(profile, SensitiveProfileConfig(candidate))
                                }
                            }.onSuccess {
                                dialog.dismiss()
                                toast(tr(
                                    "Configuration saved. Reconnect to apply it.",
                                    "پیکربندی ذخیره شد. برای اعمال دوباره متصل شوید.",
                                ))
                            }.onFailure { error ->
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                input.error = error.message ?: tr("Invalid Xray JSON", "JSON ایکس‌ری نامعتبر است")
                            }
                        }
                    }
                }
                dialog.show()
            }.onFailure(::showError)
        }
    }

    private fun shareProfile(profile: VpnProfile) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { profilesRepo.getSensitive(profile).xrayJson }
            }.onSuccess { value ->
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, value)
                    },
                    tr("Share configuration", "اشتراک‌گذاری پیکربندی"),
                ))
            }.onFailure(::showError)
        }
    }

    private fun showProfileQr(profile: VpnProfile) {
        scope.launch {
            runCatching {
                val value = withContext(Dispatchers.IO) {
                    profilesRepo.getSensitive(profile).xrayJson
                }
                withContext(Dispatchers.Default) {
                    val matrix = QRCodeWriter().encode(
                        value,
                        BarcodeFormat.QR_CODE,
                        720,
                        720,
                        mapOf(EncodeHintType.MARGIN to 2),
                    )
                    Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
                        val pixels = IntArray(matrix.width * matrix.height) { index ->
                            if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
                        }
                        setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
                    }
                }
            }.onSuccess { bitmap ->
                styledDialogBuilder(tr("Configuration QR", "QR پیکربندی"))
                    .setView(ImageView(this@MainActivity).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                        setPadding(dp(18), dp(12), dp(18), 0)
                        contentDescription = tr("Configuration QR code", "کد QR پیکربندی")
                    })
                    .setPositiveButton(tr("Done", "تمام"), null)
                    .show()
            }.onFailure {
                showError(IllegalArgumentException(tr(
                    "This configuration is too large for a QR code. Use Copy or Share instead.",
                    "این پیکربندی برای کد QR بیش از حد بزرگ است. از کپی یا اشتراک‌گذاری استفاده کنید.",
                ), it))
            }
        }
    }

    private fun confirmDeleteProfile(profile: VpnProfile) {
        styledDialogBuilder(tr("Delete profile?", "پروفایل حذف شود؟"))
            .setMessage(tr(
                "The encrypted configuration will also be removed.",
                "پیکربندی رمزگذاری‌شده نیز حذف خواهد شد.",
            ))
            .setPositiveButton(tr("Delete", "حذف")) { _, _ ->
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { profilesRepo.delete(profile.id) } }
                        .onSuccess {
                            if (selectedProfileId == profile.id) selectedProfileId = null
                            reloadData { navigateRoot(Screen.PROFILES) }
                        }.onFailure(::showError)
                }
            }
            .setNegativeButton(tr("Cancel", "لغو"), null)
            .show()
    }

    private fun confirmDeleteSubscription(subscription: Subscription) {
        styledDialogBuilder(tr("Delete subscription?", "اشتراک حذف شود؟"))
            .setMessage(tr(
                "Profiles originating from this subscription will also be removed.",
                "پروفایل‌های این اشتراک نیز حذف می‌شوند.",
            ))
            .setPositiveButton(tr("Delete", "حذف")) { _, _ ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            profilesRepo.replaceSubscriptionProfiles(subscription.id.value, emptyList())
                            subscriptionsRepo.delete(subscription.id)
                            if (subscriptionsRepo.list().isEmpty()) {
                                SubscriptionScheduler.cancel(this@MainActivity)
                            }
                        }
                    }.onSuccess {
                        if (selectedSubscriptionId == subscription.id) {
                            selectedSubscriptionId = null
                            selectedProfileId = null
                        }
                        reloadData()
                    }.onFailure(::showError)
                }
            }
            .setNegativeButton(tr("Cancel", "لغو"), null)
            .show()
    }

    private fun showAddRule(profile: VpnProfile) {
        val value = styledInput("", "example.com / 203.0.113.0/24")
        val actions = arrayOf("PROXY", "DIRECT", "BLOCK")
        var selected = 0
        val box = vertical().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            add(label(
                tr("Enter a domain, IP or CIDR range.", "یک دامنه، IP یا بازه CIDR وارد کنید."),
                palette.textSecondary,
                12f,
            ))
            add(value).margin(top = 12)
        }
        styledDialogBuilder(tr("Add routing rule", "افزودن قانون مسیریابی"))
            .setView(box)
            .setSingleChoiceItems(actions, 0) { _, which -> selected = which }
            .setPositiveButton(tr("Add", "افزودن")) { _, _ ->
                val raw = value.text.toString().trim()
                if (raw.isEmpty()) {
                    showGlassFeedback(
                        tr("Enter a domain or address", "یک دامنه یا آدرس وارد کنید"),
                        ToIcon.ROUTE,
                    )
                } else runCatching {
                    val rule = if ('/' in raw || raw.matches(Regex("[0-9A-Fa-f:.]+"))) {
                        RoutingRule(RoutingAction.valueOf(actions[selected]), cidrs = listOf(
                            if ('/' in raw) raw else "$raw/${if (':' in raw) 128 else 32}",
                        ))
                    } else {
                        RoutingRule(RoutingAction.valueOf(actions[selected]), domains = listOf(raw))
                    }
                    profile.copy(
                        routing = profile.routing.copy(rules = profile.routing.rules + rule),
                    )
                }.onSuccess {
                    updateProfile(it) {
                        showGlassFeedback(
                            tr("Routing rule added", "قانون مسیریابی افزوده شد"),
                            ToIcon.ROUTE,
                        )
                    }
                }.onFailure(::showError)
            }
            .setNegativeButton(tr("Cancel", "لغو"), null)
            .show()
    }

    private fun updateProfile(profile: VpnProfile, success: (() -> Unit)? = null) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    PolicyValidator.validate(profile)
                    val sensitive = profilesRepo.getSensitive(profile)
                    profilesRepo.upsert(profile, sensitive)
                }
            }.onSuccess {
                reloadData { success?.invoke() }
            }.onFailure(::showError)
        }
    }

    private fun testAllLatencies(candidates: List<VpnProfile>, selectFastest: Boolean = false) {
        if (connectionState !is ConnectionState.Idle && connectionState !is ConnectionState.Failed) {
            toast(tr(
                "Disconnect before testing all configurations.",
                "پیش از آزمایش همه پیکربندی‌ها اتصال را قطع کنید.",
            ))
            return
        }
        if (candidates.isEmpty() || latencyTesting) return
        latencyTesting = true
        latencyTestCompleted = 0
        latencyTestTotal = candidates.size
        latencyCancelRequested = false
        render()
        latencyTestJob = scope.launch {
            var successful = 0
            var timedOut = 0
            var failed = 0
            var engine: XrayEngineAdapter? = null
            try {
                val activeEngine = withContext(Dispatchers.IO) { XrayEngineAdapter(this@MainActivity) }
                engine = activeEngine
                val planner = XrayConfigPlanner()
                val socks = SocksEndpoint(port = 11_080)
                for (profile in candidates) {
                    if (latencyCancelRequested) break
                    latencyTestingProfileId = profile.id
                    if (screen == Screen.PROFILES) render()
                    val updated = runCatching {
                        withContext(Dispatchers.IO) {
                            val sensitive = profilesRepo.getSensitive(profile)
                            var status = LatencyTestStatus.SUCCESS
                            var errorText: String? = null
                            val latency = try {
                                withTimeout(LATENCY_TEST_TIMEOUT_MILLIS) {
                                    val planned = planner.prepare(sensitive.xrayJson, socks, profile)
                                    activeEngine.start(EngineStartRequest(planned, socks))
                                    activeEngine.measureDelay("https://www.gstatic.com/generate_204")
                                        .takeIf { it >= 0 } ?: error("Latency unavailable")
                                }
                            } catch (_: TimeoutCancellationException) {
                                status = LatencyTestStatus.TIMEOUT
                                errorText = "Timed out after ${LATENCY_TEST_TIMEOUT_MILLIS / 1_000}s"
                                null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                val message = error.message ?: error::class.simpleName.orEmpty()
                                status = if ("timeout" in message.lowercase()) {
                                    LatencyTestStatus.TIMEOUT
                                } else {
                                    LatencyTestStatus.FAILED
                                }
                                errorText = message.ifBlank { "Latency test failed" }.take(200)
                                null
                            } finally {
                                runCatching { activeEngine.stop() }
                            }
                            val value = profile.copy(
                                latencyMillis = latency ?: profile.latencyMillis,
                                lastTestedMillis = System.currentTimeMillis(),
                                latencyStatus = status,
                                latencyError = errorText,
                            )
                            profilesRepo.upsert(value, sensitive)
                            value
                        }
                    }.getOrElse {
                        profile.copy(
                            latencyMillis = profile.latencyMillis,
                            lastTestedMillis = System.currentTimeMillis(),
                            latencyStatus = LatencyTestStatus.FAILED,
                            latencyError = (it.message ?: "Unable to read configuration").take(200),
                        )
                    }
                    when (updated.latencyStatus) {
                        LatencyTestStatus.SUCCESS -> successful++
                        LatencyTestStatus.TIMEOUT -> timedOut++
                        else -> failed++
                    }
                    profiles = profiles.map { if (it.id == updated.id) updated else it }
                    latencyTestCompleted++
                    if (screen == Screen.PROFILES) render()
                }
                if (selectFastest && !latencyCancelRequested) {
                    val candidateIds = candidates.map { it.id }.toSet()
                    val best = profiles.asSequence()
                        .filter { it.id in candidateIds }
                        .filter {
                            it.latencyStatus == LatencyTestStatus.SUCCESS &&
                                it.latencyMillis != null
                        }
                        .minByOrNull { it.latencyMillis ?: Long.MAX_VALUE }
                    if (best != null) {
                        selectProfile(best)
                        toast(tr(
                            "Selected ${best.name} · ${best.latencyMillis} ms",
                            "${best.name} انتخاب شد · ${best.latencyMillis} میلی‌ثانیه",
                        ))
                    } else {
                        toast(tr(
                            "No configuration returned a successful latency result.",
                            "هیچ پیکربندی نتیجه تأخیر موفقی نداشت.",
                        ))
                    }
                } else {
                    val prefix = if (latencyCancelRequested) {
                        tr("Cancelled after current test · ", "پس از آزمایش فعلی لغو شد · ")
                    } else ""
                    toast(prefix + tr(
                        "$successful succeeded · $timedOut timed out · $failed failed",
                        "$successful موفق · $timedOut زمان‌تمام · $failed ناموفق",
                    ))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showError(error)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { engine?.stop() } }
                latencyTesting = false
                latencyTestingProfileId = null
                latencyCancelRequested = false
                latencyTestJob = null
                if (screen == Screen.PROFILES) render()
            }
        }
    }

    private fun runLatencyDiagnostic() {
        if (connectionState !is ConnectionState.Connected) {
            showGlassFeedback(
                tr("Connect the VPN before measuring latency", "پیش از اندازه‌گیری تأخیر به VPN متصل شوید"),
                ToIcon.ACTIVITY,
            )
            return
        }
        showGlassFeedback(
            tr("Measuring through Xray…", "اندازه‌گیری از طریق Xray…"),
            ToIcon.ACTIVITY,
        )
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    VpnRuntime.measureDelay("https://www.gstatic.com/generate_204")
                }
            }.onSuccess { latency ->
                val showResult: () -> Unit = {
                    styledDialogBuilder(tr("Latency result", "نتیجه تأخیر"))
                        .setMessage("$latency ms\n${tr("Measured by the active Xray engine.", "اندازه‌گیری توسط موتور فعال Xray.")}")
                        .setPositiveButton(tr("Done", "تمام"), null)
                        .show()
                }
                selectedProfile()?.let { profile ->
                    updateProfile(profile.copy(
                        latencyMillis = latency,
                        lastTestedMillis = System.currentTimeMillis(),
                        latencyStatus = LatencyTestStatus.SUCCESS,
                        latencyError = null,
                    ), showResult)
                } ?: showResult()
            }.onFailure { error ->
                selectedProfile()?.let { profile ->
                    val message = error.message ?: "Latency test failed"
                    updateProfile(profile.copy(
                        latencyMillis = null,
                        lastTestedMillis = System.currentTimeMillis(),
                        latencyStatus = if ("timeout" in message.lowercase()) {
                            LatencyTestStatus.TIMEOUT
                        } else {
                            LatencyTestStatus.FAILED
                        },
                        latencyError = message.take(200),
                    )) { showError(error) }
                } ?: showError(error)
            }
        }
    }

    private fun promptBackupPassword(export: Boolean) {
        val password = styledInput("", tr("At least 8 characters", "حداقل ۸ نویسه")).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirm = if (export) styledInput("", tr("Repeat password", "تکرار گذرواژه")).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else null
        val box = vertical().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            add(password)
            confirm?.let { add(it).margin(top = 10) }
        }
        styledDialogBuilder(if (export) tr("Protect backup", "محافظت از پشتیبان") else tr("Unlock backup", "باز کردن پشتیبان"))
            .setView(box)
            .setPositiveButton(if (export) tr("Continue", "ادامه") else tr("Restore", "بازیابی")) { _, _ ->
                val chars = password.text.toString().toCharArray()
                if (export && password.text.toString() != confirm?.text.toString()) {
                    chars.fill('\u0000')
                    showGlassFeedback(
                        tr("Passwords do not match", "گذرواژه‌ها یکسان نیستند"),
                        ToIcon.BACKUP,
                    )
                } else if (export) {
                    createBackup(chars)
                } else {
                    restoreBackup(chars)
                }
            }
            .setNegativeButton(tr("Cancel", "لغو"), null)
            .show()
    }

    private fun createBackup(password: CharArray) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val profilePayload = profilesRepo.list().map {
                        BackupProfile(it, profilesRepo.getSensitive(it).xrayJson)
                    }
                    val subscriptionsPayload = subscriptionsRepo.list().map {
                        BackupSubscription(it, subscriptionsRepo.getUrl(it))
                    }
                    backupCodec.encode(
                        BackupPayload(
                            createdAtMillis = System.currentTimeMillis(),
                            profiles = profilePayload,
                            subscriptions = subscriptionsPayload,
                        ),
                        password,
                    )
                }
            }.also { password.fill('\u0000') }
                .onSuccess {
                    pendingBackupBytes?.fill(0)
                    pendingBackupBytes = it
                    pendingDocument = PendingDocument.EXPORT_BACKUP
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_TITLE, "to-client-backup.toc")
                        },
                        REQUEST_DOCUMENT,
                    )
                }.onFailure(::showError)
        }
    }

    private fun writeBackup(uri: Uri) {
        val bytes = pendingBackupBytes ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                        ?: error("Unable to write backup")
                }
            }.onSuccess {
                showGlassFeedback(
                    tr("Encrypted backup saved", "پشتیبان رمزگذاری‌شده ذخیره شد"),
                    ToIcon.BACKUP,
                )
            }
                .onFailure(::showError)
            bytes.fill(0)
            pendingBackupBytes = null
        }
    }

    private fun readBackup(uri: Uri) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Unable to open backup")
                }
            }.onSuccess {
                pendingRestoreBytes?.fill(0)
                pendingRestoreBytes = it
                promptBackupPassword(export = false)
            }.onFailure(::showError)
        }
    }

    private fun restoreBackup(password: CharArray) {
        val bytes = pendingRestoreBytes ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val payload = backupCodec.decode(bytes, password)
                    payload.profiles.forEach {
                        profilesRepo.upsert(it.profile, SensitiveProfileConfig(it.xrayJson))
                    }
                    payload.subscriptions.forEach {
                        subscriptionsRepo.upsert(it.subscription, it.url)
                    }
                    payload
                }
            }.also {
                password.fill('\u0000')
                bytes.fill(0)
                pendingRestoreBytes = null
            }.onSuccess {
                reloadData {
                    showGlassFeedback(
                        "${it.profiles.size} ${tr("profiles restored", "پروفایل بازیابی شد")}",
                        ToIcon.BACKUP,
                    )
                }
            }.onFailure(::showError)
        }
    }

    private fun toggleTheme() {
        lightTheme = !lightTheme
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_LIGHT, lightTheme).apply()
        render()
        showGlassFeedback(
            ls(if (lightTheme) R.string.theme_light_enabled else R.string.theme_dark_enabled),
            ToIcon.THEME,
        )
    }

    private fun toggleLanguage() {
        persian = !persian
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_PERSIAN, persian).apply()
        render()
        showGlassFeedback(if (persian) "فارسی" else "English", ToIcon.GLOBE)
    }

    private fun navigate(destination: Screen) {
        if (screen != destination) screens.addLast(screen)
        screen = destination
        render()
    }

    private fun navigateRoot(destination: Screen) {
        screens.clear()
        screen = destination
        render()
    }

    private fun profilesFor(subscriptionId: SubscriptionId?): List<VpnProfile> =
        profiles.filter { profile ->
            when (val source = profile.source) {
                ProfileSource.Manual -> subscriptionId == null
                is ProfileSource.Subscription -> source.subscriptionId == subscriptionId?.value
            }
        }.sortedWith(
            compareByDescending<VpnProfile> { it.isFavorite }
                .thenBy { it.latencyMillis ?: Long.MAX_VALUE }
                .thenBy { it.name.lowercase() },
        )

    private fun visibleConfigurations(source: List<VpnProfile>): List<VpnProfile> {
        val query = configurationSearch.trim().lowercase(Locale.getDefault())
        val filtered = if (query.isEmpty()) source else source.filter { profile ->
            listOfNotNull(
                profile.name,
                profile.endpointLabel,
                profile.protocolLabel,
                profile.transportLabel,
                profile.securityLabel,
            ).any { query in it.lowercase(Locale.getDefault()) }
        }
        return when (configurationSort) {
            ConfigurationSort.DEFAULT -> filtered
            ConfigurationSort.LATENCY -> filtered.sortedWith(
                compareBy<VpnProfile> {
                    it.latencyStatus != LatencyTestStatus.SUCCESS || it.latencyMillis == null
                }.thenBy { it.latencyMillis ?: Long.MAX_VALUE }
                    .thenBy { it.name.lowercase(Locale.getDefault()) },
            )
            ConfigurationSort.NAME -> filtered.sortedBy { it.name.lowercase(Locale.getDefault()) }
            ConfigurationSort.FAVORITES -> filtered.sortedWith(
                compareByDescending<VpnProfile> { it.isFavorite }
                    .thenBy { it.name.lowercase(Locale.getDefault()) },
            )
        }
    }

    private fun showConfigurationSearch() {
        val input = styledInput(configurationSearch, tr("Name, endpoint or protocol", "نام، نقطه پایانی یا پروتکل"))
        styledDialogBuilder(tr("Search configurations", "جستجوی پیکربندی‌ها"))
            .setView(vertical().apply {
                setPadding(dp(18), dp(8), dp(18), 0)
                add(input)
            })
            .setPositiveButton(tr("Apply", "اعمال")) { _, _ ->
                configurationSearch = input.text.toString().trim().take(120)
                render()
            }
            .setNeutralButton(tr("Clear", "پاک کردن")) { _, _ ->
                configurationSearch = ""
                render()
            }
            .setNegativeButton(tr("Cancel", "لغو"), null)
            .show()
    }

    private fun showConfigurationSort() {
        val values = ConfigurationSort.entries
        val labels = values.map {
            when (it) {
                ConfigurationSort.DEFAULT -> tr("Default", "پیش‌فرض")
                ConfigurationSort.LATENCY -> tr("Latency", "تأخیر")
                ConfigurationSort.NAME -> tr("Name", "نام")
                ConfigurationSort.FAVORITES -> tr("Favorites first", "ابتدا علاقه‌مندی‌ها")
            }
        }.toTypedArray()
        styledDialogBuilder(tr("Sort configurations", "مرتب‌سازی پیکربندی‌ها"))
            .setSingleChoiceItems(labels, values.indexOf(configurationSort)) { dialog, which ->
                configurationSort = values[which]
                dialog.dismiss()
                render()
            }
            .setNegativeButton(tr("Cancel", "لغو"), null)
            .show()
    }

    private fun configurationSortLabel(): String = when (configurationSort) {
        ConfigurationSort.DEFAULT -> tr("Default", "پیش‌فرض")
        ConfigurationSort.LATENCY -> tr("Latency", "تأخیر")
        ConfigurationSort.NAME -> tr("Name", "نام")
        ConfigurationSort.FAVORITES -> tr("Favorites", "علاقه‌مندی‌ها")
    }

    private fun selectedProfile(): VpnProfile? =
        profiles.firstOrNull { it.id == selectedProfileId }

    private fun selectedSubscription(): Subscription? =
        subscriptions.firstOrNull { it.id == selectedSubscriptionId }

    private fun selectSubscription(id: SubscriptionId?) {
        selectedSubscriptionId = id
        val candidates = profilesFor(id)
        if (candidates.none { it.id == selectedProfileId }) {
            selectedProfileId = candidates.firstOrNull()?.id
        }
        persistSelection()
        render()
    }

    private fun selectProfile(profile: VpnProfile) {
        selectedProfileId = profile.id
        selectedSubscriptionId = (profile.source as? ProfileSource.Subscription)
            ?.subscriptionId?.let(::SubscriptionId)
        persistSelection()
        render()
    }

    private fun persistSelection() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_SELECTED_PROFILE, selectedProfileId?.value)
            .putString(KEY_SELECTED_SUBSCRIPTION, selectedSubscriptionId?.value)
            .apply()
    }

    private fun loadExitInfoFor(state: ConnectionState.Connected) {
        val sessionKey = "${state.profileId.value}:${state.networkGeneration}:${state.connectedAtMillis}"
        if (exitInfoSessionKey == sessionKey) return
        exitInfoSessionKey = sessionKey
        exitInfo = null
        exitInfoLoading = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10_808))
                    val connection = java.net.URL(EXIT_INFO_URL)
                        .openConnection(proxy) as HttpsURLConnection
                    try {
                        connection.connectTimeout = 8_000
                        connection.readTimeout = 8_000
                        connection.setRequestProperty("Accept", "application/json")
                        connection.inputStream.bufferedReader().use { reader ->
                            val json = JSONObject(reader.readText())
                            require(json.optBoolean("success", true)) {
                                json.optString("message", "Exit information unavailable")
                            }
                            val ip = json.optString("ip").trim()
                            require(ip.isNotEmpty()) { "Exit IP unavailable" }
                            val location = listOf(
                                json.optString("city").trim(),
                                json.optString("region").trim(),
                                json.optString("country").trim(),
                            ).filter(String::isNotEmpty).distinct().joinToString(", ")
                            val isp = json.optJSONObject("connection")
                                ?.optString("isp").orEmpty().trim()
                            ExitInfo(
                                ip = ip,
                                location = location.ifEmpty { tr("Unavailable", "در دسترس نیست") },
                                isp = isp.ifEmpty { tr("Unavailable", "در دسترس نیست") },
                            )
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
            }.getOrNull()
            if (exitInfoSessionKey == sessionKey) {
                exitInfo = result
                exitInfoLoading = false
                if (screen == Screen.HOME || screen == Screen.PROFILE_DETAIL) render()
            }
        }
    }

    private fun updateTrafficViews() {
        val current = traffic
        if (connectionState is ConnectionState.Connected && current != null) {
            downloadValue?.text = "${formatBytes(downloadRateBytes)}/s"
            uploadValue?.text = "${formatBytes(uploadRateBytes)}/s"
            totalDownloadValue?.text = formatBytes(current.engineTotal.downloadedBytes)
            totalUploadValue?.text = formatBytes(current.engineTotal.uploadedBytes)
            trafficCaption?.text = tr("Current transfer speed", "سرعت لحظه‌ای انتقال")
        } else {
            downloadValue?.text = "—"
            uploadValue?.text = "—"
            totalDownloadValue?.text = "—"
            totalUploadValue?.text = "—"
        }
        durationValue?.text = (connectionState as? ConnectionState.Connected)?.let {
            formatDuration(System.currentTimeMillis() - it.connectedAtMillis)
        } ?: "—"
    }

    private fun ConnectionState.profileIdOrNull(): ProfileId? = when (this) {
        ConnectionState.Idle -> null
        is ConnectionState.Preparing -> profileId
        is ConnectionState.StartingEngine -> profileId
        is ConnectionState.EstablishingTun -> profileId
        is ConnectionState.StartingBridge -> profileId
        is ConnectionState.Connected -> profileId
        is ConnectionState.Reconnecting -> profileId
        is ConnectionState.Stopping -> profileId
        is ConnectionState.Failed -> profileId
    }

    private fun localizedPerAppMode(mode: PerAppMode): String = when (mode) {
        PerAppMode.ALL_APPS -> tr("All applications", "همه برنامه‌ها")
        PerAppMode.ONLY_SELECTED -> tr("Only selected packages", "فقط بسته‌های انتخاب‌شده")
        PerAppMode.BYPASS_SELECTED -> tr("Bypass selected packages", "عبور بسته‌های انتخاب‌شده")
    }

    private fun latencyLabel(profile: VpnProfile): String = when {
        latencyTestingProfileId == profile.id -> tr("Testing…", "در حال آزمایش…")
        profile.latencyStatus == LatencyTestStatus.TIMEOUT -> tr("Timed out", "زمان تمام شد")
        profile.latencyStatus == LatencyTestStatus.FAILED -> tr("Failed", "ناموفق")
        profile.latencyMillis != null -> "${profile.latencyMillis} ms"
        else -> tr("Not tested", "آزمایش‌نشده")
    }

    private fun latencyColor(profile: VpnProfile): Int = when {
        latencyTestingProfileId == profile.id -> palette.warning
        profile.latencyStatus == LatencyTestStatus.TIMEOUT -> palette.warning
        profile.latencyStatus == LatencyTestStatus.FAILED -> palette.error
        profile.latencyMillis != null -> palette.primary
        else -> palette.muted
    }

    private fun selectedAppsLabel(count: Int): String =
        if (count == 0) tr("Select applications", "انتخاب برنامه‌ها")
        else tr("$count applications selected", "$count برنامه انتخاب شده")

    private fun showAppSelection(
        selectedPackages: MutableSet<String>,
        onChanged: (Int) -> Unit,
    ) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val launchablePackages = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .toSet()
        val installed = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }
        val selectablePackages = installed.asSequence()
            .filter { it.enabled }
            .filter {
                it.packageName in launchablePackages ||
                    it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 ||
                    it.packageName in selectedPackages
            }
            .map { it.packageName }
            .toSet()
        val entries = (selectablePackages + selectedPackages)
            .filterNot { it == packageName }
            .mapNotNull { packageName ->
                runCatching {
                    val info = if (Build.VERSION.SDK_INT >= 33) {
                        packageManager.getApplicationInfo(
                            packageName,
                            PackageManager.ApplicationInfoFlags.of(0),
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getApplicationInfo(packageName, 0)
                    }
                    InstalledAppChoice(
                        label = packageManager.getApplicationLabel(info).toString(),
                        packageName = packageName,
                        icon = packageManager.getApplicationIcon(info),
                    )
                }.getOrNull()
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
        if (entries.isEmpty()) {
            showGlassFeedback(
                tr("No selectable applications were found", "برنامه‌ای برای انتخاب یافت نشد"),
                ToIcon.TUNNEL,
            )
            return
        }
        val list = vertical().apply {
            background = roundedBackground(palette.glass, dp(16).toFloat(), palette.line, dp(1))
            entries.forEachIndexed { index, app ->
                if (index > 0) add(divider())
                val row = LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(9), dp(10), dp(9))
                    minimumHeight = dp(58)
                }
                row.add(ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon)
                    contentDescription = app.label
                }, dp(38), dp(38))
                row.add(vertical().apply {
                    setPaddingRelative(dp(11), 0, dp(8), 0)
                    add(label(app.label, palette.text, 13f, Typeface.BOLD).apply { maxLines = 1 })
                    add(technicalLabel(app.packageName).apply {
                        maxLines = 1
                        setPadding(0, dp(3), 0, 0)
                    })
                }, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                val selection = CheckBox(this@MainActivity).apply {
                    isChecked = app.packageName in selectedPackages
                    buttonTintList = android.content.res.ColorStateList.valueOf(palette.primary)
                    contentDescription = app.label
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) selectedPackages.add(app.packageName)
                        else selectedPackages.remove(app.packageName)
                        onChanged(selectedPackages.size)
                    }
                }
                row.add(selection, dp(48), dp(48))
                row.setOnClickListener { selection.isChecked = !selection.isChecked }
                add(row)
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            setPadding(dp(16), dp(4), dp(16), 0)
            addView(list, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        styledDialogBuilder(tr("Select applications", "انتخاب برنامه‌ها"))
            .setView(scroll)
            .setPositiveButton(tr("Done", "انجام شد"), null)
            .setNeutralButton(tr("Clear", "پاک کردن")) { _, _ ->
                selectedPackages.clear()
                onChanged(0)
            }
            .show()
    }

    private fun itFromTag(tagValue: Any): View? =
        window.decorView.findViewWithTag(tagValue)

    private fun stageAtLeast(stage: Int): Boolean = when (connectionState) {
        is ConnectionState.Connected -> true
        is ConnectionState.StartingBridge -> stage <= 3
        is ConnectionState.EstablishingTun -> stage <= 2
        is ConnectionState.StartingEngine -> stage <= 1
        is ConnectionState.Preparing -> stage <= 1
        else -> false
    }

    private fun connectionLabel(state: ConnectionState): String = when (state) {
        ConnectionState.Idle -> tr("Disconnected", "قطع")
        is ConnectionState.Preparing -> tr("Preparing", "آماده‌سازی")
        is ConnectionState.StartingEngine -> tr("Starting engine", "راه‌اندازی موتور")
        is ConnectionState.EstablishingTun -> tr("Establishing VPN", "برقراری VPN")
        is ConnectionState.StartingBridge -> tr("Starting bridge", "راه‌اندازی پل")
        is ConnectionState.Connected -> tr("Connected", "متصل")
        is ConnectionState.Reconnecting -> tr("Reconnecting", "اتصال مجدد")
        is ConnectionState.Stopping -> tr("Disconnecting", "در حال قطع")
        is ConnectionState.Failed -> tr("Connection error", "خطای اتصال")
    }

    private fun connectionActionLabel(state: ConnectionState): String = when (state) {
        ConnectionState.Idle, is ConnectionState.Failed -> tr("Connect", "اتصال")
        is ConnectionState.Connected -> tr("Disconnect", "قطع اتصال")
        else -> tr("Cancel", "لغو")
    }

    private fun isProfileConnected(id: ProfileId): Boolean =
        (connectionState as? ConnectionState.Connected)?.profileId == id

    private fun localizedIssue(issue: CompatibilityIssue): String = when (issue) {
        CompatibilityIssue.ANDROID_VERSION_UNSUPPORTED -> tr("Android version unsupported", "نسخه اندروید پشتیبانی نمی‌شود")
        CompatibilityIssue.NATIVE_ABI_UNSUPPORTED -> tr("ABI unsupported", "معماری پشتیبانی نمی‌شود")
        CompatibilityIssue.NOTIFICATION_PERMISSION_REQUIRED -> tr("Notification permission needed", "اجازه اعلان لازم است")
        CompatibilityIssue.VPN_CONSENT_REQUIRED -> tr("VPN consent needed", "اجازه VPN لازم است")
    }

    private fun styledDialogBuilder(title: String): AlertDialog.Builder =
        AlertDialog.Builder(
            this,
            if (lightTheme) R.style.Theme_TOClient_Dialog_Light
            else R.style.Theme_TOClient_Dialog_Dark,
        )
            .setTitle(title)
            .setOnDismissListener { }

    private fun showError(error: Throwable) {
        styledDialogBuilder(tr("Unable to complete action", "انجام عملیات ممکن نیست"))
            .setMessage(error.message ?: error::class.simpleName)
            .setPositiveButton(tr("Close", "بستن"), null)
            .show()
    }

    private fun showGlassFeedback(message: String, icon: ToIcon) {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) as? FrameLayout ?: return
        val pill = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = if (persian) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            background = roundedBackground(palette.glassStrong, dp(18).toFloat(), palette.lineStrong, dp(1))
            elevation = dp(14).toFloat()
            setPadding(dp(14), dp(11), dp(14), dp(11))
            add(ToIconView(this@MainActivity, icon, palette.primary), dp(20), dp(20))
            add(label(message, palette.text, 12f, Typeface.BOLD).apply {
                setPaddingRelative(dp(9), 0, 0, 0)
            })
            alpha = 0f
            translationY = dp(12).toFloat()
        }
        root.addView(
            pill,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                marginStart = dp(20)
                marginEnd = dp(20)
                bottomMargin = dp(98)
            },
        )
        pill.animate().alpha(1f).translationY(0f).setDuration(170).withEndAction {
            pill.postDelayed({
                pill.animate().alpha(0f).translationY(dp(8).toFloat()).setDuration(160)
                    .withEndAction { root.removeView(pill) }
                    .start()
            }, 1_450)
        }.start()
    }

    private fun normalizeNumericDns(value: String): String {
        val candidate = value.trim()
        val ipv4Shape = candidate.matches(Regex("[0-9.]+"))
        val ipv6Shape = ':' in candidate && candidate.matches(Regex("[0-9A-Fa-f:]+"))
        require(ipv4Shape || ipv6Shape) {
            tr("$candidate is not a numeric IPv4 or IPv6 address.",
                "$candidate یک نشانی عددی IPv4 یا IPv6 نیست.")
        }
        if (ipv4Shape) {
            val octets = candidate.split('.')
            require(octets.size == 4 && octets.all {
                it.isNotEmpty() && it.length <= 3 &&
                    it.toIntOrNull()?.let { value -> value in 0..255 } == true
            }) {
                tr("$candidate is not a valid IPv4 address.",
                    "$candidate نشانی IPv4 معتبری نیست.")
            }
            return octets.joinToString(".") { it.toInt().toString() }
        }
        val address = runCatching { java.net.InetAddress.getByName(candidate) }
            .getOrElse {
                throw IllegalArgumentException(tr(
                    "$candidate is not a valid IP address.",
                    "$candidate نشانی IP معتبری نیست.",
                ))
            }
        require(address is java.net.Inet6Address) {
            tr("$candidate is not a valid numeric DNS address.",
                "$candidate نشانی عددی DNS معتبری نیست.")
        }
        return requireNotNull(address.hostAddress).substringBefore('%').lowercase(Locale.US)
    }

    private fun shareDiagnosticSnapshot() {
        val profile = selectedProfile()
        val snapshot = traffic
        val report = buildList {
            add("TO Client diagnostic snapshot")
            add("App version: 1.0.0")
            add("Android API: ${Build.VERSION.SDK_INT}")
            add("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            add("State: ${connectionState::class.simpleName}")
            add("Engine: ${profile?.engine?.name ?: "none"}")
            add("Protocol: ${profile?.protocolLabel ?: "none"}")
            add("Source type: ${when (profile?.source) {
                ProfileSource.Manual -> "manual"
                is ProfileSource.Subscription -> "subscription"
                null -> "none"
            }}")
            (connectionState as? ConnectionState.Connected)?.let {
                add("Connected duration: ${formatDuration(System.currentTimeMillis() - it.connectedAtMillis)}")
            }
            add("Latency status: ${profile?.latencyStatus?.name ?: "NOT_TESTED"}")
            add("Latency ms: ${profile?.latencyMillis ?: "unavailable"}")
            add("DNS server count: ${profile?.dns?.servers?.size ?: 0}")
            add("Routing rule count: ${profile?.routing?.rules?.size ?: 0}")
            add("Per-app mode: ${profile?.perApp?.mode?.name ?: "none"}")
            add("Selected app count: ${profile?.perApp?.packages?.size ?: 0}")
            add("Engine download: ${snapshot?.engineTotal?.downloadedBytes ?: 0}")
            add("Engine upload: ${snapshot?.engineTotal?.uploadedBytes ?: 0}")
            add("TUN download: ${snapshot?.tun?.downloadedBytes ?: 0}")
            add("TUN upload: ${snapshot?.tun?.uploadedBytes ?: 0}")
            add("Outbound counter count: ${snapshot?.engineOutbounds?.size ?: 0}")
            (connectionState as? ConnectionState.Failed)?.let {
                add("Failure reason: ${it.reason.name}")
            }
            add("")
            add("Excluded: configuration names, endpoints, IP/location, DNS addresses, package names, credentials and raw logs.")
        }.joinToString("\n")
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report)
            },
            tr("Share diagnostic snapshot", "اشتراک‌گذاری گزارش عیب‌یابی"),
        ))
    }

    private fun toast(value: String) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    }

    private fun ls(id: Int, vararg args: Any): String {
        val configuration = android.content.res.Configuration(resources.configuration).apply {
            setLocale(if (persian) Locale("fa") else Locale.ENGLISH)
        }
        return createConfigurationContext(configuration).resources.getString(id, *args)
    }

    private fun tr(en: String, fa: String): String = if (persian) fa else en

    private fun formatBytes(value: Long): String = when {
        value >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", value / 1_073_741_824.0)
        value >= 1_048_576L -> String.format(Locale.US, "%.1f MB", value / 1_048_576.0)
        value >= 1_024L -> String.format(Locale.US, "%.1f KB", value / 1_024.0)
        else -> "$value B"
    }

    private fun formatDuration(value: Long): String {
        val seconds = (value / 1_000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    private fun relativeTime(value: Long): String {
        val elapsed = (System.currentTimeMillis() - value).coerceAtLeast(0)
        val minutes = elapsed / 60_000
        return when {
            minutes < 1 -> tr("Updated now", "هم‌اکنون به‌روز شد")
            minutes < 60 -> tr("Updated ${minutes}m ago", "${minutes} دقیقه پیش")
            minutes < 1_440 -> tr("Updated ${minutes / 60}h ago", "${minutes / 60} ساعت پیش")
            else -> tr("Updated ${minutes / 1_440}d ago", "${minutes / 1_440} روز پیش")
        }
    }

    private fun expirationLabel(value: Long): String {
        val days = (value - System.currentTimeMillis()) / 86_400_000L
        return when {
            days < 0 -> tr("Subscription expired", "اشتراک منقضی شده")
            days == 0L -> tr("Expires today", "امروز منقضی می‌شود")
            else -> tr("Expires in $days days", "$days روز تا انقضا")
        }
    }

    private fun formatTimestamp(value: Long): String =
        android.text.format.DateFormat.getMediumDateFormat(this).format(java.util.Date(value))

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        internal const val DEVELOPER_URL = "https://t.me/Espierz"
        internal fun developerIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(DEVELOPER_URL))
        private const val PREFS = "to_client_ui"
        private const val KEY_LIGHT = "light_theme"
        private const val KEY_PERSIAN = "persian"
        private const val KEY_SELECTED_PROFILE = "selected_profile"
        private const val KEY_SELECTED_SUBSCRIPTION = "selected_subscription"
        private const val KEY_SHOW_NOTIFICATION_TRAFFIC = "show_notification_traffic_totals"
        private const val REQUEST_VPN_CONSENT = 100
        private const val REQUEST_DOCUMENT = 101
        private const val REQUEST_NOTIFICATIONS = 102
        private const val MODE_BUTTON_TAG = "per_app_mode_button"
        private const val EXIT_INFO_URL = "https://ipwho.is/"
        private const val LATENCY_TEST_TIMEOUT_MILLIS = 15_000L
        private const val DAY_MILLIS = 86_400_000L
    }
}