package com.streamcontrols.app.ui

import android.graphics.BitmapFactory
import android.animation.ValueAnimator
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.streamcontrols.app.R
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.streamcontrols.app.ui.theme.StreamControlsTheme
import com.streamcontrols.app.youtube.Channel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.streamcontrols.app.preferences.Preferences
import com.streamcontrols.app.preferences.ThemePreference
import com.streamcontrols.app.preferences.isValidPreset
import com.streamcontrols.app.preferences.titleCharacterCount
import com.streamcontrols.app.youtube.LiveStream
import com.streamcontrols.app.youtube.StreamVisibility
import com.streamcontrols.app.youtube.Platform
import com.streamcontrols.app.youtube.isValidStreamTitle
import com.streamcontrols.app.youtube.streamTitleError
import com.streamcontrols.app.youtube.titleLimit
import com.streamcontrols.app.youtube.StreamsState
import com.streamcontrols.app.youtube.MessageScreen
import com.streamcontrols.app.youtube.StreamsViewModel
import com.streamcontrols.app.youtube.ThumbnailFetcher
import com.streamcontrols.app.youtube.channelImageUrl
import com.streamcontrols.app.youtube.twitchImageUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private data class Destination(val route: String, val label: String, val icon: Int)
private val destinations = listOf(
    Destination("streams", "Streams", R.drawable.ic_nav_streams),
    Destination("accounts", "Accounts", R.drawable.ic_nav_accounts),
    Destination("settings", "Settings", R.drawable.ic_nav_settings)
)

