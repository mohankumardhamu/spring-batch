#!/usr/bin/env bash
# Configures Elasticsearch and Kibana for the spring-batch logs. Idempotent: vm.sh deploy runs it every time.
#   - kibana_system password (Kibana's own connection)
#   - spring-batch-filebeat : may only write to logs-spring-batch-*        (Filebeat next to the app)
#   - spring-batch-grafana  : may only read  logs-spring-batch-*        (Grafana's data source)
#   - retention: logs are deleted after LOG_RETENTION_DAYS (default 7); no replicas (single node)
#   - Kibana data view "Spring Batch logs" (logs-spring-batch-*), set as default
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

ES=http://localhost:9200
KB=http://localhost:5601
es() { # es METHOD PATH [JSON]
  local out code
  out=$(curl -sS -u "elastic:${ELASTIC_PASSWORD}" -X "$1" "$ES$2" -H 'Content-Type: application/json' \
        ${3:+-d "$3"} -w '\n%{http_code}')
  code=${out##*$'\n'}
  if [[ $code -ge 300 ]]; then echo "Elasticsearch $1 $2 failed ($code): ${out%$'\n'*}" >&2; exit 1; fi
}

echo "Waiting for Elasticsearch..."
until curl -sf -u "elastic:${ELASTIC_PASSWORD}" "$ES/_cluster/health?wait_for_status=yellow&timeout=5s" >/dev/null; do sleep 3; done

es POST /_security/user/kibana_system/_password "{\"password\":\"${KIBANA_SYSTEM_PASSWORD}\"}"

es PUT /_security/role/spring-batch-logs-writer '{
  "cluster": ["monitor", "read_ilm", "read_pipeline"],
  "indices": [{ "names": ["logs-spring-batch-*"], "privileges": ["create_doc", "auto_configure", "view_index_metadata"] }]
}'
es PUT /_security/user/spring-batch-filebeat "{
  \"password\": \"${ES_FILEBEAT_PASSWORD}\", \"roles\": [\"spring-batch-logs-writer\"],
  \"full_name\": \"Filebeat next to the spring-batch app\"
}"

es PUT /_security/role/spring-batch-logs-reader '{
  "cluster": ["monitor"],
  "indices": [{ "names": ["logs-spring-batch-*"], "privileges": ["read", "view_index_metadata"] }]
}'
es PUT /_security/user/spring-batch-grafana "{
  \"password\": \"${ES_GRAFANA_PASSWORD}\", \"roles\": [\"spring-batch-logs-reader\"],
  \"full_name\": \"Grafana Elasticsearch data source\"
}"

# Retention. The data stream uses the built-in "logs" index template, which includes the component
# template logs@custom; the policy set there replaces the built-in one (which never deletes).
es PUT /_ilm/policy/spring-batch-logs "{
  \"policy\": { \"phases\": {
    \"hot\":    { \"actions\": { \"rollover\": { \"max_age\": \"1d\", \"max_primary_shard_size\": \"10gb\" } } },
    \"delete\": { \"min_age\": \"${LOG_RETENTION_DAYS:-7}d\", \"actions\": { \"delete\": {} } }
  } }
}"
# Single node: a replica could never be allocated and the data stream would stay YELLOW.
es PUT /_component_template/logs@custom '{
  "template": { "settings": { "index.lifecycle.name": "spring-batch-logs", "index.number_of_replicas": 0 } },
  "_meta": { "description": "spring-batch: retention policy, no replicas (monitoring/vm/elastic/setup.sh)" }
}'
# A data stream that already exists keeps its old settings until it rolls over: apply them now too.
if curl -sf -u "elastic:${ELASTIC_PASSWORD}" "$ES/_data_stream/logs-spring-batch-default" >/dev/null; then
  es PUT /logs-spring-batch-default/_settings '{"index.lifecycle.name":"spring-batch-logs","index.number_of_replicas":0}'
fi
echo "Elasticsearch: users, roles and retention configured."

echo "Waiting for Kibana..."
until curl -s "$KB/api/status" | grep -q '"level":"available"'; do sleep 5; done
kb() {
  curl -sSf -u "elastic:${ELASTIC_PASSWORD}" -X "$1" "$KB$2" -H 'kbn-xsrf: true' \
       -H 'Content-Type: application/json' -d "$3" >/dev/null
}
kb POST /api/data_views/data_view '{
  "override": true,
  "data_view": { "id": "spring-batch-logs", "name": "Spring Batch logs", "title": "logs-spring-batch-*",
                 "timeFieldName": "@timestamp" }
}'
kb POST /api/data_views/default '{"data_view_id": "spring-batch-logs", "force": true}'
echo "Kibana: data view 'Spring Batch logs' configured."
