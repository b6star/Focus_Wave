package com.yourssu.focuswave.ui.state

import com.yourssu.focuswave.R
import kotlin.math.roundToInt

data class SoundMixerUiState(
    val categories: List<SoundCategoryUiState> = defaultSoundCategories,
    val isPlaybackEnabled: Boolean = false,
    val isSelectionMode: Boolean = false
)

data class SoundCategoryUiState(
    val id: SoundCategoryId,
    val title: String,
    val tracks: List<SoundTrackUiState>,
    val selectedTrackId: SoundTrackId,
    val isEnabled: Boolean = false,
    val volume: Float = DEFAULT_VOLUME
) {
    val selectedTrack: SoundTrackUiState?
        get() = tracks.firstOrNull { it.id == selectedTrackId }

    val volumePercent: Int
        get() = (volume.coerceIn(0f, 1f) * 100).roundToInt()
}

data class SoundTrackUiState(
    val id: SoundTrackId,
    val title: String,
    val rawResId: Int
)

enum class SoundCategoryId {
    Rain,
    Ocean,
    Cafe,
    City,
    Space
}

enum class SoundTrackId {
    // Rain
    RAIN_THUNDER,
    RAIN_THUNDER2,
    RAIN_IN_CAR,

    // Ocean
    OCEAN_WAVES,
    CYPRUS_SEA,
    HASTINGS_BEACH,
    LAGOON_BEACH,

    // Cafe
    CAFE_AMBIENT,
    SWEDEN_CAFE,
    CHINA_CAFE,

    // City

    TRAFFIC_AMBIENT,
    NEW_YORK_FIELD,
    NEW_YORK_STREET,
    TORONTO_STREET,
    AMSTERDAM_STREET,
    RIDING_A_BIKE_IN_PARIS,

    // space
    SPACE_AMBIENT,
    LUNAR_WIND,
    UFO_BASS,
    MOTHERSHIP,
    SPACESHIP_ENGINE

}

private const val DEFAULT_VOLUME = 0.5f

val defaultSoundCategories = listOf(

    // Rain
    SoundCategoryUiState(
        id = SoundCategoryId.Rain,
        title = "Rain",
        tracks = listOf(
            SoundTrackUiState(
                id = SoundTrackId.RAIN_THUNDER,
                title = "Rain Thunder",
                rawResId = R.raw.rain_thunder
            ),
            SoundTrackUiState(
                id = SoundTrackId.RAIN_THUNDER2,
                title = "Rain Thunder 2",
                rawResId = R.raw.rain_thunder2
            ),
            SoundTrackUiState(
                id = SoundTrackId.RAIN_IN_CAR,
                title = "Rain in Car",
                rawResId = R.raw.rain_in_car
            )
        ),
        selectedTrackId = SoundTrackId.RAIN_THUNDER
    ),

    // Ocean
    SoundCategoryUiState(
        id = SoundCategoryId.Ocean,
        title = "Ocean",
        tracks = listOf(
            SoundTrackUiState(
                id = SoundTrackId.OCEAN_WAVES,
                title = "Ocean Waves",
                rawResId = R.raw.ocean_waves
            ),
            SoundTrackUiState(
                id = SoundTrackId.CYPRUS_SEA,
                title = "Cyprus Sea",
                rawResId = R.raw.cyprus_sea
            ),
            SoundTrackUiState(
                id = SoundTrackId.HASTINGS_BEACH,
                title = "Hastings Beach",
                rawResId = R.raw.hastings_beach
            ),
            SoundTrackUiState(
                id = SoundTrackId.LAGOON_BEACH,
                title = "Lagoon Beach",
                rawResId = R.raw.lagoon_beach
            )
        ),
        selectedTrackId = SoundTrackId.OCEAN_WAVES
    ),

    // Cafe
    SoundCategoryUiState(
        id = SoundCategoryId.Cafe,
        title = "Cafe",
        tracks = listOf(
            SoundTrackUiState(
                id = SoundTrackId.CAFE_AMBIENT,
                title = "Coffee Shop",
                rawResId = R.raw.cafe_ambient
            ),
            SoundTrackUiState(
                id = SoundTrackId.SWEDEN_CAFE,
                title = "Sweden Cafe",
                rawResId = R.raw.sweden_cafe
            ),
            SoundTrackUiState(
                id = SoundTrackId.CHINA_CAFE,
                title = "China Cafe",
                rawResId = R.raw.china_cafe
            )
        ),
        selectedTrackId = SoundTrackId.CAFE_AMBIENT
    ),

    // City
    SoundCategoryUiState(
        id = SoundCategoryId.City,
        title = "City",
        tracks = listOf(
            SoundTrackUiState(
                id = SoundTrackId.TRAFFIC_AMBIENT,
                title = "Traffic Ambient",
                rawResId = R.raw.traffic_ambient
            ),
            SoundTrackUiState(
                id = SoundTrackId.NEW_YORK_FIELD,
                title = "NewYork Field",
                rawResId = R.raw.new_york_field
            ),
            SoundTrackUiState(
                id = SoundTrackId.NEW_YORK_STREET,
                title = "NewYork Street",
                rawResId = R.raw.new_york_street
            ),
            SoundTrackUiState(
                id = SoundTrackId.AMSTERDAM_STREET,
                title = "Amsterdam Street",
                rawResId = R.raw.amsterdam_street
            ),
            SoundTrackUiState(
                id = SoundTrackId.TORONTO_STREET,
                title = "Toronto Street",
                rawResId = R.raw.toronto_street
            ),
            SoundTrackUiState(
                id = SoundTrackId.RIDING_A_BIKE_IN_PARIS,
                title = "Riding a Bike in Paris",
                rawResId = R.raw.riding_a_bike_in_paris
            )
        ),
        selectedTrackId = SoundTrackId.TRAFFIC_AMBIENT
    ),

    // Space
    SoundCategoryUiState(
        id = SoundCategoryId.Space,
        title = "Space",
        tracks = listOf(
            SoundTrackUiState(
                id = SoundTrackId.SPACE_AMBIENT,
                title = "Space Ambient",
                rawResId = R.raw.space_ambient
            ),
            SoundTrackUiState(
                id = SoundTrackId.LUNAR_WIND,
                title = "Lunar Wind",
                rawResId = R.raw.lunar_wind
            ),
            SoundTrackUiState(
                id = SoundTrackId.UFO_BASS,
                title = "UFO Bass",
                rawResId = R.raw.ufo_bass
            ),
            SoundTrackUiState(
                id = SoundTrackId.MOTHERSHIP,
                title = "Mothership",
                rawResId = R.raw.mothership
            ),
            SoundTrackUiState(
                id = SoundTrackId.SPACESHIP_ENGINE,
                title = "Spaceship Engine",
                rawResId = R.raw.spaceship_engine
            )
        ),
        selectedTrackId = SoundTrackId.SPACE_AMBIENT
    ),
)
