package com.reelcounter.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val Accent = Color(0xFFE1306C)
private val Bg = Color(0xFF0F0F14)
private val CardBg = Color(0xFF1A1A22)
private val Muted = Color(0xFF9A9AA8)
private val Danger = Color(0xFFFF5252)

class MainActivity : ComponentActivity() {

    private var serviceEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)
            ) {
                Home(
                    serviceEnabled = serviceEnabled,
                    onOpenSettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = isServiceEnabled()
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, ReelAccessibilityService::class.java)
        val setting = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return setting.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

@Composable
fun Home(serviceEnabled: Boolean, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ReelDb.get(ctx) }
    val prefs = remember { ctx.getSharedPreferences("settings", 0) }
    var limit by remember { mutableIntStateOf(prefs.getInt("limit", 100)) }
    var confirmReset by remember { mutableStateOf(false) }

    val version by ReelDb.changes.collectAsState()
    val stats by produceState<Stats?>(initialValue = null, key1 = version) {
        value = withContext(Dispatchers.IO) { Stats.compute(db) }
    }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.statusBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Reel Counter", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Instagram Reels you've watched", color = Muted, fontSize = 14.sp)
            }

            if (!serviceEnabled) item { PermissionCard(onOpenSettings) }

            val s = stats
            if (s == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                }
            } else {
                item {
                    TodayCard(s, limit) {
                        limit = it
                        prefs.edit().putInt("limit", it).apply()
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard("This week", s.week.toString(), Modifier.weight(1f))
                        StatCard("All time", s.total.toString(), Modifier.weight(1f))
                        StatCard("Avg / day", "%.0f".format(s.avgPerActiveDay), Modifier.weight(1f))
                    }
                }
                item {
                    CardBox {
                        SectionTitle("Last 7 days")
                        Row(Modifier.fillMaxWidth()) {
                            s.daily.forEach {
                                Text(
                                    it.second.toString(), Modifier.weight(1f),
                                    fontSize = 12.sp, color = Muted,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                        BarChart(s.daily.map { it.second }, highlight = s.daily.lastIndex)
                        LabelRow(s.daily.map { it.first })
                    }
                }
                item {
                    CardBox {
                        SectionTitle("When you scroll")
                        BarChart(s.hourly, highlight = s.peakHour ?: -1)
                        LabelRow(List(24) { h ->
                            when (h) { 0 -> "12a"; 6 -> "6a"; 12 -> "12p"; 18 -> "6p"; else -> "" }
                        })
                        Spacer(Modifier.height(8.dp))
                        Text(
                            s.peakHour?.let { "Peak hour: ${hourLabel(it)}" } ?: "No data yet",
                            color = Muted, fontSize = 13.sp
                        )
                    }
                }
                item {
                    CardBox {
                        SectionTitle("Today's sessions")
                        Text(
                            "${s.sessionsToday} session${if (s.sessionsToday == 1) "" else "s"} · " +
                                "longest ${s.longestSessionToday} reels",
                            color = Muted, fontSize = 14.sp
                        )
                        Text("A session ends after 5 minutes without a reel.", color = Muted, fontSize = 12.sp)
                    }
                }
                item {
                    CardBox {
                        SectionTitle("Most-watched creators")
                        if (s.creators.isEmpty()) {
                            Text("Nothing yet. Watch some reels!", color = Muted, fontSize = 14.sp)
                        } else s.creators.forEach { (name, n) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(name, Modifier.weight(1f), maxLines = 1)
                                Text(n.toString(), color = Accent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Reset all data")
                    }
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all data?") },
            text = { Text("This deletes your entire reel history from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    Thread { db.clear() }.start()
                }) { Text("Delete", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PermissionCard(onOpen: () -> Unit) {
    CardBox {
        Text("Tracking is off", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Turn on the Reel Counter accessibility service so it can see when you're on Instagram Reels. " +
                "It only reads the Instagram app and nothing leaves your phone.",
            color = Muted, fontSize = 13.sp
        )
        Text(
            "If the toggle is greyed out (Android 13+): App info → ⋮ → Allow restricted settings.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpen) { Text("Enable tracking") }
    }
}

@Composable
private fun TodayCard(s: Stats, limit: Int, onLimit: (Int) -> Unit) {
    val over = s.today >= limit
    val diff = s.today - s.yesterday
    CardBox {
        Text("TODAY", color = Muted, fontSize = 12.sp, letterSpacing = 1.5.sp)
        Text(
            s.today.toString(), fontSize = 64.sp, fontWeight = FontWeight.Bold,
            color = if (over) Danger else Accent
        )
        Text("reels watched", color = Muted)
        Text(
            when {
                diff > 0 -> "$diff more than yesterday"
                diff < 0 -> "${-diff} fewer than yesterday"
                else -> "Same as yesterday"
            },
            color = Muted, fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { (s.today.toFloat() / limit).coerceIn(0f, 1f) },
            color = if (over) Danger else Accent,
            trackColor = Color(0xFF2B2B36),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Daily limit: $limit", Modifier.weight(1f), fontSize = 13.sp, color = Muted)
            TextButton(onClick = { onLimit((limit - 10).coerceAtLeast(10)) }) { Text("−10") }
            TextButton(onClick = { onLimit(limit + 10) }) { Text("+10") }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    CardBox(modifier) {
        Text(label, color = Muted, fontSize = 12.sp, maxLines = 1)
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun BarChart(values: List<Int>, highlight: Int) {
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val slot = size.width / values.size
        val gap = slot * 0.25f
        val bw = slot - gap
        values.forEachIndexed { i, v ->
            val h = (size.height * v / max).coerceAtLeast(3f)
            drawRoundRect(
                color = if (i == highlight) Accent else Accent.copy(alpha = 0.35f),
                topLeft = Offset(i * slot + gap / 2, size.height - h),
                size = Size(bw, h),
                cornerRadius = CornerRadius(6f, 6f)
            )
        }
    }
}

@Composable
private fun LabelRow(labels: List<String>) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        labels.forEach {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(it, fontSize = 10.sp, color = Muted, softWrap = false, maxLines = 1)
            }
        }
    }
}

private fun hourLabel(h: Int): String =
    LocalTime.of(h, 0).format(DateTimeFormatter.ofPattern("h a"))
