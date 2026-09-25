# Развёртывание ChessTree в production

Инструкция описывает текущую схему из двух Ubuntu/Debian-серверов:

| Роль | Публичный адрес | WireGuard |
| --- | --- | --- |
| Nginx, Web, Ktor, PostgreSQL primary | `51.250.31.56` | `10.77.0.1` |
| PostgreSQL standby и логические бэкапы | `151.247.208.76` | `10.77.0.2` |

SSH primary доступен на TCP `2222`; SSH standby остаётся на TCP `22`.

Публичный адрес приложения — `https://chess-tree.online`. DNS-запись `A` должна
указывать на `51.250.31.56`. Запись `AAAA` добавляйте только при наличии реально
настроенного IPv6.

В примерах используется PostgreSQL 17. На обоих серверах нужна одна major-версия
и желательно одна minor-версия. Standby асинхронный и не переключается
автоматически. Репликация переносит весь кластер PostgreSQL, а не только базу
`chesstree`, и не заменяет бэкап: ошибочное удаление также реплицируется. Поэтому
ниже дополнительно настраивается ежедневный `pg_dump`.

## Карта файлов

На основном сервере:

| Путь | Назначение |
| --- | --- |
| `/etc/nginx/sites-available/chesstree` | Активный Nginx-конфиг |
| `/etc/letsencrypt/live/chess-tree.online/` | Сертификат и ключ Certbot |
| `/etc/chesstree/server.env` | Постоянные env Ktor и пароль БД |
| `/etc/systemd/system/chesstree-server.service` | systemd unit Ktor |
| `/opt/chesstree/releases/<release-id>/` | Версии Web и backend |
| `/opt/chesstree/current` | Ссылка на активный release |
| `/etc/wireguard/wg0.conf` | Туннель до standby |

На standby:

| Путь | Назначение |
| --- | --- |
| `/etc/wireguard/wg0.conf` | Туннель до primary |
| `/var/lib/postgresql/.pgpass` | Пароль репликации |
| `/var/lib/postgresql/17/main/` | Данные standby; точный путь надо проверить |
| `/usr/local/sbin/chesstree-pg-backup` | Скрипт логического бэкапа |
| `/var/backups/chesstree/` | Дампы за 14 дней |
| `/etc/systemd/system/chesstree-pg-backup.*` | service и timer бэкапа |

Секреты нельзя коммитить в Git, класть в `deploy/` или передавать в аргументах
команд. Значения `<...>` ниже всегда заменяются реальными значениями.

## 1. Предварительные проверки

На локальной машине:

```shell
dig +short A chess-tree.online
```

Ожидается `51.250.31.56`. Убедитесь, что SSH доступен на обоих серверах и у
учётной записи есть `sudo`. На обоих серверах:

```shell
cat /etc/os-release
timedatectl status
sudo timedatectl set-ntp true
sudo apt update
sudo apt full-upgrade
sudo apt install -y ca-certificates curl rsync ufw wireguard openssl
sudo reboot
```

После reboot снова подключитесь и проверьте `timedatectl`.

### Скопировать инфраструктурные шаблоны

Сами release-артефакты позже загрузит `deploy.sh`, но первичная настройка нужна до
первого deploy. Если репозиторий не клонирован на серверах, выполните из его корня
на локальной машине. Для primary используется `elvis`, для standby в примере —
`root` (замените его, если там настроен другой SSH-пользователь):

```shell
ssh -p 2222 elvis@51.250.31.56 'mkdir -p ~/chesstree-setup/nginx ~/chesstree-setup/systemd ~/chesstree-setup/remote ~/chesstree-setup/sudoers'
scp -P 2222 deploy/nginx/*.conf elvis@51.250.31.56:chesstree-setup/nginx/
scp -P 2222 deploy/systemd/chesstree-server.service deploy/systemd/server.env.example \
  elvis@51.250.31.56:chesstree-setup/systemd/
scp -P 2222 deploy/remote/chesstree-activate-release \
  elvis@51.250.31.56:chesstree-setup/remote/
scp -P 2222 deploy/sudoers/chesstree-deploy \
  elvis@51.250.31.56:chesstree-setup/sudoers/

ssh root@151.247.208.76 'mkdir -p ~/chesstree-setup/backup ~/chesstree-setup/systemd'
scp deploy/backup/chesstree-pg-backup root@151.247.208.76:chesstree-setup/backup/
scp deploy/systemd/chesstree-pg-backup.service deploy/systemd/chesstree-pg-backup.timer \
  root@151.247.208.76:chesstree-setup/systemd/
```

