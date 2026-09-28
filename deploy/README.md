# ChessTree Production Deployment

This guide describes the current two-server setup on Ubuntu/Debian:

| Role | Public address | WireGuard |
| --- | --- | --- |
| Nginx, Web, Ktor, PostgreSQL primary | `51.250.31.56` | `10.77.0.1` |
| PostgreSQL standby and logical backups | `151.247.208.76` | `10.77.0.2` |

SSH to the primary is available on TCP port `2222`; SSH to the standby remains
on TCP port `22`.

The public application URL is `https://chess-tree.online`. Its DNS `A` record
must point to `51.250.31.56`. Add an `AAAA` record only if IPv6 is actually
configured.

The examples use PostgreSQL 17. Both servers must use the same major version and
preferably the same minor version. The standby is asynchronous and does not
fail over automatically. Replication copies the entire PostgreSQL cluster, not
just the `chesstree` database, and it does not replace backups: accidental
deletions are replicated too. The guide therefore also configures a daily
`pg_dump`.

## File map

On the primary server:

| Path | Purpose |
| --- | --- |
| `/etc/nginx/sites-available/chesstree` | Active Nginx configuration |
| `/etc/letsencrypt/live/chess-tree.online/` | Certbot certificate and key |
| `/etc/chesstree/server.env` | Persistent Ktor environment and database password |
| `/etc/chesstree/secrets/firebase-service-account.json` | Firebase Admin credentials (`root:chesstree`, mode `640`) |
| `/etc/systemd/system/chesstree-server.service` | Ktor systemd unit |
| `/opt/chesstree/releases/<release-id>/` | Web and backend releases |
| `/opt/chesstree/current` | Symlink to the active release |
| `/etc/wireguard/wg0.conf` | Tunnel to the standby |

On the standby:

| Path | Purpose |
| --- | --- |
| `/etc/wireguard/wg0.conf` | Tunnel to the primary |
| `/var/lib/postgresql/.pgpass` | Replication password |
| `/var/lib/postgresql/17/main/` | Standby data; verify the exact path |
| `/usr/local/sbin/chesstree-pg-backup` | Logical backup script |
| `/var/backups/chesstree/` | Backups retained for 14 days |
| `/etc/systemd/system/chesstree-pg-backup.*` | Backup service and timer |

Never commit secrets to Git, put them in `deploy/`, or pass them as command-line
arguments. Replace every `<...>` placeholder below with a real value. For the
automated production deploy, place the local key at
`secrets/firebase-service-account.json`; keep that directory in `.gitignore`.
The script transfers it over SSH to staging, and activation installs it at the
path above with restricted permissions before removing the staging copy. Do not
include this file in build archives or public directories.

## 1. Preliminary checks

On your local machine:

```shell
dig +short A chess-tree.online
```

The expected result is `51.250.31.56`. Confirm SSH access to both servers and
that the account has `sudo` access. On both servers:

```shell
cat /etc/os-release
timedatectl status
sudo timedatectl set-ntp true
sudo apt update
sudo apt full-upgrade
sudo apt install -y ca-certificates curl rsync ufw wireguard openssl
sudo reboot
```

Reconnect after reboot and check `timedatectl` again.

### Copy infrastructure templates

The `deploy.sh` script uploads release artifacts later, but initial setup must
be completed before the first deploy. If the repository is not cloned on the
servers, run these commands from the repository root on your local machine. The
example uses `elvis` on the primary and `root` on the standby (replace `root` if
another SSH user is configured there):

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

From this point on, `$HOME/chesstree-setup/...` refers to these uploaded copies.

## 2. Firewall

Duplicate these rules in the provider's security group/firewall. Replace
`ADMIN_CIDR` with your permanent public IP followed by `/32`. Do not close the
current SSH session until you have verified that a second login works.

On the primary `51.250.31.56`:

