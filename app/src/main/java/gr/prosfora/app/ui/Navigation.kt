package gr.prosfora.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavType
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import gr.prosfora.app.notify.Channel
import gr.prosfora.app.ui.offers.EmailComposeScreen
import gr.prosfora.app.ui.offers.MessageComposeScreen
import gr.prosfora.app.ui.offers.OfferDetailScreen
import gr.prosfora.app.ui.offers.OffersListScreen
import gr.prosfora.app.ui.offers.OffersViewModel
import gr.prosfora.app.ui.pdf.PdfArchiveScreen
import gr.prosfora.app.ui.settings.SettingsScreen
import gr.prosfora.app.ui.settings.TemplateScreen

private const val ROUTE_LIST = "offers"
private const val ROUTE_DETAIL = "offer"
private const val ROUTE_EMAIL = "offer/email"
private const val ROUTE_MESSAGE = "offer/message"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_ARCHIVE = "archive"
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
                onOpenArchive = { navController.navigate(ROUTE_ARCHIVE) },
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
                onComposeMessage = { channel ->
                    navController.navigate("$ROUTE_MESSAGE/${channel.name}")
                },
            )
        }
        composable(ROUTE_EMAIL) {
            EmailComposeScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "$ROUTE_MESSAGE/{channel}",
            arguments = listOf(navArgument("channel") { type = NavType.StringType }),
        ) { entry ->
            val channel = runCatching {
                Channel.valueOf(entry.arguments?.getString("channel").orEmpty())
            }.getOrDefault(Channel.SMS)
            MessageComposeScreen(
                viewModel = viewModel,
                channel = channel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_ARCHIVE) {
            PdfArchiveScreen(
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