Далее пути `$HOME/chesstree-setup/...` означают именно эти загруженные копии.

## 2. Firewall

Продублируйте правила в security group/firewall провайдера. Замените `ADMIN_CIDR`
на свой постоянный публичный IP с `/32`. Не закрывайте текущую SSH-сессию, пока не
проверите вход во второй.

На primary `51.250.31.56`:

```shell
read -r -p 'Ваш публичный IP с /32: ' 85.143.144.44/32
test -n "$ADMIN_CIDR"
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow proto tcp from "$ADMIN_CIDR" to any port 2222
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow proto udp from 151.247.208.76 to any port 51820
sudo ufw allow in on wg0 proto tcp from 10.77.0.2 to 10.77.0.1 port 5432
sudo ufw enable
sudo ufw status verbose
```

На standby `151.247.208.76`:

```shell
read -r -p 'Ваш публичный IP с /32: ' 85.143.144.44/32
test -n "$ADMIN_CIDR"
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow proto tcp from "$ADMIN_CIDR" to any port 22
sudo ufw allow proto udp from 51.250.31.56 to any port 51820
sudo ufw enable
sudo ufw status verbose
```

Публичные `5432` и `8081` не открываются. Проверка с третьей машины:

```shell
nmap -Pn -p 2222,80,443,5432,8081 51.250.31.56
nmap -Pn -p 22,80,443,5432,8081 151.247.208.76
```

У primary публично доступны `80`/`443`; доступность `2222` зависит от IP
проверяющего. На standby это правило относится к `22`. `5432`/`8081` должны быть
закрыты или отфильтрованы.

## 3. WireGuard

На каждом сервере создайте собственную пару ключей:

```shell
sudo sh -c 'umask 077; wg genkey > /etc/wireguard/private.key; wg pubkey < /etc/wireguard/private.key > /etc/wireguard/public.key'
sudo cat /etc/wireguard/public.key
```

Обменяйте только публичные ключи. На primary создайте
`/etc/wireguard/wg0.conf`:

```ini
[Interface]
Address = 10.77.0.1/30
ListenPort = 51820
PrivateKey = <PRIMARY_PRIVATE_KEY>

[Peer]
PublicKey = <STANDBY_PUBLIC_KEY>
AllowedIPs = 10.77.0.2/32
Endpoint = 151.247.208.76:51820
PersistentKeepalive = 25
```

На standby создайте `/etc/wireguard/wg0.conf`:

```ini
[Interface]
Address = 10.77.0.2/30
ListenPort = 51820
PrivateKey = <STANDBY_PRIVATE_KEY>

[Peer]
PublicKey = <PRIMARY_PUBLIC_KEY>
AllowedIPs = 10.77.0.1/32
Endpoint = 51.250.31.56:51820
PersistentKeepalive = 25
```

На обоих серверах:

```shell
sudo chmod 600 /etc/wireguard/wg0.conf /etc/wireguard/private.key
sudo systemctl enable --now wg-quick@wg0
sudo wg show
```

Со standby:

```shell
ping -c 3 10.77.0.1
```

## 4. PostgreSQL 17 на обоих серверах

Если PostgreSQL уже установлен, сначала выполните `psql --version` и
`pg_lsclusters`: не создавайте второй кластер. Для новой установки:

```shell
sudo apt install -y postgresql-common
sudo /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh
sudo apt update
sudo apt install -y postgresql-17 postgresql-client-17
psql --version
pg_lsclusters
```

Скрипт добавления официального PGDG-репозитория интерактивный. На обоих хостах
major-версия `psql` и сервера должна совпадать.

## 5. Primary PostgreSQL

Узнайте реальные пути:

```shell
sudo -u postgres psql -Atqc 'SHOW server_version'
sudo -u postgres psql -Atqc 'SHOW config_file'
sudo -u postgres psql -Atqc 'SHOW hba_file'
sudo -u postgres psql -Atqc 'SHOW data_directory'
```

Настройте localhost, WireGuard, SCRAM и репликацию:

