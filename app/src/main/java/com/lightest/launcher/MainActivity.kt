package com.lightest.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.lightest.launcher.data.IconCache
import com.lightest.launcher.data.SettingsPrefs
import com.lightest.launcher.data.HudSettings
import com.lightest.launcher.data.LauncherRepository
import com.lightest.launcher.model.AppItem
import com.lightest.launcher.model.IconEntry
import com.lightest.launcher.ui.AppDetailDialog
import com.lightest.launcher.ui.SettingsScreen
import com.lightest.launcher.ui.rememberBatteryState
import com.lightest.launcher.ui.rememberSystemStats
import com.lightest.launcher.ui.rememberTimeAndDate
import com.lightest.launcher.ui.rememberVolumeState
import com.lightest.launcher.ui.theme.LauncherTheme
import com.lightest.launcher.ui.theme.LauncherColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : ComponentActivity() {

    private val requestLockAdmin = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            ScreenLocker.lockNow(this)
        }
    }

    fun lockScreen() {
        if (!ScreenLocker.lockNow(this)) {
            Toast.makeText(this, R.string.lock_admin_enable_toast, Toast.LENGTH_SHORT).show()
            requestLockAdmin.launch(ScreenLocker.adminRequestIntent(this))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsPrefs.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            window.isNavigationBarContrastEnforced = false
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
        }

        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.BLACK
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.BLACK

        setContent {
            LauncherTheme {
                PitchBlackLauncherScreen()
            }
        }
    }
}

