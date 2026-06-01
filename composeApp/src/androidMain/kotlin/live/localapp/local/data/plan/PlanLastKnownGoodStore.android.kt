package live.localapp.local.data.plan

import live.localapp.local.domain.model.Plan

/**
 * Placeholder in-memory impl to keep `expect` happy until the real
 * EncryptedSharedPreferences-backed store lands. No callers reference this
 * yet (see grep), so the lack of persistence is non-functional, not a bug.
 */
actual fun providePlanLastKnownGoodStore(): PlanLastKnownGoodStore =
    InMemoryPlanLastKnownGoodStore

private object InMemoryPlanLastKnownGoodStore : PlanLastKnownGoodStore {
    private val store = mutableMapOf<String, Plan>()
    override suspend fun save(plan: Plan) { store[plan.id] = plan }
    override suspend fun load(planId: String): Plan? = store[planId]
    override suspend fun loadAll(): List<Plan> = store.values.toList()
    override suspend fun evict(planId: String) { store.remove(planId) }
    override suspend fun clearAll() { store.clear() }
}
