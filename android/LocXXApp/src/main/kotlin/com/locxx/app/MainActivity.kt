package com.locxx.app

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.random.Random
import kotlinx.coroutines.delay

private val LocxxPlayerGivenNames = listOf(
    "James", "Maria", "Wei", "Sofia", "Marcus", "Priya", "Lucas", "Emma", "Diego", "Hannah",
    "Jordan", "Aisha", "Noah", "Elena", "Ryan", "Mei", "Omar", "Nina", "Ethan", "Camila",
    "Daniel", "Amara", "Leo", "Claire", "Andre", "Yuki", "Miguel", "Rosa", "Ben", "Zara",
    "Sam", "Layla", "Tyler", "Fatima", "Alex", "Chiara", "Julian", "Mira", "Chris", "Anya"
)

private val LocxxPlayerFamilyNames = listOf(
    "Reyes", "Nakamura", "Patel", "Okonkwo", "Kowalski", "García", "Müller", "Silva",
    "Andersson", "Nguyen", "Tanaka", "Rossi", "Hernández", "Cohen", "Khan", "Murphy",
    "Park", "Ouedraogo", "Dubois", "Santos", "Yamamoto", "Berg", "Kim", "Costa",
    "Fischer", "Singh", "Schmidt", "Ahmed", "Romano", "Jensen", "Huang", "Martin",
    "Ali", "O'Brien", "Suzuki", "Bernard", "Ibrahim", "Kelly", "Lopez", "Chen", "Hayes"
)

/** Random “real life”–style display name chosen when a session starts. */
private fun randomPlayerDisplayName(): String =
    "${LocxxPlayerGivenNames.random(Random)} ${LocxxPlayerFamilyNames.random(Random)}"

private const val LOCXX_PREFS = "locxx_app"

private const val KEY_DISPLAY_NAME = "display_name"

private fun Context.loadSavedDisplayName(): String =
    getSharedPreferences(LOCXX_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_DISPLAY_NAME, null)
        ?.trim()
        .orEmpty()

private fun Context.saveDisplayName(value: String) {
    getSharedPreferences(LOCXX_PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_DISPLAY_NAME, value.trim())
        .apply()
}

/** Uses [fieldText] if non-blank; otherwise a new random name (when the user starts a mode). */
private fun displayNameForNewSession(fieldText: String): String {
    val t = fieldText.trim()
    return if (t.isNotEmpty()) t else randomPlayerDisplayName()
}

private val LocxxCream = Color(0xFFF3E8DF)
private val LocxxSurface = Color(0xFFFFFBF7)
private val LocxxOnSurface = Color(0xFF3A2B22)
private val LocxxMutedOrange = Color(0xFFC48154)
private val LocxxOrangeDeep = Color(0xFF8F4F2C)
private val LocxxOutline = Color(0xFF7A5C48)

private val LocxxLightScheme = lightColorScheme(
    primary = LocxxMutedOrange,
    onPrimary = Color(0xFFFFFBF7),
    primaryContainer = Color(0xFFE8CCB8),
    onPrimaryContainer = LocxxOrangeDeep,
    secondary = LocxxOrangeDeep,
    onSecondary = Color(0xFFFFFBF7),
    tertiary = LocxxOutline,
    background = LocxxCream,
    onBackground = LocxxOnSurface,
    surface = LocxxSurface,
    onSurface = LocxxOnSurface,
    surfaceVariant = Color(0xFFEADDD0),
    onSurfaceVariant = Color(0xFF5C483C),
    outline = LocxxOutline
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
            @Suppress("DEPRECATION")
            window.isNavigationBarContrastEnforced = false
        }
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = LocxxLightScheme
            ) {
                Surface(
                    Modifier
                        .fillMaxSize()
                        .safeAreaPadding()
                ) {
                    LocXXScreen()
                }
            }
        }
    }
}

