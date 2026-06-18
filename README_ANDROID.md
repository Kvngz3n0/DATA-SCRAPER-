# Android app plan

This repository now includes a working Android project scaffold for a manga/web scraper GUI, with a Compose-based main screen and a source registry model.

## What is included
- A Jetpack Compose UI entry point in [app/src/main/java/com/example/mangascraper/MainActivity.kt](app/src/main/java/com/example/mangascraper/MainActivity.kt)
- A source extension registry in [app/src/main/java/com/example/mangascraper/SourceRegistry.kt](app/src/main/java/com/example/mangascraper/SourceRegistry.kt)
- A basic scraper helper in [app/src/main/java/com/example/mangascraper/ScraperService.kt](app/src/main/java/com/example/mangascraper/ScraperService.kt)
- Android manifest permissions for networking and cleartext access

## Current status
- The app UI now includes a search box, source filter chips, and a sample results area.
- The project has a generated Gradle wrapper.
- The remaining blocker for a full build is the local Android SDK path in the environment.

## Build prerequisites
1. Install Android Studio or the Android SDK.
2. Set up a local.properties file containing the SDK path, for example:
   - sdk.dir=/path/to/Android/Sdk
3. Make sure the required SDK platforms are installed.

## Build command
Run:

./gradlew assembleDebug

## Next steps for the real scraper app
1. Replace sample search responses with real HTTP/HTML parsing for MangaFire, MangaDex, Mangabuddy, and similar sites.
2. Add anti-blocking logic such as user-agent rotation, retry policies, caching, and optional proxy support.
3. Add chapter/page parsing, reader screens, and a local database for reading progress and favorites.
4. Add NSFW filtering controls and source-specific settings.
