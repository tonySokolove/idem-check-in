-- Однократная подготовка БД для Check-in Service. Выполняется администратором (пользователь idem).
-- Пароль передаётся переменной psql и в файл/Git не попадает:
--   psql -U idem -d idem -v password="<пароль из Secret idem-check-in>" -f db/bootstrap.sql
-- Таблицы создаёт сам сервис миграциями Flyway при старте.

SELECT 'CREATE ROLE idem_checkin LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'idem_checkin')\gexec

ALTER ROLE idem_checkin WITH LOGIN PASSWORD :'password';
GRANT CONNECT ON DATABASE idem TO idem_checkin;

CREATE SCHEMA IF NOT EXISTS checkin AUTHORIZATION idem_checkin;
REVOKE ALL ON SCHEMA checkin FROM PUBLIC;
