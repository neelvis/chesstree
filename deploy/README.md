# ChessTree production deployment

This guide configures the current two-server topology:

| Role | Public address | Private WireGuard address |
| --- | --- | --- |
| Web, Nginx, Ktor, PostgreSQL primary | `51.250.31.56` | `10.77.0.1` |
| PostgreSQL standby only | `151.247.209.76` | `10.77.0.2` |

The instructions assume Ubuntu or Debian, PostgreSQL 17 on both hosts, systemd,
UFW, and a fresh standby whose existing PostgreSQL data can be replaced. Check the
actual PostgreSQL major version with `psql --version`; physical streaming
replication requires the same major version on both machines.

The standby is an asynchronous physical replica of the entire PostgreSQL cluster,
not only the `chesstree` database. It is not an automatic failover system. Because
Ktor exists only on the primary host, losing `51.250.31.56` still takes the
application offline until Ktor is deployed elsewhere and the standby is promoted.

## Required ports

Apply equivalent rules both in the hosting-provider security groups and in UFW.
Replace `ADMIN_CIDR` below with the fixed public address from which SSH is allowed;
do not enable UFW before an SSH rule is present.

Primary `51.250.31.56` inbound:

- TCP `22` from `ADMIN_CIDR` only;
- TCP `80` and `443` from the Internet;
- UDP `51820` from `151.247.209.76` only;
- TCP `5432` only over `wg0` from `10.77.0.2`;
- no public access to Ktor `8081` or PostgreSQL `5432`.

```shell
read -r -p 'Admin public CIDR (for example 198.51.100.10/32): ' ADMIN_CIDR
test -n "$ADMIN_CIDR"
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow proto tcp from "$ADMIN_CIDR" to any port 22
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow proto udp from 151.247.209.76 to any port 51820
sudo ufw allow in on wg0 proto tcp from 10.77.0.2 to 10.77.0.1 port 5432
sudo ufw enable
sudo ufw status verbose
```

Standby `151.247.209.76` inbound:

- TCP `22` from `ADMIN_CIDR` only;
- UDP `51820` from `51.250.31.56` only;
- no public inbound rule for PostgreSQL `5432`.

```shell
read -r -p 'Admin public CIDR (for example 198.51.100.10/32): ' ADMIN_CIDR
test -n "$ADMIN_CIDR"
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow proto tcp from "$ADMIN_CIDR" to any port 22
sudo ufw allow proto udp from 51.250.31.56 to any port 51820
sudo ufw enable
sudo ufw status verbose
```

Keep the existing SSH session open and confirm a second SSH connection succeeds
before closing it. This prevents a typo in `ADMIN_CIDR` from silently locking you
out.

From a third machine, verify that the database and Ktor ports are not public:

```shell
nmap -Pn -p 22,80,443,5432,8081 51.250.31.56
nmap -Pn -p 22,80,443,5432,8081 151.247.209.76
```

Only `80`/`443` should be generally reachable on the primary. SSH visibility
depends on the source address, and `5432`/`8081` should be filtered or closed on
both public addresses.

## WireGuard between the database hosts

Installing a private tunnel avoids exposing PostgreSQL on the public Internet.
Run on both servers:

```shell
sudo apt update
sudo apt install wireguard
sudo sh -c 'umask 077; wg genkey > /etc/wireguard/private.key; wg pubkey < /etc/wireguard/private.key > /etc/wireguard/public.key'
sudo cat /etc/wireguard/public.key
```

Exchange only the public keys. Never copy either `private.key` into this repository.

Create `/etc/wireguard/wg0.conf` on the primary:

```ini
[Interface]
Address = 10.77.0.1/30
ListenPort = 51820
PrivateKey = <PRIMARY_PRIVATE_KEY>

[Peer]
PublicKey = <STANDBY_PUBLIC_KEY>
AllowedIPs = 10.77.0.2/32
Endpoint = 151.247.209.76:51820
PersistentKeepalive = 25
```

