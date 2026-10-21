package com.speedcam.wear.haptics

/**
 * Tactile vibration intensity profiles tailored for motorcycle riders wearing gloves and experiencing
 * handlebar vibration.
 */
enum class MotorcycleHapticProfile(
    val displayName: String,
    val description: String,
) {
    MOTORCYCLE_HIGH(
        displayName = "Мотоциклетный (Макс)",
        description =
            "Мощные длинные импульсы максимальной частоты. " +
                "Пробивают толстые кожаные мотоперчатки и вибрацию руля.",
    ),
    STANDARD(
        displayName = "Стандартный",
        description = "Умеренная тактильная отдача для езды на скутере, велосипеде или в легких перчатках.",
    ),
    OVERLIMIT_ONLY(
        displayName = "Только при превышении",
        description = "Вибрирует исключительно при превышении разрешенной скорости перед камерой.",
    ),
    SILENT(
        displayName = "Без вибрации",
        description = "Только визуальная индикация на экране часов.",
    ),
}
