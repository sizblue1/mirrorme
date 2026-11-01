package dev.mirrorme.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.mirrorme.analysis.Repo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MirrorTheme { MirrorRoot() } }
    }
}

private val Ink = Color(0xFF2F4F9E)

@Composable
fun MirrorTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF9DB2EC), secondary = Color(0xFFE6C35F))
    } else {
        lightColorScheme(primary = Ink, secondary = Color(0xFFB8860B), background = Color(0xFFF7F8F4), surface = Color(0xFFF7F8F4))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val label: String) { Today("오늘"), Routine("루틴"), Places("장소"), Settings("설정") }

@Composable
private fun MirrorRoot() {
    val context = LocalContext.current
    val repo = remember { Repo(context.applicationContext) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i; refresh++ },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Today -> Icons.Filled.Home
                                    Tab.Routine -> Icons.Filled.DateRange
                                    Tab.Places -> Icons.Filled.Place
                                    Tab.Settings -> Icons.Filled.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (Tab.entries[tab]) {
            Tab.Today -> TodayScreen(repo, refresh, m, onOpenSettings = { tab = Tab.Settings.ordinal })
            Tab.Routine -> RoutineScreen(repo, refresh, m)
            Tab.Places -> PlacesScreen(repo, refresh, m)
            Tab.Settings -> SettingsScreen(repo, m)
        }
    }
}
