#!/usr/bin/env python3
"""Generates the Grafana dashboards in ../dashboards/ (provisioned read-only on the monitoring VM).

Edit this file, then run:   python3 monitoring/vm/grafana/generate_dashboards.py
and push it to the VM with:  monitoring/vm.sh deploy
(vm.sh deploy also runs this script, so the JSON files never get out of date.)
"""
import json
from pathlib import Path

OUT = Path(__file__).parent / "dashboards"
PROM = {"type": "prometheus", "uid": "prometheus"}
ES = {"type": "elasticsearch", "uid": "elasticsearch"}
APP = 'application=~"$application"'


class Layout:
    """Places panels left to right on Grafana's 24-column grid, wrapping to new rows."""

    def __init__(self):
        self.panels, self.x, self.y, self.row_h, self.next_id = [], 0, 0, 0, 1

    def row(self, title):
        self._newline()
        self.panels.append({"type": "row", "title": title, "id": self._id(), "collapsed": False,
                            "gridPos": {"x": 0, "y": self.y, "w": 24, "h": 1}, "panels": []})
        self.y += 1

    def add(self, panel, w, h):
        if self.x + w > 24:
            self._newline()
        panel["id"] = self._id()
        panel["gridPos"] = {"x": self.x, "y": self.y, "w": w, "h": h}
        self.panels.append(panel)
        self.x += w
        self.row_h = max(self.row_h, h)

    def _newline(self):
        self.y += self.row_h
        self.x, self.row_h = 0, 0

    def _id(self):
        self.next_id += 1
        return self.next_id - 1


def target(expr, legend="", ref="A", instant=False):
    return {"datasource": PROM, "expr": expr, "legendFormat": legend, "refId": ref, "instant": instant,
            "range": not instant}


def timeseries(title, targets, unit="short", description="", stack=False, min0=True):
    return {
        "type": "timeseries", "title": title, "description": description, "datasource": PROM,
        "targets": targets,
        "fieldConfig": {"defaults": {
            "unit": unit, "min": 0 if min0 else None,
            "custom": {"lineWidth": 1, "fillOpacity": 15 if stack else 8, "showPoints": "never",
                       "stacking": {"mode": "normal" if stack else "none"}},
        }, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"},
                    "tooltip": {"mode": "multi", "sort": "desc"}},
    }


def stat(title, expr, unit="short", description="", thresholds=None, color_mode="value", decimals=None):
    steps = thresholds or [{"color": "green", "value": None}]
    return {
        "type": "stat", "title": title, "description": description, "datasource": PROM,
        "targets": [target(expr, instant=True)],
        "fieldConfig": {"defaults": {"unit": unit, "decimals": decimals,
                                     "thresholds": {"mode": "absolute", "steps": steps}}, "overrides": []},
        "options": {"colorMode": color_mode, "graphMode": "none", "reduceOptions": {"calcs": ["lastNotNull"]},
                    "textMode": "auto"},
    }


