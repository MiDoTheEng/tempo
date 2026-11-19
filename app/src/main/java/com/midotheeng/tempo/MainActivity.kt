package com.midotheeng.tempo

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.ensureChannel(this)
        Store.get(this)
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { Home() }
            }
        }
    }
}

private fun listenerOn(ctx: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

private fun accessibilityOn(ctx: Context): Boolean {
    val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    return list.split(':').any { it.startsWith(ctx.packageName + "/") }
}

private fun notifOn(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun exactOn(ctx: Context) = Build.VERSION.SDK_INT < 31 ||
    ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Home() {
    val ctx = LocalContext.current
    val state by Store.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh++
        Engine.settle(ctx)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    // Read refresh so permission rows recompute on resume.
    val perms = remember(refresh) {
        listOf(listenerOn(ctx), accessibilityOn(ctx), notifOn(ctx), exactOn(ctx))
    }
    val allSet = perms.all { it }
    var showSetup by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Tempo", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "Holds WhatsApp notifications from people you pick — for as long as they made you wait.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (allSet) "Setup ✓" else "Setup — ${perms.count { !it }} step(s) left",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (allSet) TextButton(onClick = { showSetup = !showSetup }) {
                            Text(if (showSetup) "Hide" else "Show")
                        }
                    }
                    if (!allSet || showSetup) {
                        SetupRow("1. Notification access", "Lets Tempo see and hide WhatsApp notifications.", perms[0]) {
                            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                        SetupRow(
                            "2. Accessibility",
                            "Lets Tempo notice when you tap Send. If it's greyed out: Settings → Apps → Tempo → ⋮ → Allow restricted settings, then try again.",
                            perms[1]
                        ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        SetupRow("3. Show notifications", "So Tempo can show the held messages.", perms[2]) {
                            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        SetupRow("4. Alarms & reminders", "So held messages appear on time, not a few minutes late.", perms[3]) {
                            if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                            )
                        }
                        Text(
                            "5. In WhatsApp, open each picked contact → Notifications → Custom notifications: tone None, vibrate Off, high priority Off. Otherwise WhatsApp buzzes before Tempo can hide it — Tempo makes the sound instead.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item { AddContact(state) }

        items(state.contacts, key = { nameKey(it.name) }) { c -> ContactCard(c) }

        if (state.log.isNotEmpty()) {
            item {
                Text("Activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            items(state.log) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SetupRow(title: String, sub: String, done: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        if (done) Text("✓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        else FilledTonalButton(onClick = onClick) { Text("Open") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddContact(state: AppState) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    fun add(name: String) {
        val clean = cleanName(name)
        if (clean.isBlank() || Store.find(ctx, clean) != null) return
        Store.update(ctx) { it.copy(contacts = it.contacts + Contact(name = clean)) }
        Store.log(ctx, "Now watching $clean")
        text = ""
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add a contact", style = MaterialTheme.typography.titleMedium)
            Text(
                "Type the name exactly as it shows in WhatsApp, or tap someone who messaged you recently.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    singleLine = true, label = { Text("Name") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { add(text) }, enabled = text.isNotBlank()) { Text("Add") }
            }
            val suggestions = state.recent.filter { r -> state.contacts.none { nameKey(it.name) == nameKey(r) } }
            if (suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s -> AssistChip(onClick = { add(s) }, label = { Text(s) }) }
                }
            }
        }
    }
}

@Composable
private fun ContactCard(c: Contact) {
    val ctx = LocalContext.current
    var confirmRemove by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = c.mode == MODE_MIRROR,
                    onClick = { Store.updateContact(ctx, c.name) { it.copy(mode = MODE_MIRROR) } },
                    label = { Text("Match their time") }
                )
                FilterChip(
                    selected = c.mode == MODE_FIXED,
                    onClick = { Store.updateContact(ctx, c.name) { it.copy(mode = MODE_FIXED) } },
                    label = { Text("Fixed delay") }
                )
            }

            if (c.mode == MODE_MIRROR) {
                Stepper(
                    label = "Never hold longer than ${fmtDuration(c.capMinutes * 60_000L)}",
                    onMinus = { Store.updateContact(ctx, c.name) { it.copy(capMinutes = (it.capMinutes - 60).coerceAtLeast(60)) } },
                    onPlus = { Store.updateContact(ctx, c.name) { it.copy(capMinutes = (it.capMinutes + 60).coerceAtMost(72 * 60)) } }
                )
            } else {
                Stepper(
                    label = "Hold for ${fmtDuration(c.fixedMinutes * 60_000L)}",
                    onMinus = { Store.updateContact(ctx, c.name) { it.copy(fixedMinutes = (it.fixedMinutes - 15).coerceAtLeast(15)) } },
                    onPlus = { Store.updateContact(ctx, c.name) { it.copy(fixedMinutes = (it.fixedMinutes + 15).coerceAtMost(72 * 60)) } }
                )
            }

            val status = when {
                c.held.isNotEmpty() && c.releaseAt > 0 ->
                    "Holding ${c.held.size} message(s) until ${fmtTime(c.releaseAt)}"
                c.mode == MODE_MIRROR && c.lastMySend > 0 ->
                    "You messaged at ${fmtTime(c.lastMySend)} — timing their reply"
                else -> "Nothing held"
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (c.lastWaitMs > 0) {
                Text(
                    "Their last reply took ${fmtDuration(c.lastWaitMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.held.isNotEmpty()) {
                    FilledTonalButton(onClick = { Engine.releaseNow(ctx, c.name) }) { Text("Show now") }
                } else if (c.mode == MODE_MIRROR) {
                    OutlinedButton(onClick = {
                        Engine.onMySend(ctx, c.name, System.currentTimeMillis(), "marked by hand")
                    }) { Text("I just messaged them") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { confirmRemove = true }) { Text("Remove") }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Stop watching ${c.name}?") },
            text = { Text("Any held messages will be shown now.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    Engine.releaseNow(ctx, c.name)
                    Store.update(ctx) { st -> st.copy(contacts = st.contacts.filter { nameKey(it.name) != nameKey(c.name) }) }
                    Store.log(ctx, "Stopped watching ${c.name}")
                    Alarms.scheduleNext(ctx)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("−") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onPlus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("+") }
    }
}
