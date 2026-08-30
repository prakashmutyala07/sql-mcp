#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

env_value() {
  local key="$1"
  sed -n "s/^${key}=//p" .env | tail -n 1
}

export ECOM_MSSQL_CONNECTION_STRING="${ECOM_MSSQL_CONNECTION_STRING:-$(env_value ECOM_MSSQL_CONNECTION_STRING)}"
DAB_MCP_BASE_URL="${DAB_MCP_BASE_URL:-$(env_value DAB_MCP_BASE_URL)}"

ASPNETCORE_URLS="${DAB_MCP_BASE_URL:-http://localhost:5001}" \
  dab start --config dab/ecommerce/dab-config.json --no-https-redirect
