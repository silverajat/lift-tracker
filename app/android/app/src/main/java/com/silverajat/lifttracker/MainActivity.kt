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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.ui.C
import com.silverajat.lifttracker.ui.ExerciseScreen
import com.silverajat.lifttracker.ui.HomeScreen
import com.silverajat.lifttracker.ui.LiftTheme
import com.silverajat.lifttracker.ui.WorkoutScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

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
        connectPod()

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
                BackHandler(enabled = screen != Screen.Home) { vm.back() }

                Box(Modifier.fillMaxSize().background(C.Bg).imePadding()) {
                    when (val s = screen) {
                        Screen.Home -> HomeScreen(vm, onConnect = ::connectPod, onExport = ::share)
                        is Screen.Workout -> WorkoutScreen(vm, s.day, onShare = ::share)
                        is Screen.ExerciseInfo -> ExerciseScreen(vm, s.exerciseId)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (vm.pod.hasPermissions()) vm.pod.connect()
    }
}