```shell
sudo -u postgres psql -v ON_ERROR_STOP=1 <<'SQL'
ALTER SYSTEM SET listen_addresses = '127.0.0.1,10.77.0.1';
ALTER SYSTEM SET wal_level = 'replica';
ALTER SYSTEM SET max_wal_senders = 5;
ALTER SYSTEM SET max_replication_slots = 5;
ALTER SYSTEM SET wal_keep_size = '1GB';
ALTER SYSTEM SET max_slot_wal_keep_size = '10GB';
ALTER SYSTEM SET password_encryption = 'scram-sha-256';
SQL
sudo systemctl restart postgresql
```

Сгенерируйте два разных пароля командой `openssl rand -hex 32`, сохраните их в
менеджере паролей и создайте роли. Команды `\password` безопасно запросят пароль
дважды:

```shell
sudo -u postgres psql -v ON_ERROR_STOP=1
```

В `psql`:

```sql
CREATE ROLE chesstree WITH LOGIN;
\password chesstree
CREATE DATABASE chesstree OWNER chesstree;
CREATE ROLE chesstree_replica WITH LOGIN REPLICATION;
\password chesstree_replica
\q
```

Первый пароль далее называется `<DATABASE_PASSWORD>`, второй —
`<REPLICATION_PASSWORD>`.

В файл из `SHOW hba_file` добавьте до более общих `host`-правил:

```text
host    chesstree      chesstree            127.0.0.1/32    scram-sha-256
host    replication   chesstree_replica     10.77.0.2/32    scram-sha-256
```

Проверьте и перечитайте конфигурацию:

```shell
sudo -u postgres psql -x -c "SELECT line_number, type, database, user_name, address, auth_method, error FROM pg_hba_file_rules WHERE error IS NOT NULL"
sudo -u postgres psql -c 'SELECT pg_reload_conf()'
sudo ss -ltnp | grep 5432
sudo -u postgres psql -Atqc 'SHOW listen_addresses'
psql -h 127.0.0.1 -U chesstree -d chesstree -c 'SELECT 1'
```

Последняя команда должна запросить пароль и вывести `1`.

## 6. Инициализация standby

Эта операция заменяет данные PostgreSQL на `151.247.208.76`. Продолжайте только
если там нет единственной копии нужных данных. Узнайте путь:

```shell
sudo -u postgres psql -Atqc 'SHOW data_directory'
```

Для стандартной установки PostgreSQL 17 обычно:

```shell
CHESSTREE_PGDATA=/var/lib/postgresql/17/main
test -d "$CHESSTREE_PGDATA"
```

Это временная shell-переменная только для текущей операции. Создайте постоянный
файл пароля репликации:

```shell
sudo install -o postgres -g postgres -m 600 /dev/null /var/lib/postgresql/.pgpass
sudoedit /var/lib/postgresql/.pgpass
```

Его единственная строка:

```text
10.77.0.1:5432:replication:chesstree_replica:<REPLICATION_PASSWORD>
```

Проверьте, что PostgreSQL доступен через WireGuard:

```shell
pg_isready -h 10.77.0.1 -p 5432
```

В той же shell-сессии остановите PostgreSQL, сохраните прежний каталог и создайте
standby:

```shell
test -n "$CHESSTREE_PGDATA"
sudo systemctl stop postgresql
sudo mv "$CHESSTREE_PGDATA" "${CHESSTREE_PGDATA}.before-replica"
sudo install -d -o postgres -g postgres -m 700 "$CHESSTREE_PGDATA"
sudo -u postgres env PGPASSFILE=/var/lib/postgresql/.pgpass pg_basebackup \
  -d 'host=10.77.0.1 port=5432 user=chesstree_replica application_name=chesstree_replica_1 sslmode=disable' \
  -D "$CHESSTREE_PGDATA" \
  -R -X stream -C -S chesstree_replica_1 \
  --checkpoint=fast --progress
sudo -u postgres pg_verifybackup "$CHESSTREE_PGDATA"
```

`-R` создаёт `standby.signal`, `-C -S` — physical replication slot. Если команда
оборвалась после создания slot, сначала проверьте `pg_replication_slots` на
primary, не запускайте повторно вслепую.

Base backup переносит `listen_addresses` primary. Откройте:

```shell
sudoedit "$CHESSTREE_PGDATA/postgresql.auto.conf"
```

и последней строкой добавьте:

```text
listen_addresses = '127.0.0.1'
```

Запустите standby:

