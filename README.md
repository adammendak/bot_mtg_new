# bot_mtg_new — Capital.com SDD-M15

Spring Boot 4 (Java 21) API plus the existing Angular `ui/bot` dashboard. One Heroku web dyno serves both. Capital.com is the default live broker behind a broker-agnostic SPI.

OAuth2 client remains on the classpath from the original app but is **unused**. `/api/**`, `/health`, `/actuator/health`, and the dashboard are `permitAll` so an unfinished login cannot block the bot.

## SDD-M15 (do not regress)

- Universe: GER40/DE40, XAU/GOLD, US100, EURUSD, BTC. Not STONKS, not US30.
- Capital.com candles. Local Heiken Ashi, Wilder RMA 33/133, daily PP with Warsaw 21:00 roll. BTC skips PP.
- Full stack on a newly closed M15: HA flip + M15 RMA with + H1 with (HA **or** RMA stacked). H4 is a regime note, not an AND filter. H1-supporting (close vs RMA33) is log-only.
- Stop 2.5× H1 Wilder ATR 14. 1R = 1× ATR. Hard 1R TP on one of two deals; runner keeps 2.5× stop and H1-trails. No 2R close, no break-even, no pyramid while the name is open.
- `EXECUTION_ENABLED=false` by default: scan + dashboard + webhooks only.
- DEMO ~1% (about 10 PLN on a small demo). Halt −30 / hard −50 vs Warsaw day-open P/L.
- LIVE only account name `bot trading konto` at 1%. Refuse equity ≥ 5000 (preferred later ~10k).
- Do not flatten TQQQ / CRCL / SPOT / SHOP. No Fintokei. No QQQ restore.
- News blackout T−30 / T+30 around red USD/EUR: no new SDD.

Scheduler: 1 minute after every M15 close, `Europe/Warsaw`, Spring 6-field cron `0 1,16,31,46 * * * *` (all hours, all week). The scan universe is then filtered on the Warsaw calendar: Monday–Friday GER40 / XAU / US100 / EURUSD / BTC; Saturday and Sunday BTC only (the other names are closed — no scan, no new SDD tickets, no junk full-stack/flip webhooks). Leftover tickets on closed names keep weekday-style management. Override the cron with `SCAN_CRON`. Closed-market / unknown-epic 404s skip that symbol, not the whole scan. Quiet if nothing. JSON webhooks to `AGENT_SIGNAL_WEBHOOK_URLS` on a new full stack or skip-worthy HA flip. Hard scan failures POST `type=failover` (Computron resumes his loop); a later healthy scan POSTs `type=scan_ok` once. Cursor automation sender key is `AGENT_SIGNAL_WEBHOOK_SECRET` (host config only).

## Local run

```bash
# API (paper adapter, no Capital creds)
BROKER=paper ./mvnw -pl server spring-boot:run

# UI with proxy to :8080
cd ui/bot && npm ci && npx ng serve
```

`mvn -B test` runs Java unit tests (HA, RMA, ATR, PP, SDD gating, mock broker, bean swap). Angular is built on `mvn package` / Heroku compile, not on `mvn test`.

## Dev profile — seeded historical data (performance checks)

The `dev` profile runs against an **in-memory H2** (fresh database every start — no file
locks, no stale credentials) and seeds it with ~2 years of historical data so the equity
chart / signals views can be performance-tested with a realistic dataset:

```bash
BROKER=paper ./mvnw -pl server spring-boot:run -Dspring-boot.run.profiles=dev
```

Seeder config in `application-dev.properties`: `app.seed.days` (730), `app.seed.intraday-per-day`
(0 = daily close only; >1 adds intraday snapshots), `app.seed.demo-start`, `app.seed.live-start`,
`app.seed.signal-per-day-max`. Every start reseeds from scratch (in-memory), so there is nothing
to clean up — just restart.

## Equity history sync (Capital.com)

The app reconstructs daily equity from the broker's transaction history into `broker_snapshots`:

