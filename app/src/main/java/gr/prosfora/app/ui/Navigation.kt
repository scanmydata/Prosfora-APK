package gr.prosfora.app.ui

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import gr.prosfora.app.notify.Channel
import gr.prosfora.app.ui.debts.DebtNotificationFocusDialog
import gr.prosfora.app.ui.debts.DebtsScreen
import gr.prosfora.app.ui.jobs.JobsScreen
import gr.prosfora.app.ui.jobs.ReviewComposeScreen
import gr.prosfora.app.ui.offers.EmailComposeScreen
import gr.prosfora.app.ui.offers.MessageComposeScreen
import gr.prosfora.app.ui.offers.OfferDetailScreen
import gr.prosfora.app.ui.offers.OffersListScreen
import gr.prosfora.app.ui.offers.OffersViewModel
import gr.prosfora.app.ui.pdf.PdfArchiveScreen
import gr.prosfora.app.ui.settings.SettingsScreen
import gr.prosfora.app.ui.settings.TemplateEditorScreen
import gr.prosfora.app.ui.settings.TemplateScreen
import gr.prosfora.app.ui.stats.StatsScreen
import kotlinx.coroutines.launch

internal const val ROUTE_LIST = "offers"
internal const val ROUTE_SETTINGS = "settings"
internal const val ROUTE_ARCHIVE = "archive"
internal const val ROUTE_JOBS = "jobs"
internal const val ROUTE_STATS = "stats"
internal const val ROUTE_DEBTS = "debts"

private const val ROUTE_DETAIL = "offer"
private const val ROUTE_EMAIL = "offer/email"
private const val ROUTE_MESSAGE = "offer/message"
private const val ROUTE_REVIEW = "jobs/review"
private const val ROUTE_TEMPLATE = "settings/template"
private const val ROUTE_TEMPLATE_EDIT = "settings/template/edit"

@Composable
fun ProsforaNavHost(
    openDebtId: String? = null,
    openPendingInstallments: Boolean = false,
    onDebtNavigationConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val viewModel: OffersViewModel = viewModel()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val onTopLevel = TopDestination.entries.any { it.route == route }

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    EnsureGoogleAccess()

    LaunchedEffect(openDebtId, openPendingInstallments) {
        if (openPendingInstallments || !openDebtId.isNullOrBlank()) {
            navController.navigate(ROUTE_DEBTS) {
                popUpTo(ROUTE_STATS) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = onTopLevel || drawerState.isOpen,
        drawerContent = {
            AppDrawer(current = route) { destination ->
                scope.launch { drawerState.close() }
                if (destination.route != route) {
                    navController.navigate(destination.route) {
                        popUpTo(ROUTE_STATS) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        },
    ) {
        NavHost(navController = navController, startDestination = ROUTE_STATS) {
            composable(ROUTE_STATS) { StatsScreen(viewModel = viewModel, onMenu = openDrawer) }
            composable(ROUTE_LIST) {
                OffersListScreen(
                    viewModel = viewModel,
                    onMenu = openDrawer,
                    onOpenOffer = { id -> viewModel.select(id); navController.navigate(ROUTE_DETAIL) },
                )
            }
            composable(ROUTE_DETAIL) {
                OfferDetailScreen(
                    viewModel = viewModel,
                    onBack = { viewModel.select(null); navController.popBackStack() },
                    onComposeEmail = { navController.navigate(ROUTE_EMAIL) },
                    onComposeMessage = { channel -> navController.navigate("$ROUTE_MESSAGE/${channel.name}") },
                )
            }
            composable(ROUTE_EMAIL) { EmailComposeScreen(viewModel = viewModel, onBack = { navController.popBackStack() }) }
            composable(
                route = "$ROUTE_MESSAGE/{channel}",
                arguments = listOf(navArgument("channel") { type = NavType.StringType }),
            ) { entry ->
                val channel = runCatching { Channel.valueOf(entry.arguments?.getString("channel").orEmpty()) }
                    .getOrDefault(Channel.SMS)
                MessageComposeScreen(viewModel = viewModel, channel = channel, onBack = { navController.popBackStack() })
            }
            composable(ROUTE_ARCHIVE) { PdfArchiveScreen(viewModel = viewModel, onMenu = openDrawer) }
            composable(ROUTE_JOBS) {
                JobsScreen(viewModel = viewModel, onMenu = openDrawer, onRequestReview = { id -> navController.navigate("$ROUTE_REVIEW/$id") })
            }
            composable(
                route = "$ROUTE_REVIEW/{offerId}",
                arguments = listOf(navArgument("offerId") { type = NavType.StringType }),
            ) { entry ->
                ReviewComposeScreen(
                    viewModel = viewModel,
                    offerId = entry.arguments?.getString("offerId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_DEBTS) { DebtsScreen(onMenu = openDrawer) }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(onMenu = openDrawer, onOpenTemplate = { navController.navigate(ROUTE_TEMPLATE) })
            }
            composable(ROUTE_TEMPLATE) {
                TemplateScreen(onBack = { navController.popBackStack() }, onEditText = { navController.navigate(ROUTE_TEMPLATE_EDIT) })
            }
            composable(ROUTE_TEMPLATE_EDIT) { TemplateEditorScreen(onBack = { navController.popBackStack() }) }
        }

        when {
            openPendingInstallments -> {
                DebtNotificationFocusDialog(
                    debtId = null,
                    pendingInstallments = true,
                    onDismiss = onDebtNavigationConsumed,
                )
            }
            !openDebtId.isNullOrBlank() -> {
                DebtNotificationFocusDialog(
                    debtId = openDebtId,
                    onDismiss = onDebtNavigationConsumed,
                )
            }
        }
    }
}
