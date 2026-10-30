#!/bin/sh
# Adapted from infiniflow/ragflow v1.0.0-rc2 docker/kvrocks-entrypoint.sh (Apache-2.0).
set -eu
: "${REDIS_PASSWORD:?REDIS_PASSWORD must be configured}"
mkdir -p /var/lib/kvrocks
{
  echo "bind 0.0.0.0"
  echo "port 6379"
  echo "dir /var/lib/kvrocks"
  printf 'requirepass %s\n' "$REDIS_PASSWORD"
  echo "rocksdb.block_cache_size 64"
  echo "rocksdb.write_buffer_size 16"
} > /var/lib/kvrocks/kvrocks.conf
exec kvrocks -c /var/lib/kvrocks/kvrocks.conf --dir /var/lib/kvrocks