```shell
sudo systemctl start postgresql
sudo -u postgres psql -Atqc 'SELECT pg_is_in_recovery()'
sudo -u postgres psql -x -c "SELECT status, sender_host, sender_port, latest_end_lsn, latest_end_time FROM pg_stat_wal_receiver"
sudo ss -ltnp | grep 5432
```

Ожидаются `t`, receiver `streaming` и только `127.0.0.1:5432`. На primary:

```shell
sudo -u postgres psql -x -c "SELECT application_name, client_addr, state, sync_state, write_lag, flush_lag, replay_lag FROM pg_stat_replication"
sudo -u postgres psql -x -c "SELECT slot_name, active, wal_status, invalidation_reason, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained_wal FROM pg_replication_slots"
```

Ожидаются `state=streaming`, `sync_state=async`, активный
`chesstree_replica_1`. Контролируйте диск primary: отключённый standby удерживает
WAL; после лимита slot может инвалидироваться и потребовать нового base backup.

## 7. Постоянные env backend на primary

Backend читает шесть переменных. Их постоянное место —
`/etc/chesstree/server.env`; `.bashrc`/`.profile` для systemd не подходят:

```shell
sudo install -d -o root -g root -m 755 /etc/chesstree
sudo install -o root -g root -m 600 "$HOME/chesstree-setup/systemd/server.env.example" /etc/chesstree/server.env
sudoedit /etc/chesstree/server.env
```

Содержимое:

```dotenv
CHESSTREE_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/chesstree
CHESSTREE_DATABASE_USER=chesstree
CHESSTREE_DATABASE_PASSWORD=<DATABASE_PASSWORD>
CHESSTREE_PUBLIC_BASE_URL=https://chess-tree.online
CHESSTREE_CORS_HOSTS=chess-tree.online
PORT=8081
```

- `CHESSTREE_DATABASE_*` — JDBC к локальному primary;
- `CHESSTREE_PUBLIC_BASE_URL` — публичные игровые ссылки;
- `CHESSTREE_CORS_HOSTS` — browser allowlist без схемы;
- `PORT` — локальный Ktor, доступный только Nginx.

После изменения env нужен `sudo systemctl restart chesstree-server`.
`daemon-reload` нужен после изменения unit, но не после изменения только env.

## 8. systemd и приложение на primary

```shell
sudo apt install -y openjdk-17-jre-headless nginx
id chesstree >/dev/null 2>&1 || sudo useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin chesstree
sudo install -d -o root -g root -m 755 /opt/chesstree/releases
sudo install -o root -g root -m 644 "$HOME/chesstree-setup/systemd/chesstree-server.service" /etc/systemd/system/chesstree-server.service
sudo systemctl daemon-reload
sudo systemctl enable chesstree-server
```

Не запускайте service до появления `/opt/chesstree/current/server/bin/server`.
Unit читает `/etc/chesstree/server.env`, поэтому env сохраняются после reboot.

## 9. Nginx и HTTPS на primary

Файлы репозитория:

- `deploy/nginx/chesstree-bootstrap.conf` — временный HTTP для сертификата;
- `deploy/nginx/chesstree.conf` — итоговый HTTPS, Web, REST и WebSocket proxy.

Сохраните существующий конфиг и поставьте bootstrap:

```shell
sudo cp -a /etc/nginx/sites-available/chesstree /etc/nginx/sites-available/chesstree.before-tls
sudo install -d -o www-data -g www-data -m 755 /var/www/letsencrypt/.well-known/acme-challenge
sudo install -o root -g root -m 644 "$HOME/chesstree-setup/nginx/chesstree-bootstrap.conf" /etc/nginx/sites-available/chesstree
sudo ln -sfn /etc/nginx/sites-available/chesstree /etc/nginx/sites-enabled/chesstree
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx
curl -I http://chess-tree.online/
```

`503` на `/` в bootstrap допустим: ACME-каталог обслуживается отдельно. Установите
Certbot через snap и получите сертификат:

```shell
sudo apt install -y snapd
sudo snap install core
sudo snap refresh core
sudo snap install --classic certbot
sudo ln -sfn /snap/bin/certbot /usr/local/bin/certbot
sudo certbot certonly \
  --webroot \
  --webroot-path /var/www/letsencrypt \
  --domain chess-tree.online
```

Укажите настоящий email. Не добавляйте `www`, пока DNS для него не настроен.
Затем включите финальный конфиг:

