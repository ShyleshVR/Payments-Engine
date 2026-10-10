"""Server side of a load-test run: reads the k6 summary from the Job's log, queries Prometheus for
the same window, waits for the backlogs to drain, and writes one JSON result with a verdict.

Usage: python load-tests/collect.py <k6-log-file> <start-epoch> <end-epoch> <out-file> [prometheus-url]
"""
import json
import sys
import time
import urllib.parse
import urllib.request

LOG, START, END, OUT = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
PROM = sys.argv[5] if len(sys.argv) > 5 else "http://localhost:9090"

# The SLOs checked on the server side (the client-side ones are k6 thresholds).
SAGA_P95_SECONDS = 3.0
OUTBOX_AGE_SECONDS = 5.0
DRAIN_SECONDS = 60
LAG_DRAINED = 100


def query(expr, at=None):
    params = {"query": expr}
    if at is not None:
        params["time"] = str(at)
    url = PROM + "/api/v1/query?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(url, timeout=30) as r:
        return json.loads(r.read())["data"]["result"]


def scalar(expr, at=None):
    result = query(expr, at)
    return float(result[0]["value"][1]) if result else None


def by(expr, label, at=None):
    return {r["metric"].get(label, "?"): round(float(r["value"][1]), 3) for r in query(expr, at)}


def k6_summary():
    text = open(LOG, encoding="utf-8", errors="replace").read()
    if "===K6_SUMMARY_BEGIN===" not in text:
        return None
    return json.loads(text.split("===K6_SUMMARY_BEGIN===")[1].split("===K6_SUMMARY_END===")[0].strip())


window = f"{max(60, END - START)}s"
server = {
    "sagaP95Seconds": scalar(
        f'histogram_quantile(0.95, sum by (le) (increase(saga_duration_seconds_bucket{{type="PAYMENT",state="COMPLETED"}}[{window}])))', END),
    "sagaP99Seconds": scalar(
        f'histogram_quantile(0.99, sum by (le) (increase(saga_duration_seconds_bucket{{type="PAYMENT",state="COMPLETED"}}[{window}])))', END),
    "paymentsCompleted": scalar(
        f'sum(increase(saga_duration_seconds_count{{type="PAYMENT",state="COMPLETED"}}[{window}]))', END),
    "outboxOldestAgeMaxSeconds": by(
        f'max by (application) (max_over_time(outbox_oldest_pending_age_seconds[{window}]))', "application", END),
    "outboxPendingMax": by(
        f'max by (application) (max_over_time(outbox_pending[{window}]))', "application", END),
    "consumerLagMax": by(
        f'max by (consumergroup) (max_over_time(kafka_consumergroup_lag_sum[{window}]))', "consumergroup", END)
    or by(f'max by (consumergroup) (max_over_time(kafka_consumergroup_lag[{window}]))', "consumergroup", END),
    "cpuAvgByApp": by(
        f'avg by (application) (avg_over_time(process_cpu_usage[{window}]))', "application", END),
    # process_cpu_usage is a share of the node's CPUs (pods have no CPU limit): times the CPU
    # count gives cores, summed over the service's pods
    "cpuCoresAvgByApp": by(
        f'sum by (application) (avg_over_time((process_cpu_usage * system_cpu_count)[{window}:15s]))', "application", END),
    "cpuCoresMaxByApp": by(
        f'max by (application) (max_over_time(sum by (application) (process_cpu_usage * system_cpu_count)[{window}:15s]))', "application", END),
    "nodeCpuBusy": scalar(f'avg_over_time(max(system_cpu_usage)[{window}:15s])', END),
    "cpuMaxByApp": by(
        f'max by (application) (max_over_time(process_cpu_usage[{window}]))', "application", END),
    "hikariPendingMax": by(
        f'max by (application) (max_over_time(hikaricp_connections_pending[{window}]))', "application", END),
    "hikariActiveMax": by(
        f'max by (application) (max_over_time(hikaricp_connections_active[{window}]))', "application", END),
    "heapUsedMaxMb": {k: round(v / 1048576) for k, v in by(
        f'max by (application) (max_over_time(sum by (application, pod) (jvm_memory_used_bytes{{area="heap"}})[{window}:15s]))',
        "application", END).items()},
    "replicas": by(f'max_over_time(count by (job) (up == 1)[{window}:15s])', "job", END),
    "rateLimited": scalar(f'sum(increase(k6_http_reqs_total{{status="429"}}[{window}]))', END),
}

# How long after the run until the outboxes and consumer groups have caught up.
drain_started = time.time()
drained_after = None
while time.time() - drain_started < 180:
    lag = scalar('sum(kafka_consumergroup_lag)') or 0
    outbox = scalar('sum(max by (application) (outbox_pending))') or 0
    if lag < LAG_DRAINED and outbox < 10:
        drained_after = round(time.time() - drain_started)
        break
    time.sleep(5)
server["drainSeconds"] = drained_after

k6 = k6_summary()
failed = []
if k6 is None:
    failed.append("no k6 summary (the run did not finish)")
else:
    failed += [name for name, ok in k6["thresholds"].items() if not ok]
if server["sagaP95Seconds"] is not None and server["sagaP95Seconds"] > SAGA_P95_SECONDS:
    failed.append(f"saga p95 {server['sagaP95Seconds']:.2f}s > {SAGA_P95_SECONDS}s")
worst_age = max(server["outboxOldestAgeMaxSeconds"].values(), default=0)
if worst_age > OUTBOX_AGE_SECONDS:
    failed.append(f"outbox oldest pending {worst_age:.1f}s > {OUTBOX_AGE_SECONDS}s")
if drained_after is None or drained_after > DRAIN_SECONDS:
    failed.append(f"backlog not drained within {DRAIN_SECONDS}s" if drained_after is None
                  else f"backlog drained after {drained_after}s (> {DRAIN_SECONDS}s)")

result = {"start": START, "end": END, "passed": not failed, "failed": failed, "k6": k6, "server": server}
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(result, f, indent=2)

create = (k6 or {}).get("create") or {}
e2e = (k6 or {}).get("e2e") or {}
print(f"{'PASS' if not failed else 'FAIL'} {(k6 or {}).get('scenario')} rate={(k6 or {}).get('rate')}/s "
      f"achieved={round((k6 or {}).get('iterationRate') or 0, 1)}/s dropped={(k6 or {}).get('droppedIterations')} "
      f"create p95={round(create.get('p(95)') or 0)}ms p99={round(create.get('p(99)') or 0)}ms "
      f"e2e p95={round(e2e.get('p(95)') or 0)}ms saga p95={server['sagaP95Seconds']} "
      f"outbox age max={worst_age} drain={'never' if drained_after is None else f'{drained_after}s'}")
for reason in failed:
    print("  - " + reason)
