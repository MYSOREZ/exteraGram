# Telegram sTGWS (на базе официального Telegram for Android)

exteraGram 9.6.6 сервер Telegram больше не пускает ко входу (ошибка 406 `UPDATE_APP_TO_LOGIN`,
которую старый код молча глотает). Поэтому рабочая сборка — официальный клиент со встроенным
мостом sTGWS.

- База: https://github.com/DrKLO/Telegram, коммит `3de37c8cfb039452be7398909461da6288babf15` (13.1.1).
- Пакет `org.telegram.stgws`, название «Telegram sTGWS», только arm64.
- Подпись: `TMessagesProj/config/stgws.jks` из этого репозитория (пароль `stgwspass`, alias `stgws`).
- Диагностика: долгое нажатие на заголовок «Ваш номер телефона» или на заголовок экрана «Прокси».

## Сборка

```sh
git clone --recursive --shallow-submodules https://github.com/DrKLO/Telegram.git tg
cd tg && git checkout 3de37c8cfb039452be7398909461da6288babf15 && git submodule update --init --recursive --depth 1
git apply ../official-telegram/telegram-13.1.1-stgws.patch
cp ../TMessagesProj/config/stgws.jks TMessagesProj/config/
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

Нужны JDK 21, Android SDK 36, NDK 27.2.12479018. APK:
`TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk`.
