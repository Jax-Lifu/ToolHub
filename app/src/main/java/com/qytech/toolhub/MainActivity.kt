package com.qytech.toolhub

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qytech.toolhub.data.AotRepository
import com.qytech.toolhub.data.AppLanguageManager
import com.qytech.toolhub.data.WebReportServer
import com.qytech.toolhub.ui.theme.ToolHubTheme
import kotlinx.coroutines.launch

sealed class Screen(
    val route: String,
    @param:StringRes val labelRes: Int,
    @param:StringRes val titleRes: Int,
    val icon: ImageVector
) {
    data object Home : Screen("home", R.string.nav_home, R.string.title_home, Icons.Default.Home)
    data object Otg : Screen("otg", R.string.nav_otg, R.string.title_otg, Icons.Default.Usb)
    data object Perf :
        Screen("perf", R.string.nav_perf, R.string.title_perf, Icons.AutoMirrored.Filled.ShowChart)

    data object Thermal :
        Screen("thermal", R.string.nav_thermal, R.string.title_thermal, Icons.Default.Thermostat)

    data object Audio :
        Screen("audio", R.string.nav_audio, R.string.title_audio, Icons.Default.GraphicEq)

    data object Tools :
        Screen("tools", R.string.nav_tools, R.string.tools_header_title, Icons.Default.Build)

    data object Device :
        Screen("device", R.string.nav_device, R.string.title_device, Icons.Default.Info)
}

private val TOP_DESTINATIONS = listOf(
    Screen.Home,
    Screen.Otg,
    Screen.Perf,
    Screen.Thermal,
    Screen.Audio,
    Screen.Tools,
    Screen.Device,
)

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebReportServer.start(applicationContext)
        AotRepository.ensureSelfAotOptimized(applicationContext)
        enableEdgeToEdge()
        setContent {
            ToolHubTheme {
                MainApp()
            }
        }
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentScreen = TOP_DESTINATIONS.firstOrNull { dest ->
        currentDestination?.hierarchy?.any { it.route == dest.route } == true
    } ?: Screen.Home

    val otgViewModel: OtgModeViewModel = viewModel()
    val deviceInfoViewModel: DeviceInfoViewModel = viewModel()
    val hardwareViewModel: HardwareViewModel = viewModel()
    val monitorViewModel: MonitorViewModel = viewModel()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, monitorViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> monitorViewModel.onLifecycleForeground()
                Lifecycle.Event.ON_STOP -> monitorViewModel.onLifecycleBackground()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        otgViewModel.refreshAll()
        deviceInfoViewModel.refresh()
        hardwareViewModel.refresh()
    }

    LaunchedEffect(otgViewModel) {
        otgViewModel.events.collect { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(hardwareViewModel) {
        hardwareViewModel.events.collect { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = this@BoxWithConstraints.maxWidth >= 720.dp

        if (isWide) {
            Scaffold(
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                containerColor = MaterialTheme.colorScheme.background,
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surface,
                        header = {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                modifier = Modifier
                                    .padding(top = 8.dp, bottom = 6.dp)
                                    .size(44.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "ToolHub",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        },
                    ) {
                        Column(
                            modifier = Modifier.fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Spacer(modifier = Modifier.size(4.dp))
                                TOP_DESTINATIONS.forEach { screen ->
                                    val selected = currentScreen == screen
                                    val label = stringResource(screen.labelRes)
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = {
                                            navigateToTopLevel(
                                                navController,
                                                screen.route
                                            )
                                        },
                                        icon = { Icon(screen.icon, contentDescription = label) },
                                        label = {
                                            Text(
                                                label,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1
                                            )
                                        },
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                            ) {
                                Text(
                                    text = if (Process.myUid() == Process.SYSTEM_UID) "SYS" else "APP",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    VerticalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        thickness = 1.dp,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        ToolHubNavHost(
                            navController = navController,
                            otgViewModel = otgViewModel,
                            deviceInfoViewModel = deviceInfoViewModel,
                            hardwareViewModel = hardwareViewModel,
                            monitorViewModel = monitorViewModel,
                            onShowMessage = showMessage,
                        )
                    }
                }
            }
        } else {
            Scaffold(
                topBar = {
                    Column {
                        TopAppBar(
                            title = {
                                Text(
                                    text = stringResource(currentScreen.titleRes),
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            thickness = 0.8.dp,
                        )
                    }
                },
                bottomBar = {
                    Column {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            thickness = 0.8.dp,
                        )
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            TOP_DESTINATIONS.forEach { screen ->
                                val selected = currentScreen == screen
                                val label = stringResource(screen.labelRes)
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTopLevel(navController, screen.route) },
                                    icon = { Icon(screen.icon, contentDescription = label) },
                                    label = {
                                        Text(
                                            text = label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                        }
                    }
                },
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                containerColor = MaterialTheme.colorScheme.background,
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    ToolHubNavHost(
                        navController = navController,
                        otgViewModel = otgViewModel,
                        deviceInfoViewModel = deviceInfoViewModel,
                        hardwareViewModel = hardwareViewModel,
                        monitorViewModel = monitorViewModel,
                        onShowMessage = showMessage,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolHubNavHost(
    navController: NavHostController,
    otgViewModel: OtgModeViewModel,
    deviceInfoViewModel: DeviceInfoViewModel,
    hardwareViewModel: HardwareViewModel,
    monitorViewModel: MonitorViewModel,
    onShowMessage: (String) -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        enterTransition = { fadeIn(animationSpec = tween(110)) },
        exitTransition = { fadeOut(animationSpec = tween(90)) },
        popEnterTransition = { fadeIn(animationSpec = tween(110)) },
        popExitTransition = { fadeOut(animationSpec = tween(90)) },
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                otgViewModel = otgViewModel,
                deviceInfoViewModel = deviceInfoViewModel,
                hardwareViewModel = hardwareViewModel,
                monitorViewModel = monitorViewModel,
                onNavigateToOtg = { navigateToTopLevel(navController, Screen.Otg.route) },
                onNavigateToPerf = { navigateToTopLevel(navController, Screen.Perf.route) },
                onNavigateToThermal = { navigateToTopLevel(navController, Screen.Thermal.route) },
                onNavigateToAudio = { navigateToTopLevel(navController, Screen.Audio.route) },
                onNavigateToDeviceInfo = { navigateToTopLevel(navController, Screen.Device.route) },
                onShowMessage = onShowMessage,
            )
        }
        composable(Screen.Otg.route) {
            OtgModeScreen(
                viewModel = otgViewModel,
                deviceInfoViewModel = deviceInfoViewModel,
                onShowMessage = onShowMessage,
            )
        }
        composable(Screen.Perf.route) {
            PerfScreen(viewModel = monitorViewModel)
        }
        composable(Screen.Thermal.route) {
            ThermalScreen(
                monitorViewModel = monitorViewModel,
                hardwareViewModel = hardwareViewModel,
            )
        }
        composable(Screen.Audio.route) {
            AudioScreen(
                viewModel = hardwareViewModel,
                onShowMessage = onShowMessage,
            )
        }
        composable(Screen.Tools.route) {
            ToolsScreen(
                deviceInfoViewModel = deviceInfoViewModel,
                otgViewModel = otgViewModel,
                hardwareViewModel = hardwareViewModel,
                onShowMessage = onShowMessage,
            )
        }
        composable(Screen.Device.route) {
            DeviceInfoScreen(
                viewModel = deviceInfoViewModel,
                otgViewModel = otgViewModel,
                hardwareViewModel = hardwareViewModel,
                onShowMessage = onShowMessage,
            )
        }
    }
}

private fun navigateToTopLevel(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