Create `/etc/wireguard/wg0.conf` on the standby:

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

On both hosts:

```shell
sudo chmod 600 /etc/wireguard/wg0.conf /etc/wireguard/private.key
sudo systemctl enable --now wg-quick@wg0
sudo wg show
```

Verify from the standby:

```shell
ping -c 3 10.77.0.1
```

## PostgreSQL primary

First inspect the active configuration paths and confirm the server version:

```shell
sudo -u postgres psql -Atqc 'SHOW server_version'
sudo -u postgres psql -Atqc 'SHOW config_file'
sudo -u postgres psql -Atqc 'SHOW hba_file'
sudo -u postgres psql -Atqc 'SHOW data_directory'
```

Set replication parameters on `51.250.31.56`:

```shell
sudo -u postgres psql -v ON_ERROR_STOP=1 <<'SQL'
ALTER SYSTEM SET listen_addresses = '127.0.0.1,10.77.0.1';
ALTER SYSTEM SET wal_level = 'replica';
ALTER SYSTEM SET max_wal_senders = 5;
ALTER SYSTEM SET max_replication_slots = 5;
ALTER SYSTEM SET wal_keep_size = '1GB';
ALTER SYSTEM SET max_slot_wal_keep_size = '10GB';
ALTER SYSTEM SET password_encryption = 'scram-sha-256';
CREATE ROLE chesstree_replica WITH LOGIN REPLICATION;
SQL
sudo -u postgres psql -c 'SELECT pg_reload_conf()'
sudo -u postgres psql -Atqc 'SHOW password_encryption'
sudo -u postgres psql -c '\password chesstree_replica'
```

Confirm that the displayed password encryption is `scram-sha-256`. Use a generated,
unique password and store it in a password manager. In the
`pg_hba.conf` path printed above, add this line before broader host rules:

```text
host    replication    chesstree_replica    10.77.0.2/32    scram-sha-256
```

Restart PostgreSQL and verify that it listens only locally and through WireGuard:

```shell
sudo systemctl restart postgresql
sudo ss -ltnp | grep 5432
sudo -u postgres psql -Atqc 'SHOW listen_addresses'
```

From the standby, verify network reachability. An authentication error is expected
until its password file is installed, but a timeout or refusal indicates a network
or `listen_addresses` problem:

```shell
pg_isready -h 10.77.0.1 -p 5432
```

## Initialize the standby

This operation replaces the standby's PostgreSQL data directory. Confirm that
`151.247.209.76` contains no unique database data and take a backup before
continuing. The commands move the existing directory aside instead of deleting it.

On the standby, obtain its data directory while PostgreSQL is still running and
assign that exact value below:

```shell
sudo -u postgres psql -Atqc 'SHOW data_directory'
CHESSTREE_PGDATA=/var/lib/postgresql/17/main
```

Create the replication password file outside the repository:

```shell
sudo install -o postgres -g postgres -m 600 /dev/null /var/lib/postgresql/.pgpass
sudoedit /var/lib/postgresql/.pgpass
```

Its single line is:

```text
10.77.0.1:5432:replication:chesstree_replica:<REPLICATION_PASSWORD>
```

Then stop the standby, preserve its old data, and take the base backup. Run these
commands in the same shell where `CHESSTREE_PGDATA` was set:

```shell
sudo systemctl stop postgresql
sudo mv "$CHESSTREE_PGDATA" "${CHESSTREE_PGDATA}.before-replica"
sudo install -d -o postgres -g postgres -m 700 "$CHESSTREE_PGDATA"
sudo -u postgres env PGPASSFILE=/var/lib/postgresql/.pgpass pg_basebackup \
  -d 'host=10.77.0.1 port=5432 user=chesstree_replica application_name=chesstree_replica_1 sslmode=disable' \
  -D "$CHESSTREE_PGDATA" \
  -R \
  -X stream \
  -C \
  -S chesstree_replica_1 \
  --checkpoint=fast \
  --progress
sudo -u postgres pg_verifybackup "$CHESSTREE_PGDATA"
```

