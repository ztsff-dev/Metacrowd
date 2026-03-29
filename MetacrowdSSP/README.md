# Metacrowd SSP Core v2.0

Легковесный плагин-клиент для рекламной сети Minecraft (SSP).

## Требования

- **Java:** 21+
- **Сервер:** Paper/Spigot 1.21+
- **Зависимости:** Нет (чистый Java + Bukkit API)

## Установка

### Сборка из исходников

```bash
cd MetacrowdSSP
mvn clean package
```

После сборки JAR файл будет находиться в `target/metacrowd-ssp-2.0.0.jar`

### Установка на сервер

1. Скопируйте `metacrowd-ssp-2.0.0.jar` в папку `plugins/` вашего сервера
2. Запустите сервер для генерации конфигов
3. Отредактируйте `plugins/MetacrowdSSP/config.yml`:
   - Укажите URL вашего Google Apps Script API
   - Настройте соль для хэширования UUID
4. Перезапустите сервер

## Команды

| Команда | Описание |
|---------|----------|
| `/mcplace <id> <width> <height>` | Создать новый рекламный щит |
| `/mcremove <id>` | Удалить рекламный щит |
| `/mcreload` | Обновить ротацию для всех щитов |
| `/mcstats` | Показать статистику плагина |

Все команды требуют права `metacrowd.admin`.

## Пример использования

```
# Создать щит 4x3 с ID "lobby_main"
/mcplace lobby_main 4 3

# Проверить статистику
/mcstats

# Принудительно обновить ротацию
/mcreload

# Удалить щит
/mcremove lobby_main
```

## Архитектура

### Ключевые особенности

1. **RAM-only кэширование** - изображения хранятся только в оперативной памяти
2. **Двухступенчатый трекинг** - быстрая математика + RayCast для проверки видимости
3. **Пакетная аналитика** - отправка данных раз в 30 секунд
4. **Нулевые зависимости** - никаких внешних библиотек

### Структура проекта

```
src/main/java/com/metacrowd/ssp/
├── MetacrowdSSPPlugin.java    # Главный класс плагина
├── api/
│   └── ApiClient.java         # HTTP клиент для Google Apps Script
├── cache/
│   └── ImageCache.java        # RAM кэш изображений
├── commands/
│   ├── CommandHandler.java    # Обработчик команд
│   └── RotationLoader.java    # Загрузчик ротации
├── geometry/
│   ├── Placement.java         # Данные размещения
│   └── PlacementManager.java  # Управление размещениями
└── tracking/
    ├── AnalyticsManager.java  # Менеджер аналитики
    └── ViewTracker.java       # Трекинг видимости
```

## Конфигурация

### config.yml

```yaml
api:
  url: "https://script.google.com/macros/s/YOUR_SCRIPT_ID/exec"

instance:
  id: ""  # Автогенерируется если пустой

security:
  salt: "change_this_to_random_string"

analytics:
  flush-interval: 30

tracking:
  max-distance: 32
  view-angle: 60
  min-view-duration: 1000
  check-interval-ticks: 40
```

## Интеграция с Google Sheets

Плагин ожидает Google Apps Script API со следующими эндпоинтами:

### GET ?action=getRotation&placementId={ID}&instanceId={UUID}

Ответ:
```json
{
  "status": "ok",
  "config": {
    "rotationIntervalSec": 15,
    "fallbackUrl": "https://..."
  },
  "cycle": [
    {"creativeId": "camp_1", "imageUrl": "https://.../img1.png"},
    {"creativeId": "camp_2", "imageUrl": "https://.../img2.png"}
  ]
}
```

### POST ?action=reportImpression

Тело: JSON Array объектов аналитики.

## Производительность

- **Потребление RAM:** < 50 МБ при 10 баннерах
- **Нагрузка CPU:** < 1% в простое
- **Интервал трекинга:** 40 тиков (2 секунды)
- **Интервал аналитики:** 30 секунд

## Лицензия

Proprietary - Metacrowd SSP Network
