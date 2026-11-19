package com.wearmux.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wearmux.android.ui.DeviceDetailScreen
import com.wearmux.android.ui.DeviceId
import com.wearmux.android.ui.DeviceRef
import com.wearmux.android.ui.DevLabScreen
import com.wearmux.android.ui.EstadoScreen
import com.wearmux.android.ui.HomeScreen
import com.wearmux.android.ui.SettingsScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_DEVICE = "device/{deviceId}/{instanceId}"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DEV_LAB = "dev_lab"
private const val ROUTE_STATUS = "status/{deviceId}/{instanceId}"
private const val STATUS_ALL_DEVICES = "all"

/**
 * Rota de um dispositivo concreto.
 */
fun deviceRoute(deviceRef: DeviceRef) = "device/${deviceRef.id.name}/${deviceRef.routeInstance}"

private fun statusRoute(deviceRef: DeviceRef?) =
    "status/${deviceRef?.id?.name ?: STATUS_ALL_DEVICES}/${deviceRef?.routeInstance ?: DeviceRef.NO_INSTANCE}"

/**
 * `popBackStack()` isn't re-entrant-safe: two calls fired from the same
 * frame (e.g. a fast double-tap on a back button, or hitting both the
 * back arrow and a "Manage devices"-style shortcut that also calls
 * onBack) can each pop one entry before Compose has a chance to recompose
 * and remove the button, popping one screen too many. When that empties
 * the back stack below the graph's start destination, NavHost is left
 * with no current destination to render — a black screen, with no crash.
 * Guard by checking there's still a previous entry to go back to.
 */
private fun NavController.safePopBackStack() {
    if (previousBackStackEntry != null) popBackStack()
}

/**
 * The two root destinations a Developer-Mode bottom bar switches between.
 * Every other screen (device detail, settings, status) is reached by
 * pushing on top of whichever root is currently showing, same as before.
 */
private val bottomBarRoutes = listOf(ROUTE_HOME, ROUTE_DEV_LAB)

@Composable
fun AppNavigation(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val isDeveloperMode by viewModel.isDeveloperMode.collectAsStateWithLifecycle()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Constants.backgroundColor)
            .safeDrawingPadding(),
        containerColor = Constants.backgroundColor,
        bottomBar = {
            // The bottom bar only exists to switch between Home and Dev Lab,
            // so it only makes sense (and only shows) once Developer Mode is
            // on and the current screen is one of those two roots.
            if (isDeveloperMode && currentRoute in bottomBarRoutes) {
                NavigationBar(containerColor = Constants.cardBackground) {
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_HOME,
                        onClick = {
                            navController.navigate(ROUTE_HOME) {
                                popUpTo(ROUTE_HOME) { inclusive = true }
                            }
                        },
                        icon = { Icon(Icons.Filled.Link, contentDescription = "Home") },
                        label = { Text("Home") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_DEV_LAB,
                        onClick = {
                            navController.navigate(ROUTE_DEV_LAB) {
                                popUpTo(ROUTE_HOME) { inclusive = false }
                            }
                        },
                        icon = { Icon(Icons.Filled.BugReport, contentDescription = "Dev Lab") },
                        label = { Text("Dev Lab") },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_HOME,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            composable(ROUTE_HOME) {
                HomeScreen(
                    viewModel = viewModel,
                    onOpenDevice = { deviceId -> navController.navigate(deviceRoute(deviceId)) },
                    onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                )
            }
            composable(
                route = ROUTE_DEVICE,
                arguments = listOf(
                    navArgument("deviceId") { type = NavType.StringType },
                    navArgument("instanceId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val deviceRef = deviceRefFrom(
                    backStackEntry.arguments?.getString("deviceId"),
                    backStackEntry.arguments?.getString("instanceId"),
                )
                DeviceDetailScreen(
                    viewModel = viewModel,
                    deviceRef = deviceRef,
                    onBack = { navController.safePopBackStack() },
                    onOpenStatus = { forDevice -> navController.navigate(statusRoute(forDevice)) },
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    onOpenDiagnostics = { navController.navigate(ROUTE_DEV_LAB) },
                    onBack = { navController.safePopBackStack() },
                )
            }
            composable(ROUTE_DEV_LAB) {
                DevLabScreen(viewModel)
            }
            composable(
                route = ROUTE_STATUS,
                arguments = listOf(
                    navArgument("deviceId") { type = NavType.StringType },
                    navArgument("instanceId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val filterDevice = filterDeviceFrom(
                    backStackEntry.arguments?.getString("deviceId"),
                    backStackEntry.arguments?.getString("instanceId"),
                )
                EstadoScreen(
                    viewModel,
                    onBack = { navController.safePopBackStack() },
                    filterDevice = filterDevice,
                )
            }
        }
    }
}

/**
 * Reconstrói um [DeviceRef] a partir dos argumentos de rota.
 *
 */

private fun deviceRefFrom(deviceId: String?, instanceId: String?): DeviceRef {
    val kind = deviceId?.takeIf { it.isNotBlank() && it != STATUS_ALL_DEVICES }?.let { name ->
        runCatching { DeviceId.valueOf(name) }.getOrNull()
    } ?: DeviceId.PHONE
    return DeviceRef(kind, decodeInstance(instanceId))
}

/** Igual a [deviceRefFrom], mas `null` para o ecrã de status agregador ("all"). */
private fun filterDeviceFrom(deviceId: String?, instanceId: String?): DeviceRef? {
    if (deviceId.isNullOrBlank() || deviceId == STATUS_ALL_DEVICES) return null
    val kind = runCatching { DeviceId.valueOf(deviceId) }.getOrNull() ?: return null
    return DeviceRef(kind, decodeInstance(instanceId))
}

private fun decodeInstance(instanceId: String?): String? =
    instanceId
        ?.takeIf { it.isNotBlank() && it != DeviceRef.NO_INSTANCE }
        ?.let { DeviceRef.percentDecode(it) }
