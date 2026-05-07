package com.charlesh.captionburn.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Scaffold
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.charlesh.captionburn.ui.ads.BottomBannerAd
import com.charlesh.captionburn.ui.ads.findActivity
import com.charlesh.captionburn.ui.ads.loadInterstitial
import com.charlesh.captionburn.ui.ads.showInterstitialThen
import com.charlesh.captionburn.ui.editor.EditorScreen
import com.charlesh.captionburn.ui.export.ExportScreen
import com.charlesh.captionburn.ui.home.HomeScreen
import com.charlesh.captionburn.ui.onboarding.OnboardingScreen
import com.charlesh.captionburn.ui.settings.SettingsScreen

@Composable
fun AppNavGraph(startDestination: Route = Route.Onboarding) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val activity = context.findActivity()
    var interstitialAd by remember { mutableStateOf<com.google.android.gms.ads.interstitial.InterstitialAd?>(null) }

    LaunchedEffect(Unit) {
        loadInterstitial(context) { loaded ->
            interstitialAd = loaded
        }
    }

    Scaffold(
        bottomBar = {
            BottomBannerAd(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars),
            )
        },
    ) { innerPadding ->
        NavHost(
            modifier = Modifier.padding(innerPadding),
            navController = nav,
            startDestination = startDestination,
            enterTransition = {
                slideInHorizontally(tween(320)) { it / 8 } +
                    fadeIn(tween(260)) +
                    scaleIn(animationSpec = tween(260), initialScale = 0.98f)
            },
            exitTransition = { fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.99f) },
            popEnterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.99f) },
            popExitTransition = {
                slideOutHorizontally(tween(280)) { it / 8 } +
                    fadeOut(tween(220))
            },
        ) {
            composable<Route.Onboarding> {
                OnboardingScreen(
                    viewModel = hiltViewModel(),
                    onContinue = {
                        nav.navigate(Route.Home) {
                            popUpTo(Route.Onboarding) { inclusive = true }
                        }
                    },
                )
            }
            composable<Route.Home> {
                HomeScreen(
                    viewModel = hiltViewModel(),
                    onOpenProject = { id -> nav.navigate(Route.Editor(id)) },
                    onOpenSettings = { nav.navigate(Route.Settings) },
                )
            }
            composable<Route.Editor> { entry ->
                val args = entry.toRoute<Route.Editor>()
                EditorScreen(
                    projectId = args.projectId,
                    viewModel = hiltViewModel(),
                    onExport = {
                        val projectId = args.projectId
                        val adToShow = interstitialAd
                        interstitialAd = null
                        showInterstitialThen(
                            activity = activity,
                            currentAd = adToShow,
                            onNavigate = { nav.navigate(Route.Export(projectId)) },
                            onReloadRequested = {
                                loadInterstitial(context) { loaded ->
                                    interstitialAd = loaded
                                }
                            },
                        )
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<Route.Export> { entry ->
                val args = entry.toRoute<Route.Export>()
                ExportScreen(
                    projectId = args.projectId,
                    viewModel = hiltViewModel(),
                    onDone = {
                        nav.popBackStack(Route.Home, inclusive = false)
                    },
                )
            }
            composable<Route.Settings> {
                SettingsScreen(
                    viewModel = hiltViewModel(),
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
