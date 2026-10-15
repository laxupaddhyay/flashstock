# Benchmark results

No benchmark has been executed for this repository yet. Docker and k6 are unavailable in the implementation environment, so this file deliberately reports no throughput or latency values.

## Reproduction

1. Start dependencies with `docker compose up --build -d` and set secrets in local `.env`.
2. Install k6 and PowerShell 7. Start with a fresh product; the comparison runner creates one with the requested stock for each run.
3. Run both strategies with identical machine, database, arrival rate, duration, quantity, and stock:

```powershell
$env:DEV_PASSWORD = "<the local DEV_PASSWORD from .env>"
./load-tests/compare-strategies.ps1 -Strategy conditional -Password $env:DEV_PASSWORD -Rate 100 -Duration 2m -Stock 100
./load-tests/compare-strategies.ps1 -Strategy row-lock -Password $env:DEV_PASSWORD -Rate 100 -Duration 2m -Stock 100
```

The script recreates only the app container with the selected strategy, waits for readiness, creates a fresh benchmark product, and writes `load-tests/summary-conditional.json` or `summary-row-lock.json`. The per-run summary includes request rate, p50/p95/p99 duration, accepted reservations, stock rejections, and unexpected response rate. Prometheus/Grafana expose HTTP and Hikari connection-pool metrics while the test runs.

## Results record template

Before comparing, record CPU model/core count, RAM, OS, Docker resource limits, PostgreSQL image/settings, DB pool size, application version, k6 version, stock, selected products, request quantity distribution, arrival rate, duration, and repetitions. Report median of repeated p50/p95/p99/RPS runs and retain raw k6 summary files outside Git. Report lock waits and CPU/RAM from instrumentation if captured. Do not infer results from the 10,000-request correctness test.

| Strategy | Hardware/configuration | Rate/duration | RPS | p50 | p95 | p99 | Accepted/rejected | Result |
|---|---|---:|---:|---:|---:|---:|---:|---|
| Conditional SQL | Not measured | — | — | — | — | — | — | Pending |
| Row lock | Not measured | — | — | — | — | — | — | Pending |
