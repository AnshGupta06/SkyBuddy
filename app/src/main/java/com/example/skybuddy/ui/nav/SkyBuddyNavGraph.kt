package com.example.skybuddy.ui.nav

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.skybuddy.ui.chat.ChatScreen
import com.example.skybuddy.ui.home.HomeScreen
import com.example.skybuddy.ui.journey.JourneyPhase
import com.example.skybuddy.ui.journey.JourneySelectionScreen
import com.example.skybuddy.ui.map.IndoorMapScreen
import com.example.skybuddy.ui.modelload.ModelLoadScreen
import com.example.skybuddy.ui.onboarding.OnboardingScreen
import com.example.skybuddy.ui.settings.SettingsScreen

@Composable
fun SkyBuddyNavGraph(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(navController = navController, startDestination = startDestination) {
        
        // ── Settings ──
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        // ── 1. Onboarding ──
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onModelReady = {
                    navController.navigate(Routes.MODEL_LOAD) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
                onSettingsClicked = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        // ── 2. Model Load ──
        composable(Routes.MODEL_LOAD) {
            ModelLoadScreen(
                onReady = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.MODEL_LOAD) { inclusive = true }
                    }
                }
            )
        }

        // ── 3. Home Screen (flight list) ──
        composable(Routes.HOME) {
            HomeScreen(
                onFlightTapped = { flightNumber ->
                    navController.navigate(Routes.journeySelect(flightNumber))
                },
                onSettingsClicked = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        // ── 4. Journey Selection ("Where are you?") ──
        composable(
            route = Routes.JOURNEY_SELECT_PATTERN,
            arguments = listOf(navArgument("flightNumber") { type = NavType.StringType })
        ) { entry ->
            val flightNumber = entry.arguments?.getString("flightNumber") ?: ""
            JourneySelectionScreen(
                flightNumber = flightNumber,
                onPhaseSelected = { phase ->
                    when (phase) {
                        JourneyPhase.HOME -> {
                            navController.navigate(Routes.chat(flightNumber))
                        }
                        else -> {
                            // Airport phases → Indoor Map
                            navController.navigate(Routes.indoorMap(flightNumber))
                        }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }


        // ── 6. Indoor Map (airport phases) ──
        composable(
            route = Routes.INDOOR_MAP_PATTERN,
            arguments = listOf(navArgument("flightNumber") { type = NavType.StringType })
        ) { entry ->
            val flightNumber = entry.arguments?.getString("flightNumber") ?: ""
            IndoorMapScreen(
                flightNumber = flightNumber,
                onChatClicked = {
                    navController.navigate(Routes.chat(flightNumber))
                },
                onHelpClicked = {
                    navController.navigate(Routes.chat(flightNumber))
                }
            )
        }

        // ── 7. Chat ──
        composable(
            route = Routes.CHAT_PATTERN,
            arguments = listOf(navArgument("flightNumber") { type = NavType.StringType })
        ) { entry ->
            val flightNumber = entry.arguments?.getString("flightNumber") ?: ""
            ChatScreen(
                flightNumber = flightNumber,
                onBack = { navController.popBackStack() },
                onSettingsClicked = { navController.navigate(Routes.SETTINGS) },
                onPhaseAdvanced = { nextPhase ->
                    if (nextPhase == JourneyPhase.AIRPORT_ENTRANCE) {
                        navController.navigate(Routes.indoorMap(flightNumber)) {
                            popUpTo(Routes.chat(flightNumber)) { inclusive = true }
                        }
                    } else {
                        navController.popBackStack()
                    }
                }
            )
        }
    }
}