```shell
sudo install -o root -g root -m 644 "$HOME/chesstree-setup/nginx/chesstree.conf" /etc/nginx/sites-available/chesstree
sudo nginx -t
sudo systemctl reload nginx
curl -I https://chess-tree.online/
```

Создайте hook `/etc/letsencrypt/renewal-hooks/deploy/reload-nginx`:

```shell
sudo install -d -o root -g root -m 755 /etc/letsencrypt/renewal-hooks/deploy
sudoedit /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
```

```sh
#!/bin/sh
set -eu
nginx -t
systemctl reload nginx
```

И установите права/проверьте renewal:

```shell
sudo chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
systemctl list-timers | grep -E 'certbot|snap.certbot'
sudo certbot renew --dry-run
```

Порт `80` оставьте открытым для redirect и HTTP-01 renewal. Финальный Nginx отдаёт
Web из `/opt/chesstree/current/web`, а `/api/` и `/health` проксирует на
`127.0.0.1:8081`, включая WebSocket Upgrade.

## 10. Сборка и первая выкладка

Скрипты запускаются на локальной машине из корня репозитория. Постоянный адрес
можно добавить в `~/.zshrc`:

```shell
export CHESSTREE_DEPLOY_HOST=elvis@51.250.31.56
export CHESSTREE_DEPLOY_SSH_PORT=2222
```

Примените: `source ~/.zshrc`. Deploy-скрипты также используют `2222` по умолчанию,
но явные переменные фиксируют настройки пользователя. Учётной записи `elvis`
нужен `sudo` для команд выкладки; по возможности ограничьте её sudo-разрешения
этими командами.

### Одноразовая настройка прав deploy

`restart.sh` работает без интерактивного терминала, поэтому обычный запрос пароля
`sudo` внутри него невозможен. Не выдавайте `elvis` полный `NOPASSWD: ALL`.
Установите root-owned helper и разрешите без пароля только его. Один раз войдите на
primary с терминалом:

```shell
ssh -t -p 2222 elvis@51.250.31.56
```

На сервере выполните; здесь `sudo` обычным образом один раз запросит пароль:

```shell
sudo install -o root -g root -m 755 \
  "$HOME/chesstree-setup/remote/chesstree-activate-release" \
  /usr/local/sbin/chesstree-activate-release
sudo visudo -cf "$HOME/chesstree-setup/sudoers/chesstree-deploy"
sudo install -o root -g root -m 440 \
  "$HOME/chesstree-setup/sudoers/chesstree-deploy" \
  /etc/sudoers.d/chesstree-deploy
sudo visudo -cf /etc/sudoers.d/chesstree-deploy
exit
```

Helper принимает только release ID заданного формата, использует фиксированные
`/home/elvis/chesstree-upload` и `/opt/chesstree`, проверяет Nginx и `/health`, а
при ошибке возвращает предыдущий symlink. Сам helper доступен для изменения только
root.

Полная выкладка:

```shell
./deploy/deploy.sh
```

Скрипт собирает Web и Ktor, запускает server checks, загружает release, атомарно
переключает `/opt/chesstree/current`, перезапускает backend, проверяет `/health` и
откатывает symlink при ошибке. Стадии доступны отдельно:

```shell
./deploy/build.sh
./deploy/upload.sh <release-id>
./deploy/restart.sh <release-id>
```

После первой выкладки на primary:

```shell
sudo systemctl status chesstree-server --no-pager
sudo journalctl -u chesstree-server -n 100 --no-pager
curl -fsS http://127.0.0.1:8081/health
curl -fsS https://chess-tree.online/health
```

Ожидается JSON со статусом `ok`. Старые releases оставляются для rollback;
удаляйте их только после проверки `readlink -f /opt/chesstree/current`.

Web-клиент использует origin открытой страницы, а Android и iOS используют общий
production URL `https://chess-tree.online` из shared Kotlin-кода. Для локальной
разработки мобильные клиенты пока также обращаются к production-серверу.

## 11. Ежедневный `pg_dump` на standby

Установите на standby подготовленные файлы:

```shell
sudo install -d -o postgres -g postgres -m 700 /var/backups/chesstree
sudo install -o root -g root -m 755 "$HOME/chesstree-setup/backup/chesstree-pg-backup" /usr/local/sbin/chesstree-pg-backup
sudo install -o root -g root -m 644 "$HOME/chesstree-setup/systemd/chesstree-pg-backup.service" /etc/systemd/system/chesstree-pg-backup.service
sudo install -o root -g root -m 644 "$HOME/chesstree-setup/systemd/chesstree-pg-backup.timer" /etc/systemd/system/chesstree-pg-backup.timer
sudo systemctl daemon-reload
sudo systemctl enable --now chesstree-pg-backup.timer
sudo systemctl start chesstree-pg-backup.service
sudo systemctl status chesstree-pg-backup.service --no-pager
sudo ls -lah /var/backups/chesstree
systemctl list-timers chesstree-pg-backup.timer
```

Проверка последнего набора:

```shell
latest_checksums="$(sudo find /var/backups/chesstree -name 'SHA256SUMS-*' -type f | sort | tail -n 1)"
sudo -u postgres sh -c "cd /var/backups/chesstree && sha256sum -c '$latest_checksums'"
latest_dump="$(sudo find /var/backups/chesstree -name 'chesstree-*.dump' -type f | sort | tail -n 1)"
sudo -u postgres pg_restore --list "$latest_dump" >/dev/null
```

Скрипт хранит 14 дней. Файл `globals-*.sql` содержит в том числе хеши паролей
ролей PostgreSQL, поэтому весь каталог бэкапа является секретным. Копируйте
зашифрованные дампы в object storage или на
третий сервер с отдельными credentials: копии только на двух текущих серверах не
защищают от потери обоих. Бэкап считается проверенным только после периодического
полного `pg_restore` в отдельный тестовый PostgreSQL.

## 12. Итоговая проверка

На primary:

```shell
sudo nginx -t
sudo systemctl is-active nginx postgresql wg-quick@wg0 chesstree-server
curl -fsS https://chess-tree.online/health
sudo -u postgres psql -x -c 'SELECT application_name, client_addr, state, sync_state FROM pg_stat_replication'
```

На standby:

```shell
sudo systemctl is-active postgresql wg-quick@wg0
sudo systemctl is-enabled chesstree-pg-backup.timer
sudo -u postgres psql -Atqc 'SELECT pg_is_in_recovery()'
sudo -u postgres psql -Atqc "SELECT now() - pg_last_xact_replay_timestamp()"
sudo ls -lah /var/backups/chesstree
```

С внешней машины:

```shell
curl -I http://chess-tree.online/
curl -I https://chess-tree.online/
curl -fsS https://chess-tree.online/health
openssl s_client -connect chess-tree.online:443 -servername chess-tree.online </dev/null
```

HTTP должен перенаправлять на HTTPS, сертификат должен быть выдан для домена,
`/health` должен отвечать `200`.

## 13. Аварийное переключение

Автоматического failover нет. Если primary окончательно потерян, сначала исключите
его возвращение в сеть как writer, затем на standby:

```shell
sudo -u postgres psql -c 'SELECT pg_promote(wait_seconds => 60)'
sudo -u postgres psql -Atqc 'SELECT pg_is_in_recovery()'
```

После promotion ожидается `f`. Затем нужно развернуть Ktor/Nginx на новом primary
или изменить инфраструктуру/DNS. Старый primary нельзя просто включать обратно:
возможен split-brain. Его пересоздают как standby либо корректно применяют
`pg_rewind`.

## Официальные источники

- [PostgreSQL для Ubuntu](https://www.postgresql.org/download/linux/ubuntu/)
- [PostgreSQL 17: streaming replication](https://www.postgresql.org/docs/17/warm-standby.html)
- [`pg_basebackup`](https://www.postgresql.org/docs/17/app-pgbasebackup.html)
- [`pg_verifybackup`](https://www.postgresql.org/docs/17/app-pgverifybackup.html)
- [`pg_hba.conf`](https://www.postgresql.org/docs/17/auth-pg-hba-conf.html)
- [`pg_dump`](https://www.postgresql.org/docs/17/app-pgdump.html)
- [Certbot для Nginx](https://certbot.eff.org/instructions?ws=nginx&os=snap)
- [Let's Encrypt HTTP-01](https://letsencrypt.org/docs/challenge-types/)
- [Nginx WebSocket proxying](https://nginx.org/en/docs/http/websocket.html)
- [Ubuntu UFW](https://documentation.ubuntu.com/server/how-to/security/firewalls/)
