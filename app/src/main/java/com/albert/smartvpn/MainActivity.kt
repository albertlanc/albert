package com.albert.smartvpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.albert.smartvpn.ui.screens.ConfigsScreen
import com.albert.smartvpn.ui.screens.HomeScreen
import com.albert.smartvpn.ui.screens.LogsScreen
import com.albert.smartvpn.ui.screens.NetworkToolsScreen
import com.albert.smartvpn.ui.theme.SmartVpnTheme
import com.albert.smartvpn.ui.viewmodel.VpnViewModel
import com.albert.smartvpn.vpn.SmartVpnService

class MainActivity : ComponentActivity() {

    private val viewModel: VpnViewModel by viewModels()

    private val vpnRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            viewModel.addLog("VPN permission was denied by the user.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmartVpnTheme {
                var selectedTab by remember { mutableIntStateOf(0) }
                val isConnected by viewModel.isConnected.collectAsState()
                val profiles by viewModel.profiles.collectAsState()
                val activeProfile = profiles.firstOrNull { it.isSelected } ?: profiles.firstOrNull()

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                label = { Text("Home") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.List, contentDescription = null) },
                                label = { Text("Configs") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.Article, contentDescription = null) },
                                label = { Text("Logs") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Icon(Icons.Default.Build, contentDescription = null) },
                                label = { Text("Tools") }
                            )
                        }
                    }
                ) { innerPadding ->
                    when (selectedTab) {
                        0 -> HomeScreen(
                            isConnected = isConnected,
                            activeProfile = activeProfile,
                            onToggleConnection = { toggleVpn(isConnected) },
                            modifier = Modifier.padding(innerPadding)
                        )
                        1 -> ConfigsScreen(
                            profiles = profiles,
                            onSelectProfile = { viewModel.selectProfile(it) },
                            onDeleteProfile = { viewModel.deleteProfile(it) },
                            onImportClipboard = { viewModel.importFromClipboard(it) },
                            modifier = Modifier.padding(innerPadding)
                        )
                        2 -> LogsScreen(
                            logs = viewModel.logs.collectAsState().value,
                            modifier = Modifier.padding(innerPadding)
                        )
                        3 -> NetworkToolsScreen(
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    private fun toggleVpn(currentlyConnected: Boolean) {
        if (currentlyConnected) {
            val stopIntent = Intent(this, SmartVpnService::class.java).apply {
                action = SmartVpnService.ACTION_STOP
            }
            startService(stopIntent)
            viewModel.setConnectedState(false)
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnRequest.launch(intent)
            } else {
                startVpnService()
            }
        }
    }

    private fun startVpnService() {
        val startIntent = Intent(this, SmartVpnService::class.java).apply {
            action = SmartVpnService.ACTION_START
        }
        startService(startIntent)
        viewModel.setConnectedState(true)
    }
}