```shell
read -r -p 'Your public IP with /32: ' ADMIN_CIDR
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

On the standby `151.247.208.76`:

```shell
read -r -p 'Your public IP with /32: ' ADMIN_CIDR
test -n "$ADMIN_CIDR"
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow proto tcp from "$ADMIN_CIDR" to any port 22
sudo ufw allow proto udp from 51.250.31.56 to any port 51820
sudo ufw enable
sudo ufw status verbose
```

Do not open public ports `5432` or `8081`. Check from a third machine:

```shell
nmap -Pn -p 2222,80,443,5432,8081 51.250.31.56
nmap -Pn -p 22,80,443,5432,8081 151.247.208.76
```

Ports `80` and `443` should be publicly available on the primary; availability
of `2222` depends on the checker's IP. On the standby, this rule applies to
port `22`. Ports `5432` and `8081` should be closed or filtered.

## 3. WireGuard

Generate a separate key pair on each server:

```shell
sudo sh -c 'umask 077; wg genkey > /etc/wireguard/private.key; wg pubkey < /etc/wireguard/private.key > /etc/wireguard/public.key'
sudo cat /etc/wireguard/public.key
```

Exchange only the public keys. On the primary, create
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

On the standby, create `/etc/wireguard/wg0.conf`:

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

On both servers:

```shell
sudo chmod 600 /etc/wireguard/wg0.conf /etc/wireguard/private.key
sudo systemctl enable --now wg-quick@wg0
sudo wg show
```

From the standby:

```shell
ping -c 3 10.77.0.1
```

## 4. PostgreSQL 17 on both servers

If PostgreSQL is already installed, first run `psql --version` and
`pg_lsclusters`; do not create a second cluster. For a new installation:

```shell
sudo apt install -y postgresql-common
sudo /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh
sudo apt update
sudo apt install -y postgresql-17 postgresql-client-17
psql --version
pg_lsclusters
```

The script that adds the official PGDG repository is interactive. The `psql`
client and server major versions must match on both hosts.

## 5. Primary PostgreSQL

Find the actual paths:

```shell
sudo -u postgres psql -Atqc 'SHOW server_version'
sudo -u postgres psql -Atqc 'SHOW config_file'
sudo -u postgres psql -Atqc 'SHOW hba_file'
sudo -u postgres psql -Atqc 'SHOW data_directory'
```

Configure localhost, WireGuard, SCRAM, and replication:

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

Generate two different passwords with `openssl rand -hex 32`, store them in a
password manager, and create the roles. The `\password` commands prompt for the
password twice without echoing it:

```shell
sudo -u postgres psql -v ON_ERROR_STOP=1
```

In `psql`:

```sql
CREATE ROLE chesstree WITH LOGIN;
\password chesstree
CREATE DATABASE chesstree OWNER chesstree;
CREATE ROLE chesstree_replica WITH LOGIN REPLICATION;
\password chesstree_replica
\q
```

The first password is referred to below as `<DATABASE_PASSWORD>`, and the
second as `<REPLICATION_PASSWORD>`.

In the file shown by `SHOW hba_file`, add these lines before more general
`host` rules:

```text
host    chesstree      chesstree            127.0.0.1/32    scram-sha-256
host    replication   chesstree_replica     10.77.0.2/32    scram-sha-256
```

Check and reload the configuration:

```shell
sudo -u postgres psql -x -c "SELECT line_number, type, database, user_name, address, auth_method, error FROM pg_hba_file_rules WHERE error IS NOT NULL"
sudo -u postgres psql -c 'SELECT pg_reload_conf()'
sudo ss -ltnp | grep 5432
sudo -u postgres psql -Atqc 'SHOW listen_addresses'
psql -h 127.0.0.1 -U chesstree -d chesstree -c 'SELECT 1'
```

The last command should prompt for the password and print `1`.

## 6. Initialize the standby

This operation replaces the PostgreSQL data on `151.247.208.76`. Continue only
if it does not contain the only copy of data you need. Find the data directory:

```shell
sudo -u postgres psql -Atqc 'SHOW data_directory'
```

For a standard PostgreSQL 17 installation, the path is usually:

```shell
CHESSTREE_PGDATA=/var/lib/postgresql/17/main
test -d "$CHESSTREE_PGDATA"
```

This is a temporary shell variable for the current operation only. Create a
persistent replication-password file:

```shell
sudo install -o postgres -g postgres -m 600 /dev/null /var/lib/postgresql/.pgpass
sudoedit /var/lib/postgresql/.pgpass
```

It should contain exactly this line:

```text
10.77.0.1:5432:replication:chesstree_replica:<REPLICATION_PASSWORD>
```

Check that PostgreSQL is reachable over WireGuard:

```shell
pg_isready -h 10.77.0.1 -p 5432
```

In the same shell session, stop PostgreSQL, preserve the old data directory,
and create the standby:

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

`-R` creates `standby.signal`; `-C -S` creates a physical replication slot. If
the command stops after creating the slot, first check `pg_replication_slots`
on the primary. Do not blindly rerun it.

The base backup copies the primary's `listen_addresses`. Open:

```shell
sudoedit "$CHESSTREE_PGDATA/postgresql.auto.conf"
```

and add this as the last line:

```text
listen_addresses = '127.0.0.1'
```

Start the standby:

```shell
sudo systemctl start postgresql
sudo -u postgres psql -Atqc 'SELECT pg_is_in_recovery()'
sudo -u postgres psql -x -c "SELECT status, sender_host, sender_port, latest_end_lsn, latest_end_time FROM pg_stat_wal_receiver"
sudo ss -ltnp | grep 5432
```

Expect `t`, a `streaming` receiver, and only `127.0.0.1:5432`. On the primary:

```shell
sudo -u postgres psql -x -c "SELECT application_name, client_addr, state, sync_state, write_lag, flush_lag, replay_lag FROM pg_stat_replication"
sudo -u postgres psql -x -c "SELECT slot_name, active, wal_status, invalidation_reason, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained_wal FROM pg_replication_slots"
```

Expect `state=streaming`, `sync_state=async`, and an active
`chesstree_replica_1`. Monitor disk space on the primary: a disconnected
standby retains WAL. Once the slot limit is reached, the slot may become
invalid and require a new base backup.

## 7. Persistent backend environment on the primary

The backend reads required settings and optional pool and trusted-proxy
settings from `/etc/chesstree/server.env`. `.bashrc` and `.profile` are not
used by systemd:

```shell
sudo install -d -o root -g root -m 755 /etc/chesstree
sudo install -o root -g root -m 600 "$HOME/chesstree-setup/systemd/server.env.example" /etc/chesstree/server.env
sudoedit /etc/chesstree/server.env
```

Contents:

```dotenv
CHESSTREE_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/chesstree
CHESSTREE_DATABASE_USER=chesstree
CHESSTREE_DATABASE_PASSWORD=<DATABASE_PASSWORD>
CHESSTREE_PUBLIC_BASE_URL=https://chess-tree.online
CHESSTREE_CORS_HOSTS=chess-tree.online
PORT=8081
CHESSTREE_DB_POOL_SIZE=10
CHESSTREE_TRUSTED_PROXY_ADDRESSES=127.0.0.1,::1
```

- `CHESSTREE_DATABASE_*` — JDBC connection to the local primary.
- `CHESSTREE_PUBLIC_BASE_URL` — public game links.
- `CHESSTREE_CORS_HOSTS` — browser allowlist without a scheme.
- `PORT` — local Ktor port, accessible only to Nginx.
- `CHESSTREE_DB_POOL_SIZE` — maximum JDBC pool connections per backend. The
  total across all instances must fit within PostgreSQL `max_connections`, with
  room for administration and dedicated `LISTEN` connections.
- `CHESSTREE_TRUSTED_PROXY_ADDRESSES` — direct peer IPs of proxies allowed to
  set `X-Forwarded-For`; loopback is the default for this Nginx setup.

Nginx overwrites `X-Forwarded-For` with the observed client address. Do not
expose the Ktor port publicly or add untrusted addresses here: forwarded headers
may be trusted only when they come from a controlled proxy.

After changing environment settings, run `sudo systemctl restart
chesstree-server`. `daemon-reload` is needed after changing the unit, but not
after changing only the environment file.

## 8. systemd and the application on the primary

```shell
sudo apt install -y openjdk-17-jre-headless nginx
id chesstree >/dev/null 2>&1 || sudo useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin chesstree
sudo install -d -o root -g root -m 755 /opt/chesstree/releases
sudo install -o root -g root -m 644 "$HOME/chesstree-setup/systemd/chesstree-server.service" /etc/systemd/system/chesstree-server.service
sudo systemctl daemon-reload
sudo systemctl enable chesstree-server
```

Do not start the service before `/opt/chesstree/current/server/bin/server`
exists. The unit reads `/etc/chesstree/server.env`, so the settings persist
across reboots.

## 9. Nginx and HTTPS on the primary

Repository files:

- `deploy/nginx/chesstree-bootstrap.conf` — temporary HTTP setup for the
  certificate.
- `deploy/nginx/chesstree.conf` — final HTTPS, Web, REST, and WebSocket proxy.

Back up the existing config and install the bootstrap config:

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

A `503` on `/` during bootstrap is expected; the ACME directory is served
separately. Install Certbot using snap and request a certificate:

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

Provide a real email address. Do not add `www` until DNS for it is configured.
Then install the final config:

```shell
sudo install -o root -g root -m 644 "$HOME/chesstree-setup/nginx/chesstree.conf" /etc/nginx/sites-available/chesstree
sudo nginx -t
sudo systemctl reload nginx
curl -I https://chess-tree.online/
```

Create the hook at `/etc/letsencrypt/renewal-hooks/deploy/reload-nginx`:

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

Set permissions and check renewal:

```shell
sudo chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
systemctl list-timers | grep -E 'certbot|snap.certbot'
sudo certbot renew --dry-run
```

Keep port `80` open for redirects and HTTP-01 renewal. The final Nginx config
serves Web from `/opt/chesstree/current/web` and proxies `/api/` and `/health`
to `127.0.0.1:8081`, including WebSocket upgrades.

## 10. Build and first deployment

Run the scripts locally from the repository root. To persist the address, add
this to `~/.zshrc`:

```shell
export CHESSTREE_DEPLOY_HOST=elvis@51.250.31.56
export CHESSTREE_DEPLOY_SSH_PORT=2222
```

Apply it with `source ~/.zshrc`. Deploy scripts also default to port `2222`, but
explicit variables make the settings clear. The `elvis` account needs `sudo`
for deployment commands; restrict its sudo permissions to those commands when
possible.

### One-time deploy permissions setup

`restart.sh` runs without an interactive terminal, so it cannot answer a normal
`sudo` password prompt. Do not grant `elvis` unrestricted `NOPASSWD: ALL`.
Install a root-owned helper and allow passwordless execution only for that
helper. First, open an interactive session to the primary:

```shell
ssh -t -p 2222 elvis@51.250.31.56
```

Run these commands on the server; `sudo` will prompt for the password once:

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

The helper accepts only release IDs in a specified format, uses fixed paths
`/home/elvis/chesstree-upload` and `/opt/chesstree`, installs the staged Nginx
configuration, checks Nginx and `/health`, and restores the previous release and
Nginx configuration on failure. Only root can modify the helper. The helper is
installed outside release directories and ordinary deployments do not replace it.
When its source changes, copy the updated helper to the server and repeat the
root-owned installation step before relying on the change.

Full deployment:

```shell
./deploy/deploy.sh
```

The script builds Web and Ktor, runs server checks, uploads the release,
atomically switches `/opt/chesstree/current`, installs the staged Nginx config,
restarts the backend, checks `/health`, reloads Nginx, and restores the symlink
and Nginx config if anything fails. Individual stages are also available:

```shell
./deploy/build.sh
./deploy/upload.sh <release-id>
./deploy/restart.sh <release-id>
```

After the first deployment on the primary:

```shell
sudo systemctl status chesstree-server --no-pager
sudo journalctl -u chesstree-server -n 100 --no-pager
curl -fsS http://127.0.0.1:8081/health
curl -fsS https://chess-tree.online/health
```

Expect JSON with status `ok`. Old releases are retained for rollback; remove
them only after checking `readlink -f /opt/chesstree/current`.

The Web client uses the origin of the open page. Android and iOS use the shared
production URL `https://chess-tree.online` from shared Kotlin code. During local
development, mobile clients also currently connect to the production server.

