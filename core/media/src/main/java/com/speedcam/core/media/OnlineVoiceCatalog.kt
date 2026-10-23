package com.speedcam.core.media

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class CatalogVoicePack(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val language: String = "ru",
    val downloadUrl: String,
    val approximateSizeMb: Float,
    val clipCount: Int,
    val license: String = "Бесплатно (Creative Commons / Open)",
)

object OnlineVoiceCatalog {
    const val DEFAULT_CATALOG_URL =
        "https://raw.githubusercontent.com/Mishakorobov/speedcam/main/data/voice_packs.json"

    /**
     * Built-in curated catalog of free community voice packages with fallback URLs and mirrors.
     */
    val BUNDLED_CATALOG: List<CatalogVoicePack> =
        listOf(
            CatalogVoicePack(
                id = "elena_standard",
                name = "Стандартный диктор (Елена)",
                author = "SpeedCam Community",
                description = "Классический студийный женский голос. Четкая артикуляция и спокойные предупреждения.",
                language = "ru",
                downloadUrl =
                    "https://raw.githubusercontent.com/Mishakorobov/speedcam/main/assets/voice/elena_standard.zip",
                approximateSizeMb = 1.4f,
                clipCount = 48,
                license = "CC-BY 4.0 / Свободное использование",
            ),
            CatalogVoicePack(
                id = "oxana_navigator",
                name = "Оксана (Навигатор)",
                author = "Open Voice Project",
                description = "Популярный голос навигации для водителей с акцентом на безопасность.",
                language = "ru",
                downloadUrl =
                    "https://raw.githubusercontent.com/Mishakorobov/speedcam/main/assets/voice/oxana_navigator.zip",
                approximateSizeMb = 1.8f,
                clipCount = 52,
                license = "Public Domain / Бесплатно",
            ),
            CatalogVoicePack(
                id = "dmitry_brutal",
                name = "Дмитрий (Командный)",
                author = "Road Safety Media",
                description = "Уверенный мужской голос. Лаконичные строгие фразы для быстрой реакции на трассе.",
                language = "ru",
                downloadUrl =
                    "https://raw.githubusercontent.com/Mishakorobov/speedcam/main/assets/voice/dmitry_brutal.zip",
                approximateSizeMb = 1.6f,
                clipCount = 46,
                license = "CC-BY-SA / Бесплатно",
            ),
            CatalogVoicePack(
                id = "retro_humor",
                name = "Веселый штурман (Юмор)",
                author = "Auto Club CIS",
                description = "Непринужденные фразы с дорожным юмором для комфортных дальних поездок.",
                language = "ru",
                downloadUrl =
                    "https://raw.githubusercontent.com/Mishakorobov/speedcam/main/assets/voice/retro_humor.zip",
                approximateSizeMb = 2.1f,
                clipCount = 54,
                license = "Free Non-Commercial",
            ),
            CatalogVoicePack(
                id = "alex_english",
                name = "English Navigator (Alex)",
                author = "Global SpeedCam",
                description = "Concise and clear English alerts for speed limits, red lights and highway cameras.",
                language = "en",
                downloadUrl =
                    "https://raw.githubusercontent.com/Mishakorobov/speedcam/main/assets/voice/alex_english.zip",
                approximateSizeMb = 1.3f,
                clipCount = 42,
                license = "MIT / Open Data",
            ),
        )

    /**
     * Fetches the latest online catalog manifest from [catalogUrl], falling back to [BUNDLED_CATALOG] on failure.
     */
    fun fetchCatalog(catalogUrl: String = DEFAULT_CATALOG_URL): List<CatalogVoicePack> =
        try {
            val url = URL(catalogUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "SpeedCamApp/1.0 (Android)")

            if (conn.responseCode in 200..299) {
                val jsonText = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                parseCatalogJson(jsonText)
            } else {
                BUNDLED_CATALOG
            }
        } catch (_: Throwable) {
            BUNDLED_CATALOG
        }

    fun parseCatalogJson(jsonText: String): List<CatalogVoicePack> =
        try {
            val root = JSONObject(jsonText)
            val array = root.optJSONArray("packs") ?: JSONArray(jsonText)
            val list = mutableListOf<CatalogVoicePack>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    CatalogVoicePack(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        author = obj.optString("author", "Community"),
                        description = obj.optString("description", ""),
                        language = obj.optString("language", "ru"),
                        downloadUrl = obj.getString("downloadUrl"),
                        approximateSizeMb = obj.optDouble("sizeMb", 1.5).toFloat(),
                        clipCount = obj.optInt("clipCount", 40),
                        license = obj.optString("license", "Бесплатно"),
                    ),
                )
            }
            if (list.isNotEmpty()) list else BUNDLED_CATALOG
        } catch (_: Throwable) {
            BUNDLED_CATALOG
        }
}
