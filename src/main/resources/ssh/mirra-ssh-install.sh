#!/bin/bash
#
# Installs the restricted SSH login of Mirra (STEM plugin) on this server. Run as root; running it again updates
# the installation (new key, other level).
#
#   mirra-ssh-install.sh <login user> <level> <server name> <allowed from|-> <key type> <key data>
#
#   login user    account Mirra logs in with, created if missing (default "mirra")
#   level         read: look only (logs, status, docker ps/logs); ops: also restart services and containers,
#                 apt upgrade, reboot; full: sudo for everything
#   server name   name of this server in Mirra, marks Mirra's line in authorized_keys
#   allowed from  source addresses for sshd's from="..." option (e.g. "10.50.0.11" or "203.0.113.7,10.0.0.0/8"),
#                 "-" for any address
#
# What it installs:
#   /usr/local/bin/mirra-gate     forced command of Mirra's key: logs every command to syslog (tag mirra-gate),
#                                 refuses interactive logins and runs the command
#   /usr/local/sbin/mirra-keys    replaces or removes Mirra's key (key rotation from Mirra, through sudo)
#   /etc/mirra/login.conf         login user, level, server name, allowed source addresses
#   /etc/sudoers.d/mirra-<user>   what the login may run as root, by level
#   ~<user>/.ssh/authorized_keys  Mirra's key with from="...",restrict,command="/usr/local/bin/mirra-gate"; for a
#                                 login other than root, ~/.ssh and the file belong to root, so the login cannot
#                                 change its own restrictions. Other keys in the file are kept.
#
set -euo pipefail

LOGIN_USER=${1:?login user}
LEVEL=${2:?level}
NAME=${3:?server name}
FROM=${4:?allowed from}
KEY_TYPE=${5:?key type}
KEY_DATA=${6:?key data}

fail() { echo "mirra-ssh-install: $*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || fail "must run as root"
[[ "$LOGIN_USER" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || fail "invalid login user"
[[ "$LEVEL" =~ ^(read|ops|full)$ ]] || fail "level must be read, ops or full"
[[ "$NAME" =~ ^[a-z0-9][a-z0-9._-]{0,47}$ ]] || fail "invalid server name"
[[ "$FROM" == "-" || "$FROM" =~ ^[0-9A-Fa-f.:/,*?]+$ ]] || fail "invalid allowed from"
[[ "$KEY_TYPE" == "ssh-ed25519" ]] || fail "only ssh-ed25519 keys"
[[ "$KEY_DATA" =~ ^[A-Za-z0-9+/]+=*$ ]] || fail "invalid key data"
[ "$LOGIN_USER" != root ] || [ "$LEVEL" = full ] || fail "a root login is always level full; use another login user"
command -v sudo >/dev/null || fail "sudo is not installed"
command -v visudo >/dev/null || fail "visudo is not installed"

# --------------------------------------------------------------------------- login user
if ! id "$LOGIN_USER" >/dev/null 2>&1; then
  useradd --system --create-home --shell /bin/bash --comment "Mirra (STEM)" "$LOGIN_USER"
  echo "created user $LOGIN_USER"
fi
if [ "$LOGIN_USER" != root ]; then
  # read the system journal and /var/log without root
  for group in systemd-journal adm; do
    if getent group "$group" >/dev/null; then usermod -aG "$group" "$LOGIN_USER"; fi
  done
fi

# --------------------------------------------------------------------------- configuration
install -d -o root -g root -m 755 /etc/mirra
cat > /etc/mirra/login.conf.new <<EOF
LOGIN_USER=$LOGIN_USER
LEVEL=$LEVEL
NAME=$NAME
FROM=$FROM
EOF
chmod 644 /etc/mirra/login.conf.new
mv /etc/mirra/login.conf.new /etc/mirra/login.conf

# --------------------------------------------------------------------------- gate (forced command)
cat > /usr/local/bin/mirra-gate.new <<'GATE'
#!/bin/bash
# Forced command of Mirra's SSH key (installed by mirra-ssh-install.sh). Logs the command and runs it. What the
# login may do as root is set by /etc/sudoers.d/mirra-<user>.
COMMAND=${SSH_ORIGINAL_COMMAND:-}
logger -t mirra-gate -p auth.info -- "from ${SSH_CLIENT%% *}: ${COMMAND:-<interactive>}" 2>/dev/null || true
case "$COMMAND" in
  "") echo "mirra-gate: interactive logins are not allowed" >&2; exit 1 ;;
  __mirra_info__) cat /etc/mirra/login.conf; exit 0 ;;
  __mirra_rotate__) exec sudo -n /usr/local/sbin/mirra-keys rotate ;;
  __mirra_revoke__) exec sudo -n /usr/local/sbin/mirra-keys revoke ;;
esac
exec /bin/bash -c "$COMMAND"
GATE
chown root:root /usr/local/bin/mirra-gate.new
chmod 755 /usr/local/bin/mirra-gate.new
mv /usr/local/bin/mirra-gate.new /usr/local/bin/mirra-gate

