package com.silverajat.lifttracker

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.ui.C
import com.silverajat.lifttracker.ui.InsightsScreen
import com.silverajat.lifttracker.insights.InsightEngine
import com.silverajat.lifttracker.ui.ExerciseScreen
import com.silverajat.lifttracker.ui.HomeScreen
import com.silverajat.lifttracker.ui.LiftTheme
import com.silverajat.lifttracker.ui.MealsScreen
import com.silverajat.lifttracker.ui.TodayScreen
import com.silverajat.lifttracker.ui.WorkoutScreen

enum class Tab(val title: String) { TODAY("Today"), DAY("Productivity"), MOVE("Movement"), MEALS("Food") }

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val hub: HubViewModel by viewModels()
    private val journal: com.silverajat.lifttracker.journal.JournalViewModel by viewModels()
    private val garden: com.silverajat.lifttracker.garden.GardenViewModel by viewModels()

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Tab to open (a notification opens Insights). */
    private val startTab = kotlinx.coroutines.flow.MutableStateFlow<Tab?>(null)
    /** Notifications open the notification list over whatever tab you're on. */
    private val startPanel = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    private fun openFrom(t: String) {
        when (t) {
            "INSIGHTS" -> startPanel.value = "notifications"
            "GYM", "SPORTS" -> startTab.value = Tab.MOVE
            else -> startTab.value = runCatching { Tab.valueOf(t) }.getOrNull()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("tab")?.let { openFrom(it) }
    }

    /** Health Connect's own permission screen (Fitbit data via the Google Health app). */
    private val askHealth = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { hub.refreshHealth() }

    private fun connectHealth() {
        if (hub.health.available()) askHealth.launch(hub.health.requested)
        else runCatching {
            startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata")))
        }
    }

    private val askBluetooth = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) vm.pod.connect()
    }

    /** GPS for a run or walk (and notifications for the live session). */
    private var gpsSport = com.silverajat.lifttracker.activity.Sport.RUN
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true)
            com.silverajat.lifttracker.activity.RunTracker.start(this, gpsSport)
    }

    private fun startGps(sport: com.silverajat.lifttracker.activity.Sport) {
        gpsSport = sport
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            com.silverajat.lifttracker.activity.RunTracker.start(this, sport)
        else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun connectPod() {
        if (vm.pod.hasPermissions()) vm.pod.connect()
        else askBluetooth.launch(
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        )
    }

    /** Android share sheet with the workout as text (ChatGPT, WhatsApp, notes…). */
    private fun share(s: com.silverajat.lifttracker.data.Session) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${s.day.title} day workout")
            putExtra(Intent.EXTRA_TEXT, vm.shareText(s))
        }
        startActivity(Intent.createChooser(send, "Share workout"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.silverajat.lifttracker.activity.RunTracker.restore(this)
        InsightEngine.channels(this)
        InsightEngine.schedule(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        intent?.getStringExtra("tab")?.let { openFrom(it) }

        setContent {
            LiftTheme {
                val screen by vm.screen.collectAsStateWithLifecycle()
                val session by vm.session.collectAsStateWithLifecycle()

                // Screen stays on during a workout, so the timers and the
                // live rep count are there when you look.
                LaunchedEffect(session != null) {
                    if (session != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
                var gymOpen by rememberSaveable { mutableStateOf(false) }
                var panel by rememberSaveable { mutableStateOf<String?>(null) }   // notifications, settings, chat
                val wanted by startTab.collectAsStateWithLifecycle()
                LaunchedEffect(wanted) { wanted?.let { tab = it; panel = null; startTab.value = null } }
                val wantedPanel by startPanel.collectAsStateWithLifecycle()
                LaunchedEffect(wantedPanel) { wantedPanel?.let { panel = it; startPanel.value = null } }
                // in a workout or an exercise page the gym owns the whole screen
                val fullScreen = screen != Screen.Home
                val shown = if (fullScreen) Tab.MOVE else tab
                BackHandler(enabled = screen != Screen.Home) { vm.back() }
                BackHandler(enabled = !fullScreen && panel != null) { panel = null }
                BackHandler(enabled = !fullScreen && panel == null && tab == Tab.MOVE && gymOpen) { gymOpen = false }
                BackHandler(enabled = screen == Screen.Home && panel == null && !gymOpen && tab != Tab.TODAY) { tab = Tab.TODAY }
                val gymShown = fullScreen || (tab == Tab.MOVE && gymOpen && panel == null)
                LaunchedEffect(gymShown || session != null) {
                    wantPod = gymShown || session != null
                    if (wantPod) connectPod()
                }
                LaunchedEffect(tab) {
                    if (tab == Tab.TODAY) hub.refreshAnalysis()
                    if (tab == Tab.TODAY || tab == Tab.DAY) journal.refresh()
                }
                LaunchedEffect(panel) { if (panel == "notifications") { hub.reloadInsights(); hub.markInsightsSeen() } }
                val unread by hub.unread.collectAsStateWithLifecycle()

                Column(Modifier.fillMaxSize().background(C.Bg).imePadding()) {
                    if (!fullScreen) com.silverajat.lifttracker.ui.TopIcons(panel, unread, onPanel = { panel = if (panel == it) null else it })
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            !fullScreen && panel == "notifications" -> InsightsScreen(hub)
                            !fullScreen && panel == "settings" -> com.silverajat.lifttracker.ui.SettingsScreen(hub)
                            !fullScreen && panel == "chat" -> com.silverajat.lifttracker.ui.ChatScreen(hub, onBack = { panel = null })
                            else -> when (shown) {
                                Tab.TODAY -> TodayScreen(hub, vm, onConnectHealth = ::connectHealth, onConnectPod = ::connectPod,
                                    onOpenGym = { tab = Tab.MOVE; gymOpen = true }, onOpenNet = { tab = Tab.MEALS }, journal = journal, onOpenDay = { tab = Tab.DAY })
                                Tab.DAY -> com.silverajat.lifttracker.ui.DayScreen(journal, garden, hub, vm)
                                Tab.MOVE -> when (val s = screen) {
                                    Screen.Home -> if (gymOpen) {
                                        val an by hub.analysis.collectAsStateWithLifecycle()
                                        HomeScreen(vm, onConnect = ::connectPod, onExport = ::share, strength = an?.exercises.orEmpty(), onBack = { gymOpen = false })
                                    } else com.silverajat.lifttracker.ui.SportsScreen(hub, onStartGps = ::startGps,
                                        onOpenGym = { id -> vm.open(Screen.Past(id)) }, gym = vm, onOpenGymHome = { gymOpen = true })
                                    is Screen.Workout -> WorkoutScreen(vm, s.day, onShare = ::share)
                                    is Screen.ExerciseInfo -> ExerciseScreen(vm, s.exerciseId)
                                    is Screen.Past -> com.silverajat.lifttracker.ui.SessionScreen(vm, s.sessionId, onShare = ::share)
                                }
                                Tab.MEALS -> MealsScreen(hub)
                            }
                        }
                    }
                    if (!fullScreen) NavigationBar(containerColor = C.Bg, tonalElevation = 0.dp,
                        modifier = Modifier.drawBehind { drawLine(C.Line, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t && panel == null,
                                onClick = { if (tab == t && t == Tab.MOVE && panel == null) gymOpen = false; tab = t; panel = null },
                                icon = {
                                    Icon(when (t) {
                                        Tab.TODAY -> Icons.Filled.Favorite
                                        Tab.DAY -> Icons.Filled.EditNote
                                        Tab.MOVE -> Icons.Filled.FitnessCenter
                                        Tab.MEALS -> Icons.Filled.Restaurant
                                    }, t.title)
                                },
                                label = { Text(t.title, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = C.Text, selectedTextColor = C.Text, indicatorColor = C.Raised,
                                    unselectedIconColor = C.Muted, unselectedTextColor = C.Muted,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /** The pod is only worn at the gym: look for it on the Gym tab or during a workout. */
    private var wantPod = false

    /** Focus timer: switching to another app pauses growth; just locking the phone doesn't. */
    override fun onStop() {
        super.onStop()
        val screenOn = getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true
        if (screenOn && !isChangingConfigurations) garden.leftApp()
    }

    override fun onStart() {
        super.onStart()
        garden.cameBack()
    }

    override fun onResume() {
        super.onResume()
        if (wantPod && vm.pod.hasPermissions()) vm.pod.connect()
        hub.refreshHealth()
        hub.reloadInsights()
    }
}