def overview():
    L = Layout()

    L.row("Health")
    L.add(stat("Services up", 'sum(up{job="spring-batch"})', description="Spring Boot services Prometheus can scrape (of 4)",
               thresholds=[{"color": "red", "value": None}, {"color": "orange", "value": 1},
                           {"color": "green", "value": 4}]), 4, 4)
    L.add(stat("PostgreSQL", 'max(pg_up)', description="1 = postgres-exporter can query the database",
               thresholds=[{"color": "red", "value": None}, {"color": "green", "value": 1}]), 3, 4)
    L.add(stat("Requests / s", f'sum(rate(http_server_requests_seconds_count{{{APP},uri!~"/actuator.*"}}[$__rate_interval]))',
               unit="reqps", decimals=1), 4, 4)
    L.add(stat("5xx errors (5m)", f'sum(increase(http_server_requests_seconds_count{{{APP},status=~"5.."}}[5m])) or vector(0)',
               decimals=0, thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}]), 4, 4)
    L.add(stat("Users propagated (range)", 'sum(increase(propagation_users_total{result="success"}[$__range])) or vector(0)',
               decimals=0), 5, 4)
    L.add(stat("Propagation failures (range)", 'sum(increase(propagation_users_total{result="failed"}[$__range])) or vector(0)',
               decimals=0, thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 1}]), 4, 4)

    L.row("HTTP (Spring Boot services)")
    L.add(timeseries("Request rate by service", [target(
        f'sum by (application) (rate(http_server_requests_seconds_count{{{APP},uri!~"/actuator.*"}}[$__rate_interval]))',
        "{{application}}")], unit="reqps"), 8, 8)
    L.add(timeseries("p95 latency by service", [target(
        f'histogram_quantile(0.95, sum by (application, le) (rate(http_server_requests_seconds_bucket{{{APP},uri!~"/actuator.*"}}[$__rate_interval])))',
        "{{application}}")], unit="s"), 8, 8)
    L.add(timeseries("Errors by service and status", [target(
        f'sum by (application, status) (rate(http_server_requests_seconds_count{{{APP},status=~"[45].."}}[$__rate_interval]))',
        "{{application}} {{status}}")], unit="reqps",
        description="4xx (e.g. user not found during propagation) and 5xx responses"), 8, 8)
    L.add(timeseries("Busiest endpoints", [target(
        f'topk(8, sum by (application, method, uri) (rate(http_server_requests_seconds_count{{{APP},uri!~"/actuator.*"}}[$__rate_interval])))',
        "{{application}} {{method}} {{uri}}")], unit="reqps"), 12, 8)
    L.add(timeseries("batch-service → other services: p95 latency", [target(
        'histogram_quantile(0.95, sum by (client_name, method, le) (rate(http_client_requests_seconds_bucket{application="batch-service"}[$__rate_interval])))',
        "{{method}} {{client_name}}")], unit="s",
        description="Outbound calls made by the propagation job (RestClient)"), 12, 8)

    L.row("Propagation (Spring Batch)")
    L.add(timeseries("Users propagated / s", [
        target('sum by (result) (rate(propagation_users_total[$__rate_interval]))', "{{result}}")],
        unit="ops", stack=True), 8, 8)
    L.add(timeseries("Failures by hop", [
        target('sum by (stage) (increase(propagation_users_total{result="failed"}[$__rate_interval]))', "{{stage}}")],
        description="USER_LOOKUP / ORDER_CREATION / PAYMENT_CREATION"), 8, 8)
    L.add(timeseries("Running jobs", [
        target('sum by (spring_batch_job_active_name) (spring_batch_job_active_seconds_active_count)',
               "{{spring_batch_job_active_name}}")], description="Spring Batch long-task timer"), 8, 8)
    L.add(timeseries("Item processing time (avg, user → order → payment)", [target(
        'sum by (spring_batch_item_process_job_name) (rate(spring_batch_item_process_seconds_sum[$__rate_interval]))'
        ' / sum by (spring_batch_item_process_job_name) (rate(spring_batch_item_process_seconds_count[$__rate_interval]))',
        "{{spring_batch_item_process_job_name}}")], unit="s"), 8, 8)
    L.add(timeseries("Chunk write time (avg)", [target(
        'sum by (spring_batch_chunk_write_job_name) (rate(spring_batch_chunk_write_seconds_sum[$__rate_interval]))'
        ' / sum by (spring_batch_chunk_write_job_name) (rate(spring_batch_chunk_write_seconds_count[$__rate_interval]))',
        "{{spring_batch_chunk_write_job_name}}")], unit="s"), 8, 8)
    L.add(timeseries("Requests submitted and bulk selection", [
        target('sum by (type) (increase(propagation_requests_total[$__rate_interval]))', "{{type}} requests", "A"),
        target('sum(increase(propagation_bulk_selected_users_total[$__rate_interval]))', "users selected by bulk", "B")]),
        8, 8)

    L.row("Business")
    L.add(timeseries("Users added / s", [
        target('sum by (source) (rate(users_added_total[$__rate_interval]))', "{{source}}")], unit="ops",
        description="source=generator: POST /api/users/generate (virtual threads); source=api: POST /api/users"), 8, 8)
    L.add(timeseries("Orders placed / s", [
        target('sum by (status) (rate(orders_placed_total[$__rate_interval]))', "{{status}}")], unit="ops"), 8, 8)
    L.add(timeseries("Payments / s and amount", [
        target('sum by (method, status) (rate(payments_processed_total[$__rate_interval]))', "{{method}} {{status}}", "A"),
        target('sum(rate(payments_amount_sum[$__rate_interval]))', "amount (INR / s)", "B")], unit="short"), 8, 8)

    L.row("JVM and connection pools")
    L.add(timeseries("Heap used", [target(
        f'sum by (application) (jvm_memory_used_bytes{{{APP},area="heap"}})', "{{application}}")], unit="bytes"), 6, 8)
    L.add(timeseries("CPU", [target(f'max by (application) (process_cpu_usage{{{APP}}})', "{{application}}")],
                     unit="percentunit"), 6, 8)
    L.add(timeseries("Live threads (incl. virtual-thread carriers)", [target(
        f'max by (application) (jvm_threads_live_threads{{{APP}}})', "{{application}}")]), 6, 8)
    L.add(timeseries("DB connections: active / pending", [
        target(f'sum by (application) (hikaricp_connections_active{{{APP}}})', "{{application}} active", "A"),
        target(f'sum by (application) (hikaricp_connections_pending{{{APP}}})', "{{application}} pending", "B")],
        description="pending > 0 means threads are waiting for a connection"), 6, 8)

    L.row("PostgreSQL")
    L.add(timeseries("Database size", [target(
        'max by (datname) (pg_database_size_bytes{datname=~"userdb|orderdb|paymentdb|batchdb"})', "{{datname}}")],
        unit="bytes"), 8, 8)
    L.add(timeseries("Rows inserted / updated per second", [
        target('sum by (datname) (rate(pg_stat_database_tup_inserted{datname=~"userdb|orderdb|paymentdb|batchdb"}[$__rate_interval]))',
               "{{datname}} inserted", "A"),
        target('sum by (datname) (rate(pg_stat_database_tup_updated{datname=~"userdb|orderdb|paymentdb|batchdb"}[$__rate_interval]))',
               "{{datname}} updated", "B")], unit="ops"), 8, 8)
    L.add(timeseries("Connections and commits / s", [
        target('sum by (datname) (pg_stat_database_numbackends{datname=~"userdb|orderdb|paymentdb|batchdb"})',
               "{{datname}} connections", "A"),
        target('sum by (datname) (rate(pg_stat_database_xact_commit{datname=~"userdb|orderdb|paymentdb|batchdb"}[$__rate_interval]))',
               "{{datname}} commits/s", "B")]), 8, 8)

    L.row("Logs (Elasticsearch)")
    L.add({
        "type": "timeseries", "title": "Log lines by level", "datasource": ES,
        "targets": [{"datasource": ES, "refId": "A", "query": 'service.name:($service)',
                     "metrics": [{"id": "1", "type": "count"}],
                     "bucketAggs": [{"id": "2", "type": "terms", "field": "log.level",
                                     "settings": {"size": "10", "order": "desc", "orderBy": "_count", "min_doc_count": "1"}},
                                    {"id": "3", "type": "date_histogram", "field": "@timestamp",
                                     "settings": {"interval": "auto"}}],
                     "timeField": "@timestamp"}],
        "fieldConfig": {"defaults": {"custom": {"drawStyle": "bars", "fillOpacity": 60, "stacking": {"mode": "normal"}}},
                        "overrides": []},
    }, 24, 7)
    L.add({
        "type": "logs", "title": "Warnings and errors", "datasource": ES,
        "targets": [{"datasource": ES, "refId": "A", "query": 'service.name:($service) AND log.level:(WARN OR ERROR)',
                     "metrics": [{"id": "1", "type": "logs", "settings": {"limit": "200"}}], "bucketAggs": [],
                     "timeField": "@timestamp"}],
        "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending", "enableLogDetails": True},
    }, 24, 12)

    return {
        "uid": "spring-batch-overview",
        "title": "Spring Batch microservices",
        "tags": ["spring-batch"],
        "timezone": "browser",
        "refresh": "10s",
        "time": {"from": "now-1h", "to": "now"},
        "schemaVersion": 41,
        "templating": {"list": [
            {"name": "application", "label": "Service", "type": "query", "datasource": PROM,
             "query": {"query": 'label_values(http_server_requests_seconds_count, application)', "refId": "v"},
             "definition": 'label_values(http_server_requests_seconds_count, application)',
             "multi": True, "includeAll": True, "allValue": ".*", "refresh": 2,
             "current": {"text": "All", "value": "$__all"}},
            {"name": "service", "label": "Log service", "type": "custom",
             "query": "user-service,order-service,payment-service,batch-service,postgres,postgres-exporter",
             "multi": True, "includeAll": True, "allValue": "*", "current": {"text": "All", "value": "$__all"}},
        ]},
        "annotations": {"list": []},
        "panels": L.panels,
    }


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    path = OUT / "spring-batch-overview.json"
    path.write_text(json.dumps(overview(), indent=2) + "\n")
    print(f"wrote {path.relative_to(Path.cwd()) if path.is_relative_to(Path.cwd()) else path}")
