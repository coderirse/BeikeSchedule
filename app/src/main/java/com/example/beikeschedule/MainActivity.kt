package com.example.beikeschedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.beikeschedule.import.ImportScreen
import com.example.beikeschedule.ui.schedule.ScheduleScreen
import com.example.beikeschedule.ui.theme.BeikeScheduleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeikeScheduleTheme {
                // 页面少，用简单状态切换代替导航库
                var screen by rememberSaveable { mutableStateOf("schedule") }
                when (screen) {
                    "schedule" -> ScheduleScreen(onImportClick = { screen = "import" })
                    "import" -> ImportScreen(onDone = { screen = "schedule" })
                }
            }
        }
    }
}
