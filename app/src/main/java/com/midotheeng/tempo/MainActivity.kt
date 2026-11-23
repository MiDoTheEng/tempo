package com.midotheeng.tempo

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- Look ----------

private val Forest = Color(0xFF1F3B34)
private val ForestLight = Color(0xFF2F5A4E)
private val Amber = Color(0xFFE0A458)
private val Sand = Color(0xFFF3E9D2)

private val LightColors = lightColorScheme(
    primary = Forest, onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E8E1), onPrimaryContainer = Forest,
    secondary = Color(0xFFB4732A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8E6CB), onSecondaryContainer = Color(0xFF5A3A10),
    background = Color(0xFFF6F1E7), onBackground = Color(0xFF1B2421),
    surface = Color(0xFFFFFCF6), onSurface = Color(0xFF1B2421),
    surfaceVariant = Color(0xFFEDE6D8), onSurfaceVariant = Color(0xFF5E6662),
    surfaceContainerLow = Color(0xFFFFFCF6), surfaceContainer = Color(0xFFFAF5EC),
    surfaceContainerHigh = Color(0xFFF3EDE1), surfaceContainerHighest = Color(0xFFEDE6D8),
    outline = Color(0xFFCFC6B4), outlineVariant = Color(0xFFE6DFD0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCFBE), onPrimary = Color(0xFF07201A),
    primaryContainer = Color(0xFF24443B), onPrimaryContainer = Color(0xFFCDEBE0),
    secondary = Amber, onSecondary = Color(0xFF2E1C04),
    secondaryContainer = Color(0xFF4A3414), onSecondaryContainer = Color(0xFFF7DDB8),
    background = Color(0xFF0F1715), onBackground = Color(0xFFE6E2D8),
    surface = Color(0xFF16201D), onSurface = Color(0xFFE6E2D8),
    surfaceVariant = Color(0xFF26312D), onSurfaceVariant = Color(0xFFA9B3AE),
    surfaceContainerLow = Color(0xFF16201D), surfaceContainer = Color(0xFF1A2522),
    surfaceContainerHigh = Color(0xFF212D29), surfaceContainerHighest = Color(0xFF28342F),
    outline = Color(0xFF45524D), outlineVariant = Color(0xFF2C3733),
)

private fun tempoType(): Typography {
    val t = Typography()
    val serif = FontFamily.Serif
    return t.copy(
        displaySmall = t.displaySmall.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.ensureChannel(this)
        Store.get(this)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
                typography = tempoType()
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Home() }
            }
        }
    }
}

// ---------- Permissions ----------

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

private fun batteryOn(ctx: Context) =
    ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

