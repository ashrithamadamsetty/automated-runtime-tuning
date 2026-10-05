# Automated Runtime Performance Tuning

Automatic runtime tuning for a high-throughput Java server. The application continuously
measures its own performance and adjusts three runtime resources without a restart:

| Resource | Controlled property | Implementation |
|---|---|---|
| Rate limiter | permitted requests per second | Resilience4j |
| Application thread pool | Tomcat `maxThreads` | embedded Tomcat executor |
| Database connection pool | Hikari `maximumPoolSize` | HikariCP |

Each resource has an independent PID control loop guarded by a common safety pipeline.

**Technology:** Java 21, Spring Boot 3.5.5, Micrometer, Prometheus, Grafana, HikariCP,
Resilience4j, PostgreSQL, JUnit 5 + AssertJ + Mockito, k6.

---

## Quick start

```bash
# 1. PostgreSQL must be reachable on localhost:5432 with a database named "autotuning".

# 2. Run the application (shadow mode by default - nothing is changed automatically).
./gradlew bootRun

# 3. Inspect the tuning subsystem.
curl http://localhost:8080/tuning/status

# 4. Optional: start Prometheus and Grafana.
cd ops && docker compose up -d
# Prometheus  http://localhost:9090
# Grafana     http://localhost:3000   (admin / admin)
```

The application starts with `tuning.enabled=false`, so every loop runs in **shadow mode**:
the controllers advance and publish metrics, but no runtime resource is ever modified.

---

## How it works

```
                Traffic
                   |
            Rate limiter  <--- 429 when shedding
                   |
            Thread pool
                   |
            Application
                   |
             HikariCP
                   |
             Database
                   |
   Micrometer metrics ---> PID controller ---> Safety pipeline ---> Actuators
```

Every 10 seconds `ControlLoopScheduler` calls `ControlLoop.evaluateAll()`. For each loop:

1. **Measure** the controlled signal.
2. **PID** computes a demanded change using the *real* elapsed time since the last cycle.
3. **Safety pipeline** applies deadband, clamping, slew-rate limiting and cooldown.
4. **Apply** the value, but only if the master switch, the loop switch, the emergency stop
   and the backpressure rule all permit it.
5. **Publish** a `ControlDecision` to Micrometer regardless of whether a change was made.

Because step 5 always runs, shadow mode and live mode are observationally identical apart
from the final write. This is what makes shadow mode a trustworthy rehearsal.

### Control direction

The pool loops are **reverse acting**: adding capacity *reduces* utilisation.

| Loop | Signal | Target | Direction |
|---|---|---|---|
| `thread-pool` | % of max request threads in use | 70 | reverse (more threads → lower utilisation) |
| `database-pool` | % of max connections in use | 70 | reverse (more connections → lower utilisation) |
| `rate-limiter` | thread pool utilisation % | 70 | direct (more admitted traffic → higher utilisation) |

This matters. If a pool loop is measured on an *absolute* count rather than utilisation,
an idle server reads as "below target" and the pool grows to its maximum while doing no
work. Utilisation plus `reverse-acting=true` gives the required behaviour: shrink when
idle, grow under pressure.

### Thread blocking vs. downstream starvation

Adding request threads cannot increase throughput when those threads are blocked waiting
for a database connection — it only increases contention. `ControlLoop` therefore checks
`hikaricp.connections.pending` each cycle. When threads are waiting:

* the thread-pool loop is **held** (no growth), and
* the rate limiter is multiplied by `tuning.backpressure.shed-factor` to reduce inbound load.

The condition is published as `tuning_downstream_starved`.

---

## Safety

| Requirement | Implementation |
|---|---|
| Min/max resource limits | `SafetyManager.clamp`, plus a separate DBA ceiling for the DB pool |
| Integral windup | integral accumulator clamped to `integral-limit` in `PidController` |
| NaN / infinite values | `PidOutput.finite()` and `SafetyManager.isUsable`; controller self-resets on a fault |
| Max change per cycle | `SafetyManager.limitStep` (`max-step`) |
| Deadband | `SafetyManager.withinDeadband` (`deadband`) |
| Cooldown | per-loop timer in `TuningLoop` (`cooldown`) |
| Safe config on failure | all faults are caught; the resource keeps its last known-good value |
| Emergency off switch | `POST /tuning/emergency-stop` |
| Reset to defaults | `POST /tuning/reset` |
| Every change logged | `TuningLoop` logs target, actual, error, P, I, D and the transition |

The DBA ceiling is enforced independently of the PID limits: `DatabasePoolActuator` rejects
any value above `tuning.database-pool.dba-max-connections`, even a manual override.

---

## Endpoints

### Operations

