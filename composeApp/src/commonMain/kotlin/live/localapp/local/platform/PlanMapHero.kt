package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * One stop on a plan day, geocoded for the map hero.
 */
data class MapHeroStop(
    val lat: Double,
    val lng: Double,
    val name: String,
)

/**
 * Aggregated travel totals across all legs of the day's route — bubbled up
 * so the floating title card can show "X stops · X mi · ~X min".
 */
data class MapHeroTotals(
    val miles: Double,
    val minutes: Int,
    /** "walk" or "drive" — whichever consumed more travel time across legs. */
    val dominantMode: String = "walk",
)

/**
 * Renders the day's stops as a route map (numbered pins + dashed polyline +
 * per-leg walk/drive labels) with a fixed height inside the hero ZStack.
 *
 * Returns true when the platform actually rendered a map (caller hides the
 * static-image fallback). iOS returns false because PlanDetailView.swift
 * uses MapKit directly, not this Compose path.
 */
@Composable
expect fun PlanMapHero(
    stops: List<MapHeroStop>,
    modifier: Modifier = Modifier,
    onTotalsChanged: (MapHeroTotals?) -> Unit = {},
): Boolean