private fun contactsOn(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

private fun addContact(ctx: Context, name: String) {
    val typedNumber = typedPhone(ctx, name)
    val clean = if (typedNumber != null) "+$typedNumber" else cleanName(name)
    if (clean.isBlank() || Store.find(ctx, clean) != null) return
    val number = typedNumber ?: Avatars.whatsAppNumber(ctx, clean) ?: ""
    if (number.isNotEmpty() && Store.get(ctx).contacts.any { sameNumber(it.number, number) }) return
    Store.update(ctx) { it.copy(contacts = it.contacts + Contact(name = clean, number = number)) }
    Store.log(ctx, "Now watching $clean")
    fetchWhatsAppPhoto(ctx, clean)
}

/** If the text is a phone number (no letters), its international digits; otherwise null. */
private fun typedPhone(ctx: Context, text: String): String? =
    if (text.none { it.isLetter() } && text.count { it.isDigit() } >= 8) normalizeNumber(ctx, text) else null

// ---------- Home ----------

@Composable
private fun Home() {
    val ctx = LocalContext.current
    val state by Store.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh++
        Engine.settle(ctx)
        WaListener.instance?.sweep()
        // Remember numbers for people added before numbers were saved.
        Store.get(ctx).contacts.filter { it.number.isBlank() }.forEach { c ->
            Avatars.whatsAppNumber(ctx, c.name)?.let { n -> Store.updateContact(ctx, c.name) { it.copy(number = n) } }
        }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(20_000)
            value = System.currentTimeMillis()
        }
    }
    val perms = remember(refresh) { listOf(listenerOn(ctx), accessibilityOn(ctx), notifOn(ctx), exactOn(ctx), batteryOn(ctx), contactsOn(ctx)) }
    val allSet = perms.all { it }
    var showAdd by remember { mutableStateOf(false) }
    var setupOpen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var flashKey by remember { mutableStateOf<String?>(null) }
    val jumpTo: (String) -> Unit = { name ->
        val i = state.contacts.indexOfFirst { nameKey(it.name) == nameKey(name) }
        if (i >= 0) {
            // hero, optional setup card, "People" header, then the cards
            val index = 1 + (if (allSet) 0 else 1) + 1 + i
            scope.launch {
                listState.animateScrollToItem(index)
                flashKey = nameKey(name)
                delay(1_400)
                flashKey = null
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Hero(state, now, jumpTo) }

        if (!allSet) {
            item { SetupCard(perms, onChanged = { refresh++ }, collapsible = false, open = true, onToggle = {}) }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("People", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (state.contacts.isNotEmpty()) {
                    Button(onClick = { showAdd = true }, contentPadding = PaddingValues(horizontal = 16.dp)) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add")
                    }
                }
            }
        }

        if (state.contacts.isEmpty()) {
            item { EmptyPeople(onAdd = { showAdd = true }) }
        } else {
            items(state.contacts, key = { nameKey(it.name) }) { c -> ContactCard(c, now, flash = flashKey == nameKey(c.name)) }
        }

        if (state.log.isNotEmpty()) item { ActivityCard(state.log) }

        if (allSet) {
            item { SetupCard(perms, onChanged = { refresh++ }, collapsible = true, open = setupOpen, onToggle = { setupOpen = !setupOpen }) }
        }
    }

    if (showAdd) AddSheet(state, onDismiss = { showAdd = false })
}

@Composable
private fun Hero(state: AppState, now: Long, onPick: (String) -> Unit) {
    val held = state.contacts.filter { it.held.isNotEmpty() && it.releaseAt > 0 }
    val timing = state.contacts.filter { it.mode == MODE_MIRROR && it.lastMySend > 0 && it.held.isEmpty() }
    val mint = Color(0xFF9CCFBE)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Forest, ForestLight)))
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_fg), null,
            Modifier.size(190.dp).align(Alignment.TopEnd).offset(x = 52.dp, y = (-34).dp).alpha(0.16f)
        )
        Column(Modifier.padding(vertical = 22.dp)) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                Text("Tempo", style = MaterialTheme.typography.displaySmall, color = Sand)
                Text("Their pace, mirrored.", style = MaterialTheme.typography.bodyMedium, color = Sand.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(20.dp))
            PeopleRow(
                "Watching",
                state.contacts.map { c ->
                    Bubble(
                        c.name, null,
                        when {
                            c.held.isNotEmpty() -> Amber
                            c.mode == MODE_MIRROR && c.lastMySend > 0 -> mint
                            else -> Sand.copy(alpha = 0.35f)
                        }
                    )
                },
                empty = "No one yet — add someone below",
                onPick = onPick
            )
            Spacer(Modifier.height(18.dp))
            PeopleRow(
                "Timing",
                timing.map { Bubble(it.name, shortDur(now - it.lastMySend), mint) },
                empty = "No replies being timed",
                onPick = onPick
            )
            if (held.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                PeopleRow(
                    "Held",
                    held.map { Bubble(it.name, shortDur(it.releaseAt - now) + " left", Amber) },
                    empty = "",
                    onPick = onPick
                )
            }
        }
    }
}

private data class Bubble(val name: String, val caption: String?, val ring: Color)