- **Manual**: button "Sync history" on the Dashboard (rebuilds live) or `POST /api/history/sync?book=live&replace=true`
- **Automatic**: runs on startup and every day at 03:00 Europe/Warsaw (`EquityHistorySyncJob`);
  disable with `app.history-sync.enabled=false`, change cron with `app.history-sync.cron`
- Safe: only inserts missing daily rows (never touches existing unless `replace=true`),
  skips unconfigured books. Capital.com caps a broad query at ~100 rows, so the adapter
  fetches day-by-day windows and dedupes on the broker reference.

Note: Capital.com only exposes ~a few days of transaction history for an account, so the
reconstructed equity chart goes back as far as the broker keeps data (verified live: 24–28 Aug
2026, equity 405 → 440.60 PLN).

## Heroku deploy

One web dyno. The in-process scheduler only runs while the web dyno is up (Eco sleeps). Optional backup: Heroku Scheduler `POST /api/scan`.

```bash
# App: bot-reinvented (Europe). Attach Postgres if it is not already there.
heroku addons:create heroku-postgresql -a bot-reinvented
# Heroku then sets DATABASE_URL. Do not put DATABASE_URL in git.
heroku config:set TZ=Europe/Warsaw -a bot-reinvented
heroku config:set BROKER=capital EXECUTION_ENABLED=false -a bot-reinvented
```

Or a new app:

```bash
heroku create your-app-name
heroku addons:create heroku-postgresql
heroku config:set TZ=Europe/Warsaw
heroku config:set BROKER=capital
heroku config:set EXECUTION_ENABLED=false
heroku config:set CAPITAL_DEMO_HOST=https://demo-api-capital.backend-capital.com
heroku config:set CAPITAL_API_KEY=... CAPITAL_EMAIL=... CAPITAL_API_PASSWORD=...
heroku config:set CAPITAL_LIVE_HOST=https://api-capital.backend-capital.com
heroku config:set CAPITAL_LIVE_API_KEY=... CAPITAL_LIVE_EMAIL=... CAPITAL_LIVE_PASSWORD=...
heroku config:set AGENT_SIGNAL_WEBHOOK_URLS=https://example.com/hook
heroku ps:scale web=1
git push heroku main
```

Heroku Postgres sets `DATABASE_URL` (`postgres://…`). The app converts it to `jdbc:postgresql://` (user/password as separate datasource properties so the JDBC URL is never logged with a secret). Liquibase XML changelog (`db/changelog/db.changelog-master.xml`) creates `payments`, `sdd_scans`, `sdd_signals`, `broker_snapshots` plus its own `databasechangelog` tables on an empty database. Hibernate `ddl-auto=none` in that mode. Optional manual SQL: `server/src/main/resources/db/schema-postgres.sql`.

Local without `DATABASE_URL` stays H2 (`ddl-auto=update`). Never commit `DATABASE_URL`.

Heroku sets `PORT`. The app binds `server.port=${PORT:8080}`.

GitHub Actions: PRs and pushes to `main` run `mvn -B test` only. Production deploys of `bot-reinvented` come from Heroku's GitHub integration on `main`, not from Actions.

Never commit secrets. Config vars live on Heroku only.

### Config vars

The dashboard always shows **two books**: Demo and Live. P/L is never mixed. The app boots if one side is missing; that pane shows disconnected.

