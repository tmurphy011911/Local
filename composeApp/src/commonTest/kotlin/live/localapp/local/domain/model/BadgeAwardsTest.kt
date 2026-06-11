package live.localapp.local.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Locks the creator-side collaboration award VALUES that BadgeService.claimCreatorBadges()
 * pays out. The point math lives in pointsForMilestone/thresholdsFor (pure), so we assert it
 * directly — no devices, no mocks, no coroutines.
 *
 * Scenario these guard (see BadgeService.claimCreatorBadges):
 *   - someone accepts a plan invite      -> INVITE_ACCEPTED, first accept = 2,500
 *   - an owned plan reaches 5+ confirmed  -> GROUP_5_CONFIRMED  = 5,000 (one-time)
 *   - an owned plan reaches 10+ confirmed -> GROUP_10_CONFIRMED = 10,000 (one-time)
 */
class BadgeAwardsTest {

    @Test
    fun firstInviteAccept_awards_2500() {
        // claimCreatorBadges ratchets the counter to snapshot.totalAccepts and awards each
        // newly-crossed milestone. The first accept crosses milestone 1.
        assertEquals(1, thresholdsFor(BadgeAction.INVITE_ACCEPTED).first())
        assertEquals(2500, pointsForMilestone(BadgeAction.INVITE_ACCEPTED, 1))
    }

    @Test
    fun group5_confirmed_awards_5000_oneTime() {
        // GROUP_5_CONFIRMED is one-time: a single milestone of 1, flat basePoints.
        assertTrue(BadgeAction.GROUP_5_CONFIRMED.isOneTime)
        assertEquals(listOf(1), thresholdsFor(BadgeAction.GROUP_5_CONFIRMED))
        assertEquals(5000, pointsForMilestone(BadgeAction.GROUP_5_CONFIRMED, 1))
    }

    @Test
    fun group10_confirmed_awards_10000_oneTime() {
        assertTrue(BadgeAction.GROUP_10_CONFIRMED.isOneTime)
        assertEquals(listOf(1), thresholdsFor(BadgeAction.GROUP_10_CONFIRMED))
        assertEquals(10000, pointsForMilestone(BadgeAction.GROUP_10_CONFIRMED, 1))
    }

    @Test
    fun inviteAccepted_isMilestoneLadder_notFlatPerAccept() {
        // GOTCHA: INVITE_ACCEPTED is NOT isOneTime, so points scale as milestone * basePoints
        // on the standard ladder [1,3,5,...]. The flat "2,500 per accept" intuition only holds
        // for the FIRST accept. The 2nd accept earns nothing new; the 3rd crosses milestone 3.
        assertFalse(BadgeAction.INVITE_ACCEPTED.isOneTime)
        assertEquals(MILESTONE_THRESHOLDS, thresholdsFor(BadgeAction.INVITE_ACCEPTED))
        assertEquals(2500, pointsForMilestone(BadgeAction.INVITE_ACCEPTED, 1))
        assertEquals(7500, pointsForMilestone(BadgeAction.INVITE_ACCEPTED, 3))
        // No milestone sits at 2, so two accepts award nothing beyond the first crossing.
        assertFalse(2 in thresholdsFor(BadgeAction.INVITE_ACCEPTED))
    }

    @Test
    fun groupAwards_pushUserAcrossStatusTiers() {
        // A single 5+ plan (5,000) lands the creator in SETTLING_IN; a 10+ plan (10,000)
        // reaches GETTING_OUT_THERE. Guards the tier thresholds the celebration toast reads.
        assertEquals(StatusTier.SETTLING_IN, statusTierForPoints(5000))
        assertEquals(StatusTier.GETTING_OUT_THERE, statusTierForPoints(10000))
    }
}
