package live.localapp.local.domain.model

data class CarouselSection(
    val slug: String,
    val title: String,
    val subtitle: String?,
    val filterType: String,
    val events: List<Event>,
)
