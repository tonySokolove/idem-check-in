# idem-check-in

Check-in Service IDEM: проверка билета на входе, Station Session и задания на запись NFC-бейджа.
Публичный адрес: https://idem-check-in.thelid.ru. Документация: Confluence → IDEM → API & System Analysis.

## Сборка

```
./mvnw verify            # тесты на встроенном PostgreSQL 18, Docker не нужен
./mvnw jib:build         # образ ghcr.io/tonysokolove/idem-check-in:<version>; креды — server ghcr.io в ~/.m2/settings.xml
```

## Настройки (переменные окружения)

| Переменная | Назначение | По умолчанию |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL; схема `checkin` (Flyway) | — |
| `CHECKIN_EVENT_ID` | Event для всех Station Session (временно) | — |
| `CARD_BASE_URL` | начало ссылки на бейдже | `https://idem-check-in.thelid.ru/c/` |
| `STATION_AGENT_API_KEYS` | ключи Station Agent через запятую, каждый ≥ 32 символов | — |
| `STATION_SESSION_TTL`, `STATION_HEARTBEAT_INTERVAL` | жизнь сессии без heartbeat / интервал heartbeat | `PT5M` / `PT60S` |
| `PROVISIONING_JOB_TTL` | сколько у Admin Hub времени, чтобы начать запись | `PT2M` |
| `REGISTRATION_URL`, `REGISTRATION_API_TOKEN` | Registration Service | `http://idem-registration` |

## Первый запуск в кластере

1. `db/bootstrap.sql` — роль `idem_checkin` и схема `checkin` (выполняет администратор БД).
2. Secret `idem-check-in`: `db-password`, `station-agent-api-keys`. Токен к Registration берётся из Secret `idem-registration` (`checkin-token`).
