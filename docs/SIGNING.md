# Ключ подписи для GitHub Actions

Ключ нужен один раз и навсегда: APK, подписанный другим ключом, не встанет поверх
установленного (придётся удалять приложение вместе с настройками и калибровкой).

Все команды — в Termux, по одной строке. Вместо `ПАРОЛЬ` придумайте свой пароль (только латиница
и цифры, минимум 6 символов).

1. Установить Java и GitHub CLI:

   `pkg install -y openjdk-21 gh`

2. Создать ключ (срок — 100 лет):

   `keytool -genkeypair -keystore ~/autovol.jks -alias autovol -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=z3f1rr" -storepass ПАРОЛЬ -keypass ПАРОЛЬ`

3. Войти в GitHub (выбрать GitHub.com → HTTPS → Login with a web browser):

   `gh auth login`

4. Загрузить ключ и пароль в секреты репозитория:

   `base64 -w0 ~/autovol.jks | gh secret set KEYSTORE_BASE64 -R z3f1rr/autovol-app`

   `gh secret set KEYSTORE_PASSWORD -R z3f1rr/autovol-app -b 'ПАРОЛЬ'`

   `gh secret set KEY_ALIAS -R z3f1rr/autovol-app -b autovol`

5. Проверить, что секретов три:

   `gh secret list -R z3f1rr/autovol-app`

6. Сохранить копию ключа в «Загрузки» и дальше в надёжное место (облако, флешка):

   `termux-setup-storage && cp ~/autovol.jks ~/storage/downloads/`

После этого каждая сборка (и debug, и release) подписывается этим ключом. Первый такой APK
встанет только после удаления APK, собранного без ключа; дальше обновления ставятся поверх.

## Выпуск релиза

На GitHub: Releases → Draft a new release → Choose a tag → ввести, например, `v0.1.0` →
Create new tag → Publish release. Сборка сама приложит к релизу подписанный APK
(через несколько минут, вкладка Actions).