## 11. Daily `pg_dump` on the standby

Install the prepared files on the standby:

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

Check the latest backup set:

```shell
latest_checksums="$(sudo find /var/backups/chesstree -name 'SHA256SUMS-*' -type f | sort | tail -n 1)"
sudo -u postgres sh -c "cd /var/backups/chesstree && sha256sum -c '$latest_checksums'"
latest_dump="$(sudo find /var/backups/chesstree -name 'chesstree-*.dump' -type f | sort | tail -n 1)"
sudo -u postgres pg_restore --list "$latest_dump" >/dev/null
```

The script retains backups for 14 days. `globals-*.sql` also contains
PostgreSQL role password hashes, so the entire backup directory is sensitive.
Copy encrypted dumps to object storage or a third server with separate
credentials: copies on only the two current servers do not protect against
losing both. A backup is verified only after periodically running a full
`pg_restore` into a separate test PostgreSQL instance.

## 12. Final checks

On the primary:

```shell
sudo nginx -t
sudo systemctl is-active nginx postgresql wg-quick@wg0 chesstree-server
curl -fsS https://chess-tree.online/health
sudo -u postgres psql -x -c 'SELECT application_name, client_addr, state, sync_state FROM pg_stat_replication'
```

On the standby:

```shell
sudo systemctl is-active postgresql wg-quick@wg0
sudo systemctl is-enabled chesstree-pg-backup.timer
sudo -u postgres psql -Atqc 'SELECT pg_is_in_recovery()'
sudo -u postgres psql -Atqc "SELECT now() - pg_last_xact_replay_timestamp()"
sudo ls -lah /var/backups/chesstree
```

