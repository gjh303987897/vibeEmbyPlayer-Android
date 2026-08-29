package com.vibeplayer.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vibeplayer.app.R
import com.vibeplayer.app.ui.details.MediaDetailsScreen
import com.vibeplayer.app.ui.home.HomeScreen
import com.vibeplayer.app.ui.library.LibraryScreen
import com.vibeplayer.app.ui.player.PlayerScreen
import com.vibeplayer.app.ui.search.SearchScreen
import com.vibeplayer.app.ui.services.ServicesScreen
import com.vibeplayer.app.ui.history.HistoryScreen
import com.vibeplayer.app.ui.settings.SettingsScreen
import com.vibeplayer.app.ui.transfer.TransfersScreen
import com.vibeplayer.app.ui.webdav.WebDavBrowseScreen
import com.vibeplayer.app.ui.webdav.WebDavPlayerScreen
import com.vibeplayer.app.ui.link.LinkHomeScreen
import com.vibeplayer.app.ui.link.LinkPlayerScreen
import com.vibeplayer.app.ui.iptv.IptvHomeScreen
import com.vibeplayer.app.ui.iptv.IptvPlayerScreen
import com.vibeplayer.app.ui.local.LocalBrowseScreen
import com.vibeplayer.app.ui.local.LocalPlayerScreen
import com.vibeplayer.app.ui.tssl.TsslManagerScreen

enum class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
) {
    Services("services", R.string.nav_services, Icons.Outlined.Home),
    Transfers("transfers", R.string.nav_transfers, Icons.Outlined.Sync),
    History("history", R.string.nav_history, Icons.Outlined.History),
    Settings("settings", R.string.nav_settings, Icons.Outlined.Settings),
}

/**
 * Root navigation host. The Material 3 NavigationBar is only shown on the
 * top-level destinations; the media browsing and player screens run full-screen.
 */
