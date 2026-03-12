package live.localapp.local

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform