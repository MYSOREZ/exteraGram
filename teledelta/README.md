# TeleDelta — Telegram для работы

Отдельное приложение на базе официального Telegram for Android 13.1.1 со встроенным sTGWS
(обход блокировок без VPN, как в `../official-telegram/`) и рабочей вкладкой.

- Пакет `com.teledelta.messenger`, название «TeleDelta», иконка Delta. Ставится рядом с
  Telegram и «Telegram sTGWS» (`org.telegram.stgws`), вход отдельный.
- Подпись: `TMessagesProj/config/stgws.jks` из этого репозитория.

## Что внутри

- **Вкладка «Работа»** посередине нижней панели, открывается при запуске. В ней — выбранные
  рабочие чаты и личные переписки (по умолчанию); архивированные рабочие чаты тоже видны.
  Значок на вкладке — число рабочих чатов с непрочитанным.
- **Выбор рабочих чатов:** ⋮ → «Выбрать рабочие чаты», долгое нажатие на вкладку «Работа»,
  или долгое нажатие на любой чат → «Добавить в «Работу»». Без лимита в 100 чатов; можно
  отметить типы (контакты, не контакты, группы, каналы, боты).
- **Закрепить в «Работе»** — своё закрепление, не трогает основной список.
- **Только непрочитанные** и **Рабочий режим уведомлений** (уведомления только из «Работы» и
  при упоминании) — в меню ⋮ вкладки.
- Выбор хранится локально на телефоне, по аккаунтам.

Код: `org.telegram.messenger.teledelta`, `org.telegram.ui.teledelta`; правки в
`MainTabsActivity`, `DialogsActivity`, `DialogsAdapter`, `UsersSelectActivity`,
`NotificationsController`, `LocaleController`.

## Сборка

```sh
git clone --recursive --shallow-submodules https://github.com/DrKLO/Telegram.git tg
cd tg && git checkout 3de37c8cfb039452be7398909461da6288babf15 && git submodule update --init --recursive --depth 1
git apply ../teledelta/teledelta-13.1.1.patch
cp ../TMessagesProj/config/stgws.jks TMessagesProj/config/
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

Нужны JDK 21, Android SDK 36, NDK 27.2.12479018. APK:
`TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk`.
