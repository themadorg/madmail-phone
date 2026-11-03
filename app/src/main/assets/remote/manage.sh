#!/bin/sh
# Dedicated app-owned installation. Never adopt or overwrite another Madmail service.
set -eu
umask 077
base=/opt/madmail-mobile
data=/var/lib/madmail-mobile
conf=/etc/madmail-mobile
unit=madmail-mobile.service
operation=$1
shift
[ "$(id -u)" = 0 ] || { echo 'Administrator access is required'; exit 1; }
case "$operation" in
 install|start|stop|remove)
    command -v flock >/dev/null
    exec 9>/run/lock/madmail-mobile.lock
    flock -n 9 || { echo 'Another lifecycle operation is still running'; exit 1; }
    ;;
esac
case "$operation" in
 status)
    if [ ! -f "$base/managed-by-mobile" ]; then echo absent; exit 0; fi
    if systemctl is-active --quiet "$unit"; then echo running; else echo stopped; fi
    cat "$base/selected-version"
    ;;
 start|stop)
    [ -f "$base/managed-by-mobile" ]
    systemctl "$operation" "$unit"
    ;;
 token)
    [ -f "$base/managed-by-mobile" ]
    cat "$data/admin_token"
    ;;
 versions)
    [ -f "$base/managed-by-mobile" ]
    ls "$base/releases" | sed -n '/^[0-9][0-9.]*$/p'
    ;;
 remove)
    version=$1
    printf '%s' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'
    [ -f "$base/managed-by-mobile" ]
    [ "$version" != "$(cat "$base/selected-version")" ] || { echo 'Cannot remove the selected version'; exit 1; }
    rm -f "$base/releases/$version"
    ;;
 install)
    version=$1; digest=$2; upload=$3; ip=${4:-}
    printf '%s' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'
    printf '%s' "$digest" | grep -Eq '^[a-f0-9]{64}$'
    case "$upload" in /tmp/madmail-mobile-*) ;; *) exit 1 ;; esac
    [ -f "$upload" ] && [ ! -L "$upload" ]
    printf '%s  %s\n' "$digest" "$upload" | sha256sum -c - >/dev/null
    command -v systemctl >/dev/null
    command -v useradd >/dev/null
    if [ -e "$base" ] && [ ! -f "$base/managed-by-mobile" ]; then echo 'Unrecognized installation directory'; exit 1; fi
    if [ -f "$base/selected-version" ]; then
        old=$(cat "$base/selected-version")
        newest=$(printf '%s\n%s\n' "$old" "$version" | sort -V | tail -1)
        [ "$newest" = "$version" ] || { echo 'Downgrade requires restoring a matching backup'; exit 1; }
    else
        for service in madmail.service maddy.service; do
            if systemctl cat "$service" >/dev/null 2>&1; then echo 'Existing Madmail installation detected; migration is required'; exit 1; fi
        done
    fi
    if [ ! -f "$conf/chatmail.toml" ]; then
        # A new config takes the server's IPv4 address as its mail domain, never a .local name. Resolve it before changing anything.
        valid_ip() { printf '%s' "$1" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' && case "$1" in 127.*|0.*) false ;; esac; }
        valid_ip "$ip" || ip=$(ip -o -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p')
        valid_ip "$ip" || { echo 'Could not find an IPv4 address for this server'; exit 1; }
    fi
    mkdir -p "$base/releases" "$base/backups" "$conf"
    id madmail-mobile >/dev/null 2>&1 || useradd --system --home-dir "$data" --shell /usr/sbin/nologin madmail-mobile
    chmod 755 "$base" "$base/releases"
    chown root:madmail-mobile "$conf"
    chmod 750 "$conf"
    install -d -o madmail-mobile -g madmail-mobile -m 700 "$data"
    if [ -f "$base/managed-by-mobile" ]; then
        systemctl stop "$unit"
        # Cold snapshot before switching. A failed snapshot leaves the old version selected and stopped.
        backup="$base/backups/$(date +%s)-$(cat "$base/selected-version").tar"
        tar -cpf "$backup.partial" -C / var/lib/madmail-mobile etc/madmail-mobile
        mv "$backup.partial" "$backup"
    fi
    install -m 755 "$upload" "$base/releases/$version.partial"
    # Verify the root-owned copy, not only the uploaded file, before activating it.
    printf '%s  %s\n' "$digest" "$base/releases/$version.partial" | sha256sum -c - >/dev/null
    mv "$base/releases/$version.partial" "$base/releases/$version"
    if [ ! -f "$conf/chatmail.toml" ]; then
        cat > "$conf/chatmail.toml" <<CONFIG
hostname = "$ip"
primary_domain = "[$ip]"
public_ip = "$ip"
tls_mode = "self_signed"
auth_auto_create = true
smtp_listen = "127.0.0.1:1125"
submission_tls_listen = "0.0.0.0:11465"
imap_tls_listen = "0.0.0.0:11993"
http_listen = "127.0.0.1:18080"
http_tls_listen = "0.0.0.0:18443"
turn_enable = false
CONFIG
        chown root:madmail-mobile "$conf/chatmail.toml"
        chmod 640 "$conf/chatmail.toml"
    fi
    cat > /etc/systemd/system/madmail-mobile.service <<'UNIT'
[Unit]
Description=Madmail managed by Madmail Mobile
After=network.target
[Service]
Type=simple
User=madmail-mobile
Group=madmail-mobile
ExecStart=/opt/madmail-mobile/current --config /etc/madmail-mobile/chatmail.toml --state-dir /var/lib/madmail-mobile run
WorkingDirectory=/var/lib/madmail-mobile
TimeoutStopSec=30
KillSignal=SIGTERM
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/madmail-mobile
[Install]
WantedBy=multi-user.target
UNIT
    rm -f "$base/current.next"
    ln -s "$base/releases/$version" "$base/current.next"
    mv -Tf "$base/current.next" "$base/current"
    printf '%s\n' "$version" > "$base/selected-version.next"
    mv -f "$base/selected-version.next" "$base/selected-version"
    touch "$base/managed-by-mobile"
    systemctl daemon-reload
    systemctl start "$unit"
    ;;
 *) exit 64 ;;
esac
