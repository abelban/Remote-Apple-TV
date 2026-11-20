# Разработка и тестирование

[← Вернуться к README](../README.md)

## Окружение

| Инструмент | Версия |
| :--- | :--- |
| JDK для сборки | 21 |
| Python | 3.11 |
| Android SDK Platform | 36.1 |
| Gradle | 9.6.0, через wrapper |
| Android Gradle Plugin | 9.4.1 |
| Chaquopy | 17.2.0 |

Откройте корень проекта в Android Studio, укажите SDK и дождитесь синхронизации. Локальный путь к SDK хранится в `local.properties`, который исключён из Git. На Windows используйте `gradlew.bat` вместо `./gradlew`.

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`. Сборка включает Python-движок и не требует внешнего сервера.

## Структура проекта

```text
app/
├── src/main/java/dev/abelman/remote/
│   ├── MainActivity.kt          # Экран, диалоги и аппаратные клавиши
│   ├── RemoteView.kt            # Сенсорная область и отрисовка кнопок
│   ├── RemoteRepository.kt      # Состояние соединения и мост к Python
│   ├── DeviceDiscovery.kt       # Поиск через Android NSD
│   ├── DeviceStore.kt           # Хранилище сопряжений и Android Keystore
│   └── RemoteTileService.kt     # Плитка быстрых настроек
├── src/main/python/             # Транспорт pyatv и адаптеры совместимости
├── src/androidTest/             # Сквозной тест с локальным симулятором
├── requirements.txt            # Зафиксированные Python-зависимости
└── wheels/                     # Pure-Python сборка zeroconf
tests/                          # Тесты транспорта
tools/                          # Симулятор и пересборка wheel
docs/assets/                    # Обложка и скриншоты для README
docs/qa/                        # Итоговые отчёты QA
```

## Тесты транспорта

В корне проекта создайте Python-окружение и установите зависимости:

```sh
python3.11 -m venv .venv
.venv/bin/python -m pip install --find-links app/wheels -r app/requirements.txt
.venv/bin/python -m unittest discover -s tests -v
```

Тесты не требуют физического Apple TV или ключей сопряжения. Они проверяют команды, PIN, работу громкости, ошибки, AEAD и отпускание удержанной кнопки при отмене. На Windows Python окружения находится в `.venv\Scripts\python.exe`.

## Сквозной тест Android

Тест выполняется в отдельной сборке **«Пульт · тест»** (`dev.abelman.remote.qa`) со своими данными и ключами Keystore. Он допускает только локальный симулятор и не отправляет команды на настоящий Apple TV. Основное приложение и его сопряжения не нужно сбрасывать.

### 1. Подготовьте симулятор

После установки зависимостей скачайте официальный [`tests/fake_device/companion.py`](https://github.com/postlund/pyatv/blob/v0.14.5/tests/fake_device/companion.py) из тега `v0.14.5` репозитория pyatv.

SHA-256 файла:

```text
a057835a00685c6d8a0ed28b1e131f296d1f9771e739f69ad489754f357cf9d7
```

Запустите симулятор в отдельном терминале и оставьте его работать:

```sh
.venv/bin/python tools/companion_simulator.py --fixture /path/to/companion.py
```

Он напечатает порт и PIN. Обёртка исправляет две ошибки официальной тестовой заготовки: порядок отправки события громкости и очистку RTI-сессии при отключении клиента. Код протокола приложения не меняется.

### 2. Соберите и запустите тест

Подключите телефон с USB-отладкой или запустите эмулятор. Замените `SERIAL` идентификатором из `adb devices`, а `PORT` — портом, который напечатал симулятор.

```sh
./gradlew :app:assembleQa :app:assembleQaAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/qa/app-qa.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk
adb -s SERIAL reverse tcp:39191 tcp:PORT
adb -s SERIAL shell am instrument -w -e host 127.0.0.1 -e port 39191 -e pin 1111 \
  dev.abelman.remote.qa.test/dev.abelman.remote.RemoteSmokeInstrumentation
```

В официальной заготовке используется PIN `1111`. Сквозной сценарий проверяет сопряжение, шифрованное соединение, 18 операций, передачу русского текста и сохранение сопряжения.

### 3. Завершите проверку

Удалите созданный USB-туннель и остановите симулятор через `Ctrl+C`:

```sh
adb -s SERIAL reverse --remove tcp:39191
```

Если QA-приложения больше не нужны, удалите **«Пульт · тест»** и его тестовый пакет. При добавлении временной QA-плитки удалите её через редактирование быстрых настроек.

## Особенности зависимостей

`pyatv==0.14.5` выбран из-за совместимости с Android-сборкой `cryptography==42.0.8`. Для этого сочетания в `app/src/main/python/` находятся два адаптера:

| Файл | Назначение |
| :--- | :--- |
| `chacha20poly1305_reuseable.py` | Использует публичный `cryptography.ChaCha20Poly1305` вместо старой оптимизации через внутренние API OpenSSL. Реализация шифра не меняется. |
| `companion_compat.py` | Добавляет двойные нажатия и удержания через стандартные HID down/up, как в более новых версиях pyatv. При отмене удержания отправляется отпускание кнопки. |

В `app/wheels/` включена pure-Python сборка zeroconf без необязательных Cython-расширений. Исходники библиотеки не менялись; лицензия включена в wheel. Для пересборки в подготовленном окружении:

```sh
.venv/bin/python -m pip install wheel build
.venv/bin/python tools/build_zeroconf_wheel.py
```

## Материалы проверок

- [Проверка 0.1.1 на физическом Android-телефоне](qa/0.1.1.md) — сборка, Lint, 28 тестов транспорта, 14 UI-сценариев и отдельная QA-плитка.
- [Первичная проверка 0.1.0](qa/0.1.0.md) — эмулятор, обнаружение Apple TV и сквозной сценарий с симулятором.

В версии 0.1.1 исправлена гонка обновлений громкости: после изменения уровня и быстрого отключения звука восстанавливается подтверждённый новый уровень. Данные основной версии и исходный список быстрых настроек при QA-прогоне были проверены контрольными суммами.

Проверки с симулятором не подтверждают совместимость с конкретной версией tvOS. Управление настоящим Apple TV остаётся непроверенным до доступности устройства в локальной сети.

## Справочные материалы

- [pyatv: поддерживаемые устройства и функции](https://pyatv.dev/documentation/supported_features/).
- [Chaquopy: Python для Android](https://chaquo.com/chaquopy/doc/current/android.html).
- [Android: Quick Settings TileService](https://developer.android.com/develop/ui/views/quicksettings-tiles).

## Локальные файлы и публикация

Исходники, тесты, Gradle wrapper и `app/wheels/` входят в репозиторий. Wrapper JAR нужен для запуска Gradle, а wheel zeroconf — для сборки Python-зависимостей под Android.

Результаты сборки, виртуальное окружение, кэши, настройки IDE, локальные пути SDK и ключи подписи исключены через `.gitignore`. Для временных APK, журналов и скриншотов проверки можно использовать папку `artifacts/`: она также исключена из Git. Публичные изображения и итоговые отчёты храните в `docs/`.

Готовые APK для пользователей размещайте в GitHub Releases отдельно от исходников.