| Method | Path | Description |
|---|---|---|
| `GET` | `/tuning/status` | Mode, config and last decision for every loop |
| `GET` | `/tuning/loops/{name}` | One loop: `thread-pool`, `database-pool`, `rate-limiter` |
| `POST` | `/tuning/evaluate` | Force an immediate tuning cycle |
| `POST` | `/tuning/reset` | Restore all configured defaults |
| `POST` | `/tuning/emergency-stop` | Stop all automatic tuning (optionally `?restoreDefaults=false`) |
| `POST` | `/tuning/emergency-stop/release` | Resume automatic tuning |

### Manual actuator control

| Method | Path | Description |
|---|---|---|
| `GET` / `POST` | `/tuning/thread-pool` | Read / set `maxThreads` (`?size=`) |
| `GET` / `POST` | `/tuning/database-pool` | Read / set `maximumPoolSize` (`?size=`) |
| `GET` / `POST` | `/tuning/rate-limiter` | Read / set requests per second (`?limit=`) |

### Application and observability

| Method | Path | Description |
|---|---|---|
| `GET` | `/hello?delayMillis=` | Sample endpoint |
| `GET` | `/work?baseMillis=` | Sample workload with jitter, for load tests |
| `GET` | `/db-health` | Verifies a database connection can be obtained |
| `GET` | `/runtime-metrics` | JVM snapshot (disable with `tuning.metrics.endpoint-enabled=false`) |
| `GET` | `/actuator/prometheus` | Prometheus scrape endpoint |

`/actuator/**` and `/tuning/**` are exempt from rate limiting so the system stays
observable and controllable while it is shedding load.

A change refused by a safety limit returns **400** with the reason, rather than an opaque
500 — a rejected input is not a server fault:

```json
{"error":"rejected","message":"Requested pool size 25 exceeds the DBA limit of 20"}
```

---

## Metrics

Per-loop series, tagged only by `loop` to keep cardinality fixed:

```
tuning_loop_target          tuning_loop_proportional     tuning_loop_requested
tuning_loop_actual          tuning_loop_integral         tuning_loop_applied
tuning_loop_error           tuning_loop_derivative       tuning_loop_at_min / at_max
tuning_loop_changes_total   tuning_loop_skipped_total    tuning_loop_faults_total
tuning_downstream_starved
```

Resource series:

```
apptuning_threadpool_max / active / size / queue / submitted / utilization
apptuning_dbpool_max / active / idle / pending / utilization / dba_limit
ratelimiter_limit / permissions_available / requests_permitted_total / requests_rejected_total
```

Standard `jvm_*`, `system_*`, `process_*`, `hikaricp_*` and `http_server_requests_*` series
are also exported. A provisioned Grafana dashboard covering all of these is in
`ops/grafana/dashboards/`.

---

## Configuration

All settings live under `tuning.*` in `src/main/resources/application.properties`.

```properties
tuning.enabled=false          # master switch; false = shadow mode for every loop
tuning.interval=PT10S         # tuning cycle period

tuning.thread-pool.enabled=false
tuning.thread-pool.reverse-acting=true
tuning.thread-pool.target=70  # % utilisation
tuning.thread-pool.kp=0.5
tuning.thread-pool.ki=0.05
tuning.thread-pool.kd=0.1
tuning.thread-pool.integral-limit=100
tuning.thread-pool.min=20
tuning.thread-pool.max=400
tuning.thread-pool.max-step=25
tuning.thread-pool.deadband=5
tuning.thread-pool.cooldown=PT30S
tuning.thread-pool.default-value=200

tuning.database-pool.dba-max-connections=20   # hard ceiling, never exceeded

tuning.backpressure.enabled=true
tuning.backpressure.pending-connection-threshold=1
tuning.backpressure.shed-factor=0.8
```

`tuning.database-pool.*` and `tuning.rate-limiter.*` take the same keys.

To disable the tuning subsystem entirely, set `tuning.enabled=false` and leave each
`*.enabled` flag false. The only remaining cost is one scheduled task per interval.

---

## Rollout procedure

Enable one loop at a time. The thread pool and connection pool interact, so enabling
both at once makes a misbehaving loop hard to attribute.

```
1. Run in shadow mode under production-like load.
   Compare tuning_loop_requested against tuning_loop_applied in Grafana.
   Confirm the recommendations look sane before trusting them.

2. tuning.enabled=true + tuning.thread-pool.enabled=true
   Watch tuning_loop_changes_total. Steady oscillation means kp is too high.

3. Add tuning.database-pool.enabled=true
   Verify apptuning_dbpool_max never exceeds the DBA limit.

4. Add tuning.rate-limiter.enabled=true
   Verify 429s appear only under genuine pressure.
```

