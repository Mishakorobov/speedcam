package com.speedcam.app.util

import com.speedcam.core.data.HazardReportType
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.QuickSetting
import com.speedcam.core.detect.TransportProfile
import com.speedcam.core.media.OutputMode
import com.speedcam.core.media.VoiceEngine
import com.speedcam.core.media.VoicePhraseStyle

fun CameraKind.toRussianTitle(): String =
    when (this) {
        CameraKind.FIXED -> "Камера скорости"
        CameraKind.RED_LIGHT -> "Контроль светофора"
        CameraKind.AVERAGE_ENTRY -> "Начало КСС"
        CameraKind.AVERAGE_EXIT -> "Конец КСС"
        CameraKind.MOBILE -> "Мобильная засада"
        CameraKind.COMPLEX -> "Комплексный контроль"
        CameraKind.DUMMY -> "Муляж"
        CameraKind.BUS_LANE -> "Контроль полосы ОТ"
        CameraKind.ROAD_MARKING -> "Разметка"
        CameraKind.UNKNOWN -> "Опасность"
    }

fun CameraKind.toRussianBadge(): String =
    when (this) {
        CameraKind.FIXED -> "КАМЕРА"
        CameraKind.RED_LIGHT -> "СВЕТОФОР"
        CameraKind.AVERAGE_ENTRY -> "НАЧАЛО КСС"
        CameraKind.AVERAGE_EXIT -> "КОНЕЦ КСС"
        CameraKind.MOBILE -> "ЗАСАДА"
        CameraKind.COMPLEX -> "КОМПЛЕКС"
        CameraKind.DUMMY -> "МУЛЯЖ"
        CameraKind.BUS_LANE -> "ПОЛОСА ОТ"
        CameraKind.ROAD_MARKING -> "РАЗМЕТКА"
        CameraKind.UNKNOWN -> "ОПАСНОСТЬ"
    }

fun QuickSetting.toRussianTitle(): String =
    when (this) {
        QuickSetting.MAXIMUM_ALERTS -> "Максимум"
        QuickSetting.MODERATE -> "Умеренный"
        QuickSetting.QUIET -> "Тихий"
        QuickSetting.SILENT -> "Без звука"
        QuickSetting.MELODIC -> "Мелодичный"
        QuickSetting.SIGNAL -> "Сигнал"
        QuickSetting.VISUAL_ONLY -> "Только экран"
        QuickSetting.QUIET_CITY -> "Тихий город"
    }

fun TransportProfile.toRussianTitle(): String =
    when (this) {
        TransportProfile.COMMON -> "Общий"
        TransportProfile.CAR -> "Автомобиль"
        TransportProfile.TRUCK -> "Грузовик"
        TransportProfile.MOTO -> "Мотоцикл"
    }

fun OutputMode.toRussianTitle(): String =
    when (this) {
        OutputMode.STANDARD -> "Авто / Медиа"
        OutputMode.ALWAYS_SPEAKER -> "Динамик телефона"
        OutputMode.BLUETOOTH_MULTIMEDIA -> "BT Мультимедиа"
        OutputMode.BLUETOOTH_HFP -> "BT Громкая связь (HFP)"
        OutputMode.SYSTEM -> "Системный"
        OutputMode.RING -> "Звонок"
        OutputMode.MULTIMEDIA -> "Мультимедиа"
        OutputMode.ALARM -> "Будильник"
        OutputMode.NOTIFICATION -> "Уведомления"
        OutputMode.ANDROID_AUTO -> "Android Auto"
        OutputMode.NAVIGATION -> "Навигация"
        OutputMode.HEADRESTS -> "Подголовники"
    }

fun HazardReportType.toRussianTitle(): String =
    when (this) {
        HazardReportType.NEW_CAMERA -> "Новая камера"
        HazardReportType.REMOVED_CAMERA -> "Демонтирована"
        HazardReportType.WRONG_SPEED_LIMIT -> "Неверный лимит"
        HazardReportType.FALSE_POSITIVE -> "Ложная отметка"
        HazardReportType.DPS_PATROL -> "Засада ДПС"
        HazardReportType.MOBILE_TRIPOD -> "Мобильная тренога"
        HazardReportType.ROAD_ACCIDENT -> "Авария (ДТП)"
    }

fun HazardReportType.toShortTitle(): String =
    when (this) {
        HazardReportType.NEW_CAMERA -> "Камера"
        HazardReportType.REMOVED_CAMERA -> "Снята"
        HazardReportType.WRONG_SPEED_LIMIT -> "Лимит"
        HazardReportType.FALSE_POSITIVE -> "Ложная"
        HazardReportType.DPS_PATROL -> "ДПС"
        HazardReportType.MOBILE_TRIPOD -> "Тренога"
        HazardReportType.ROAD_ACCIDENT -> "ДТП"
    }

fun VoiceEngine.toRussianTitle(): String =
    when (this) {
        VoiceEngine.TTS -> "Синтезатор речи (TTS)"
        VoiceEngine.AUDIO_PACK -> "Аудио-пакет фраз"
        VoiceEngine.DISABLED -> "Отключено"
    }

fun VoicePhraseStyle.toRussianTitle(): String =
    when (this) {
        VoicePhraseStyle.FULL -> "Полные предупреждения"
        VoicePhraseStyle.SHORT -> "Короткие фразы"
    }
