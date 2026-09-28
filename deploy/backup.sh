#!/usr/bin/env bash
# Dumps the PulseGuard database to ~/pulseguard-backups and keeps the last 14 days.
# Run by cron each night (see DEPLOY.md). Restore one with:
#   gunzip -c <file>.sql.gz | docker compose exec -T postgres psql -U pulseguard -d pulseguard
set -euo pipefail

cd "$(dirname "$0")"
dir="$HOME/pulseguard-backups"
mkdir -p "$dir"
file="$dir/pulseguard-$(date +%Y-%m-%d-%H%M).sql.gz"

# Write to a temp name first, so a failed dump never looks like a good backup.
trap 'rm -f "$file.partial"' EXIT
docker compose exec -T postgres pg_dump -U pulseguard -d pulseguard --clean --if-exists \
    | gzip > "$file.partial"
mv "$file.partial" "$file"

find "$dir" -name 'pulseguard-*.sql.gz' -mtime +14 -delete
echo "Backed up to $file"