After verification, edit the generated configuration:

```shell
sudoedit "$CHESSTREE_PGDATA/postgresql.auto.conf"
```

Append this line; the base backup otherwise carries the primary host's
`listen_addresses` value onto the standby:

```text
listen_addresses = '127.0.0.1'
```

Then start the standby:

```shell
sudo systemctl start postgresql
```

`-R` creates `standby.signal` and records the primary connection; `-C -S` creates
and uses a physical replication slot. If the command fails after creating the slot,
inspect `pg_replication_slots` on the primary before retrying instead of creating a
second slot.

## Verify and monitor replication

On the primary:

```shell
sudo -u postgres psql -x -c "SELECT application_name, client_addr, state, sync_state, write_lag, flush_lag, replay_lag FROM pg_stat_replication"
sudo -u postgres psql -x -c "SELECT slot_name, active, wal_status, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained_wal FROM pg_replication_slots"
```

Expected values are `state = streaming`, `sync_state = async`, and an active
`chesstree_replica_1` slot.

On the standby:

```shell
sudo -u postgres psql -Atqc 'SELECT pg_is_in_recovery()'
sudo -u postgres psql -x -c "SELECT status, sender_host, sender_port, latest_end_lsn, latest_end_time FROM pg_stat_wal_receiver"
```

`pg_is_in_recovery()` must return `t`. Monitor disk usage on the primary: a physical
slot retains WAL while the standby is unavailable. `max_slot_wal_keep_size` limits
that growth, but exceeding the limit can invalidate the slot and require a new base
backup.

## Nginx and HTTPS on `51.250.31.56`

The application handles passwords and bearer tokens, so do not expose it over
plain HTTP. The repository contains a temporary ACME bootstrap configuration and
the final HTTPS configuration:

- `deploy/nginx/chesstree-bootstrap.conf`;
- `deploy/nginx/chesstree.conf`.

Install Nginx, create the ACME web root, and activate the bootstrap configuration:

The commands below assume the repository is checked out on the primary. If it is
not, first run `upload.sh` and use the corresponding files under
`~/chesstree-upload/<release-id>/infra` instead of the local `deploy/` paths.

```shell
sudo apt update
sudo apt install nginx
sudo install -d -o www-data -g www-data -m 755 /var/www/letsencrypt/.well-known/acme-challenge
sudo install -m 644 deploy/nginx/chesstree-bootstrap.conf /etc/nginx/sites-available/chesstree
sudo ln -sfn /etc/nginx/sites-available/chesstree /etc/nginx/sites-enabled/chesstree
sudo nginx -t
sudo systemctl reload nginx
```

Let's Encrypt IP certificates are short-lived and require current Certbot support.
Verify that Certbot is at least 5.4, then issue a staging certificate first:

```shell
certbot --version
sudo certbot certonly --staging \
  --preferred-profile shortlived \
  --webroot \
  --webroot-path /var/www/letsencrypt \
  --ip-address 51.250.31.56
```

After the staging attempt succeeds, repeat without `--staging`, install the final
configuration, and reload Nginx:

```shell
sudo certbot certonly \
  --preferred-profile shortlived \
  --webroot \
  --webroot-path /var/www/letsencrypt \
  --ip-address 51.250.31.56
sudo install -m 644 deploy/nginx/chesstree.conf /etc/nginx/sites-available/chesstree
sudo nginx -t
sudo systemctl reload nginx
sudo certbot renew --dry-run
```

Configure a Certbot deploy hook to run `systemctl reload nginx` after renewal and
verify that the Certbot timer is active:

```shell
sudo install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
sudoedit /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
sudo chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
systemctl list-timers | grep -E 'certbot|snap.certbot'
```

The hook contents are:

```sh
#!/bin/sh
set -eu
nginx -t
systemctl reload nginx
```

IP certificates currently last about six days, so unattended renewal is
mandatory.