@Composable
fun StreamControlApp(settings: Preferences, setTheme: (ThemePreference) -> Unit, setPresets: (List<String>) -> Unit, setAccountNotifications: (Boolean) -> Unit, model: StreamsViewModel, chooseAccount: () -> Unit, chooseTwitch: () -> Unit) {
    val nav = rememberNavController()
    val state by model.state.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    var consent by remember { mutableStateOf(false) }
    fun navigateToTab(route: String) {
        if (nav.currentDestination?.route == route) return
        nav.navigate(route) {
            popUpTo("streams")
            launchSingleTop = true
        }
    }
    Scaffold(bottomBar = {
        DashboardBottomBar(when (entry?.destination?.route) { "manage" -> "streams"; "notices", "notices/{license}" -> "settings"; else -> entry?.destination?.route }) { destination ->
            navigateToTab(destination.route)
        }
    }) { padding ->
        NavHost(navController = nav, startDestination = "streams", modifier = Modifier.padding(padding)) {
            composable("streams") { StreamsScreen(state, settings.presets, { stream -> model.select(stream); nav.navigate("manage") }, model::edit, { model.updateTitle() }, model::refresh, onAccounts = { navigateToTab("accounts") }, onConnect = { consent = true }) }
            composable("manage") {
                DisposableEffect(Unit) { onDispose { model.clearCompletedMessage() } }
                StreamManagementScreen(state, settings.presets, model::edit, { model.updateTitle() }, model::endSelectedStream, { nav.popBackStack() })
            }
            composable("accounts") { AccountsScreen(state, onConnect = { consent = true }, onConnectTwitch = chooseTwitch, onDisconnect = model::disconnect) }
            composable("settings") { SettingsScreen(settings, setTheme, setPresets, setAccountNotifications, onAccounts = { navigateToTab("accounts") }, onNotices = { nav.navigate("notices") }) }
            composable("notices") { ThirdPartyNoticesScreen(onBack = { nav.popBackStack() }, onSelect = { nav.navigate("notices/$it") }) }
            composable("notices/{license}") { backStack ->
                LicenseDetailsScreen(backStack.arguments?.getString("license"), onBack = { nav.popBackStack() })
            }
        }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Connect YouTube") },
        text = { Text("Google will ask you to grant YouTube account management access. This permission is broader than the app's stream controls. StreamControl uses it to find your channel and manage the selected livestream. Select the intended YouTube channel.") },
        confirmButton = { TextButton(onClick = { consent = false; chooseAccount() }) { Text("Continue with Google") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Cancel") } })
    state.conflictTitle?.let { current ->
        AlertDialog(onDismissRequest = model::dismissConflict, title = { Text("Title changed elsewhere") },
            text = { Text("The current title is:\n\n$current\n\nReplace it with your new title?") },
            confirmButton = { TextButton(onClick = { model.updateTitle(acceptConflict = true) }) { Text("Replace title") } },
            dismissButton = { TextButton(onClick = model::dismissConflict) { Text("Cancel") } })
    }
}

@Composable
private fun DashboardBottomBar(selectedRoute: String?, onNavigate: (Destination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = selectedRoute == destination.route,
                onClick = { onNavigate(destination) },
                icon = { Icon(painterResource(destination.icon), contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = { Text(destination.label, style = MaterialTheme.typography.labelLarge) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

@Composable
private fun Screen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content
    )
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DashboardHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(42.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_broadcast), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(27.dp))
            }
        }
        Header("StreamControl", "Manage your live stream")
    }
}

@Composable
private fun PlatformMark(platform: Platform) {
    // The supplied YouTube asset includes transparent padding around the mark.
    if (platform == Platform.YOUTUBE) Image(painterResource(R.drawable.ic_youtube), contentDescription = "YouTube", modifier = Modifier.size(width = 26.dp, height = 22.dp))
    else Icon(painterResource(R.drawable.ic_twitch), contentDescription = "Twitch", tint = Color.Unspecified, modifier = Modifier.size(18.dp))
}

@Composable
private fun Message(state: StreamsState, screen: MessageScreen) {
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.messageFor(screen)?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
internal fun ChannelCard(channel: Channel?, reconnect: Boolean, platform: Platform = channel?.platform ?: Platform.YOUTUBE, showDisconnected: Boolean = true, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        BoxWithConstraints(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            val compact = trailing != null && maxWidth / LocalDensity.current.fontScale < (if (reconnect || (channel == null && showDisconnected)) 340.dp else 290.dp)
            @Composable fun Details(modifier: Modifier = Modifier) {
                Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ChannelAvatar(channel?.imageUrl, platform)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(channel?.name ?: if (platform == Platform.TWITCH) "Twitch" else "YouTube", modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        PlatformMark(platform)
                    }
                    if (reconnect || (channel == null && showDisconnected)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(8.dp)) {}
                        Text(if (reconnect) "Reconnect required" else "Not connected", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                }
            }
            if (compact) {
                Column(Modifier.fillMaxWidth()) {
                    Details(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { trailing?.invoke(this) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Details(Modifier.weight(1f))
                    trailing?.invoke(this)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamsScreen(state: StreamsState, presets: List<String>, onSelect: (LiveStream) -> Unit, onEdit: (String) -> Unit, onUpdate: () -> Unit, onRefresh: () -> Unit, onAccounts: () -> Unit, onConnect: () -> Unit) {
    Screen {
        DashboardHeader()
        if (state.channels.size > 1) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.channels.size} channels", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    FilledTonalButton(onClick = onAccounts) { Text("Accounts") }
                }
            }
        } else {
            val summary = state.channels.singleOrNull()
            ChannelCard(summary, state.reconnect || (summary?.id?.let { it in state.reconnectIds } == true)) {
                FilledTonalButton(onClick = onAccounts, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Accounts") }
            }
        }
        Message(state, MessageScreen.STREAMS)
        if (state.channels.isEmpty()) {
            Text("Active Streams", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Connect an account to find and manage your active streams.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAccounts, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp)) {
                Text("Go to Accounts")
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Active Streams", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${state.streams.size} ${if (state.streams.size == 1) "stream" else "streams"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.loaded && state.streams.isEmpty()) Text("No active livestreams found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.streams.forEach { stream -> StreamCard(stream, state.channels.find { it.id == stream.channelId }?.name.orEmpty(), stream.id == state.selectedId, !state.busy) { onSelect(stream) } }
            }
            if (state.reconnect) OutlinedButton(onClick = onConnect, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Reconnect YouTube") }
            OutlinedButton(onClick = onRefresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp)); Text("Refresh", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamManagementScreen(state: StreamsState, presets: List<String>, onEdit: (String) -> Unit, onUpdate: () -> Unit, onEnd: (String) -> Unit, onBack: () -> Unit) {
    var confirmEnd by remember { mutableStateOf<Pair<LiveStream, Channel>?>(null) }
    val selected = state.streams.find { it.id == state.selectedId }
    val selectedChannel = state.channels.find { it.id == selected?.channelId }
    Screen {
        TextButton(onClick = onBack, enabled = !state.busy) { Text("Back to streams") }
        Message(state, MessageScreen.MANAGE)
        if (selected == null) Text("This livestream is no longer available.")
        else {
            StreamCard(selected, selectedChannel?.name.orEmpty(), selected = true, enabled = false)
            var editing by remember(selected.id) { mutableStateOf(false) }
            if (!editing) {
                Button(onClick = { editing = true }, enabled = !state.busy && selectedChannel != null && selected.channelId !in state.reconnectIds, modifier = Modifier.fillMaxWidth()) { Text("Edit Title") }
                if (selected.platform == Platform.YOUTUBE) {
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(onClick = { selectedChannel?.let { confirmEnd = selected to it } }, enabled = !state.busy && selectedChannel != null && selected.channelId !in state.reconnectIds, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("End Stream") }
                } else Text("End your Twitch stream in your streaming software.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
                    Text("Title Editor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Current title", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth()) {
                        Text(selected.title, modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("New title", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(value = state.draft, onValueChange = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.busy, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant),
                        isError = state.draft.isNotEmpty() && !isValidStreamTitle(state.draft, selected.platform))
                    val error = streamTitleError(state.draft, selected.platform)
                    if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text("${titleCharacterCount(state.draft)}/${titleLimit(selected.platform)}", modifier = Modifier.align(Alignment.End), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (presets.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Title presets", style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            presets.forEach { preset -> AssistChip(onClick = { onEdit(preset) }, enabled = !state.busy, label = { Text(preset, maxLines = 1, overflow = TextOverflow.Ellipsis) }, shape = RoundedCornerShape(50)) }
                        }
                    }
                }
                Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), shape = RoundedCornerShape(10.dp), enabled = !state.busy && selectedChannel != null && selected.channelId !in state.reconnectIds && isValidStreamTitle(state.draft, selected.platform) && state.draft != selected.title) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp)); Text("Update Title", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { editing = false }, enabled = !state.busy) { Text("Back") }
            }
        }
    }
    confirmEnd?.let { confirmed -> AlertDialog(
        onDismissRequest = { confirmEnd = null }, title = { Text("End this livestream?") },
        text = { Text("You're about to end '${confirmed.first.title}' on the YouTube channel '${confirmed.second.name}'. This cannot be undone. Your streaming software may continue sending video until you stop it separately.") },
        confirmButton = { TextButton(onClick = { confirmEnd = null; if (state.channels.any { it.id == confirmed.second.id }) onEnd(confirmed.first.id) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("End Livestream") } },
        dismissButton = { TextButton(onClick = { confirmEnd = null }) { Text("Cancel") } }
    )
    }
}

@Composable
private fun StreamCard(stream: LiveStream, channelName: String, selected: Boolean, enabled: Boolean, onSelect: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth().then(if (onSelect != null) Modifier.selectable(selected = selected, enabled = enabled, role = Role.Button, onClick = onSelect) else Modifier),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .48f).compositeOver(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        BoxWithConstraints(Modifier.padding(8.dp)) {
          val compact = maxWidth / LocalDensity.current.fontScale < 300.dp
          Column {
           Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                Thumbnail(stream.thumbnail, stream.platform, compact)
                Surface(Modifier.padding(4.dp).align(Alignment.TopStart), color = Color(0xFFE91A25), shape = RoundedCornerShape(5.dp)) {
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Surface(Modifier.size(5.dp), shape = RoundedCornerShape(50), color = Color.White) {}
                        Text("LIVE", color = Color.White, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StreamTitle(stream.title)
                if (channelName.isNotBlank()) Text(channelName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlatformMark(stream.platform)
                    val visibility = when (stream.visibility) {
                        StreamVisibility.PUBLIC -> "Public"
                        StreamVisibility.PRIVATE -> "Private"
                        StreamVisibility.UNLISTED -> "Unlisted"
                        StreamVisibility.UNKNOWN -> "Visibility unknown"
                    }
                    if (stream.platform == Platform.YOUTUBE) Text("YouTube · $visibility", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Text("Twitch", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_clock), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(liveDuration(stream.startedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onSelect != null && !compact) Text("Manage", modifier = Modifier.padding(end = 6.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
           }
           if (onSelect != null && compact) Text("Manage", modifier = Modifier.align(Alignment.End).padding(end = 6.dp, top = 4.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
          }
        }
    }
}

@Composable
private fun StreamTitle(title: String) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(title, scroll, lifecycle, density) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { scroll.maxValue to visible }.collectLatest { (end, isVisible) ->
                scroll.scrollTo(0)
                if (end > 0 && end != Int.MAX_VALUE && isVisible && ValueAnimator.areAnimatorsEnabled()) {
                    while (isActive && ValueAnimator.areAnimatorsEnabled()) {
                        delay(1000)
                        val duration = (end / density / 40f * 1000).roundToInt().coerceAtLeast(1)
                        scroll.animateScrollTo(end, tween(durationMillis = duration, easing = LinearEasing))
                        delay(1000)
                        scroll.scrollTo(0)
                    }
                }
            }
        }
    }
    Text(title, modifier = Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = bounds.width > 0 && bounds.height > 0 && bounds.bottom > 0 && bounds.top < view.height
    }.horizontalScroll(scroll, enabled = false), style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
}

@Composable
private fun liveDuration(startedAt: String?): String {
    var now by remember(startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) { while (startedAt != null) { delay(60_000); now = System.currentTimeMillis() } }
    val elapsed = remember(startedAt, now) {
        try { startedAt?.let { Duration.between(Instant.parse(it), Instant.ofEpochMilli(now)).toMinutes().coerceAtLeast(0) } }
        catch (_: Exception) { null }
    }
    return when {
        elapsed == null -> "Live now"
        elapsed >= 60 -> "Live for ${elapsed / 60}h ${elapsed % 60}m"
        else -> "Live for ${elapsed}m"
    }
}
private fun decodeThumbnail(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) null
    else {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}
private val thumbnailFetcher = ThumbnailFetcher<ImageBitmap>(decode = ::decodeThumbnail)
private val channelImageFetcher = ThumbnailFetcher<ImageBitmap>(validateUrl = ::channelImageUrl, decode = ::decodeThumbnail)
private val twitchImageFetcher = ThumbnailFetcher<ImageBitmap>(validateUrl = ::twitchImageUrl, decode = ::decodeThumbnail)

@Composable
private fun ChannelAvatar(url: String?, platform: Platform) {
    val bitmap by produceState<ImageBitmap?>(null, url, platform) {
        value = null
        value = withContext(Dispatchers.IO) { (if (platform == Platform.TWITCH) twitchImageFetcher else channelImageFetcher).load(url) }
    }
    Surface(Modifier.size(40.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
        if (bitmap != null) Image(bitmap!!, contentDescription = null, contentScale = ContentScale.Crop)
        else Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_nav_accounts), contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun Thumbnail(url: String?, platform: Platform, compact: Boolean = false) {
    val bitmap by produceState<ImageBitmap?>(null, url, platform) {
        value = null
        value = withContext(Dispatchers.IO) {
            (if (platform == Platform.TWITCH) twitchImageFetcher else thumbnailFetcher).load(url)
        }
    }
    Surface(Modifier.size(width = if (compact) 96.dp else 112.dp, height = if (compact) 60.dp else 72.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
        if (bitmap != null) Image(bitmap!!, contentDescription = null, contentScale = ContentScale.Crop)
        else Box(contentAlignment = Alignment.Center) { Text(if (platform == Platform.TWITCH) "Twitch" else "YouTube", style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun AccountsScreen(state: StreamsState, onConnect: () -> Unit, onConnectTwitch: () -> Unit, onDisconnect: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    var confirmDisconnect by remember { mutableStateOf(false) }
    var disconnectChannel by remember { mutableStateOf<Channel?>(null) }
    Screen {
        Header("Accounts", "Manage your streaming connections")
        Message(state, MessageScreen.ACCOUNTS)
        state.channels.forEach { channel ->
            ChannelCard(channel, channel.id in state.reconnectIds) {
                if (channel.id in state.reconnectIds) {
                    var menu by remember(channel.id) { mutableStateOf(false) }
                    TextButton(onClick = if (channel.platform == Platform.TWITCH) onConnectTwitch else onConnect, enabled = !state.busy, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Reconnect") }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; disconnectChannel = channel; confirmDisconnect = true })
                        }
                    }
                } else {
                    TextButton(onClick = { disconnectChannel = channel; confirmDisconnect = true }) { Text("Disconnect") }
                }
            }
        }
        if (state.channels.none { it.platform == Platform.YOUTUBE }) {
            ChannelCard(null, state.reconnect, showDisconnected = false) {
                TextButton(onClick = onConnect, enabled = !state.busy) { Text(if (state.reconnect) "Reconnect" else "Connect") }
            }
        } else {
            OutlinedButton(onClick = onConnect, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Add YouTube channel")
            }
        }
        if (state.channels.none { it.platform == Platform.TWITCH }) {
            ChannelCard(null, false, Platform.TWITCH, showDisconnected = false) { TextButton(onClick = onConnectTwitch, enabled = !state.busy) { Text("Connect") } }
        } else {
            OutlinedButton(onClick = onConnectTwitch, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Add Twitch channel") }
        }
        TextButton(onClick = { uriHandler.openUri("https://myaccount.google.com/permissions") }) { Text("Manage Google account access") }
        TextButton(onClick = { uriHandler.openUri("https://www.twitch.tv/settings/connections") }) { Text("Manage Twitch account access") }
    }
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text(if (disconnectChannel?.platform == Platform.TWITCH) "Disconnect Twitch?" else "Disconnect YouTube?") },
        text = { Text(if (disconnectChannel?.platform == Platform.TWITCH) "Disconnect ‘${disconnectChannel?.name}’ from Twitch? StreamControl will remove the local connection and ask Twitch to revoke access." else "Disconnect ${disconnectChannel?.name?.let { "‘$it’ from YouTube" } ?: "this YouTube connection"}? StreamControl will remove the local connection and ask Google to revoke access. Google may also revoke access for other connected channels or clients on the same account and project.") },
        confirmButton = { TextButton(onClick = {
            confirmDisconnect = false
            disconnectChannel?.id?.takeIf { id -> state.channels.any { it.id == id } }?.let(onDisconnect)
        }) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } }
    )
}

@Composable
private fun SettingsScreen(settings: Preferences, setTheme: (ThemePreference) -> Unit, setPresets: (List<String>) -> Unit, setAccountNotifications: (Boolean) -> Unit, onAccounts: () -> Unit, onNotices: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notificationsAvailable by remember { mutableStateOf(accountNotificationsAvailable(context)) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAvailable = accountNotificationsAvailable(context)
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsAvailable = accountNotificationsAvailable(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var showEditor by remember { mutableStateOf(false) }
    var presetText by remember { mutableStateOf("") }
    Screen {
        Header("Settings", "Personalize StreamControl")
        Text("Appearance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        ThemePreference.entries.forEach { theme ->
            Row(Modifier.fillMaxWidth().clickable { setTheme(theme) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.theme == theme, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(when (theme) { ThemePreference.SYSTEM -> "System default"; ThemePreference.LIGHT -> "Light"; ThemePreference.DARK -> "Dark" })
            }
        }
        HorizontalDivider()
        Text("Notifications", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().toggleable(value = settings.accountConnectionNotifications, role = Role.Switch, onValueChange = { enabled ->
            setAccountNotifications(enabled)
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Accounts connected", style = MaterialTheme.typography.bodyLarge)
                Text("Notify when an account connects while you're away from the app.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = settings.accountConnectionNotifications, onCheckedChange = null)
        }
        if (settings.accountConnectionNotifications && !notificationsAvailable) {
            Text("Notifications are disabled in Android settings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("Open notification settings") }
        }
        HorizontalDivider()
        Text("Title presets", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (settings.presets.isEmpty()) Text("No presets yet. Add titles you use often.")
        settings.presets.forEachIndexed { index, preset -> ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(preset, style = MaterialTheme.typography.bodyLarge)
            Row { TextButton(onClick = { editingIndex = index; presetText = preset; showEditor = true }) { Text("Edit") }; TextButton(onClick = { setPresets(settings.presets.filterIndexed { i, _ -> i != index }) }) { Text("Delete") } }
        } } }
        OutlinedButton(onClick = { editingIndex = -1; presetText = ""; showEditor = true }) { Text("Add preset") }
        HorizontalDivider()
        Text("Privacy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("StreamControl communicates directly with Google, YouTube, and Twitch. It has no analytics or advertising. Google authorization grants broader YouTube access than title editing.")
        TextButton(onClick = onAccounts) { Text("Connected accounts") }
        TextButton(onClick = onNotices) { Text("Third-party notices") }
        Text("Version 1.0 - Development build", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showEditor) AlertDialog(onDismissRequest = { showEditor = false }, title = { Text(if (editingIndex < 0) "Add title preset" else "Edit title preset") },
        text = { OutlinedTextField(value = presetText, onValueChange = { presetText = it }, label = { Text("Title") }, supportingText = { Text("${titleCharacterCount(presetText)}/140 · YouTube titles have a 100-character limit and cannot contain < or >.") }) },
        confirmButton = { TextButton(enabled = isValidPreset(presetText), onClick = {
            val next = settings.presets.toMutableList(); if (editingIndex in next.indices) next[editingIndex] = presetText else next.add(presetText)
            setPresets(next); showEditor = false
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { showEditor = false }) { Text("Cancel") } })
}

@Composable
private fun ThirdPartyNoticesScreen(onBack: () -> Unit, onSelect: (String) -> Unit) {
    Screen {
        TextButton(onClick = onBack) { Text("Back to settings") }
        Text("Third-party notices", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        ElevatedCard(onClick = { onSelect("apache") }, modifier = Modifier.fillMaxWidth()) {
            Text("Apache License 2.0", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
        ElevatedCard(onClick = { onSelect("mozilla") }, modifier = Modifier.fillMaxWidth()) {
            Text("Mozilla Public License 2.0", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun LicenseDetailsScreen(license: String?, onBack: () -> Unit) {
    val details = when (license) {
        "apache" -> Triple("Apache License 2.0", "Libraries: OkHttp and Okio", R.raw.apache_license_2)
        "mozilla" -> Triple("Mozilla Public License 2.0", "Included with: OkHttp public suffix data", R.raw.mozilla_license_2)
        else -> null
    }
    val context = LocalContext.current
    Screen {
        TextButton(onClick = onBack) { Text("Back to notices") }
        if (details == null) {
            Text("License not found.")
        } else {
            val text = remember(details.third) { context.resources.openRawResource(details.third).bufferedReader().use { it.readText() } }
            Text(details.first, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(details.second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Preview(showBackground = true, name = "Connected dashboard")
@Composable
private fun DashboardPreview() {
    val stream = LiveStream("preview", "Live stream title", null, null, StreamVisibility.PUBLIC, "preview")
    StreamControlsTheme(darkTheme = true) {
        Scaffold(bottomBar = { DashboardBottomBar("streams") {} }) { padding ->
            Box(Modifier.padding(padding)) {
                StreamsScreen(StreamsState(channels = listOf(Channel("preview", "CreatorName")), streams = listOf(stream, LiveStream("preview2", "Just Chatting", null, null, StreamVisibility.UNLISTED, "preview")), selectedId = stream.id, draft = "Late Night Push", loaded = true), listOf("Just Chatting", "Ranked Grind"), {}, {}, {}, {}, {}, {})
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 320, name = "Narrow accounts")
@Preview(showBackground = true, widthDp = 360, fontScale = 1.5f, name = "Large text accounts")
@Composable
private fun AccountsPreview() {
    StreamControlsTheme(darkTheme = true) {
        AccountsScreen(
            StreamsState(channels = listOf(Channel("preview", "A very long channel name")), reconnectIds = setOf("preview")),
            onConnect = {}, onConnectTwitch = {}, onDisconnect = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 320, fontScale = 1.5f, name = "Narrow stream card")
@Composable
private fun StreamCardPreview() {
    StreamControlsTheme(darkTheme = true) {
        StreamCard(LiveStream("preview", "A long live stream title", null, null, StreamVisibility.UNLISTED, "preview"), "Channel name", selected = false, enabled = true, onSelect = {})
    }
}
