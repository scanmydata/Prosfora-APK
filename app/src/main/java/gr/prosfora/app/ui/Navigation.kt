package gr.prosfora.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import gr.prosfora.app.ui.offers.OfferDetailScreen
import gr.prosfora.app.ui.offers.OffersListScreen
import gr.prosfora.app.ui.offers.OffersViewModel
import gr.prosfora.app.ui.settings.SettingsScreen

private const val ROUTE_LIST = "offers"
private const val ROUTE_DETAIL = "offer"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun ProsforaNavHost() {
    val navController = rememberNavController()
    val viewModel: OffersViewModel = viewModel()

    NavHost(navController = navController, startDestination = ROUTE_LIST) {
        composable(ROUTE_LIST) {
            OffersListScreen(
                viewModel = viewModel,
                onOpenOffer = { id ->
                    viewModel.select(id)
                    navController.navigate(ROUTE_DETAIL)
                },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_DETAIL) {
            OfferDetailScreen(
                viewModel = viewModel,
                onBack = {
                    viewModel.select(null)
                    navController.popBackStack()
                },
            )
        }
    }
}
