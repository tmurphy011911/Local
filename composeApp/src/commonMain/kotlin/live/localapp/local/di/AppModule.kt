package live.localapp.local.di

import live.localapp.local.data.remote.SupabaseClientFactory
import live.localapp.local.data.repository.ServerCarouselRepository
import live.localapp.local.data.repository.CommunityInterestRepositoryImpl
import live.localapp.local.data.repository.EventRepositoryImpl
import live.localapp.local.data.repository.OnboardingRepositoryImpl
import live.localapp.local.data.repository.PlanRepositoryImpl
import live.localapp.local.data.repository.UserPreferencesRepositoryImpl
import live.localapp.local.data.repository.BadgeRepositoryImpl
import live.localapp.local.data.repository.TasteRepositoryImpl
import live.localapp.local.data.repository.VenueRepositoryImpl
import live.localapp.local.domain.repository.BadgeRepository
import live.localapp.local.domain.repository.CarouselRepository
import live.localapp.local.domain.repository.CommunityInterestRepository
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.repository.OnboardingRepository
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
import live.localapp.local.ui.plans.PlansViewModel
import live.localapp.local.ui.progress.ProgressViewModel
import live.localapp.local.ui.taste.TasteProfileViewModel
import org.koin.dsl.module

val appModule = module {
    single { SupabaseClientFactory.create() }
    single<CommunityInterestRepository> { CommunityInterestRepositoryImpl(get(), get()) }
    single<EventRepository> { EventRepositoryImpl(get()) }
    single<CarouselRepository> { ServerCarouselRepository() }
    single<OnboardingRepository> { OnboardingRepositoryImpl(get()) }
    single<UserPreferencesRepository> { UserPreferencesRepositoryImpl(get()) }
    single<VenueRepository> { VenueRepositoryImpl(get()) }
    single<PlanRepository> { PlanRepositoryImpl(get(), get()) }
    single<BadgeRepository> { BadgeRepositoryImpl(get()) }
    single<TasteRepository> { TasteRepositoryImpl(get()) }
    single { BadgeService(get(), get()) }
    single { ClaudeApiService() }
    single { HomeViewModel(get(), get(), get(), get(), get(), get()) }
    single { ChatViewModel(get(), get(), get(), get(), get(), get(), get()) }
    single { PlacesViewModel(get(), get(), get()) }
    single { PlansViewModel(get(), get(), get(), get(), get()) }
    single { ProgressViewModel(get(), get(), get(), get()) }
    single { TasteProfileViewModel(get(), get()) }
    factory { OnboardingViewModel(get(), get()) }
    factory { PhoneAuthViewModel(get(), get()) }
}
