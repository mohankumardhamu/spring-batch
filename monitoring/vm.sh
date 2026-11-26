#!/usr/bin/env bash
# Manages the monitoring VM (multipass): Elasticsearch, Kibana, Prometheus and Grafana.
#
#   monitoring/vm.sh create    create the VM and deploy the stack (first time, ~5-10 min)
#   monitoring/vm.sh deploy    push changes in monitoring/vm/ to the VM and apply them
#   monitoring/vm.sh status    VM state, container health and Prometheus targets
#   monitoring/vm.sh urls      URLs and logins
#   monitoring/vm.sh start | stop | shell
#   monitoring/vm.sh destroy   delete the VM and all its data
#
# Settings (environment): VM_NAME (spring-batch-monitoring), VM_CPUS (4), VM_MEMORY (6G), VM_DISK (30G),
# VM_IMAGE (24.04), ES_HEAP (1g), LOG_RETENTION_DAYS (7).
set -euo pipefail

VM_NAME=${VM_NAME:-spring-batch-monitoring}
HERE=$(cd "$(dirname "$0")" && pwd)
GEN=$HERE/.generated                  # git-ignored: passwords and the staged deploy
SECRETS=$GEN/secrets.env
APP_ENV=$GEN/app.env
REMOTE_DIR=/home/ubuntu/monitoring

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }
vm_exec() { multipass exec "$VM_NAME" -- "$@"; }
vm_ip() { multipass info "$VM_NAME" --format json | jq -r ".info[\"$VM_NAME\"].ipv4[0]"; }
vm_state() { multipass info "$VM_NAME" --format json 2>/dev/null | jq -r ".info[\"$VM_NAME\"].state" || echo "Absent"; }
# The app machine's address as seen from the VM: the gateway of the VM's multipass network
app_host() { vm_exec sh -c "ip route show default | awk '{print \$3; exit}'"; }

require() {
  command -v multipass >/dev/null || die "multipass is not installed (sudo snap install multipass)"
  command -v jq >/dev/null || die "jq is not installed"
}

ensure_secrets() {
  mkdir -p "$GEN"; chmod 700 "$GEN"
  [[ -f $SECRETS ]] && return
  log "Generating passwords in ${SECRETS#$PWD/}"
  rnd() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"; }
  umask 077
  cat >"$SECRETS" <<EOS
ELASTIC_PASSWORD=$(rnd 24)
KIBANA_SYSTEM_PASSWORD=$(rnd 24)
KIBANA_ENCRYPTION_KEY=$(rnd 48)
GRAFANA_ADMIN_PASSWORD=$(rnd 20)
ES_FILEBEAT_PASSWORD=$(rnd 24)
ES_GRAFANA_PASSWORD=$(rnd 24)
EOS
}

# Images that are already on this machine (e.g. from another project) are copied into the VM instead
# of being pulled: much faster when a registry is slow (docker.elastic.co managed ~370 KB/s here).
preload_images() {
  command -v docker >/dev/null || return 0
  local img
  for img in $(awk '$1 == "image:" {print $2}' "$HERE/vm/docker-compose.yml"); do
    vm_exec docker image inspect "$img" >/dev/null 2>&1 && continue
    docker image inspect "$img" >/dev/null 2>&1 || continue
    log "Copying image $img from this machine"
    docker save -o "$GEN/image.tar" "$img"
    multipass transfer "$GEN/image.tar" "$VM_NAME:/home/ubuntu/image.tar"
    vm_exec sh -c 'docker load -q -i /home/ubuntu/image.tar && rm -f /home/ubuntu/image.tar'
    rm -f "$GEN/image.tar"
  done
}

cmd_create() {
  require
  [[ $(vm_state) == Absent ]] || die "VM $VM_NAME already exists (use: $0 deploy)"
  log "Launching VM $VM_NAME (Ubuntu ${VM_IMAGE:-24.04}, ${VM_CPUS:-4} CPUs, ${VM_MEMORY:-6G} RAM, ${VM_DISK:-30G} disk)"
  multipass launch "${VM_IMAGE:-24.04}" --name "$VM_NAME" --cpus "${VM_CPUS:-4}" --memory "${VM_MEMORY:-6G}" \
    --disk "${VM_DISK:-30G}" --cloud-init "$HERE/cloud-init.yaml" --timeout 900
  log "Waiting for cloud-init (Docker install)"
  vm_exec cloud-init status --wait >/dev/null || true
  vm_exec docker version --format 'Docker {{.Server.Version}} on the VM' \
    || vm_exec sudo docker version --format 'Docker {{.Server.Version}} on the VM'
  cmd_deploy
}

