package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// iOS plan detail uses SwiftUI + MapKit (PlanDetailView.swift), not this
// Compose path. Returning false tells the Compose hero to fall back to its
// static-image / gradient layer, which iOS never actually renders anyway.
@Composable
actual fun PlanMapHero(
    stops: List<MapHeroStop>,
    modifier: Modifier,
    onTotalsChanged: (MapHeroTotals?) -> Unit,
): Boolean = false
