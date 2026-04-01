package live.localapp.local.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class WeatherForecast(
    val tempHigh: Int,
    val description: String,
    val weatherCode: Int,
)

class WeatherService {

    private val client = HttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, WeatherForecast>()

    suspend fun getDayForecast(lat: Double, lng: Double, date: String): WeatherForecast? {
        val key = "${(lat * 10).toInt()}_${(lng * 10).toInt()}_$date"
        cache[key]?.let { return it }

        return try {
            val response = client.get("https://api.open-meteo.com/v1/forecast") {
                parameter("latitude", lat)
                parameter("longitude", lng)
                parameter("daily", "temperature_2m_max,weathercode")
                parameter("start_date", date)
                parameter("end_date", date)
                parameter("temperature_unit", "fahrenheit")
                parameter("timezone", "auto")
            }

            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val daily = root["daily"]?.jsonObject ?: return null
            val temps = daily["temperature_2m_max"]?.jsonArray ?: return null
            val codes = daily["weathercode"]?.jsonArray ?: return null

            if (temps.isEmpty() || codes.isEmpty()) return null

            val temp = temps[0].jsonPrimitive.content.toDoubleOrNull()?.toInt() ?: return null
            val code = codes[0].jsonPrimitive.content.toIntOrNull() ?: 0

            val forecast = WeatherForecast(
                tempHigh = temp,
                description = weatherDescription(code),
                weatherCode = code,
            )
            cache[key] = forecast
            forecast
        } catch (e: Exception) {
            println("WeatherService error: ${e.message}")
            null
        }
    }

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly Clear"
        2 -> "Partly Cloudy"
        3 -> "Overcast"
        in 45..48 -> "Foggy"
        in 51..55 -> "Drizzle"
        in 56..57 -> "Freezing Drizzle"
        in 61..65 -> "Rain"
        in 66..67 -> "Freezing Rain"
        in 71..75 -> "Snow"
        in 77..77 -> "Snow Grains"
        in 80..82 -> "Showers"
        in 85..86 -> "Snow Showers"
        95 -> "Thunderstorm"
        in 96..99 -> "Thunderstorm w/ Hail"
        else -> "Unknown"
    }
}