# --------------------------------------------------------------------------- key helper
cat > /usr/local/sbin/mirra-keys.new <<'KEYS'
#!/bin/bash
# Writes or removes Mirra's key in authorized_keys of the Mirra login (installed by mirra-ssh-install.sh).
#   mirra-keys install <type> <data>   (root only, used by the installer)
#   mirra-keys rotate                  new public key on stdin; restrictions stay as installed
#   mirra-keys revoke                  removes Mirra's key
set -euo pipefail
fail() { echo "mirra-keys: $*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || fail "must run as root"
# shellcheck disable=SC1091
. /etc/mirra/login.conf
HOME_DIR=$(getent passwd "$LOGIN_USER" | cut -d: -f6)
[ -n "$HOME_DIR" ] || fail "no home of $LOGIN_USER"
FILE=$HOME_DIR/.ssh/authorized_keys
MARK="mirra-key:$NAME"

write() {
  if [ "$LOGIN_USER" = root ]; then
    [ -d "$HOME_DIR/.ssh" ] || install -d -o root -g root -m 700 "$HOME_DIR/.ssh"
    MODE=600
  else
    # the directory and the file belong to root: the login cannot replace its own restricted key
    install -d -o root -g root -m 755 "$HOME_DIR/.ssh"
    chown root:root "$HOME_DIR/.ssh"
    chmod 755 "$HOME_DIR/.ssh"
    MODE=644
  fi
  touch "$FILE"
  {
    awk -v mark="$MARK" '$NF != mark' "$FILE"
    if [ $# -eq 2 ]; then
      OPTIONS='restrict,command="/usr/local/bin/mirra-gate"'
      [ "$FROM" = "-" ] || OPTIONS="from=\"$FROM\",$OPTIONS"
      echo "$OPTIONS $1 $2 $MARK"
    fi
  } > "$FILE.new"
  chown root:root "$FILE.new"
  chmod "$MODE" "$FILE.new"
  mv "$FILE.new" "$FILE"
}

valid() {
  [[ "$1" == "ssh-ed25519" && "$2" =~ ^[A-Za-z0-9+/]+=*$ ]]
}

case "${1:-}" in
  install)
    # not in sudoers of the login: only root (the installer) gets here
    valid "${2:-}" "${3:-}" || fail "invalid key"
    write "$2" "$3" ;;
  rotate)
    read -r TYPE DATA _ || true
    valid "${TYPE:-}" "${DATA:-}" || fail "invalid key"
    write "$TYPE" "$DATA"
    logger -t mirra-keys -p auth.notice -- "key of $NAME rotated" 2>/dev/null || true
    echo "rotated" ;;
  revoke)
    write
    logger -t mirra-keys -p auth.notice -- "key of $NAME revoked" 2>/dev/null || true
    echo "revoked" ;;
  *) fail "usage: mirra-keys install|rotate|revoke" ;;
esac
KEYS
chown root:root /usr/local/sbin/mirra-keys.new
chmod 755 /usr/local/sbin/mirra-keys.new
mv /usr/local/sbin/mirra-keys.new /usr/local/sbin/mirra-keys

# --------------------------------------------------------------------------- sudoers by level
SUDOERS=/etc/sudoers.d/mirra-$LOGIN_USER
SYSTEMCTL=$(command -v systemctl || true)
DOCKER=$(command -v docker || true)
APT=$(command -v apt-get || true)
{
  echo "# Mirra (STEM), level $LEVEL - written by mirra-ssh-install.sh, changes are overwritten"
  echo "Defaults:$LOGIN_USER !requiretty"
  echo "$LOGIN_USER ALL=(root) NOPASSWD: /usr/local/sbin/mirra-keys rotate, /usr/local/sbin/mirra-keys revoke"
  case "$LEVEL" in
    full)
      echo "$LOGIN_USER ALL=(ALL:ALL) NOPASSWD: ALL" ;;
    read|ops)
      READ=()
      [ -n "$SYSTEMCTL" ] && READ+=("$SYSTEMCTL status *" "$SYSTEMCTL is-active *" "$SYSTEMCTL list-units *")
      [ -n "$DOCKER" ] && READ+=("$DOCKER ps" "$DOCKER ps *" "$DOCKER logs *" "$DOCKER stats --no-stream" \
                                  "$DOCKER stats --no-stream *" "$DOCKER inspect *" "$DOCKER images")
      if [ ${#READ[@]} -gt 0 ]; then
        (IFS=,; echo "$LOGIN_USER ALL=(root) NOPASSWD: ${READ[*]}" | sed 's/,/, /g')
      fi
      if [ "$LEVEL" = ops ]; then
        OPS=()
        [ -n "$SYSTEMCTL" ] && OPS+=("$SYSTEMCTL start *" "$SYSTEMCTL stop *" "$SYSTEMCTL restart *" \
                                      "$SYSTEMCTL reload *" "$SYSTEMCTL reboot")
        [ -n "$DOCKER" ] && OPS+=("$DOCKER start *" "$DOCKER stop *" "$DOCKER restart *")
        [ -n "$APT" ] && OPS+=("$APT update" "$APT -y upgrade" "$APT -y full-upgrade" "$APT -y autoremove")
        if [ ${#OPS[@]} -gt 0 ]; then
          (IFS=,; echo "$LOGIN_USER ALL=(root) NOPASSWD: ${OPS[*]}" | sed 's/,/, /g')
        fi
      fi ;;
  esac
} > "$SUDOERS.new"
chmod 440 "$SUDOERS.new"
visudo -cqf "$SUDOERS.new" || { rm -f "$SUDOERS.new"; fail "generated sudoers file is invalid"; }
mv "$SUDOERS.new" "$SUDOERS"

# --------------------------------------------------------------------------- key
/usr/local/sbin/mirra-keys install "$KEY_TYPE" "$KEY_DATA"

echo "mirra-ssh-install: done (login $LOGIN_USER, level $LEVEL, server $NAME, from $FROM)"