@Composable
fun LocXXScreen(vm: LocXXViewModel = viewModel()) {
    var name by remember { mutableStateOf("") }
    var prefsReady by remember { mutableStateOf(false) }
    var gameBoardOpen by remember { mutableStateOf(false) }
    var showGameStartingCountdown by remember { mutableStateOf(false) }
    /** Client: “about to start” runs once per LAN session (not on every [match] update / roll). */
    var clientGameIntroConsumed by remember { mutableStateOf(false) }
    var showSoundSettings by remember { mutableStateOf(false) }
    val log by vm.log.collectAsState()
    val peers by vm.peers.collectAsState()
    val match by vm.match.collectAsState()
    val role by vm.role.collectAsState()
    val lanSessionPlayStarted by vm.lanSessionPlayStarted.collectAsState()
    val hostEndedSessionMessage by vm.hostEndedSessionMessage.collectAsState()
    val peerJoinedPopup by vm.peerJoinedPopup.collectAsState()
    val joinHostPrompt by vm.joinHostPrompt.collectAsState()
    val joinAwaitingHostStart by vm.joinAwaitingHostStart.collectAsState()
    val joinSessionHostDisplayName by vm.joinSessionHostDisplayName.collectAsState()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val activity = context as? Activity
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = previous
        }
    }

    LaunchedEffect(Unit) {
        name = context.applicationContext.loadSavedDisplayName()
        context.applicationContext.loadLocxxSoundSettingsIntoRuntime()
        prefsReady = true
    }

    LaunchedEffect(prefsReady) {
        if (!prefsReady) return@LaunchedEffect
        val chosen = displayNameForNewSession(name)
        if (chosen != name) {
            name = chosen
            context.applicationContext.saveDisplayName(chosen)
        }
    }

    LaunchedEffect(lanSessionPlayStarted, joinAwaitingHostStart) {
        if (lanSessionPlayStarted && joinAwaitingHostStart) {
            vm.clearJoinAwaitingHostStart()
        }
    }

    LaunchedEffect(role) {
        if (role == null) {
            gameBoardOpen = false
            showGameStartingCountdown = false
            clientGameIntroConsumed = false
        }
    }

    LaunchedEffect(lanSessionPlayStarted, role, match) {
        if (clientGameIntroConsumed) return@LaunchedEffect
        if (
            lanSessionPlayStarted &&
            role == LocXXViewModel.Role.Client &&
            match != null
        ) {
            clientGameIntroConsumed = true
            showGameStartingCountdown = true
        }
    }

    LaunchedEffect(showGameStartingCountdown) {
        if (!showGameStartingCountdown) return@LaunchedEffect
        // Keep in sync with [GameStartingCountdownDialog]: slide + 3×1s + 4×200ms + 3×1ms gaps + slide (~4.56s).
        delay(4_600)
        showGameStartingCountdown = false
        gameBoardOpen = true
    }

    val canShowLanBoard =
        match != null && (role == LocXXViewModel.Role.Host || role == LocXXViewModel.Role.Client)

    if (gameBoardOpen && (role == LocXXViewModel.Role.SinglePlayer || canShowLanBoard)) {
        SinglePlayerGameScreen(
            vm = vm,
            onExit = { gameBoardOpen = false },
            onOpenSoundSettings = { showSoundSettings = true }
        )
    } else {
        LocXXLandingContent(
            name = name,
            onNameChange = {
                name = it
                context.applicationContext.saveDisplayName(it)
            },
            log = log,
            peers = peers,
            role = role,
            showJoinSearching = role == LocXXViewModel.Role.JoinSearching && joinHostPrompt == null,
            onStartHostedGame = {
                vm.broadcastLanGameStarted()
                showGameStartingCountdown = true
            },
            onHostGame = {
                val chosen = displayNameForNewSession(name)
                if (chosen != name) {
                    name = chosen
                    context.applicationContext.saveDisplayName(chosen)
                }
                vm.startHost(chosen)
            },
            onJoinGame = {
                val chosen = displayNameForNewSession(name)
                if (chosen != name) {
                    name = chosen
                    context.applicationContext.saveDisplayName(chosen)
                }
                vm.startJoinHostDiscovery(chosen)
            },
            onCancelJoinSearch = { vm.cancelJoinHostFlow() },
            onStopHosting = { vm.stopHostingLobby() },
            onOpenScoreSheet = { gameBoardOpen = true },
            onPlayOffline = {
                val chosen = displayNameForNewSession(name)
                name = chosen
                context.applicationContext.saveDisplayName(chosen)
                vm.startSinglePlayer(chosen)
                gameBoardOpen = true
            },
            onExitApp = {
                vm.stopAll()
                (context as? Activity)?.finish()
            }
        )
    }

    if (showSoundSettings) {
        LocxxSoundSettingsDialog(
            appContext = context.applicationContext,
            onDismiss = { showSoundSettings = false }
        )
    }

    hostEndedSessionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.dismissHostEndedMessage() },
            title = { Text("Host stopped") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { vm.dismissHostEndedMessage() }) {
                    Text("OK")
                }
            }
        )
    }

    val showJoinDialog =
        joinHostPrompt != null ||
            (joinAwaitingHostStart && role == LocXXViewModel.Role.Client)
    if (showJoinDialog) {
        val offer = joinHostPrompt
        AlertDialog(
            onDismissRequest = {
                if (offer != null) vm.cancelJoinHostFlow()
            },
            properties = DialogProperties(
                dismissOnBackPress = offer != null,
                dismissOnClickOutside = offer != null
            ),
            title = { Text("Join game") },
            text = {
                when {
                    offer != null ->
                        Text("Would you like to join ${offer.hostDisplayName}'s game?")
                    else ->
                        Text("Waiting on $joinSessionHostDisplayName to start the game")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (offer != null) vm.cancelJoinHostFlow()
                        else vm.cancelClientWaitForHost()
                    }
                ) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = offer != null,
                    onClick = { if (offer != null) vm.acceptJoinHostOffer(offer) }
                ) {
                    Text("Yes")
                }
            }
        )
    }

    peerJoinedPopup?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.dismissPeerJoinedPopup() },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { vm.dismissPeerJoinedPopup() }) {
                    Text("OK")
                }
            }
        )
    }

    if (showGameStartingCountdown) {
        GameStartingCountdownDialog(
            onFinished = {
                showGameStartingCountdown = false
                gameBoardOpen = true
            }
        )
    }
}

