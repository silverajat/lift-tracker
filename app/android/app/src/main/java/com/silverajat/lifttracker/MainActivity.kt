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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.FitnessCenter
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

enum class Tab(val title: String) { TODAY("Today"), GYM("Gym"), MEALS("Food"), INSIGHTS("Insights") }

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val hub: HubViewModel by viewModels()

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Tab to open (a notification opens Insights). */
    private val startTab = kotlinx.coroutines.flow.MutableStateFlow<Tab?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("tab")?.let { t -> startTab.value = runCatching { Tab.valueOf(t) }.getOrNull() }
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
        InsightEngine.channels(this)
        InsightEngine.schedule(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        intent?.getStringExtra("tab")?.let { t -> startTab.value = runCatching { Tab.valueOf(t) }.getOrNull() }

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
                val wanted by startTab.collectAsStateWithLifecycle()
                LaunchedEffect(wanted) { wanted?.let { tab = it; startTab.value = null } }
                // in a workout or an exercise page the gym tab owns the whole screen
                val fullScreen = screen != Screen.Home
                val shown = if (fullScreen) Tab.GYM else tab
                BackHandler(enabled = screen != Screen.Home) { vm.back() }
                BackHandler(enabled = screen == Screen.Home && tab != Tab.TODAY) { tab = Tab.TODAY }
                LaunchedEffect(shown == Tab.GYM || session != null) {
                    wantPod = shown == Tab.GYM || session != null
                    if (wantPod) connectPod()
                }
                LaunchedEffect(tab) {
                    if (tab == Tab.TODAY) hub.refreshAnalysis()
                    if (tab == Tab.INSIGHTS) hub.reloadInsights()
                }

                Column(Modifier.fillMaxSize().background(C.Bg).imePadding()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (shown) {
                            Tab.TODAY -> TodayScreen(hub, vm, onConnectHealth = ::connectHealth, onConnectPod = ::connectPod,
                                onOpenGym = { tab = Tab.GYM }, onOpenNet = { tab = Tab.MEALS })
                            Tab.GYM -> when (val s = screen) {
                                Screen.Home -> HomeScreen(vm, onConnect = ::connectPod, onExport = ::share)
                                is Screen.Workout -> WorkoutScreen(vm, s.day, onShare = ::share)
                                is Screen.ExerciseInfo -> ExerciseScreen(vm, s.exerciseId)
                            }
                            Tab.MEALS -> MealsScreen(hub)
                            Tab.INSIGHTS -> InsightsScreen(hub)
                        }
                    }
                    if (!fullScreen) NavigationBar(containerColor = C.Surface) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = {
                                    Icon(when (t) {
                                        Tab.TODAY -> Icons.Filled.Favorite
                                        Tab.GYM -> Icons.Filled.FitnessCenter
                                        Tab.MEALS -> Icons.Filled.Restaurant
                                        Tab.INSIGHTS -> Icons.Filled.Notifications
                                    }, t.title)
                                },
                                label = { Text(t.title) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = C.Accent, selectedTextColor = C.Accent, indicatorColor = C.Raised,
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

    override fun onResume() {
        super.onResume()
        if (wantPod && vm.pod.hasPermissions()) vm.pod.connect()
        hub.refreshHealth()
    }
}