@Composable
fun PitchBlackLauncherScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installedApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    val iconMap = remember { mutableStateMapOf<String, IconEntry>() }
    var selectedAppForDialog by remember { mutableStateOf<AppItem?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isEditMode by remember { mutableStateOf(false) }
    var selectedAppToSwap by remember { mutableStateOf<AppItem?>(null) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var isRefreshingApps by remember { mutableStateOf(false) }

    val iconLoadJob = remember { arrayOfNulls<Job>(1) }
    val listReloadJob = remember { arrayOfNulls<Job>(1) }

    fun startIconLoad(apps: List<AppItem>) {
        if (apps.isEmpty()) return
        iconLoadJob[0]?.cancel()
        iconLoadJob[0] = scope.launch {
            LauncherRepository.loadIcons(context, apps) { key, entry ->
                iconMap[key] = entry
            }
        }
    }

    fun evictPackageIcons(packageName: String) {
        IconCache.removeMemoryByPackage(packageName)
        val prefix = "$packageName|"
        iconMap.keys.filter { it.startsWith(prefix) }.forEach { iconMap.remove(it) }
    }

    fun applyAppList(apps: List<AppItem>) {
        installedApps = apps
        isLoading = false
        val valid = apps.mapTo(HashSet(apps.size)) { it.stableKey }
        iconMap.keys.filter { it !in valid }.forEach { iconMap.remove(it) }
        startIconLoad(apps.filter { it.stableKey !in iconMap })
    }

    fun refreshAppsManually() {
        if (isRefreshingApps) return
        listReloadJob[0]?.cancel()
        isRefreshingApps = true
        scope.launch {
            try {
                IconCache.clearMemory()
                iconMap.clear()
                val apps = withContext(Dispatchers.IO) {
                    LauncherRepository.getInstalledApps(context)
                }
                installedApps = apps
                isLoading = false
                startIconLoad(apps)
            } finally {
                isRefreshingApps = false
            }
        }
    }

    fun scheduleAppListReload() {
        listReloadJob[0]?.cancel()
        listReloadJob[0] = scope.launch {
            delay(300)
            val apps = withContext(Dispatchers.IO) {
                LauncherRepository.getInstalledApps(context)
            }
            applyAppList(apps)
        }
    }

    // Intercept back presses so Android never re-delivers the HOME intent
    // (which causes the launcher slide-in animation bug on spam-back).
    // Idle Back is a HUD poke only — charger check without a full app rescan.
    BackHandler(enabled = true) {
        when {
            isSettingsOpen -> isSettingsOpen = false
            isEditMode -> {
                isEditMode = false
                selectedAppToSwap = null
            }
            else -> refreshKey++
        }
    }

    LaunchedEffect(Unit) {
        val apps = withContext(Dispatchers.IO) {
            LauncherRepository.getInstalledApps(context)
        }
        installedApps = apps
        isLoading = false
        startIconLoad(apps)
    }

    DisposableEffect(Unit) {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
        val callback = object : android.content.pm.LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: android.os.UserHandle) {
                scheduleAppListReload()
            }
            override fun onPackageChanged(packageName: String, user: android.os.UserHandle) {
                evictPackageIcons(packageName)
                scheduleAppListReload()
            }
            override fun onPackageRemoved(packageName: String, user: android.os.UserHandle) {
                installedApps = installedApps.filterNot {
                    it.packageName == packageName && it.userHandle == user
                }
                evictPackageIcons(packageName)
                scheduleAppListReload()
            }
            override fun onPackagesAvailable(
                packageNames: Array<out String>,
                user: android.os.UserHandle,
                replacing: Boolean
            ) {
                if (replacing) packageNames.forEach { evictPackageIcons(it) }
                scheduleAppListReload()
            }
            override fun onPackagesUnavailable(
                packageNames: Array<out String>,
                user: android.os.UserHandle,
                replacing: Boolean
            ) {
                if (!replacing) {
                    packageNames.forEach { evictPackageIcons(it) }
                    scheduleAppListReload()
                }
            }
            override fun onPackagesSuspended(packageNames: Array<out String>, user: android.os.UserHandle) {
                scheduleAppListReload()
            }
            override fun onPackagesUnsuspended(packageNames: Array<out String>, user: android.os.UserHandle) {
                scheduleAppListReload()
            }
        }
        launcherApps.registerCallback(callback)
        onDispose {
            iconLoadJob[0]?.cancel()
            listReloadJob[0]?.cancel()
            launcherApps.unregisterCallback(callback)
        }
    }

    val showWorkApps by SettingsPrefs.showWorkAppsFlow.collectAsState(initial = true)
    val whiteTheme by SettingsPrefs.whiteThemeFlow.collectAsState(initial = false)
    val hiddenApps by SettingsPrefs.hiddenAppsFlow.collectAsState(initial = emptySet())

    val (regularApps, workApps) = remember(installedApps, showWorkApps, hiddenApps) {
        LauncherRepository.partitionVisible(
            installedApps, hiddenApps, "", showWorkApps
        )
    }

    val screenBg = if (whiteTheme) Color(0xFFF7F7F7) else Color.Black

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBg)
    ) {
        if (isSettingsOpen) {
            SettingsScreen(
                onDismiss = { isSettingsOpen = false },
                allApps = installedApps
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            CenteredMinimalHeader(
                isEditMode = isEditMode,
                onToggleEditMode = {
                    isEditMode = !isEditMode
                    selectedAppToSwap = null
                },
                refreshKey = refreshKey,
                onRefreshApps = { refreshAppsManually() },
                isRefreshingApps = isRefreshingApps
            )

            AnimatedVisibility(visible = isEditMode) {
                Text(
                    text = if (selectedAppToSwap == null) {
                        "Tap an app to select, then tap another to swap!"
                    } else {
                        "Tap another app to swap with ${selectedAppToSwap!!.label}!"
                    },
                    color = LauncherColors.Green,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Loading apps...",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 16.sp
                    )
                }
            } else {
                val pages = if (showWorkApps && workApps.isNotEmpty()) 2 else 1
                val pagerState = rememberPagerState(pageCount = { pages })

                val highlightedKey = selectedAppToSwap?.stableKey
                val handleAppClick = remember(isEditMode, highlightedKey, installedApps) {
                    { app: AppItem ->
                        if (isEditMode) {
                            if (highlightedKey == null) {
                                selectedAppToSwap = app
                            } else {
                                val mutableList = installedApps.toMutableList()
                                val idx1 = mutableList.indexOfFirst { it.stableKey == highlightedKey }
                                val idx2 = mutableList.indexOfFirst { it.stableKey == app.stableKey }
                                if (idx1 != -1 && idx2 != -1) {
                                    val temp = mutableList[idx1]
                                    mutableList[idx1] = mutableList[idx2]
                                    mutableList[idx2] = temp
                                    installedApps = mutableList
                                    scope.launch(Dispatchers.IO) {
                                        LauncherRepository.saveAppOrder(context, mutableList)
                                    }
                                }
                                selectedAppToSwap = null
                            }
                        } else {
                            if (app.packageName == context.packageName) {
                                isSettingsOpen = true
                            } else {
                                launchApp(context, app)
                            }
                        }
                    }
                }

                val handleAppLongClick = remember(isEditMode) {
                    { app: AppItem ->
                        if (!isEditMode) selectedAppForDialog = app
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f)
                    ) { page ->
                        if (page == 0) {
                            AppGrid(
                                apps = regularApps,
                                iconMap = iconMap,
                                highlightedKey = highlightedKey,
                                onAppClick = handleAppClick,
                                onAppLongClick = handleAppLongClick
                            )
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Text(
                                    "Work",
                                    color = LauncherColors.Green,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                        .align(Alignment.CenterHorizontally)
                                )
                                AppGrid(
                                    apps = workApps,
                                    iconMap = iconMap,
                                    highlightedKey = highlightedKey,
                                    onAppClick = handleAppClick,
                                    onAppLongClick = handleAppLongClick
                                )
                            }
                        }
                    }

                    if (pages > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            repeat(pages) { iteration ->
                                val color = if (pagerState.currentPage == iteration) LauncherColors.Green else Color.DarkGray
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .size(6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        selectedAppForDialog?.let { app ->
            AppDetailDialog(
                appItem = app,
                iconEntry = iconMap[app.stableKey],
                onDismiss = { selectedAppForDialog = null },
                onHideApp = {
                    SettingsPrefs.hideApp(app.stableKey)
                    selectedAppForDialog = null
                },
                onEditLayout = {
                    isEditMode = true
                    selectedAppToSwap = app
                }
            )
        }
    }
}

@Composable
fun AppGrid(
    apps: List<AppItem>,
    iconMap: Map<String, IconEntry>,
    highlightedKey: String?,
    onAppClick: (AppItem) -> Unit,
    onAppLongClick: (AppItem) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val totalCount = apps.size
        if (totalCount == 0) return@BoxWithConstraints

        val availableHeight = maxHeight
        val availableWidth = maxWidth

        // Step 1: Intelligent column selection based on screen width & count
        // Phone screens typically support 4, 5, 6, or 7 columns
        val columns = when {
            totalCount <= 12 -> 4
            totalCount <= 20 -> 5
            availableWidth > 600.dp && totalCount > 40 -> 7
            totalCount > 48 -> 7
            else -> 6
        }
        val rows = kotlin.math.max(1, (totalCount + columns - 1) / columns)

        // Step 2: Intelligent cell budget calculation
        val cellHeight = availableHeight / rows
        val cellWidth = availableWidth / columns

        // Step 3: Proportional responsive icon sizing
        // Maximize icon presence to fill the cell generously while keeping room for the 2-line label
        // Icon takes ~58% of cell height (bounded comfortably between 38dp and 58dp)
        val idealIconFromHeight = cellHeight * 0.58f
        val idealIconFromWidth = cellWidth * 0.72f
        val iconSize = minOf(idealIconFromHeight, idealIconFromWidth).coerceIn(38.dp, 58.dp)

        // Step 4: Proportional responsive typography
        // Base font size is comfortably readable (9.5sp to 11.5sp)
        val fontSize = ((cellHeight.value * 0.13f + cellWidth.value * 0.05f) / 2f).coerceIn(9.5f, 11.5f).sp

        val gridState = rememberLazyGridState()

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            userScrollEnabled = false,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            items(
                items = apps,
                key = { app -> app.stableKey },
                contentType = { "app_card" }
            ) { app ->
                val iconEntry = iconMap[app.stableKey]
                AppCard(
                    app = app,
                    iconEntry = iconEntry,
                    iconSize = iconSize,
                    fontSize = fontSize,
                    isHighlighted = app.stableKey == highlightedKey,
                    modifier = Modifier.height(cellHeight),
                    onAppClick = { onAppClick(app) },
                    onAppLongClick = { onAppLongClick(app) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CenteredMinimalHeader(
    isEditMode: Boolean,
    onToggleEditMode: () -> Unit,
    refreshKey: Int = 0,
    onRefreshApps: () -> Unit = {},
    isRefreshingApps: Boolean = false
) {
    val hud by SettingsPrefs.hudSettingsFlow.collectAsState(initial = HudSettings())

    val watchTime = hud.showTime || hud.showDate || hud.showUptime
    val watchBattery = hud.showBatteryBar || hud.showBatteryPercent || hud.showVoltage ||
        hud.showDeviceTemp || hud.showBatteryTemp || hud.showThermal ||
        hud.showHealth || hud.showCurrent
    val watchVolume = hud.showMediaVolume || hud.showRingVolume
    val watchStats = hud.showMemory || hud.showStorage || hud.showRefreshRate

    val timeData by rememberTimeAndDate(refreshKey, enabled = watchTime)
    val batteryData by rememberBatteryState(
        refreshKey,
        enabled = watchBattery,
        requestDeviceTemp = hud.showDeviceTemp
    )
    val volumeData by rememberVolumeState(refreshKey, enabled = watchVolume)
    val systemStats by rememberSystemStats(refreshKey, enabled = watchStats)

    val time12 = timeData.time12
    val amPm = timeData.amPm
    val dateText = timeData.dateText
    val weekday = timeData.weekday
    val uptimeText = timeData.uptimeText
    val batteryPct = batteryData.percentage
    val isCharging = batteryData.isCharging
    val deviceTempCelsius = batteryData.deviceTemperatureCelsius
    val batteryTempCelsius = batteryData.batteryTemperatureCelsius
    val voltageMv = batteryData.voltageMv
    val healthLabel = batteryData.healthLabel
    val currentMa = batteryData.currentMa
    val thermalLabel = batteryData.thermalLabel
    val memoryUsedTotal = systemStats.memoryUsedTotalLabel
    val storageUsedTotal = systemStats.storageUsedTotalLabel
    val freeRamGb = systemStats.freeRamGb
    val totalRamGb = systemStats.totalRamGb
    val freeStorageGb = systemStats.freeStorageGb
    val totalStorageGb = systemStats.totalStorageGb
    val refreshRateHz = systemStats.refreshRateHz
    val mediaVolumePct = volumeData.mediaVolumePercentage
    val ringVolumePct = volumeData.ringVolumePercentage
    val ringerMode = volumeData.ringerMode

    val isWhiteTheme by SettingsPrefs.whiteThemeFlow.collectAsState(initial = false)
    val textPrimary = if (isWhiteTheme) Color(0xFF111111) else Color.White
    val muted = if (isWhiteTheme) Color(0xFF555555) else Color.White.copy(alpha = 0.78f)

    // —— Status colors (green = good, orange = caution, red = bad) ——
    val voltageV = voltageMv / 1000f
    val voltageColor = when {
        voltageMv <= 0 -> muted
        voltageV < 3.50f -> LauncherColors.Red
        voltageV < 3.60f -> LauncherColors.Orange
        voltageV > 4.30f -> LauncherColors.Red
        voltageV > 4.20f -> LauncherColors.Orange
        else -> LauncherColors.Green
    }
    val healthColor = when (healthLabel) {
        "Good" -> LauncherColors.Green
        "Cold" -> LauncherColors.Orange
        "Unknown" -> muted
        else -> LauncherColors.Red
    }
    val thermalColor = when (thermalLabel) {
        "Normal" -> LauncherColors.Green
        "Warm" -> LauncherColors.Orange
        else -> LauncherColors.Red
    }
    val deviceTemp = deviceTempCelsius
    val deviceColor = when {
        deviceTemp == null -> muted
        deviceTemp >= 45f -> LauncherColors.Red
        deviceTemp >= 40f -> LauncherColors.Orange
        else -> LauncherColors.Green
    }
    val batteryTempColor = when {
        batteryTempCelsius >= 40f -> LauncherColors.Red
        batteryTempCelsius >= 35f -> LauncherColors.Orange
        else -> LauncherColors.Green
    }
    val batteryPctColor = when {
        batteryPct <= 15 -> LauncherColors.Red
        batteryPct <= 30 -> LauncherColors.Orange
        else -> LauncherColors.Green
    }
    // Memory: based on free ratio remaining
    val freeRamRatio = if (totalRamGb > 0f) freeRamGb / totalRamGb else 1f
    val memoryColor = when {
        freeRamRatio < 0.12f -> LauncherColors.Red
        freeRamRatio < 0.25f -> LauncherColors.Orange
        else -> LauncherColors.Green
    }
    val freeStorageRatio = if (totalStorageGb > 0f) freeStorageGb / totalStorageGb else 1f
    val storageColor = when {
        freeStorageRatio < 0.10f -> LauncherColors.Red
        freeStorageRatio < 0.20f -> LauncherColors.Orange
        else -> LauncherColors.Green
    }
    val currentColor = when {
        currentMa == null -> muted
        isCharging && kotlin.math.abs(currentMa) >= 1500 -> LauncherColors.Green
        isCharging -> LauncherColors.Orange
        else -> muted
    }

    val calendarMonths by SettingsPrefs.calendarMonthsFlow.collectAsState(initial = 1)
    val sharpEdgesForCal by SettingsPrefs.sharpEdgesFlow.collectAsState(initial = false)
    val isWhiteThemeForCal by SettingsPrefs.whiteThemeFlow.collectAsState(initial = false)

    // Calculate active metrics count to adjust font size and packing dynamically
    val activeMetricsCount = listOf(
        hud.showDeviceTemp && deviceTemp != null,
        hud.showBatteryTemp,
        hud.showVoltage && voltageMv > 0,
        hud.showHealth,
        hud.showCurrent && currentMa != null,
        hud.showThermal,
        hud.showMemory,
        hud.showStorage,
        hud.showRefreshRate,
        hud.showUptime,
        hud.showRingVolume,
        hud.showMediaVolume
    ).count { it }

    val isDenseMode = activeMetricsCount > 2

    val timeFontSize = if (isDenseMode) 22.sp else 30.sp
    val amPmFontSize = if (isDenseMode) 12.sp else 14.sp
    val dateFontSize = if (isDenseMode) 11.sp else 13.sp
    val metricFontSize = if (isDenseMode) 9.5.sp else 11.sp

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Split top header: Left = Clock & Live Status | Right = Calendar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Left column: Self-adjusting Column
            Column(
                modifier = Modifier
                    .weight(if (calendarMonths == 2) 1.25f else 1.15f)
                    .padding(end = if (calendarMonths > 0) 8.dp else 0.dp)
            ) {
                // Collect only active indicators in a clean list
                val statusChips = remember(
                    hud, ringerMode, ringVolumePct, mediaVolumePct,
                    deviceTemp, batteryTempCelsius, voltageV, currentMa, isCharging
                ) {
                    val list = mutableListOf<Pair<String, Color>>()
                    if (hud.showRingVolume) {
                        val ringerText = when (ringerMode) {
                            AudioManager.RINGER_MODE_SILENT -> "Silent"
                            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                            else -> "$ringVolumePct%"
                        }
                        list.add("Ring: $ringerText" to Color(0xFFA0A0A0))
                    }
                    if (hud.showMediaVolume) {
                        list.add("Media: $mediaVolumePct%" to Color(0xFFA0A0A0))
                    }
                    if (hud.showDeviceTemp && deviceTemp != null) {
                        list.add("Device: ${"%.1f".format(deviceTemp)}°C" to deviceColor)
                    }
                    if (hud.showBatteryTemp) {
                        list.add("Battery: ${"%.1f".format(batteryTempCelsius)}°C" to batteryTempColor)
                    }
                    if (hud.showVoltage && voltageMv > 0) {
                        list.add("Voltage: ${"%.2f".format(voltageV)}V" to voltageColor)
                    }
                    if (hud.showCurrent && currentMa != null) {
                        val label = if (isCharging) "+${kotlin.math.abs(currentMa)}mA" else "${currentMa}mA"
                        list.add(label to currentColor)
                    }
                    list
                }

                // Dynamically scale typography based on how many items exist
                val itemCount = statusChips.size
                val (tSize, apSize, dSize, sSize) = when {
                    itemCount >= 4 -> Quad(20.sp, 11.sp, 10.sp, 8.sp)
                    itemCount >= 2 -> Quad(25.sp, 12.sp, 11.5.sp, 9.sp)
                    else -> Quad(29.sp, 13.5.sp, 12.5.sp, 9.5.sp)
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (hud.showTime) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = time12,
                                    fontSize = tSize,
                                    fontWeight = FontWeight.ExtraLight,
                                    color = textPrimary,
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = amPm,
                                    fontSize = apSize,
                                    fontWeight = FontWeight.Bold,
                                    color = textPrimary,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            IconButton(
                                onClick = onRefreshApps,
                                enabled = !isRefreshingApps,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_refresh),
                                    contentDescription = "Refresh apps",
                                    tint = if (isRefreshingApps) LauncherColors.Green else textPrimary.copy(alpha = 0.7f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }

                    if (hud.showDate) {
                        Text(
                            text = "$weekday, $dateText",
                            fontSize = dSize,
                            color = textPrimary,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (statusChips.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            statusChips.forEach { (text, color) ->
                                Text(
                                    text = text,
                                    fontSize = sSize,
                                    color = color,
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // Right column: Compact Calendar Widget
            if (calendarMonths > 0) {
                Box(
                    modifier = Modifier
                        .weight(if (calendarMonths == 2) 1.55f else 1.0f)
                        .wrapContentHeight()
                ) {
                    MiniCalendarWidget(
                        monthCount = calendarMonths,
                        sharpEdges = sharpEdgesForCal,
                        isWhiteTheme = isWhiteThemeForCal,
                        dateKey = "${timeData.weekday}_${timeData.dateText}"
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

            if (isEditMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = onToggleEditMode) {
                        Text(
                            text = "Done",
                            color = LauncherColors.Green,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
    }
}

/** Single HUD chip: "Label : value" with status color. */
@Composable
private fun HudMetric(
    label: String,
    value: String,
    color: Color,
    emphasize: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp
) {
    Text(
        text = "$label: $value",
        color = color,
        fontSize = fontSize,
        fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1
    )
}

/** Renders 1–3 month calendar grids side by side. Respects sharpEdges and WhiteTheme. */
@Composable
fun MiniCalendarWidget(
    monthCount: Int,
    sharpEdges: Boolean,
    isWhiteTheme: Boolean = false,
    dateKey: String = ""
) {
    if (monthCount <= 0) return
    // Always compute current calendar day fresh, reactive to dateKey (minute tick & date changed broadcasts)
    val today = remember(dateKey) { Calendar.getInstance() }
    val todayYear = today.get(Calendar.YEAR)
    val todayMonth = today.get(Calendar.MONTH)
    val todayDay = today.get(Calendar.DAY_OF_MONTH)

    val cardRadius = if (sharpEdges) 0.dp else 12.dp
    val dayNames = listOf("S", "M", "T", "W", "T", "F", "S")
    // col 0 = Sun, col 6 = Sat → deep red
    val weekendCols = setOf(0, 6)
    val deepRed = Color(0xFFD32F2F)

    // Sizing adjusted to fit cleanly in split header with crisp modern typography
    val (headerSp, dowSp, dateSp, cellHeightDp, colGapDp) = when (monthCount) {
        1 -> Quintuple(10.sp, 8.5.sp, 9.sp, 18.dp, 6.dp)
        else -> Quintuple(8.sp, 6.5.sp, 6.8.sp, 14.dp, 3.dp)
    }

    val months = remember(monthCount, dateKey) {
        (0 until monthCount).map { offset ->
            val cal = Calendar.getInstance()
            cal.add(Calendar.MONTH, offset)
            cal
        }
    }

    val fullMonthNames = remember {
        java.text.DateFormatSymbols().months
    }

    val cardBg = if (isWhiteTheme) Color.White else Color(0xFF101010)
    val cardBorder = if (isWhiteTheme) Color(0xFFE0E0E0) else Color(0xFF222222)
    val dividerColor = if (isWhiteTheme) Color(0xFFEEEEEE) else Color(0xFF202020)
    val headerColor = if (isWhiteTheme) Color(0xFF111111) else Color(0xFFF0F0F0)
    val weekdayDefaultColor = if (isWhiteTheme) Color(0xFF888888) else Color(0xFF757575)
    val dateDefaultColor = if (isWhiteTheme) Color(0xFF212121) else Color(0xFFB0B0B0)
    val weekendRed = Color(0xFFE53935)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 0.5.dp,
                color = cardBorder,
                shape = RoundedCornerShape(cardRadius)
            )
            .clip(RoundedCornerShape(cardRadius))
            .background(cardBg)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(colGapDp)
    ) {
        months.forEachIndexed { idx, cal ->
            if (idx > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .padding(vertical = 4.dp)
                        .background(dividerColor)
                )
            }
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH)
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val firstDayOfWeek = run {
                val tmp = Calendar.getInstance()
                tmp.set(year, month, 1)
                tmp.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sun
            }
            val isCurrentMonth = todayYear == year && todayMonth == month
            val activeDay = if (isCurrentMonth) todayDay else -1
            val monthTitle = if (monthCount == 1) {
                "${fullMonthNames[month].uppercase()} $year"
            } else {
                "${fullMonthNames[month].take(3).uppercase()} $year"
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 0.5.dp)
            ) {
                // Month + year header
                Text(
                    text = monthTitle,
                    color = headerColor,
                    fontSize = headerSp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                // Day-of-week header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 1.dp)
                ) {
                    dayNames.forEachIndexed { col, d ->
                        Text(
                            text = d,
                            color = if (col in weekendCols) weekendRed else weekdayDefaultColor,
                            fontSize = dowSp,
                            fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Day rows
                val cells = firstDayOfWeek + daysInMonth
                val rows = (cells + 6) / 7
                var dayCounter = 1 - firstDayOfWeek
                repeat(rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(7) { col ->
                            val day = dayCounter++
                            if (day in 1..daysInMonth) {
                                val isToday = day == activeDay
                                val isWeekend = col in weekendCols
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(cellHeightDp)
                                ) {
                                    if (isToday) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(cellHeightDp)
                                                .background(
                                                    color = LauncherColors.Green,
                                                    shape = CircleShape
                                                )
                                        ) {
                                            Text(
                                                text = "$day",
                                                color = Color.Black,
                                                fontSize = dateSp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false,
                                                lineHeight = dateSp,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "$day",
                                            color = if (isWeekend) weekendRed else dateDefaultColor,
                                            fontSize = dateSp,
                                            fontWeight = FontWeight.Normal,
                                            maxLines = 1,
                                            softWrap = false,
                                            lineHeight = dateSp,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                }
                            } else {
                                Spacer(modifier = Modifier.weight(1f).height(cellHeightDp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCard(
    app: AppItem,
    iconEntry: IconEntry?,
    iconSize: androidx.compose.ui.unit.Dp = 38.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 8.5.sp,
    isHighlighted: Boolean = false,
    modifier: Modifier = Modifier,
    onAppClick: (AppItem) -> Unit,
    onAppLongClick: (AppItem) -> Unit
) {
    val sharpEdges by SettingsPrefs.sharpEdgesFlow.collectAsState()
    val whiteTheme by SettingsPrefs.whiteThemeFlow.collectAsState()
    val appShape = if (sharpEdges) RectangleShape else CircleShape
    val labelColor = if (whiteTheme) Color(0xFF111111) else Color.White
    val cardBg = if (whiteTheme) Color(0xFFE5E5E5) else LauncherColors.CardBackground

    // Format app name: if multi-word, allow breaking naturally or replace space with newline if beneficial
    val formattedLabel = remember(app.label) {
        val trimmed = app.label.trim()
        val words = trimmed.split(Regex("\\s+"))
        if (words.size == 2) {
            "${words[0]}\n${words[1]}"
        } else if (words.size > 2) {
            // Split approximately in half across lines
            val mid = (words.size + 1) / 2
            val line1 = words.take(mid).joinToString(" ")
            val line2 = words.drop(mid).joinToString(" ")
            "$line1\n$line2"
        } else {
            trimmed
        }
    }

    // Dynamic font scaling
    val longestWordLen = remember(app.label) {
        app.label.split(Regex("\\s+")).maxOfOrNull { it.length } ?: app.label.length
    }
    val scaledFontSize = when {
        longestWordLen > 14 -> (fontSize.value * 0.85f).sp
        longestWordLen > 10 -> (fontSize.value * 0.92f).sp
        else -> fontSize
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onAppClick(app) },
                onLongClick = { onAppLongClick(app) }
            )
            .padding(horizontal = 1.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(iconSize)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isHighlighted) LauncherColors.Green else cardBg,
                        shape = appShape
                    )
                    .border(
                        width = if (isHighlighted) 2.dp else 0.dp,
                        color = if (isHighlighted) (if (whiteTheme) Color.Black else Color.White) else Color.Transparent,
                        shape = appShape
                    )
                    .clip(appShape),
                contentAlignment = Alignment.Center
            ) {
                if (iconEntry != null) {
                    Image(
                        bitmap = iconEntry.bitmap,
                        contentDescription = app.label,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (iconEntry.isAdaptive) {
                                    Modifier.graphicsLayer(scaleX = 1.5f, scaleY = 1.5f)
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            }

            // Work Profile badge positioned on the top-right corner of the icon so it doesn't crowd app names
            if (app.isWorkProfile) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size((iconSize * 0.38f).coerceIn(12.dp, 16.dp))
                        .background(
                            color = Color(0xFF2E7D32),
                            shape = CircleShape
                        )
                        .padding(2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_work),
                        contentDescription = "Work Profile",
                        tint = Color.White,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = formattedLabel,
            color = labelColor,
            fontSize = scaledFontSize,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = (scaledFontSize.value * 1.05f).sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 0.5.dp)
        )
    }
}

private fun launchApp(context: Context, app: AppItem) {
    try {
        if (app.userHandle != null) {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                as android.content.pm.LauncherApps
            val component = ComponentName(app.packageName, app.className)
            launcherApps.startMainActivity(component, app.userHandle, null, null)
        } else {
            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(app.packageName, app.className)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            context.startActivity(launchIntent)
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Error launching app: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