@Composable
private fun GameStartingCountdownDialog(onFinished: () -> Unit) {
    val density = LocalDensity.current
    val screenWidthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val slide = remember { Animatable(-1f) }
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        // Dialog content is otherwise clip-bounds–tight; card would vanish once it leaves the box.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { clip = false },
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .padding(24.dp)
                    .graphicsLayer {
                        translationX = slide.value * screenWidthPx
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "The game is about to start! Get ready!",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        slide.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
        )
        playLocxxGameStartCountdownBeep(0)
        delay(1_000)
        playLocxxGameStartCountdownBeep(1)
        delay(1_000)
        playLocxxGameStartCountdownBeep(2)
        delay(1_000)
        playLocxxGameStartCountdownBeep(3)
        delay(803)
        slide.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
        )
        onFinished()
    }
}

@Composable
private fun LocXXLandingContent(
    name: String,
    onNameChange: (String) -> Unit,
    log: List<String>,
    peers: List<UiPeer>,
    role: LocXXViewModel.Role?,
    showJoinSearching: Boolean,
    onStartHostedGame: () -> Unit,
    onHostGame: () -> Unit,
    onJoinGame: () -> Unit,
    onCancelJoinSearch: () -> Unit,
    onStopHosting: () -> Unit,
    onOpenScoreSheet: () -> Unit,
    onPlayOffline: () -> Unit,
    onExitApp: () -> Unit
) {
    var showDebugLog by remember { mutableStateOf(true) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val tallEnough = maxHeight >= 460.dp
        val compact = maxHeight < 460.dp
        val padH: Dp = if (compact) 10.dp else 16.dp
        val padV: Dp = if (compact) 4.dp else 8.dp
        val btnPad = PaddingValues(
            horizontal = if (compact) 6.dp else 12.dp,
            vertical = if (compact) 4.dp else 10.dp
        )
        val sideBySide = maxWidth >= 520.dp

        if (sideBySide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = padH, vertical = padV),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier
                        .weight(if (showDebugLog) 0.54f else 1f)
                        .fillMaxHeight()
                        .padding(end = if (showDebugLog) 10.dp else 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LocLandingBrandingAndControls(
                        name = name,
                        onNameChange = onNameChange,
                        role = role,
                        peers = peers,
                        showJoinSearching = showJoinSearching,
                        onStartHostedGame = onStartHostedGame,
                        onHostGame = onHostGame,
                        onJoinGame = onJoinGame,
                        onCancelJoinSearch = onCancelJoinSearch,
                        onStopHosting = onStopHosting,
                        onPlayOffline = onPlayOffline,
                        onExitApp = onExitApp,
                        tallEnough = tallEnough,
                        compact = compact,
                        btnPad = btnPad,
                        onTitleDoubleClick = { showDebugLog = !showDebugLog },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                    LanSessionExtras(
                        role = role,
                        onOpenScoreSheet = onOpenScoreSheet,
                        compact = compact
                    )
                }
                if (showDebugLog) {
                    LocLandingLogPanel(
                        log = log,
                        compact = compact,
                        modifier = Modifier
                            .weight(0.46f)
                            .fillMaxHeight()
                            .fillMaxWidth()
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = padH, vertical = padV),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LocLandingBrandingAndControls(
                    name = name,
                    onNameChange = onNameChange,
                    role = role,
                    peers = peers,
                    showJoinSearching = showJoinSearching,
                    onStartHostedGame = onStartHostedGame,
                    onHostGame = onHostGame,
                    onJoinGame = onJoinGame,
                    onCancelJoinSearch = onCancelJoinSearch,
                    onStopHosting = onStopHosting,
                    onPlayOffline = onPlayOffline,
                    onExitApp = onExitApp,
                    tallEnough = tallEnough,
                    compact = compact,
                    btnPad = btnPad,
                    onTitleDoubleClick = { showDebugLog = !showDebugLog },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                LanSessionExtras(
                    role = role,
                    onOpenScoreSheet = onOpenScoreSheet,
                    compact = compact
                )
                if (showDebugLog) {
                    Spacer(Modifier.height(8.dp))
                    LocLandingLogPanel(
                        log = log,
                        compact = compact,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocLandingBrandingAndControls(
    name: String,
    onNameChange: (String) -> Unit,
    role: LocXXViewModel.Role?,
    peers: List<UiPeer>,
    showJoinSearching: Boolean,
    onStartHostedGame: () -> Unit,
    onHostGame: () -> Unit,
    onJoinGame: () -> Unit,
    onCancelJoinSearch: () -> Unit,
    onStopHosting: () -> Unit,
    onPlayOffline: () -> Unit,
    onExitApp: () -> Unit,
    tallEnough: Boolean,
    compact: Boolean,
    btnPad: PaddingValues,
    onTitleDoubleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val btnColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    )
    val secondaryBtn = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary
    )
    val canHostOrStart =
        role == null ||
            role == LocXXViewModel.Role.Host ||
            role == LocXXViewModel.Role.SinglePlayer
    val hostPrimaryLabel =
        if (role == LocXXViewModel.Role.Host && peers.isNotEmpty()) "Start Game" else "Host a Game"
    val hostPrimaryAction =
        if (role == LocXXViewModel.Role.Host && peers.isNotEmpty()) onStartHostedGame else onHostGame
    val hostPrimaryEnabled =
        when (role) {
            LocXXViewModel.Role.Host -> peers.isNotEmpty()
            null, LocXXViewModel.Role.SinglePlayer -> true
            else -> false
        }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "LocXX",
            style = if (tallEnough) {
                MaterialTheme.typography.displaySmall
            } else {
                MaterialTheme.typography.headlineLarge
            },
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onDoubleClick = onTitleDoubleClick
            )
        )
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        Text(
            text = "Cross the line. Lock the row. Don’t look back.",
            style = if (compact) {
                MaterialTheme.typography.bodyLarge
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Display name") },
            placeholder = {
                Text(
                    "Name for online games",
                    style = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = MaterialTheme.colorScheme.primary
            )
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (role == LocXXViewModel.Role.Host && peers.isNotEmpty()) {
                Text(
                    text = peers.joinToString(separator = "\n") { peer ->
                        val label =
                            peer.displayName.trim().ifBlank { "Player ${peer.playerId + 1}" }
                        "Player: $label"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (compact) 4.dp else 6.dp)
                )
            }
            if (showJoinSearching) {
                Text(
                    text = "Looking for a game to join...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onCancelJoinSearch() }
                )
            }
            if (role == LocXXViewModel.Role.Host) {
                if (peers.isEmpty()) {
                    Text(
                        text = "Waiting for players to join the game...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (compact) 4.dp else 6.dp)
                    )
                }
                Text(
                    text = "Stop hosting",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onStopHosting() }
                )
            }
        }
        Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = hostPrimaryAction,
                    enabled = canHostOrStart && hostPrimaryEnabled,
                    modifier = Modifier.weight(1f),
                    colors = btnColors,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = btnPad
                ) {
                    Text(
                        hostPrimaryLabel,
                        maxLines = 2,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center
                    )
                }
                Button(
                    onClick = onJoinGame,
                    enabled = role == null || role == LocXXViewModel.Role.SinglePlayer,
                    modifier = Modifier.weight(1f),
                    colors = btnColors,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = btnPad
                ) {
                    Text("Join a Game", maxLines = 2, style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onPlayOffline,
                    enabled = role == null,
                    modifier = Modifier.weight(1f),
                    colors = btnColors,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = btnPad
                ) {
                    Text("Single Player Game", maxLines = 1)
                }
                Button(
                    onClick = onExitApp,
                    modifier = Modifier.weight(1f),
                    colors = secondaryBtn,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = btnPad
                ) {
                    Text("Quit", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun LocLandingLogPanel(
    log: List<String>,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val logScroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxHeight(),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Debug log",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(logScroll)
                    .padding(if (compact) 8.dp else 10.dp)
            ) {
                Text(
                    text = if (log.isEmpty()) "No log lines yet." else log.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LanSessionExtras(
    role: LocXXViewModel.Role?,
    onOpenScoreSheet: () -> Unit,
    compact: Boolean = false
) {
    if (role != LocXXViewModel.Role.SinglePlayer) return

    Spacer(Modifier.height(if (compact) 6.dp else 12.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = onOpenScoreSheet) { Text("Open score sheet") }
    }
}