Demo credentials (from `capital.env` on Adam's box — do not commit them):

| Var | Default | Purpose |
| --- | --- | --- |
| `CAPITAL_API_KEY` | empty | Demo API key (alias `CAPITAL_DEMO_API_KEY`) |
| `CAPITAL_EMAIL` | empty | Demo login email (alias `CAPITAL_DEMO_EMAIL`) |
| `CAPITAL_API_PASSWORD` | empty | Demo API-key custom password (alias `CAPITAL_DEMO_API_PASSWORD`) |
| `CAPITAL_DEMO_HOST` | `https://demo-api-capital.backend-capital.com` | Demo REST host |

Live credentials (separate API key; email may match demo):

| Var | Default | Purpose |
| --- | --- | --- |
| `CAPITAL_LIVE_API_KEY` | empty | Live API key |
| `CAPITAL_LIVE_EMAIL` | falls back to `CAPITAL_EMAIL` | Live login email |
| `CAPITAL_LIVE_PASSWORD` | empty | Live API-key custom password (alias `CAPITAL_LIVE_API_PASSWORD`) |
| `CAPITAL_LIVE_HOST` | `https://api-capital.backend-capital.com` | Live REST host |

LIVE view only uses account name `bot trading konto`. Equity ≥ 5000 is hidden (the ~10k preferred account). Fintokei accounts are ignored.

"Główne" (main) view uses `GLOWNE_ACCOUNT_NAME` (defaults to `Glowne`, the main Capital.com account) to pin its account. It always skips the live trading account (`LIVE_ACCOUNT_NAME`), so it can never show live data.

Equity history sync starts from `2020-01-01` and fetches transactions in 7-day windows, so the reconstructed chart covers the whole life of the account (as far back as Capital.com keeps data), not just the last few days.

| Var | Default | Purpose |
| --- | --- | --- |
| `BROKER` | `capital` | `capital` (demo+live Capital beans) or `paper` |
| `AGENT_SIGNAL_WEBHOOK_URLS` | empty | Comma-separated webhook URLs |
| `AGENT_SIGNAL_WEBHOOK_SECRET` | empty | Cursor automation sender key. Host config only. Never commit. |
| `TZ` | `Europe/Warsaw` | Scheduler + PP session (`app.scan.zone`) |
| `SCAN_CRON` | `0 1,16,31,46 * * * *` | Spring 6-field cron: every M15 close, 24/7 Warsaw |
| `EXECUTION_ENABLED` | `false` | Must be true to place SDD entries (demo + live `bot trading konto`) |
| `LIVE_EQUITY_REFUSE` | `5000` | Refuse live execution when equity ≥ this |
| `LIVE_ACCOUNT_NAME` | `bot trading konto` | Only account allowed to trade live |
| `DEMO_RISK_PLN` | `10` | Demo risk per entry (~1% of demo) |
| `HALT_PLN` | `-30` | Demo day P/L halt for new SDD entries |
| `LIVE_HALT_PLN` | `-18` | Live day P/L halt for new SDD entries |
| `MIN_DEAL_SIZE` | `0.01` | Minimum per-ticket size; below this, one ticket instead of two |
| `MAX_OPEN_NAMES` | `4` | Max unique SDD names open per book |
| `PORT` | Heroku sets this | HTTP bind |
| `DATABASE_URL` | Heroku Postgres addon | Production JDBC. Local omit this (H2). Never commit. |

## Execution (EXECUTION_ENABLED)

Off by default. When `EXECUTION_ENABLED=true`, the Java bot places SDD-M15 **fullStack**
entries on both the Capital DEMO and LIVE `bot trading konto` books; **Computron becomes
audit-only** — it stops polling every 15 minutes and instead reads the `type=execution`
webhooks (fill / skip with reason) to audit tickets, caps and stops.

Rules:

- Only a **fullStack** signal places. Flip-but-not-fullStack never places, never flattens,
  never re-scans; the existing signal webhook still pings.
- **Two separate deals** (two tickets) when the per-ticket size clears `MIN_DEAL_SIZE`;
  a single ticket otherwise. **Never** `DELETE + size=` (that flattens a whole ticket) —
  closes are always `closePosition(id, 0)`.
- Stop = 2.5× H1 ATR on BOTH deals at entry. 1R = 1× H1 ATR. No broker trailingStop.
- **Hard 1R TP on ONE deal only** (the TP ticket) right after fill; the other deal
  (runner) has no TP. Capital quirk: on the TP ticket the stop is PUT together with the
  profit level (setting profitLevel alone wipes the stop).
- When the TP ticket is gone on the broker (it took its 1R), the runner **keeps the
  original 2.5× stop** (never moved to break-even, never amended to entry) and is then
  **H1-trailed**: the stop only ratchets in the trade's favour, never worse than the
  original 2.5× stop, via PUT stopLevel (not the trailingStop API).
- Single-ticket entry: that one deal gets stop + 1R TP PUT together; when it is gone the
  row is removed.
- Skip when the SDD name is already open; max `MAX_OPEN_NAMES` unique names; **no pyramid**
  while a name is open — blocked until BOTH tickets are gone.
- Demo risk ~10 PLN (`DEMO_RISK_PLN`); live 1% of the bot-konto equity. Day-P/L halt for
  new entries: demo −30, live −18 (per book).
- Idempotent keyed on `book|symbol|direction|barTime` — a webhook retry or re-scan of the
  same bar never opens a second entry.
- Never touches the stocks book (TQQQ / CRCL / SPOT / SHOP). **Glowne is never executed.**

### Execution state survives dyno restarts

`SddExecutionState` is persisted in Postgres (table `sdd_execution_entries`, same
`DATABASE_URL` / Liquibase as `broker_snapshots`); RAM is only a cache. Every transition
(place, TP filled, runner trail, remove) is written through, and on `ApplicationReady` the
entries are reloaded and reconciled against the broker's open positions — so after a Heroku
dyno restart the bot still detects the TP ticket taking 1R, still H1-trails the runner
(floor = original 2.5× stop), still refuses to re-place the same M15 bar, and never pyramids
a name until BOTH tickets are gone. Leftover SDD positions opened by Computron before
persistence (no row in `sdd_execution_entries`) are NOT adopted.

Enable on Heroku (only when you intend to trade live):

```bash
heroku config:set EXECUTION_ENABLED=true -a bot-reinvented
```

## REST

- `GET /health` — app up, plus `demoConfigured` / `liveConfigured`, `webhookConfigured` (boolean only), and `lastWebhook` (`ok` / `never` / short error)
- `GET /actuator/health` — Spring Actuator liveness (`{"status":"UP"}`). Health only; no env/heapdump/beans. Safe for a Heroku health check.
- `GET /api/accounts` — `[{id: demo\|live, broker, accountName, equity, available, dayPnl, connected, error?}, …]`
- `GET /api/positions?account=demo\|live` — omit `account` for `{demo: [...], live: [...]}`
- `GET /api/scan/last` — shared SDD symbols plus `books[]` (per-book halt/error, no mixed P/L)
- `GET /api/signals`
- `POST /api/scan` (manual trigger; also a Scheduler backup)
- `GET /api/broker` — both books
- `GET /api/v1` and `GET /api/legacy` — not a Bybit/Binance API. This repo never had those clients (see below).

Dashboard: two columns Demo \| Live. `/signals` and `/payments` unchanged.

## Add another broker

1. Implement `com.adam.server.broker.BrokerClient` (session, accounts, candles, market price, working orders, positions, confirmations). Keep Capital JSON out of this interface.
2. Register `@Bean("demoBroker")` / `@Bean("liveBroker")` (or only demo + an `UnavailableBrokerClient` for live) next to the Capital adapters.
3. Set `BROKER=your-id`. Strategy, scheduler, REST, and Angular talk to `BrokerBooks` + the SDD engine.

`PaperBrokerClient` is the stub that proves the swap; it is not a second live broker.

This repository's git history has **no Bybit, Binance, ccxt, or exchange client classes**. They were not deleted on merge; they were never here.

Search (2026-08-27): `git log --all` is 12 commits; branches `main` and `cursor/capital-sdd-m15-337f`; no tags. `git grep` across every commit's `*.java`/`*.ts`/`*.xml` finds no `bybit`/`binance`/`ccxt` except this paragraph. GitHub API lists the same two branches. Public `adammendak/*` repos are unrelated (GroceryList, etc.). The tree before Capital.com was a Spring payments starter (`ui/bot` YES/NO form) plus `ui/01-starting-project`.

`GET /api/v1` and `GET /api/legacy` return that finding as JSON. Do not invent a fake Bybit/Binance adapter. When you have real code, implement `BrokerClient` and register beans beside Capital.