cmd_deploy() {
  require
  [[ $(vm_state) == Running ]] || die "VM $VM_NAME is not running (state: $(vm_state)). Use: $0 create | start"
  ensure_secrets
  local ip host stage
  ip=$(vm_ip); host=$(app_host)
  log "VM $VM_NAME at $ip; it reaches the application at $host"

  log "Generating Grafana dashboards"
  python3 "$HERE/vm/grafana/generate_dashboards.py" >/dev/null

  log "Staging files"
  stage=$GEN/stage
  rm -rf "$stage"; mkdir -p "$stage"
  cp -r "$HERE/vm/." "$stage/"
  find "$stage" -name '__pycache__' -prune -exec rm -rf {} +
  sed "s/__APP_HOST__/$host/g" "$stage/prometheus/prometheus.yml.tmpl" >"$stage/prometheus/prometheus.yml"
  rm "$stage/prometheus/prometheus.yml.tmpl"
  { cat "$SECRETS"; echo "VM_IP=$ip"; echo "ES_HEAP=${ES_HEAP:-1g}"; echo "LOG_RETENTION_DAYS=${LOG_RETENTION_DAYS:-7}"; } \
    >"$stage/.env"

  log "Copying to $VM_NAME:$REMOTE_DIR"
  vm_exec rm -rf "$REMOTE_DIR.new"
  multipass transfer --recursive "$stage" "$VM_NAME:$REMOTE_DIR.new"
  # Sync into the existing directory rather than replacing it: running containers bind-mount
  # ~/monitoring/prometheus etc., and a replaced directory would leave them looking at the deleted one.
  vm_exec sh -c "mkdir -p $REMOTE_DIR && rsync -a --delete $REMOTE_DIR.new/ $REMOTE_DIR/ && rm -rf $REMOTE_DIR.new && chmod 600 $REMOTE_DIR/.env"

  preload_images
  log "Starting containers"
  vm_exec sh -c "cd $REMOTE_DIR && docker compose up -d --remove-orphans --quiet-pull"
  log "Configuring Elasticsearch and Kibana"
  vm_exec bash "$REMOTE_DIR/elastic/setup.sh"
  log "Reloading Prometheus configuration"
  vm_exec sh -c 'for i in $(seq 1 30); do
      code=$(curl -s -o /tmp/reload.out -w "%{http_code}" -X POST http://localhost:9090/-/reload)
      [ "$code" = 200 ] && exit 0
      sleep 2
    done
    echo "Prometheus did not accept the configuration: $(cat /tmp/reload.out)" >&2; exit 1' \
    || die "Prometheus reload failed (check: $0 shell, then docker compose -f ~/monitoring/docker-compose.yml logs prometheus)"
  # Grafana re-reads dashboards every 30 s; data sources and alert settings need a restart
  vm_exec sh -c "cd $REMOTE_DIR && docker compose restart grafana >/dev/null"

  log "Writing ${APP_ENV#$PWD/} for Filebeat next to the app"
  umask 077
  . "$SECRETS"
  printf 'ELASTICSEARCH_URL=http://%s:9200\nES_FILEBEAT_PASSWORD=%s\n' "$ip" "$ES_FILEBEAT_PASSWORD" >"$APP_ENV"
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx filebeat; then
    log "Recreating Filebeat so it picks up the VM address"
    (cd "$HERE/.." && docker compose --profile monitoring up -d --force-recreate filebeat >/dev/null)
  fi
  cmd_urls
  echo
  echo "Ship this project's metrics and logs to it with:"
  echo "  docker compose --profile monitoring up -d"
}

cmd_status() {
  require
  echo "VM $VM_NAME: $(vm_state)"
  [[ $(vm_state) == Running ]] || return 0
  vm_exec sh -c "cd $REMOTE_DIR 2>/dev/null && docker compose ps --format 'table {{.Name}}\t{{.Status}}'" || true
  echo
  echo "Prometheus targets:"
  vm_exec curl -s http://localhost:9090/api/v1/targets |
    jq -r '.data.activeTargets[] | "  \(.health | ascii_upcase)\t\(.labels.job)\t\(.scrapeUrl)\t\(.lastError)"'
}

cmd_urls() {
  local ip; ip=$(vm_ip)
  . "$SECRETS"
  cat <<EOU

Monitoring VM ($VM_NAME, $ip):
  Grafana        http://$ip:3000   admin / $GRAFANA_ADMIN_PASSWORD
  Kibana         http://$ip:5601   elastic / $ELASTIC_PASSWORD
  Prometheus     http://$ip:9090
  Elasticsearch  http://$ip:9200   elastic / $ELASTIC_PASSWORD
(passwords: ${SECRETS#$PWD/})
EOU
}

cmd_destroy() {
  require
  read -r -p "Delete VM $VM_NAME and all monitoring data (logs, metrics, dashboards state)? [y/N] " a
  [[ $a == [yY] ]] || exit 0
  multipass delete --purge "$VM_NAME"
  rm -f "$APP_ENV"; rm -rf "$GEN/stage"
  log "Deleted. Passwords kept in ${SECRETS#$PWD/}; delete the file too for new ones."
}

case "${1:-}" in
  create)  cmd_create ;;
  deploy)  cmd_deploy ;;
  status)  cmd_status ;;
  urls)    require; cmd_urls ;;
  start)   require; multipass start "$VM_NAME" ;;
  stop)    require; multipass stop "$VM_NAME" ;;
  shell)   require; multipass shell "$VM_NAME" ;;
  destroy) cmd_destroy ;;
  *) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