Nginx serves the Web bundle from `/opt/chesstree/current/web`, proxies `/api/` and
`/health` to Ktor on `127.0.0.1:8081`, and forwards WebSocket upgrade headers.

Install the service account and systemd unit once on the primary:

```shell
sudo useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin chesstree
sudo install -d -m 755 /etc/chesstree /opt/chesstree/releases
sudo install -m 644 deploy/systemd/chesstree-server.service /etc/systemd/system/chesstree-server.service
sudo install -m 600 deploy/systemd/server.env.example /etc/chesstree/server.env
sudoedit /etc/chesstree/server.env
sudo systemctl daemon-reload
sudo systemctl enable chesstree-server
```

Replace the placeholder database password before starting the service. Its effective
environment should contain:

```shell
CHESSTREE_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/chesstree
CHESSTREE_DATABASE_USER=chesstree
CHESSTREE_DATABASE_PASSWORD=<DATABASE_PASSWORD>
CHESSTREE_PUBLIC_BASE_URL=https://51.250.31.56
CHESSTREE_CORS_HOSTS=51.250.31.56
PORT=8081
```

The firewall must continue blocking external TCP `8081`. The current Android and
iOS entry points still contain development API addresses and need a production URL
build configuration before release. A domain should replace the IP before enabling
Android App Links or iOS Universal Links.

## Build and deploy scripts

Run all deployment scripts from the development machine. They default to
`root@51.250.31.56`; override the destination without editing the scripts when
needed:

```shell
export CHESSTREE_DEPLOY_HOST=deploy@51.250.31.56
```

The scripts are split so each stage can also be run independently:

```shell
./deploy/build.sh
./deploy/upload.sh 20260913T120000Z-1ec8078
./deploy/restart.sh 20260913T120000Z-1ec8078
```

The release ID must have the shown UTC timestamp and Git-hash form. `upload.sh`
copies the Web bundle, Ktor distribution, Nginx files, and systemd templates into
`~/chesstree-upload/<release-id>` on the server. It does not install configuration
or secrets. `restart.sh` copies application artifacts to
`/opt/chesstree/releases/<release-id>`, atomically changes the `current` symlink,
restarts Ktor, checks `/health`, and rolls back the symlink if startup fails.

For the normal end-to-end path, use:

```shell
./deploy/deploy.sh
```

The legacy root command remains available and delegates to the same script:

```shell
./build-and-deploy.sh
```

The remote account needs SSH access, `rsync`, and passwordless `sudo` for the
specific deployment commands. Prefer a dedicated `deploy` account with restricted
sudo rules over long-term root SSH access. Releases are intentionally retained for
rollback; remove old release directories only after confirming which target the
`/opt/chesstree/current` symlink uses.

`build.sh` runs the production Web bundle, server checks, and server distribution
tasks before anything is uploaded.

## Recovery and promotion

Replication is not a backup: accidental deletes and corrupt writes are also
replicated. Keep independent, tested backups.

If the primary is irrecoverable, stop attempts to write to it and promote the
standby manually:

```shell
sudo -u postgres psql -c 'SELECT pg_promote(wait_seconds => 60)'
```

After promotion, deploy and point Ktor at the promoted database. Do not restart the
old primary as a writer; it must be rewound or recreated as a standby before it can
rejoin. Record a separate failover runbook before relying on this topology for high
availability.

## Primary references

- [PostgreSQL streaming replication and standby setup](https://www.postgresql.org/docs/17/warm-standby.html)
- [`pg_basebackup`](https://www.postgresql.org/docs/17/app-pgbasebackup.html)
- [`pg_hba.conf` authentication rules](https://www.postgresql.org/docs/17/auth-pg-hba-conf.html)
- [Nginx WebSocket proxying](https://nginx.org/en/docs/http/websocket.html)
- [Ubuntu UFW documentation](https://documentation.ubuntu.com/server/how-to/security/firewalls/)
- [Let's Encrypt IP-address certificates](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability/)