From an external machine:

```shell
curl -I http://chess-tree.online/
curl -I https://chess-tree.online/
curl -fsS https://chess-tree.online/health
openssl s_client -connect chess-tree.online:443 -servername chess-tree.online </dev/null
```

HTTP should redirect to HTTPS, the certificate should be issued for the domain,
and `/health` should return `200`.

## 13. Disaster recovery

There is no automatic failover. If the primary is permanently lost, first
prevent it from returning as a writer. Then promote the standby:

```shell
sudo -u postgres psql -c 'SELECT pg_promote(wait_seconds => 60)'
sudo -u postgres psql -Atqc 'SELECT pg_is_in_recovery()'
```

After promotion, expect `f`. Then deploy Ktor/Nginx on the new primary or change
the infrastructure/DNS. Do not simply restart the old primary; that can cause
split-brain. Rebuild it as a standby or apply `pg_rewind` correctly.

## Official references

- [PostgreSQL for Ubuntu](https://www.postgresql.org/download/linux/ubuntu/)
- [PostgreSQL 17: streaming replication](https://www.postgresql.org/docs/17/warm-standby.html)
- [`pg_basebackup`](https://www.postgresql.org/docs/17/app-pgbasebackup.html)
- [`pg_verifybackup`](https://www.postgresql.org/docs/17/app-pgverifybackup.html)
- [`pg_hba.conf`](https://www.postgresql.org/docs/17/auth-pg-hba-conf.html)
- [`pg_dump`](https://www.postgresql.org/docs/17/app-pgdump.html)
- [Certbot for Nginx](https://certbot.eff.org/instructions?ws=nginx&os=snap)
- [Let's Encrypt HTTP-01](https://letsencrypt.org/docs/challenge-types/)
- [Nginx WebSocket proxying](https://nginx.org/en/docs/http/websocket.html)
- [Ubuntu UFW](https://documentation.ubuntu.com/server/how-to/security/firewalls/)