At any point, `POST /tuning/emergency-stop` halts tuning and restores defaults.

### Tuning the gains

Start with proportional only (`ki=0`, `kd=0`) and raise `kp` until the loop responds
promptly without overshooting. Add a small `ki` to remove steady-state error, then a small
`kd` to damp. Keep `max-step` and `cooldown` conservative; they bound the damage a badly
tuned gain can do.

Observed behaviour with the shipped defaults (3 second interval, no cooldown):

```
idle          thread pool 200 -> 20    (min)       utilisation 0%
load applied  thread pool 20 -> 160                utilisation 100% -> 80%
settled       no further changes                   converged, no oscillation
load removed  thread pool 160 -> 85 -> ...         returning toward idle
```

---

## Load testing

```bash
k6 run loadtest/load-profile.js
k6 run -e BASE_URL=http://localhost:8080 -e WORK_MILLIS=200 loadtest/load-profile.js
```

The profile moves through baseline, ramp, sustained peak, sudden spike, and recovery so
each regime can be compared on the dashboard. HTTP 429 is treated as a valid outcome —
shedding load is the system working, not failing.

---

## Tests

```bash
./gradlew test
```

* `PidControllerTest` — proportional response, elapsed-time handling, anti-windup, NaN rejection, reset.
* `SafetyManagerTest` — clamping, slew rate, deadband, non-finite guards.
* `TuningLoopTest` — shadow vs live, step limiting, deadband, cooldown, min/max saturation, actuator and measurement faults, reset, control direction.
* `ControlLoopBackpressureTest` — thread pool held and traffic shed during downstream starvation, emergency stop, master switch.
* `TuningControllerErrorHandlingTest` — safety-limit rejections surface as 400 with a reason.

Only `AutomatedRuntimeTuningApplicationTests` needs a live PostgreSQL instance; the
control-logic tests run without a Spring context.

---

## Requirements coverage

| Section | Status |
|---|---|
| 3.1 Monitoring and telemetry | Complete |
| 3.2 PID controller | Complete — real `dt`, separate loops, configurable gains and output bounds |
| 3.3 Runtime tuning | Complete — all three resources change real runtime state |
| 4 Safety | Complete — see the safety table above |
| 5 Thread management | Complete for platform threads; virtual threads evaluated below |
| 6 Observability | Complete — all listed per-loop series exported |
| 7 Non-functional | Complete — one scheduled task, fixed-cardinality tags, fully disableable |
| 9 Phases 1–4 | Complete |

### Virtual threads (Section 5)

Java 21 virtual threads were evaluated and **not** adopted for this version:

* With `spring.threads.virtual.enabled=true` Tomcat uses an unbounded
  `VirtualThreadExecutor`. There is no `maximumPoolSize`, so the thread-pool control loop
  has nothing to actuate and its safety limits become meaningless.
* The bottleneck this system protects is the database connection pool, which virtual
  threads do not enlarge. Unbounded virtual threads would in fact *increase* the number of
  threads contending for the same connections — the exact failure the backpressure rule exists to prevent.
* Under virtual threads the natural control point shifts from thread count to a concurrency
  limiter plus the connection pool. The rate-limiter and database-pool loops would still
  apply unchanged; only the thread-pool loop would be retired.

Recommendation: keep platform threads while the connection pool is the constraint, and
revisit if the workload becomes predominantly non-blocking.

---

## Open questions

These remain unconfirmed and affect the defaults shipped here:

1. **Primary control target** — currently resource utilisation. If p99 latency is the real
   objective, the measured signal changes (the loop structure does not).
2. **Platform vs virtual threads** — assumed platform threads; see above.
3. **DBA maximum connections** — assumed 20 via `tuning.database-pool.dba-max-connections`.
4. **Staging environment** for load testing.
5. **Prometheus and Grafana availability** in the target environment.
6. **Target operating system.**
7. **Shadow mode or live** for the first production release.
8. **Single or multiple instances** — this is the most consequential. The rate limiter is
   **per JVM**. With *N* instances the effective admitted rate is *N* × the configured
   limit, and each instance tunes itself independently against a shared database. A
   multi-instance deployment needs either a distributed rate limiter or a per-instance
   limit derived from the cluster budget.

---

## Troubleshooting

**`Port 8080 was already in use`** — a previous instance is still running. Find and stop it:

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | Select-Object OwningProcess
Stop-Process -Id <pid> -Force
```

**`Unable to delete directory build\...` or `not a regular file`** — the project sits in a
OneDrive-synced folder and files have been turned into cloud placeholders. Delete the build
directory and retry:

```powershell
Remove-Item -Recurse -Force .\build
```

**No `hikaricp_*` metrics** — the pool initialises lazily. Call `/db-health` once to create
the first connection.
