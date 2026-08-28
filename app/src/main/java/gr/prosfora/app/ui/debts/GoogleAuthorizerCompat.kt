package gr.prosfora.app.ui.debts

import androidx.compose.runtime.Composable
import gr.prosfora.app.google.GoogleAuthorizer

/** Compatibility wrapper for the debts screen. */
@Composable
internal fun rememberGoogleAuthorizer(): GoogleAuthorizer =
    gr.prosfora.app.google.rememberGoogleAuthorizer()
