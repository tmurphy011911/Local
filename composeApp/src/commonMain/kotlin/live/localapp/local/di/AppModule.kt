package live.localapp.local.di

import live.localapp.local.data.remote.SupabaseClientFactory
import live.localapp.local.data.repository.CarouselRepositoryImpl
import live.localapp.local.data.repository.EventRepositoryImpl
import live.localapp.local.data.repository.OnboardingRepositoryImpl
import live.localapp.local.data.repository.UserPreferencesRepositoryImpl
import live.localapp.local.domain.repository.CarouselRepository
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.repository.OnboardingRepository
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.feature.onboarding.OnboardingViewModel
import live.localapp.local.ui.chat.ChatViewModel
import live.localapp.local.ui.home.HomeViewModel
import org.koin.dsl.module

val appModule = module {
    single { SupabaseClientFactory.create() }
    single<EventRepository> { EventRepositoryImpl(get()) }
    single<CarouselRepository> { CarouselRepositoryImpl(get()) }
    single<OnboardingRepository> { OnboardingRepositoryImpl(get()) }
    single<UserPreferencesRepository> { UserPreferencesRepositoryImpl(get()) }
    single { HomeViewModel(get(), get()) }
    single { ChatViewModel() }
    factory { OnboardingViewModel(get(), get()) }
}
