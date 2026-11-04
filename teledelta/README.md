# TeleDelta — Telegram для работы

Отдельное приложение на базе официального Telegram for Android 13.1.1 со встроенным sTGWS
(обход блокировок без VPN, как в `../official-telegram/`) и рабочей вкладкой.

- Пакет `com.teledelta.messenger`, название «TeleDelta», иконка Delta. Ставится рядом с
  Telegram и «Telegram sTGWS» (`org.telegram.stgws`), вход отдельный.
- Подпись: `TMessagesProj/config/stgws.jks` из этого репозитория.

## Что внутри

- **Два профиля — «Рабочий» (по умолчанию) и «Обычный».** Переключение — кнопка-портфель в
  шапке списка чатов или первая строка Настроек; без перезапуска.
  - **Рабочий:** вкладка «Чаты» называется «Работа» и показывает только выбранные рабочие
    чаты (группы, каналы, конкретные люди; архивированные тоже). Вкладок папок, историй,
    Premium/Звёзд/Подарков/Business/кошелька и рекламы в каналах нет. Счётчик на вкладке —
    рабочие чаты с непрочитанным.
  - **Обычный:** всё как в Telegram.
- **Выбор рабочих чатов** — из всех чатов, включая личные: ⋮ → «Выбрать рабочие чаты» в рабочем
  профиле, или долгое нажатие на любой чат → «Добавить в «Работу»». Без лимита, по одному.
- **Закрепить в «Работе»**, **Только непрочитанные**, **Рабочий режим уведомлений** (только из
  рабочих чатов и при упоминании) — в меню ⋮ рабочего профиля.
- Выбор хранится локально на телефоне, по аккаунтам.
- **Свой экран входа** TeleDelta; вход без Play Integrity; ошибки входа видны на экране и в
  журнале sTGWS.

Код: `org.telegram.messenger.teledelta`, `org.telegram.ui.teledelta`; правки в
`MainTabsActivity`, `SettingsActivity`, `LaunchActivity`, `DialogsActivity`, `DialogsAdapter`, `UsersSelectActivity`,
`NotificationsController`, `LocaleController`.

## Сборка

```sh
git clone --recursive --shallow-submodules https://github.com/DrKLO/Telegram.git tg
cd tg && git checkout 3de37c8cfb039452be7398909461da6288babf15 && git submodule update --init --recursive --depth 1
git apply ../teledelta/teledelta-13.1.1.patch
# свои ключи с https://my.telegram.org — в TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java
# (APP_ID и APP_HASH); в репозитории их нет намеренно
cp ../TMessagesProj/config/stgws.jks TMessagesProj/config/
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

Нужны JDK 21, Android SDK 36, NDK 27.2.12479018. APK:
`TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk`.
