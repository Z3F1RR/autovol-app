# Разработка

## Сборка и тесты

`./gradlew assembleDebug` (JDK 21, Android SDK). Тесты: `./gradlew testDebugUnitTest` — логика,
Android-часть на Android 10/12/14/16 через Robolectric и скриншоты (`app/build/outputs/roborazzi`).

Каждый push в рабочую ветку (не `main`) выкладывает пререлиз «Тестовая сборка» с APK; любой
релиз его удаляет.

## Выпуск версии

1. Поднять `appVersionName` и `appVersionCode` в `app/build.gradle.kts`.
2. Добавить `fastlane/metadata/android/{ru-RU,en-US}/changelogs/<versionCode>.txt` (это текст
   релиза на GitHub) и раздел в `CHANGELOG.md`.
3. Влить в `main`, затем Releases → Draft a new release → тег `1.2.3` или `v1.2.3` → Publish.
   Сборка проверит тег, подпишет APK ключом из секретов ([SIGNING.md](SIGNING.md)), приложит его
   к релизу и удалит тестовые сборки.

Если релиз уже создан, а APK к нему не приложился: Actions → build → Run workflow → указать тег.

## Устройство

См. `CLAUDE.md`: порт `reference/autovol.py`, логика в `app/src/main/java/.../core`.
