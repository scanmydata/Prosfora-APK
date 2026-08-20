package gr.prosfora.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import gr.prosfora.app.ui.offers.OfferDetailScreen
import gr.prosfora.app.ui.offers.OffersListScreen
import gr.prosfora.app.ui.offers.OffersViewModel

private const val ROUTE_LIST = "offers"
private const val ROUTE_DETAIL = "offer"

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
            )
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