@Composable
fun VibePlayerNavHost(
    pageTransitions: Boolean = true,
    navController: NavHostController = rememberNavController()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isTopLevel = TopLevelDestination.entries.any { it.route == currentDestination?.route }

    Scaffold(
        // Child screens own their top app bars and therefore their status-bar
        // insets. Passing the default safe-drawing inset here as well would
        // apply the top inset twice on edge-to-edge devices (especially
        // cutout/notched screens), leaving a large blank strip above content.
        // The NavigationBar still contributes its measured height below.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (isTopLevel) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                // Already on this tab: do nothing.
                                if (selected) return@NavigationBarItem
                                if (destination == TopLevelDestination.Services) {
                                    // Services is the navigation root (start
                                    // destination) and stays at the bottom of the
                                    // back stack, so navigating to it with
                                    // launchSingleTop is a no-op in Navigation
                                    // Compose. Return to the root by popping the
                                    // back stack instead.
                                    navController.popBackStack(
                                        TopLevelDestination.Services.route,
                                        inclusive = false
                                    )
                                } else {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Services.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                if (pageTransitions) {
                    fadeIn(animationSpec = tween(240)) +
                        scaleIn(initialScale = 0.98f, animationSpec = tween(240))
                } else {
                    EnterTransition.None
                }
            },
            exitTransition = {
                if (pageTransitions) fadeOut(animationSpec = tween(200)) else ExitTransition.None
            },
            popEnterTransition = {
                if (pageTransitions) {
                    fadeIn(animationSpec = tween(240)) +
                        scaleIn(initialScale = 0.98f, animationSpec = tween(240))
                } else {
                    EnterTransition.None
                }
            },
            popExitTransition = {
                if (pageTransitions) {
                    fadeOut(animationSpec = tween(200)) +
                        scaleOut(targetScale = 0.99f, animationSpec = tween(200))
                } else {
                    ExitTransition.None
                }
            }
        ) {
            composable(TopLevelDestination.Services.route) {
                ServicesScreen(navController = navController)
            }
            composable(TopLevelDestination.Transfers.route) { TransfersScreen() }
            composable(TopLevelDestination.History.route) { HistoryScreen(navController = navController) }
            composable(TopLevelDestination.Settings.route) { SettingsScreen() }

            composable(
                route = Routes.HOME,
                arguments = listOf(navArgument("serverId") { type = NavType.StringType })
            ) {
                HomeScreen(navController = navController)
            }

            composable(
                route = Routes.LIBRARY,
                arguments = listOf(
                    navArgument("serverId") { type = NavType.StringType },
                    navArgument("libraryId") { type = NavType.StringType }
                )
            ) { entry ->
                entry.arguments?.getString("serverId")?.let { serverId ->
                    val libraryId = entry.arguments?.getString("libraryId").orEmpty()
                    LibraryScreen(serverId = serverId, libraryId = libraryId, navController = navController)
                }
            }

            composable(
                route = Routes.DETAILS,
                arguments = listOf(
                    navArgument("serverId") { type = NavType.StringType },
                    navArgument("itemId") { type = NavType.StringType }
                )
            ) { entry ->
                entry.arguments?.getString("serverId")?.let { serverId ->
                    val itemId = entry.arguments?.getString("itemId").orEmpty()
                    MediaDetailsScreen(serverId = serverId, itemId = itemId, navController = navController)
                }
            }

            composable(
                route = Routes.SEARCH,
                arguments = listOf(navArgument("serverId") { type = NavType.StringType })
            ) {
                SearchScreen(navController = navController)
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(
                    navArgument("serverId") { type = NavType.StringType },
                    navArgument("itemId") { type = NavType.StringType }
                )
            ) { entry ->
                entry.arguments?.getString("serverId")?.let { serverId ->
                    val itemId = entry.arguments?.getString("itemId").orEmpty()
                    PlayerScreen(serverId = serverId, itemId = itemId, navController = navController)
                }
            }

            composable(
                route = Routes.WEBDAV_BROWSE,
                arguments = listOf(navArgument("serverId") { type = NavType.StringType })
            ) { entry ->
                entry.arguments?.getString("serverId")?.let { serverId ->
                    WebDavBrowseScreen(serverId = serverId, navController = navController)
                }
            }

            composable(
                route = Routes.WEBDAV_PLAYER,
                arguments = listOf(
                    navArgument("serverId") { type = NavType.StringType },
                    navArgument("path") { type = NavType.StringType }
                )
            ) { entry ->
                entry.arguments?.getString("serverId")?.let { serverId ->
                    val path = entry.arguments?.getString("path").orEmpty()
                    WebDavPlayerScreen(serverId = serverId, encodedPath = path, navController = navController)
                }
            }

            composable(
                route = Routes.IPTV_HOME,
                arguments = listOf(navArgument("serverId") { type = NavType.StringType })
            ) { entry ->
                entry.arguments?.getString("serverId")?.let { serverId ->
                    IptvHomeScreen(serverId = serverId, navController = navController)
                }
            }

            composable(
                route = Routes.IPTV_PLAYER,
                arguments = listOf(
                    navArgument("serverId") { type = NavType.StringType },
                    navArgument("url") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType }
                )
            ) { entry ->
                entry.arguments?.getString("serverId")?.let { serverId ->
                    val url = entry.arguments?.getString("url").orEmpty()
                    val name = entry.arguments?.getString("name").orEmpty()
                    IptvPlayerScreen(serverId = serverId, encodedUrl = url, encodedName = name, navController = navController)
                }
            }

            composable(
                route = Routes.LINK_HOME,
                arguments = listOf(navArgument("serverId") { type = NavType.StringType })
            ) { entry ->
                entry.arguments?.getString("serverId")?.let { serverId ->
                    LinkHomeScreen(serverId = serverId, navController = navController)
                }
            }

            composable(
                route = Routes.LINK_PLAYER,
                arguments = listOf(navArgument("url") { type = NavType.StringType })
            ) { entry ->
                val url = entry.arguments?.getString("url").orEmpty()
                LinkPlayerScreen(encodedUrl = url, navController = navController)
            }

            composable(Routes.LOCAL_HOME) {
                LocalBrowseScreen(navController = navController)
            }

            composable(Routes.TSSL_HOME) {
                TsslManagerScreen(navController = navController)
            }

            composable(
                route = Routes.LOCAL_PLAYER,
                arguments = listOf(navArgument("url") { type = NavType.StringType })
            ) { entry ->
                val url = entry.arguments?.getString("url").orEmpty()
                LocalPlayerScreen(encodedUri = url, navController = navController)
            }
        }
    }
}
