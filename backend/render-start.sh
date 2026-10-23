#!/bin/sh
set -eu
: "${DB_HOST:?DB_HOST is required}"
: "${DB_NAME:?DB_NAME is required}"
export DB_URL="jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}"
exec java -jar /app/app.jar
