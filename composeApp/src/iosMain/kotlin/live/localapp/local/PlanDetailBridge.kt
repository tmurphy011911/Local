package live.localapp.local

import live.localapp.local.domain.model.Plan

object PlanDetailBridge {
    /**
     * Swift sets this. Compose calls it to present the plan detail with a hero transition.
     * Parameters: plan JSON string, card frame (x, y, w, h in points)
     */
    var showPlanDetail: ((
        planJson: String,
        cardX: Float, cardY: Float, cardW: Float, cardH: Float,
    ) -> Unit)? = null

    var dismissPlanDetail: (() -> Unit)? = null
    var onDismissed: (() -> Unit)? = null
    var onShare: (() -> Unit)? = null
}