private fun shortDur(ms: Long): String {
    val m = (ms.coerceAtLeast(0) / 60_000)
    return when {
        m < 1 -> "<1m"
        m < 60 -> "${m}m"
        m % 60 == 0L -> "${m / 60}h"
        else -> "${m / 60}h ${m % 60}m"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleRow(label: String, people: List<Bubble>, empty: String, onPick: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp, color = Sand.copy(alpha = 0.7f))
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { Text("${people.size}", style = MaterialTheme.typography.labelSmall, color = Sand) }
    }
    Spacer(Modifier.height(10.dp))
    if (people.isEmpty()) {
        Text(
            empty,
            style = MaterialTheme.typography.bodySmall,
            color = Sand.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 22.dp)
        )
    } else {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            people.forEach { p ->
                Column(
                    Modifier.width(62.dp).clip(RoundedCornerShape(14.dp)).clickable { onPick(p.name) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.size(58.dp).border(2.5.dp, p.ring, CircleShape).padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) { Avatar(p.name, 50.dp) }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        p.name.substringBefore(' '),
                        style = MaterialTheme.typography.labelSmall,
                        color = Sand,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    p.caption?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = p.ring, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPeople(onAdd: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(Forest),
                contentAlignment = Alignment.Center
            ) {
                Image(painterResource(R.drawable.ic_launcher_fg), null, Modifier.size(120.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("No one yet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Pick someone whose replies you want to mirror.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = onAdd) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add someone")
            }
        }
    }
}

// ---------- Contact ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactCard(c: Contact, now: Long, flash: Boolean = false) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val holding = c.held.isNotEmpty() && c.releaseAt > 0
    val timing = !holding && c.mode == MODE_MIRROR && c.lastMySend > 0
    val ring by animateColorAsState(
        when {
            holding -> cs.secondary
            timing -> cs.primary
            else -> cs.outlineVariant
        }, label = "ring"
    )
    val flashColor by animateColorAsState(if (flash) cs.secondary else cs.outlineVariant, label = "flash")
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var editNumber by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        border = BorderStroke(if (flash) 2.5.dp else 1.dp, flashColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(64.dp)
                        .border(2.5.dp, ring, CircleShape)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .clickable { fetchWhatsAppPhoto(ctx, c.name) }
                ) { Avatar(c.name, 54.dp) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (c.number.isNotBlank()) "+${c.number}" else "Add phone number",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (c.number.isNotBlank()) cs.onSurfaceVariant else cs.secondary,
                        modifier = Modifier.clickable { editNumber = true }
                    )
                    if (c.aliases.isNotEmpty()) {
                        Text(
                            "Also shows as: " + c.aliases.joinToString(", "),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    val (label, bg, fg) = when {
                        holding -> Triple("Holding ${c.held.size}", cs.secondaryContainer, cs.onSecondaryContainer)
                        timing -> Triple("Timing their reply", cs.primaryContainer, cs.onPrimaryContainer)
                        else -> Triple("Idle", cs.surfaceVariant, cs.onSurfaceVariant)
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = fg,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Refresh photo & name") }, onClick = { menu = false; fetchWhatsAppPhoto(ctx, c.name) })
                        DropdownMenuItem(text = { Text("Remove", color = cs.error) }, onClick = { menu = false; confirmRemove = true })
                    }
                }
            }

            if (holding) {
                Spacer(Modifier.height(16.dp))
                HoldPanel(c, now)
            } else if (timing) {
                Spacer(Modifier.height(16.dp))
                TimingPanel(c, now)
            }

            if (c.mode == MODE_MIRROR && !holding) {
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { Engine.onMySend(ctx, c.name, System.currentTimeMillis(), "marked by hand") },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (timing) "I messaged them again — restart timer" else "I just messaged them")
                }
            }

            Spacer(Modifier.height(16.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = c.mode == MODE_MIRROR,
                    onClick = { Store.updateContact(ctx, c.name) { it.copy(mode = MODE_MIRROR) } },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Match their time") }
                SegmentedButton(
                    selected = c.mode == MODE_FIXED,
                    onClick = { Store.updateContact(ctx, c.name) { it.copy(mode = MODE_FIXED) } },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("Fixed delay") }
            }
            Spacer(Modifier.height(12.dp))
            if (c.mode == MODE_MIRROR) {
                ValueStepper(
                    label = "Longest hold",
                    value = fmtDuration(c.capMinutes * 60_000L),
                    onMinus = { Store.updateContact(ctx, c.name) { it.copy(capMinutes = (it.capMinutes - 60).coerceAtLeast(60)) } },
                    onPlus = { Store.updateContact(ctx, c.name) { it.copy(capMinutes = (it.capMinutes + 60).coerceAtMost(72 * 60)) } }
                )
            } else {
                ValueStepper(
                    label = "Hold for",
                    value = fmtDuration(c.fixedMinutes * 60_000L),
                    onMinus = { Store.updateContact(ctx, c.name) { it.copy(fixedMinutes = (it.fixedMinutes - 15).coerceAtLeast(15)) } },
                    onPlus = { Store.updateContact(ctx, c.name) { it.copy(fixedMinutes = (it.fixedMinutes + 15).coerceAtMost(72 * 60)) } }
                )
            }
            if (c.lastWaitMs > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Their last reply took ${fmtDuration(c.lastWaitMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            AutoReplyBox(c, now)
        }
    }

    if (editNumber) {
        var draft by remember { mutableStateOf(if (c.number.isNotBlank()) "+${c.number}" else "") }
        val parsed = normalizeNumber(ctx, draft)
        AlertDialog(
            onDismissRequest = { editNumber = false },
            title = { Text("${c.name}'s number") },
            text = {
                Column {
                    Text(
                        "Tempo uses this to recognise them even if their name changes. 050… or +971… both work.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        singleLine = true,
                        placeholder = { Text("+971 50 123 4567") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (parsed != null) {
                        Text("Saved as +$parsed", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = parsed != null, onClick = {
                    Store.updateContact(ctx, c.name) { it.copy(number = parsed ?: "") }
                    Store.log(ctx, "Saved ${c.name}'s number")
                    editNumber = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val found = Avatars.whatsAppNumber(ctx, c.name)
                    if (found != null) {
                        draft = "+$found"
                    } else {
                        Toast.makeText(ctx, "Not found in your contacts — type it in", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Find in contacts") }
            }
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoReplyBox(c: Contact, now: Long) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh)
            .padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = if (c.autoReply.isBlank()) 6.dp else 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-reply", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text(
                    when {
                        !c.autoReplyOn -> "Off"
                        c.autoReplyRepeat -> "Every time they message"
                        else -> "Once, when the hold ends"
                    } + if (c.autoReplyOn && c.autoReplySilent) " · silent" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Switch(
                checked = c.autoReplyOn,
                onCheckedChange = { on ->
                    if (on && c.autoReply.isBlank()) editing = true
                    else Store.updateContact(ctx, c.name) { it.copy(autoReplyOn = on) }
                }
            )
        }
        val pauseEnd = c.lastAutoReplyAt + AUTO_REPLY_PAUSE_MS
        if (c.autoReplyOn && now < pauseEnd) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().padding(end = 6.dp).clip(RoundedCornerShape(12.dp))
                    .background(cs.secondaryContainer).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⏸", modifier = Modifier.padding(end = 8.dp))
                Column {
                    Text(
                        "Replied at ${fmtTime(c.lastAutoReplyAt)} — paused for ${shortDur(pauseEnd - now)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSecondaryContainer
                    )
                    Text(
                        "Their messages are collected and get one reply at ${fmtTime(pauseEnd)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
        if (c.autoReply.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                c.autoReply,
                style = MaterialTheme.typography.bodyMedium,
                color = if (c.autoReplyOn) cs.onPrimary else cs.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(start = 40.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                    .background(if (c.autoReplyOn) cs.primary else cs.outlineVariant)
                    .clickable { editing = true }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(end = 6.dp)) {
                SegmentedButton(
                    selected = !c.autoReplyRepeat,
                    onClick = { Store.updateContact(ctx, c.name) { it.copy(autoReplyRepeat = false) } },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Just once") }
                SegmentedButton(
                    selected = c.autoReplyRepeat,
                    onClick = { Store.updateContact(ctx, c.name) { it.copy(autoReplyRepeat = true) } },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("Every time") }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Silent", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Reply without notifying me",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
                Switch(
                    checked = c.autoReplySilent,
                    onCheckedChange = { on -> Store.updateContact(ctx, c.name) { it.copy(autoReplySilent = on) } }
                )
            }
            Text(
                "Tap the message to edit",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
            )
        }
    }

    if (editing) {
        var draft by remember { mutableStateOf(c.autoReply) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Auto-reply to ${c.name}") },
            text = {
                Column {
                    Text(
                        "Tempo sends this for you when their held messages are shown. Choose Just once or Every time on the card.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        minLines = 3,
                        placeholder = { Text("Your message") },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = draft.isNotBlank(), onClick = {
                    Store.updateContact(ctx, c.name) { it.copy(autoReply = draft.trim(), autoReplyOn = true) }
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HoldPanel(c: Contact, now: Long) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val start = c.held.first().time
    val total = (c.releaseAt - start).coerceAtLeast(1)
    val progress = ((now - start).toFloat() / total).coerceIn(0f, 1f)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.secondaryContainer).padding(16.dp)
    ) {
        Text(
            "${c.held.size} message${if (c.held.size == 1) "" else "s"} waiting",
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSecondaryContainer.copy(alpha = 0.8f)
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "Shows in ${fmtDuration((c.releaseAt - now).coerceAtLeast(0))}",
                style = MaterialTheme.typography.headlineSmall,
                color = cs.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            Text(fmtTime(c.releaseAt), style = MaterialTheme.typography.labelLarge, color = cs.onSecondaryContainer)
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
            color = cs.secondary,
            trackColor = cs.onSecondaryContainer.copy(alpha = 0.12f),
            drawStopIndicator = {}
        )
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(
            onClick = { Engine.releaseNow(ctx, c.name) },
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = cs.secondary, contentColor = cs.onSecondary)
        ) { Text("Show now") }
    }
}

@Composable
private fun TimingPanel(c: Contact, now: Long) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.primaryContainer).padding(16.dp)
    ) {
        Text(
            "You messaged at ${fmtTime(c.lastMySend)}",
            style = MaterialTheme.typography.labelLarge,
            color = cs.onPrimaryContainer.copy(alpha = 0.8f)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Waiting ${fmtDuration((now - c.lastMySend).coerceAtLeast(0))}",
            style = MaterialTheme.typography.headlineSmall,
            color = cs.onPrimaryContainer
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Their reply will be held this long.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onPrimaryContainer.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun ValueStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        RoundIcon("−", onMinus)
        Spacer(Modifier.width(6.dp))
        RoundIcon("+", onPlus)
    }
}

@Composable
private fun RoundIcon(symbol: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(symbol, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary) }
}

// ---------- Setup ----------

@Composable
private fun SetupCard(perms: List<Boolean>, onChanged: () -> Unit, collapsible: Boolean, open: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val done = perms.count { it }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                Modifier.fillMaxWidth().then(if (collapsible) Modifier.clickable(onClick = onToggle) else Modifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (collapsible) "Setup & tips" else "Finish setup", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (done == perms.size) "Everything is allowed" else "$done of ${perms.size} done",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
                if (collapsible) Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
            }
            if (!collapsible) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { done / perms.size.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                    drawStopIndicator = {}
                )
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SetupStep(1, "Notification access", "See and hide WhatsApp notifications.", perms[0]) {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    SetupStep(
                        2, "Accessibility",
                        "Notice when you tap Send. Greyed out? Settings → Apps → Tempo → ⋮ → Allow restricted settings.",
                        perms[1]
                    ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    SetupStep(3, "Notifications", "Show the held messages when it's time.", perms[2]) {
                        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    SetupStep(4, "Alarms & reminders", "Release messages on the minute.", perms[3]) {
                        if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                    SetupStep(
                        5, "Unrestricted battery",
                        "Keeps Tempo running so auto-replies send even when the phone is locked.",
                        perms[4]
                    ) {
                        try {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                            )
                        } catch (_: Exception) {
                            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                        Avatars.refresh()
                        onChanged()
                    }
                    SetupStep(
                        6, "Contacts",
                        "Recognise people by phone number, even when WhatsApp shows their saved name.",
                        perms[5]
                    ) {
                        contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                    val st by Store.state.collectAsState()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Debug log", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Write every WhatsApp notification Tempo sees into Activity (with seconds)",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                        Switch(checked = st.debug, onCheckedChange = { on -> Store.update(ctx) { it.copy(debug = on) } })
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.secondaryContainer.copy(alpha = 0.6f))
                    ) {
                        Box(Modifier.width(4.dp).height(96.dp).background(cs.secondary))
                        Text(
                            "In WhatsApp, open each person → Notifications → Custom notifications: tone None, vibrate Off, high priority Off. Tempo makes the sound instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSecondaryContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupStep(n: Int, title: String, sub: String, done: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (done) cs.primary else cs.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            if (done) Icon(Icons.Filled.Check, null, tint = cs.onPrimary, modifier = Modifier.size(18.dp))
            else Text("$n", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (!done) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Allow") }
        }
    }
}

// ---------- Activity ----------

@Composable
private fun ActivityCard(log: List<String>) {
    val cs = MaterialTheme.colorScheme
    var all by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Activity", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            val lines = if (all) log else log.take(5)
            lines.forEachIndexed { i, line ->
                val time = line.substringBefore("  ", "")
                val text = line.substringAfter("  ", line)
                Row {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(14.dp)) {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == 0) cs.secondary else cs.outline))
                        if (i < lines.lastIndex) Box(Modifier.width(1.5.dp).height(34.dp).background(cs.outlineVariant))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.padding(bottom = 10.dp)) {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                        if (time.isNotEmpty()) Text(time, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            if (log.size > 5) {
                TextButton(onClick = { all = !all }) { Text(if (all) "Show less" else "Show all ${log.size}") }
            }
        }
    }
}

// ---------- Add sheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(state: AppState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var permTick by remember { mutableIntStateOf(0) }
    val hasPerm = remember(permTick) { contactsOn(ctx) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        Avatars.refresh()
        permTick++
    }
    val all = remember(hasPerm) { if (hasPerm) try { loadWhatsAppContacts(ctx) } catch (_: Exception) { emptyList() } else emptyList() }
    var query by remember { mutableStateOf("") }
    val watched = state.contacts.map { nameKey(it.name) }.toSet()
    val q = query.trim()
    val list = all.filter { nameKey(it) !in watched && (q.isEmpty() || it.contains(q, ignoreCase = true)) }
    val recent = state.recent.filter { nameKey(it) !in watched && (q.isEmpty() || it.contains(q, ignoreCase = true)) }
    fun pick(name: String) {
        addContact(ctx, name)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surface
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Add someone", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search, name, or +country number") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (recent.isNotEmpty()) {
                SectionLabel("Recent chats")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(recent) { s ->
                        Column(
                            Modifier.width(66.dp).clip(RoundedCornerShape(12.dp)).clickable { pick(s) }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Avatar(s, 54.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(s, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            SectionLabel("WhatsApp contacts")
            if (!hasPerm) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHigh).padding(18.dp)
                ) {
                    Text("See your WhatsApp contacts here", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Tempo only reads names and photos, on your phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { permLauncher.launch(Manifest.permission.READ_CONTACTS) }) { Text("Allow contacts") }
                }
            }
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                if (q.isNotEmpty() && all.none { it.equals(q, ignoreCase = true) } && nameKey(q) !in watched) {
                    item {
                        ListItem(
                            headlineContent = {
                                val num = typedPhone(ctx, q)
                                Text(if (num != null) "Add number +$num" else "Add “$q”")
                            },
                            supportingContent = {
                                Text(
                                    if (typedPhone(ctx, q) != null) "Works even if they're not in your contacts"
                                    else "Exactly as WhatsApp shows it — or type their number with country code"
                                )
                            },
                            leadingContent = {
                                Box(Modifier.size(42.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Add, null, tint = cs.onPrimaryContainer)
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { pick(q) }
                        )
                    }
                }
                items(list) { name ->
                    ListItem(
                        headlineContent = { Text(name) },
                        leadingContent = { Avatar(name, 42.dp) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { pick(name) }
                    )
                }
                if (hasPerm && all.isEmpty()) {
                    item {
                        Text(
                            "No WhatsApp contacts found. Open their chat in WhatsApp and come back — they'll show under Recent chats.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 18.dp, bottom = 10.dp)
    )
}

/** Names of contacts that WhatsApp has linked in the phone's address book. */
private fun loadWhatsAppContacts(ctx: Context): List<String> {
    val ids = mutableSetOf<Long>()
    ctx.contentResolver.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(ContactsContract.RawContacts.CONTACT_ID),
        "${ContactsContract.RawContacts.ACCOUNT_TYPE} IN (?, ?) AND ${ContactsContract.RawContacts.DELETED} = 0",
        arrayOf("com.whatsapp", "com.whatsapp.w4b"),
        null
    )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
    if (ids.isEmpty()) return emptyList()

    val names = mutableSetOf<String>()
    ids.chunked(400).forEach { chunk ->
        ctx.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            "${ContactsContract.Contacts._ID} IN (${chunk.joinToString(",")})",
            null, null
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { if (it.isNotBlank()) names += it } }
    }
    return names.sortedBy { it.lowercase() }
}

@Composable
private fun Avatar(name: String, size: Dp) {
    val ctx = LocalContext.current
    val ver by Avatars.version.collectAsState()
    val bmp by produceState<Bitmap?>(initialValue = null, name, ver) {
        value = withContext(Dispatchers.IO) {
            try { Avatars.load(ctx, name) } catch (_: Exception) { null }
        }
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                cleanName(name).firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = if (size > 40.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelMedium
            )
        }
    }
}

/**
 * Opens the contact's WhatsApp chat for a moment so the accessibility service can copy the
 * profile photo from the chat header, then comes back to Tempo.
 */
private fun fetchWhatsAppPhoto(ctx: Context, name: String) {
    if (Build.VERSION.SDK_INT < 30) return
    if (!accessibilityOn(ctx)) {
        Toast.makeText(ctx, "Turn on Accessibility for Tempo to get the WhatsApp photo", Toast.LENGTH_LONG).show()
        return
    }
    val number = Store.find(ctx, name)?.number?.takeIf { it.isNotBlank() } ?: Avatars.whatsAppNumber(ctx, name)
    if (number == null) {
        Toast.makeText(ctx, "Couldn't find $name's number — open their chat in WhatsApp once and the photo will be grabbed", Toast.LENGTH_LONG).show()
        Avatars.openedByNumber = false
        Avatars.pendingCapture = name to (System.currentTimeMillis() + 60_000)
        return
    }
    val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
        ctx.packageManager.getLaunchIntentForPackage(it) != null
    } ?: return
    Avatars.openedByNumber = true
    Avatars.pendingCapture = name to (System.currentTimeMillis() + 15_000)
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$number"))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Toast.makeText(ctx, "Getting $name's photo…", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Avatars.pendingCapture = null
    }
}
