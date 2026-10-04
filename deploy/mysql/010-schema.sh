#!/usr/bin/env bash
set -Eeuo pipefail

# The official MySQL entrypoint already creates MYSQL_DATABASE and its app user.
# Reuse the canonical schema without trying to CREATE DATABASE a second time.
test "${MYSQL_DATABASE}" = "netdisk"
sed \
  -e '/^[[:space:]]*create database netdisk;[[:space:]]*$/d' \
  -e '/^[[:space:]]*use netdisk;[[:space:]]*$/d' \
  /opt/netdisk-schema/database.sql \
  | MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql --protocol=socket --user=root \
      --default-character-set=utf8mb4 --database="${MYSQL_DATABASE}"
