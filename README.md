# TideWidget

An Android tide app with a weather-map-style tide map and a home-screen widget showing the next high and low tide for your beach.

## Features

- **Tide map**: NOAA tide stations drawn as dots colored blue (low tide) through yellow to red (high tide). Drag the time slider across the next 24 hours and watch the tide move along the coast, like a radar loop.
- **Station sheet**: tap a station to see its 24-hour tide curve, the next six highs and lows, and a **Use for my widget** button.
- **Widget** (Glance): your beach's name, next high and next low (time and height), plus a 24-hour curve at medium size. It re-renders just after each tide turn from a cached three-day forecast, so it stays correct offline.

## Layout

| Path | What |
|---|---|
| `tidecore/` | Pure Kotlin/JVM: NOAA CO-OPS client, models, tide math (next high/low, cosine interpolation between highs and lows, map color scale). Included build, unit tested. |
| `app/` | Android app: Compose + Google Maps (`map/`), Glance widget and WorkManager refresh (`widget/`), cached forecast (`data/`). |

## Build

1. Open the project in Android Studio (Ladybug or newer, JDK 17+).
2. Get a Google Maps SDK for Android key and add it to `local.properties`:
   ```
   MAPS_API_KEY=your-key-here
   ```
3. Run the `app` configuration, tap a station, tap **Use for my widget**, then long-press the home screen and add the **Tides** widget.

Core logic tests run without Android: `./gradlew -p tidecore test`.

## Data

[NOAA CO-OPS](https://api.tidesandcurrents.noaa.gov/api/prod/) tide predictions: free, no API key, about 3,000 US stations. The map only fetches predictions for stations on screen (up to 60 per pan).

## Known gaps

- The Android app module hasn't been compiled yet; it was written in an environment without the Android SDK. Expect small fixes on first build.
- Times are shown in the phone's time zone, not the station's.
- The station list is downloaded on every launch and should be cached.
- The widget's beach is picked in the app; a widget configuration screen would be nicer.
- A true interpolated color overlay between stations (beyond dots) is a possible next step.
