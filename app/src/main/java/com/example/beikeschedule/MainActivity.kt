package com.example.beikeschedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.beikeschedule.ui.schedule.ScheduleScreen
import com.example.beikeschedule.ui.theme.BeikeScheduleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeikeScheduleTheme {
                ScheduleScreen()
            }
        }
    }
}
