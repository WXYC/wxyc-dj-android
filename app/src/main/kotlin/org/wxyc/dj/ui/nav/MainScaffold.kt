package org.wxyc.dj.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlin.reflect.typeOf
import org.wxyc.dj.R
import org.wxyc.dj.api.AlbumSearchResult
import org.wxyc.dj.ui.bin.BinScreen
import org.wxyc.dj.ui.detail.AlbumDetailScreen
import org.wxyc.dj.ui.search.SearchScreen

/**
 * The signed-in app shell (issue #7): a two-tab bottom bar (search, bin)
 * over a single [NavHost], with [AlbumRoute] reachable from either tab and
 * coalescing on the back stack by id (see that type's KDoc).
 *
 * Every destination below is a placeholder body. This whole graph -- tabs,
 * every route, and the album-detail route with its id-only identity --
 * lands in this one issue rather than being split across #8-#11 so those
 * four PRs can run in parallel without four hands touching this same file:
 * each fills in exactly one placeholder screen (`ui/login/LoginScreen.kt`,
 * `ui/search/SearchScreen.kt`, `ui/detail/AlbumDetailScreen.kt`,
 * `ui/bin/BinScreen.kt`) and nothing here needs to change to accommodate it.
 *
 * Sign-out lives on the top bar here (issue #7's "reachable from the app
 * shell" requirement) rather than on either tab, so it survives whichever
 * tab is selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(onSignOut: () -> Unit) {
    val navController = rememberNavController()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onSignOut) {
                        Text(stringResource(R.string.sign_out))
                    }
                },
            )
        },
        bottomBar = { MainBottomBar(navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = SearchRoute,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable<SearchRoute> {
                SearchScreen(onAlbumSelected = { route -> navController.navigate(route) })
            }
            composable<BinRoute> {
                BinScreen(onAlbumSelected = { route -> navController.navigate(route) })
            }
            composable<AlbumRoute>(
                typeMap = mapOf(typeOf<AlbumSearchResult?>() to AlbumSearchResultNavType),
            ) { backStackEntry ->
                AlbumDetailScreen(route = backStackEntry.toRoute())
            }
        }
    }
}

@Composable
private fun MainBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    NavigationBar {
        NavigationBarItem(
            selected = currentDestination?.hierarchy?.any { it.hasRoute<SearchRoute>() } == true,
            onClick = { navController.navigateToTab(SearchRoute) },
            icon = { Text(stringResource(R.string.tab_search_icon)) },
            label = { Text(stringResource(R.string.tab_search_label)) },
        )
        NavigationBarItem(
            selected = currentDestination?.hierarchy?.any { it.hasRoute<BinRoute>() } == true,
            onClick = { navController.navigateToTab(BinRoute) },
            icon = { Text(stringResource(R.string.tab_bin_icon)) },
            label = { Text(stringResource(R.string.tab_bin_label)) },
        )
    }
}

/**
 * Standard bottom-tab navigation: reselecting the current tab is a no-op,
 * switching tabs saves/restores each tab's own back stack, and never piles
 * up duplicate copies of a tab's start destination.
 */
private fun NavHostController.navigateToTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
