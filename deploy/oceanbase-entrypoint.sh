#!/bin/bash
# Reconcile the application tenant explicitly: the pinned CE image can finish boot with only sys.
set -euo pipefail
/usr/sbin/sshd
# OBD persists startup parameters separately from ALTER SYSTEM; keep restarts consistent.
config=/root/.obd/cluster/obcluster/config.yaml
if [ -f "$config" ]; then
  sed -i "s/    memory_limit:.*/    memory_limit: ${OB_MEMORY_LIMIT}/" "$config"
fi
/root/boot/start.sh > >(tee /tmp/runtime-oceanbase-boot.log) 2>&1 &
boot_pid=$!
for attempt in $(seq 1 180); do
  # SQL becomes available before OBD releases its deployment lock.
  if grep -q 'boot success!' /tmp/runtime-oceanbase-boot.log &&
      obclient -h127.0.0.1 -P2881 -uroot@sys -N -e "SELECT 1" >/dev/null 2>&1; then break; fi
  kill -0 "$boot_pid" || exit 1
  sleep 2
done
obclient -h127.0.0.1 -P2881 -uroot@sys -e "ALTER SYSTEM SET memory_limit='$OB_MEMORY_LIMIT';"
if ! obclient -h127.0.0.1 -P2881 -uroot@sys -N -e "SELECT tenant_name FROM oceanbase.DBA_OB_TENANTS WHERE tenant_name='runtime'" | grep -q '^runtime$'; then
  obd cluster tenant create obcluster -n runtime --min-cpu=1 --max-cpu=2 --memory-size=2G --log-disk-size=2G --password "$OB_TENANT_PASSWORD"
fi
export MYSQL_PWD="$OB_TENANT_PASSWORD"
for attempt in $(seq 1 120); do
  if obclient -h127.0.0.1 -P2881 -uroot@runtime -e "SELECT 1" >/dev/null 2>&1; then break; fi
  sleep 2
done
obclient -h127.0.0.1 -P2881 -uroot@runtime -e "CREATE DATABASE IF NOT EXISTS agent_runtime; CREATE DATABASE IF NOT EXISTS ragflow_doc;"
for script in /root/boot/init.d/*.sql; do
  [ -f "$script" ] || continue
  obclient -h127.0.0.1 -P2881 -uroot@runtime -Dagent_runtime < "$script"
done
echo "Runtime OceanBase tenant and schema ready."
wait "$boot_pid"
