package com.example.tidewidget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tidewidget.areas.AreasScreen
import com.example.tidewidget.map.StationSheet
import com.example.tidewidget.map.TideMapScreen
import com.example.tidewidget.map.TideMapViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                TidesApp()
            }
        }
    }
}

private val locationPermissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TidesApp(vm: TideMapViewModel = viewModel()) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var hasLocation by remember {
        mutableStateOf(locationPermissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocation = result.values.any { it }
        if (hasLocation) vm.locateUser()
    }
    // Center on the user at launch: ask once if needed, otherwise locate right away.
    LaunchedEffect(Unit) { if (hasLocation) vm.locateUser() else askLocation.launch(locationPermissions) }

    val selected by vm.selected.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Place, null) }, label = { Text("Map") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Areas") })
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> TideMapScreen(vm, hasLocation = hasLocation, onLocate = {
                    if (hasLocation) vm.locateUser() else askLocation.launch(locationPermissions)
                })
                else -> AreasScreen(vm, onShowOnMap = { tab = 0 })
            }
        }
    }

    selected?.let { s ->
        ModalBottomSheet(onDismissRequest = { vm.selected.value = null }) {
            StationSheet(vm, s, onUseForWidget = { vm.useForWidget(s) { vm.selected.value = null } })
        }
    }
}
