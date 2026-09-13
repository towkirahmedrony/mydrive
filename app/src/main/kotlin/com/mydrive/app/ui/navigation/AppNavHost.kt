package com.mydrive.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.ui.album.AlbumDetailScreen
import com.mydrive.app.ui.album.AlbumDetailViewModel
import com.mydrive.app.ui.favorites.FavoritesScreen
import com.mydrive.app.ui.favorites.FavoritesViewModel
import com.mydrive.app.ui.gallery.GalleryScreen
import com.mydrive.app.ui.gallery.GalleryViewModel
import com.mydrive.app.ui.media.MediaDetailsScreen
import com.mydrive.app.ui.media.MediaDetailsViewModel
import com.mydrive.app.ui.media.MediaViewerScreen
import com.mydrive.app.ui.media.MediaViewerViewModel
import com.mydrive.app.ui.settings.SettingsScreen
import com.mydrive.app.ui.settings.SettingsViewModel
import com.mydrive.app.ui.settings.TelegramSettingsScreen
import com.mydrive.app.ui.sync.SyncScreen
import com.mydrive.app.ui.sync.SyncViewModel
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Graphite
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Mist
import com.mydrive.app.ui.theme.Radius
import com.mydrive.app.ui.theme.Stroke

private data class TabItem(
    val destination: AppDestination,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val tabs = listOf(
    TabItem(AppDestination.Albums, "Albums", Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary),
    TabItem(AppDestination.Favorites, "Favorites", Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder),
    TabItem(AppDestination.Sync, "Sync", Icons.Filled.Sync, Icons.Outlined.Sync),
    TabItem(AppDestination.Settings, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
)

@Composable
fun AppNavHost(repository: MediaRepository) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val showBottomBar = currentDestination?.route in bottomDestinations.map { it.route }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            if (showBottomBar) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .clip(RoundedCornerShape(Radius.xl))
                        .background(Graphite)
                        .border(1.dp, Stroke, RoundedCornerShape(Radius.xl))
                ) {
                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp
                    ) {
                        tabs.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.destination.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Copper,
                                    selectedTextColor = Copper,
                                    unselectedIconColor = Mist,
                                    unselectedTextColor = Mist,
                                    indicatorColor = Copper.copy(alpha = 0.14f)
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Albums.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(AppDestination.Albums.route) {
                val vm: GalleryViewModel = viewModel(factory = GalleryViewModel.factory(repository))
                GalleryScreen(
                    viewModel = vm,
                    onMediaClick = { id -> navController.navigate(AppDestination.MediaViewer.create(id)) },
                    onAlbumClick = { id -> navController.navigate(AppDestination.AlbumDetail.create(id)) }
                )
            }
            composable(AppDestination.Favorites.route) {
                val vm: FavoritesViewModel = viewModel(factory = FavoritesViewModel.factory(repository))
                FavoritesScreen(
                    viewModel = vm,
                    onMediaClick = { id -> navController.navigate(AppDestination.MediaViewer.create(id)) }
                )
            }
            composable(AppDestination.Sync.route) {
                val vm: SyncViewModel = viewModel(factory = SyncViewModel.factory(repository))
                SyncScreen(
                    viewModel = vm,
                    onMediaClick = { id -> navController.navigate(AppDestination.MediaViewer.create(id)) }
                )
            }
            composable(AppDestination.Settings.route) {
                val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(repository))
                SettingsScreen(
                    viewModel = vm,
                    onOpenTelegram = { navController.navigate(AppDestination.TelegramSettings.route) }
                )
            }
            composable(
                route = AppDestination.MediaViewer.route,
                arguments = listOf(navArgument("mediaId") { type = NavType.StringType })
            ) { entry ->
                val mediaId = entry.arguments?.getString("mediaId").orEmpty()
                val vm: MediaViewerViewModel = viewModel(
                    factory = MediaViewerViewModel.factory(repository, mediaId)
                )
                MediaViewerScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpenDetails = { id -> navController.navigate(AppDestination.MediaDetails.create(id)) }
                )
            }
            composable(
                route = AppDestination.MediaDetails.route,
                arguments = listOf(navArgument("mediaId") { type = NavType.StringType })
            ) { entry ->
                val mediaId = entry.arguments?.getString("mediaId").orEmpty()
                val vm: MediaDetailsViewModel = viewModel(
                    factory = MediaDetailsViewModel.factory(repository, mediaId)
                )
                MediaDetailsScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }
            composable(
                route = AppDestination.AlbumDetail.route,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType })
            ) { entry ->
                val albumId = android.net.Uri.decode(entry.arguments?.getString("albumId").orEmpty())
                val vm: AlbumDetailViewModel = viewModel(
                    factory = AlbumDetailViewModel.factory(repository, albumId)
                )
                AlbumDetailScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onMediaClick = { id -> navController.navigate(AppDestination.MediaViewer.create(id)) }
                )
            }
            composable(AppDestination.TelegramSettings.route) {
                val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(repository))
                TelegramSettingsScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }
        }
    }
}
