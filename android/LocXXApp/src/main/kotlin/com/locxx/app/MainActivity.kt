package com.locxx.app

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.locxx.rules.DiceRoll
import com.locxx.rules.MatchState

class MainActivity : ComponentActivity() {

    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(permissions)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    LocXXScreen()
                }
            }
        }
    }
}

@Composable
fun LocXXScreen(vm: LocXXViewModel = viewModel()) {
    var name by remember { mutableStateOf("Player") }
    val log by vm.log.collectAsState()
    val peers by vm.peers.collectAsState()
    val match by vm.match.collectAsState()
    val lastRoll by vm.lastRoll.collectAsState()
    val role by vm.role.collectAsState()

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val singlePlayerLandscape = role == LocXXViewModel.Role.SinglePlayer && isLandscape

    if (singlePlayerLandscape) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                Modifier
                    .weight(0.34f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LocXXMenuBlock(
                    vm = vm,
                    name = name,
                    onNameChange = { name = it },
                    log = log,
                    peers = peers,
                    match = match,
                    lastRoll = lastRoll,
                    role = role,
                    includeSinglePlayerSection = false
                )
            }
            SinglePlayerSection(
                vm = vm,
                modifier = Modifier
                    .weight(0.66f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
            )
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LocXXMenuBlock(
                vm = vm,
                name = name,
                onNameChange = { name = it },
                log = log,
                peers = peers,
                match = match,
                lastRoll = lastRoll,
                role = role,
                includeSinglePlayerSection = true
            )
        }
    }
}

@Composable
private fun LocXXMenuBlock(
    vm: LocXXViewModel,
    name: String,
    onNameChange: (String) -> Unit,
    log: List<String>,
    peers: List<UiPeer>,
    match: MatchState?,
    lastRoll: DiceRoll?,
    role: LocXXViewModel.Role?,
    includeSinglePlayerSection: Boolean
) {
    Text("LocXX", style = MaterialTheme.typography.headlineMedium)
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Display name") },
        modifier = Modifier.fillMaxWidth()
    )
    Button(onClick = { vm.startHost(name) }) {
        Text("Host game")
    }
    Button(onClick = { vm.startClient(name) }) {
        Text("Join (scan)")
    }
    if (role == LocXXViewModel.Role.Client) {
        val bleScanCandidates by vm.bleScanCandidates.collectAsState()
        Text(
            "Nearby devices — tap your host if auto-connect fails",
            style = MaterialTheme.typography.labelMedium
        )
        bleScanCandidates.forEach { c ->
            TextButton(onClick = { vm.connectToBleDevice(c.address) }) {
                Text("${c.name ?: "Unknown"}  ${c.address}  RSSI ${c.rssi}")
            }
        }
    }
    Button(onClick = { vm.stopAll() }) {
        Text("Stop")
    }
    Button(onClick = { vm.startSinglePlayer() }) {
        Text("Single player")
    }
    if (role == LocXXViewModel.Role.Host) {
        Button(onClick = { vm.rollDice() }) {
            Text("Roll dice (host)")
        }
    }
    if (includeSinglePlayerSection && role == LocXXViewModel.Role.SinglePlayer) {
        SinglePlayerSection(vm)
    }
    if (role != LocXXViewModel.Role.SinglePlayer) {
        lastRoll?.let { r ->
            Spacer(Modifier.height(8.dp))
            Text("Last roll: W1=${r.white1} W2=${r.white2} R=${r.red} Y=${r.yellow} G=${r.green} B=${r.blue}  sum=${r.whiteSum()}")
        }
        match?.let { m ->
            Text("Players: ${m.playerCount}  active=${m.activePlayerIndex}  locks=${m.globallyLockedRows.size}")
        }
    }
    Text("Peers: ${peers.joinToString { "${it.displayName}#${it.playerId}" }}")
    Spacer(Modifier.height(8.dp))
    Text("Log", style = MaterialTheme.typography.titleMedium)
    Text(log.joinToString("\n"), modifier = Modifier.fillMaxWidth())
}
