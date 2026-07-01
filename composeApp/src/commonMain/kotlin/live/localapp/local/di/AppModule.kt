package live.localapp.local.di

import com.russhwolf.settings.Settings
import live.localapp.local.data.featureflags.FeatureFlagsRepository
import live.localapp.local.data.remote.SupabaseClientFactory
import live.localapp.local.data.repository.HttpExploreRepository
import live.localapp.local.data.bo.BoTools
import live.localapp.local.data.sync.MutationLog
import live.localapp.local.data.sync.MutationLogImpl
import live.localapp.local.data.sync.PlanMutationRpc
import live.localapp.local.data.sync.PlanRealtimeWatcher
import live.localapp.local.data.sync.PlanSyncer
import live.localapp.local.data.repository.ServerCarouselRepository
import live.localapp.local.data.repository.CommunityInterestRepositoryImpl
import live.localapp.local.data.repository.EventRepositoryImpl
import live.localapp.local.data.repository.OnboardingRepositoryImpl
import live.localapp.local.data.repository.PlanChatRepositoryImpl
import live.localapp.local.data.repository.PlanRepositoryImpl
import live.localapp.local.data.repository.UserPreferencesRepositoryImpl
import live.localapp.local.data.repository.BadgeRepositoryImpl
import live.localapp.local.data.repository.TasteRepositoryImpl
import live.localapp.local.data.repository.VenueRepositoryImpl
import live.localapp.local.domain.repository.BadgeRepository
import live.localapp.local.domain.repository.CarouselRepository
import live.localapp.local.domain.repository.CommunityInterestRepository
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.repository.ExploreRepository
import live.localapp.local.domain.repository.OnboardingRepository
import live.localapp.local.domain.repository.PlanChatRepository
import live.localapp.local.domain.repository.PlanRepository
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.domain.repository.TasteRepository
import live.localapp.local.domain.repository.VenueRepository
import live.localapp.local.feature.auth.PhoneAuthViewModel
import live.localapp.local.feature.onboarding.OnboardingViewModel
import live.localapp.local.data.remote.ClaudeApiService
import live.localapp.local.domain.service.BadgeService
import live.localapp.local.ui.chat.ChatViewModel
import live.localapp.local.ui.home.HomeViewModel
import live.localapp.local.ui.places.PlacesViewModel
import live.localapp.local.ui.plans.PlanChatViewModel
import live.localapp.local.ui.plans.PlansViewModel
import live.localapp.local.ui.progress.ProgressViewModel
import live.localapp.local.ui.taste.TasteProfileViewModel
import org.koin.dsl.module

val appModule = module {
    single { SupabaseClientFactory.create() }
    single<CommunityInterestRepository> { CommunityInterestRepositoryImpl(get(), get()) }
    single<EventRepository> { EventRepositoryImpl(get()) }
    single<CarouselRepository> { ServerCarouselRepository(get()) }
    single<OnboardingRepository> { OnboardingRepositoryImpl(get()) }
    single<UserPreferencesRepository> { UserPreferencesRepositoryImpl(get()) }
    single<VenueRepository> { VenueRepositoryImpl(get()) }
    single<PlanRepository> { PlanRepositoryImpl(get(), get()) }
    single<PlanChatRepository> { PlanChatRepositoryImpl(get()) }
    single<BadgeRepository> { BadgeRepositoryImpl(get()) }
    single<TasteRepository> { TasteRepositoryImpl(get()) }
    single<ExploreRepository> { HttpExploreRepository() }
    single<live.localapp.local.domain.repository.LeaderboardRepository> { live.localapp.local.data.repository.LeaderboardRepositoryImpl(get(), get()) }
    single { BadgeService(get(), get()) }
    single { ClaudeApiService(get()) }
    single { live.localapp.local.domain.service.SplashGreetingService(get(), get(), get(), get()) }
    single { live.localapp.local.domain.service.PlanSwapService(get(), get()) }
    single<MutationLog> { MutationLogImpl(Settings()) }
    single { BoTools(get(), get()) }
    single { PlanMutationRpc(get(), get()) }
    // PlanSyncer starts its drain loop in its constructor — `createAtStart`
    // would run it eagerly, but Koin invokes the factory lazily on first
    // `get()`. We resolve it from `App.kt` at startup so the loop is alive
    // for the whole session even if no ViewModel has touched it yet.
    single { PlanSyncer(get(), get(), get()) }
    single { PlanRealtimeWatcher(get()) }
    single { FeatureFlagsRepository(get()) }
    // Single owner of deep-link resolution (runtime Universal Links + deferred
    // install referrer / clipboard). See DeepLinkController for why this is a
    // singleton rather than per-screen callbacks.
    single { live.localapp.local.feature.deeplink.DeepLinkController(get(), get(), get()) }
    single<live.localapp.local.domain.repository.YocalPromptsRepository> { live.localapp.local.data.repository.HttpYocalPromptsRepository() }
    single<live.localapp.local.domain.repository.BoChipsRepository> { live.localapp.local.data.repository.HttpBoChipsRepository() }
    single { HomeViewModel(get(), get(), get(), get(), get(), get(), get()) }
    single { ChatViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { PlacesViewModel(get(), get(), get(), get(), get(), get()) }
    single { PlansViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { ProgressViewModel(get(), get(), get(), get()) }
    single { live.localapp.local.ui.leaderboard.LeaderboardViewModel(get(), get(), get()) }
    single { TasteProfileViewModel(get(), get()) }
    factory { OnboardingViewModel(get(), get(), get()) }
    factory { PhoneAuthViewModel(get(), get()) }
    factory { (plan: live.localapp.local.domain.model.Plan) -> PlanChatViewModel(plan, get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
