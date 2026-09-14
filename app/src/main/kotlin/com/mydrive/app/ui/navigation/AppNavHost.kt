package com.mydrive.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.mydrive.app.data.local.GalleryTabStore
import com.mydrive.app.data.repository.AuthRepository
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.ui.album.AlbumDetailScreen
import com.mydrive.app.ui.album.AlbumDetailViewModel
import com.mydrive.app.ui.albums.AlbumsScreen
import com.mydrive.app.ui.albums.AlbumsViewModel
import com.mydrive.app.ui.gallery.GalleryScreen
import com.mydrive.app.ui.gallery.GalleryViewModel
import com.mydrive.app.ui.media.MediaViewerScreen
import com.mydrive.app.ui.media.MediaViewerViewModel
import com.mydrive.app.ui.settings.SettingsScreen
import com.mydrive.app.ui.settings.SettingsViewModel
import com.mydrive.app.ui.settings.TelegramSettingsScreen
import com.mydrive.app.ui.sync.SyncScreen
import com.mydrive.app.ui.sync.SyncViewModel
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Radius

private data class TabItem(
    val destination: AppDestination,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val tabs = listOf(
    TabItem(AppDestination.Photos, "Photos", Icons.Filled.Photo, Icons.Outlined.Photo),
    TabItem(AppDestination.Albums, "Albums", Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary),
    TabItem(AppDestination.Sync, "Sync", Icons.Filled.Sync, Icons.Outlined.Sync),
    TabItem(AppDestination.Settings, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
)

private val tabFade = tween<Float>(durationMillis = 160)

@Composable
fun AppNavHost(
    repository: MediaRepository,
    authRepository: AuthRepository,
    galleryTabStore: GalleryTabStore
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isViewer = currentDestination?.route?.startsWith("viewer") == true
    val showBottomBar = !isViewer && currentDestination?.route in bottomDestinations.map { it.route }
    val colors = MaterialTheme.colorScheme

    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            if (showBottomBar) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .clip(RoundedCornerShape(Radius.xl))
                        .background(colors.surfaceVariant)
                        .border(1.dp, colors.outlineVariant, RoundedCornerShape(Radius.xl))
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
                                    if (tab.destination in galleryDestinations) {
                                        galleryTabStore.saveRoute(tab.destination.route)
                                    }
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
                                    unselectedIconColor = colors.onSurfaceVariant,
                                    unselectedTextColor = colors.onSurfaceVariant,
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
            startDestination = galleryTabStore.startRoute(),
            modifier = Modifier
                .fillMaxSize()
                .then(if (isViewer) Modifier else Modifier.padding(innerPadding)),
            enterTransition = { fadeIn(tabFade) },
            exitTransition = { fadeOut(tabFade) },
            popEnterTransition = { fadeIn(tabFade) },
            popExitTransition = { fadeOut(tabFade) }
        ) {
            composable(AppDestination.Photos.route) {
                val vm: GalleryViewModel = viewModel(factory = GalleryViewModel.factory(repository))
                GalleryScreen(
                    viewModel = vm,
                    onMediaClick = { id ->
                        repository.beginViewerSession(vm.visibleItemIds())
                        navController.navigate(AppDestination.MediaViewer.create(id))
                    }
                )
            }
            composable(AppDestination.Albums.route) {
                val vm: AlbumsViewModel = viewModel(factory = AlbumsViewModel.factory(repository))
                AlbumsScreen(
                    viewModel = vm,
                    onAlbumClick = { id -> navController.navigate(AppDestination.AlbumDetail.create(id)) }
                )
            }
            composable(AppDestination.Sync.route) {
                val vm: SyncViewModel = viewModel(factory = SyncViewModel.factory(repository))
                SyncScreen(
                    viewModel = vm,
                    onMediaClick = { id ->
                        repository.beginViewerSession(vm.visibleItemIds())
                        navController.navigate(AppDestination.MediaViewer.create(id))
                    }
                )
            }
            composable(AppDestination.Settings.route) {
                val vm: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.factory(repository, authRepository)
                )
                SettingsScreen(
                    viewModel = vm,
                    onOpenTelegram = { navController.navigate(AppDestination.TelegramSettings.route) }
                )
            }
            composable(
                route = AppDestination.MediaViewer.route,
                arguments = listOf(
                    navArgument("mediaId") { type = NavType.StringType },
                    navArgument("albumId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { entry ->
                val mediaId = android.net.Uri.decode(entry.arguments?.getString("mediaId").orEmpty())
                val albumId = entry.arguments?.getString("albumId")
                    ?.let { android.net.Uri.decode(it) }
                    ?.takeIf { it.isNotBlank() }
                val vm: MediaViewerViewModel = viewModel(
                    factory = MediaViewerViewModel.factory(repository, mediaId, albumId)
                )
                MediaViewerScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() }
                )
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
                    onMediaClick = { id ->
                        repository.beginViewerSession(vm.visibleItemIds())
                        navController.navigate(AppDestination.MediaViewer.create(id, albumId))
                    }
                )
            }
            composable(AppDestination.TelegramSettings.route) {
                val vm: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.factory(repository, authRepository)
                )
                TelegramSettingsScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }
        }
    }
}
