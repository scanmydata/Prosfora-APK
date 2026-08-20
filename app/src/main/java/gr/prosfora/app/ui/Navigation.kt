package gr.prosfora.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import gr.prosfora.app.ui.offers.EmailComposeScreen
import gr.prosfora.app.ui.offers.OfferDetailScreen
import gr.prosfora.app.ui.offers.OffersListScreen
import gr.prosfora.app.ui.offers.OffersViewModel
import gr.prosfora.app.ui.settings.SettingsScreen
import gr.prosfora.app.ui.settings.TemplateScreen

private const val ROUTE_LIST = "offers"
private const val ROUTE_DETAIL = "offer"
private const val ROUTE_EMAIL = "offer/email"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_TEMPLATE = "settings/template"

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
        composable(ROUTE_DETAIL) {
            OfferDetailScreen(
                viewModel = viewModel,
                onBack = {
                    viewModel.select(null)
                    navController.popBackStack()
                },
                onComposeEmail = { navController.navigate(ROUTE_EMAIL) },
            )
        }
        composable(ROUTE_EMAIL) {
            EmailComposeScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenTemplate = { navController.navigate(ROUTE_TEMPLATE) },
            )
        }
        composable(ROUTE_TEMPLATE) {
            TemplateScreen(onBack = { navController.popBackStack() })
        }
    }
}
