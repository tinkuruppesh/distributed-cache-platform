# How to run the project (VS Code, IntelliJ, Docker)

## 1. What to install

| Tool | Version | Needed for | Check |
|---|---|---|---|
| **JDK** | 21 (Temurin / Oracle / OpenJDK) | everything backend | `java -version` must say 21 |
| **Apache Maven** | 3.9+ | building / running from VS Code or terminal (IntelliJ has its own bundled Maven) | `mvn -v` |
| **Node.js** | 20 LTS or 22 | React dashboard | `node -v`, `npm -v` |
| **VS Code** | latest | editing / running | |
| VS Code extensions | *Extension Pack for Java*, *Spring Boot Extension Pack*, *ESLint*, *Docker* | Java + Spring support (VS Code will suggest them: they are listed in `.vscode/extensions.json`) | |
| **Git** | any | version control | |
| Docker Desktop | optional | the full 6-container stack with PostgreSQL, Prometheus, Grafana | `docker compose version` |
| k6 | optional | load testing (`k6/load-test.js`) | `k6 version` |

You said you already use **IntelliJ + Spring Boot**: that works too (section 4).
Make sure `JAVA_HOME` points to JDK 21 and `mvn -v` reports Java 21, otherwise Maven will build with the wrong Java.

## 2. Run in VS Code (no Docker, no PostgreSQL needed)

The **dev profile** uses an in-memory H2 database and starts **six cache nodes inside the backend process**
(six real TCP servers on ports 7001-7006, each with its own memory store).

```bash
# 1. unzip, then open the folder:  File > Open Folder > distributed-cache-platform
# 2. open a terminal in VS Code (Ctrl+`) and build everything once
mvn -DskipTests install

# 3. start the backend (terminal 1)  ->  http://localhost:8080
mvn -pl management-api spring-boot:run

# 4. start the dashboard (terminal 2)  ->  http://localhost:5173
cd frontend
npm install
npm run dev
```

Windows shortcut: `scripts\run-backend.ps1` and `scripts\run-frontend.ps1` (right-click > Run with PowerShell).
VS Code shortcut: **Terminal > Run Task** has "Backend: run" and "Frontend: dev server", and **Run and Debug** has
"Run Management API (dev ...)" so you can set breakpoints.

Open **http://localhost:5173** and sign in:

| Account | Password (dev defaults) | Can do |
|---|---|---|
| `admin` | `Admin@12345` | everything, including simulation, settings, audit |
| `operator` | `Operator@12345` | cache writes, maintenance, advisor actions |
| `viewer` | `Viewer@12345` | read-only (values are masked) |

Startup log lines to look for: `Started DistributedCacheApplication`, then `NODE_JOINED ... is ACTIVE` x6.
Backend health: http://localhost:8080/actuator/health  - Prometheus metrics: http://localhost:8080/actuator/prometheus

## 3. Five-minute demo script

1. **Overview** -> *Load sample data* then *Generate traffic*. Charts start moving (real requests).
2. **Cache Explorer** -> search `user:` -> click a key: primary owner and replica owners are shown.
3. **Hash Ring** -> click a node arc; click a key dot.
4. **Failure Simulation** (admin) -> pick a node -> *Start simulation* -> confirm. Watch: heartbeat timeout -> SUSPECTED -> FAILED -> replicas promoted -> traffic continues.
5. Press **Restart node** -> recovery and synchronization -> node ACTIVE.
6. **Recovery Analytics** -> open the report: detection time, failover time, recovery time, failed requests, consistency check (all measured).
7. **Optimization Advisor** -> *Run analysis now* (needs some traffic first; rules ignore tiny samples on purpose).
8. (Docker only) **Grafana** at http://localhost:3001.

## 4. Run in IntelliJ IDEA

1. *File > Open* -> select the root `pom.xml` -> *Open as Project* (Maven imports both modules).
2. *Project Structure > SDK* = 21.
3. Run `DistributedCacheApplication` (module `management-api`). Active profile defaults to `dev`.
4. Use VS Code or the IntelliJ terminal for `cd frontend && npm install && npm run dev`.

## 5. Run everything with Docker (PostgreSQL + 6 separate node containers + Prometheus + Grafana)

```bash
cp .env.example .env          # then EDIT every password / secret in .env
docker compose up --build     # first build takes a few minutes
```

| URL | What |
|---|---|
| http://localhost:3000 | dashboard (nginx -> proxies /api to the backend) |
| http://localhost:8080 | REST API, `/actuator/prometheus` |
| http://localhost:9090 | Prometheus |
| http://localhost:3001 | Grafana (user/password from `.env`), dashboard "Distributed Cache Platform" |
| 127.0.0.1:5432 | PostgreSQL (localhost only) |

The six cache nodes (`cache-node-1..6`) listen on port 7001 **inside** the Docker network only.
Stop with `Ctrl+C`; `docker compose down -v` also deletes the volumes.

## 6. Tests

```bash
mvn test                      # cache-core (unit + 6-node integration tests) and management-api tests
mvn -pl cache-core test       # only the engine tests (about 1-2 minutes: they use real sockets and real timeouts)
```

## 7. Load test (k6)

```bash
k6 run -e VUS=100  -e DURATION=60s -e ADMIN_PASSWORD='<your admin password>' k6/load-test.js
k6 run -e VUS=500  ...
k6 run -e VUS=1000 ...
```
Compare: healthy cluster, one node failed (use the Failure Simulation page while k6 runs), during recovery, different
replication factors (Settings page). Write down only what you measured.

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| `release version 21 not supported` / `invalid target release` | Maven is using an older JDK: set `JAVA_HOME` to JDK 21 |
| `Address already in use` (7001-7006 / 8080 / 5173) | stop the other process, or change `cluster.embedded-base-port` / `server.port` |
| Login returns 401 | use the dev passwords above; with Docker use the values from `.env` |
| Dashboard shows "Network Error" | backend not running yet, or not on port 8080 (Vite proxies `/api` there) |
| `app.security.jwt-secret must be at least 32 characters` | set `JWT_SECRET` to 32+ characters |
| Compile error in `management-api` | see **PROGRESS.md**: that module could not be compiled where it was generated. Paste the first error here and it will be fixed |
| Failure Simulation says "disabled" | `SIMULATION_ENABLED=true` (default in dev) |
