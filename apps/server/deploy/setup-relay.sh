#!/usr/bin/env bash
set -euo pipefail

# Run on relay server (e.g. your-server-ip) as root to create restricted SSH relay user & keepalive settings
RELAY_USER="toolhub-relay"

if ! id -u "$RELAY_USER" >/dev/null 2>&1; then
  useradd -r -m -s /usr/sbin/nologin "$RELAY_USER"
fi

mkdir -p "/home/${RELAY_USER}/.ssh"
touch "/home/${RELAY_USER}/.ssh/authorized_keys"
chmod 700 "/home/${RELAY_USER}/.ssh"
chmod 600 "/home/${RELAY_USER}/.ssh/authorized_keys"
chown -R "${RELAY_USER}:${RELAY_USER}" "/home/${RELAY_USER}/.ssh"

cat >/etc/ssh/sshd_config.d/99-toolhub-relay.conf <<EOF
ClientAliveInterval 15
ClientAliveCountMax 3

Match User ${RELAY_USER}
    AllowTcpForwarding remote
    GatewayPorts no
    PermitTTY no
    X11Forwarding no
    AllowAgentForwarding no
EOF

sshd -t
systemctl reload ssh || systemctl reload sshd

echo "Done! Add each device's Ed25519 public key to /home/${RELAY_USER}/.ssh/authorized_keys"
