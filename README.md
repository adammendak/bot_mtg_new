# bot_mtg_new — Capital.com SDD-M15

Spring Boot 4 (Java 21) API plus the existing Angular `ui/bot` dashboard. One Heroku web dyno serves both. Capital.com is the default live broker behind a broker-agnostic SPI.

OAuth2 client remains on the classpath from the original app but is **unused**. `/api/**`, `/health`, `/actuator/health`, and the dashboard are `permitAll` so an unfinished login cannot block the bot.

## SDD-M15 (do not regress)

- Universe: GER40/DE40, XAU/GOLD, US100, EURUSD, BTC. Not STONKS, not US30.
- Capital.com candles. Local Heiken Ashi, Wilder RMA 33/133, daily PP with Warsaw 21:00 roll. BTC skips PP.
- Full stack on a newly closed M15: HA flip + M15 RMA with + H1 with (HA **or** RMA stacked). H4 is a regime note, not an AND filter. H1-supporting (close vs RMA33) is log-only.
- Stop 2.5× H1 Wilder ATR 14. 1R = 1× ATR. No TP at entry. Scale 50% at 2R then BE + H1 trail. No auto-BE before that. No pyramid while the name is open.
- `EXECUTION_ENABLED=false` by default: scan + dashboard + webhooks only.
- DEMO ~1% (about 10 PLN on a small demo). Halt −30 / hard −50 vs Warsaw day-open P/L.
- LIVE only account name `bot trading konto` at 1%. Refuse equity ≥ 5000 (preferred later ~10k).
- Do not flatten TQQQ / CRCL / SPOT / SHOP. No Fintokei. No QQQ restore.
- News blackout T−30 / T+30 around red USD/EUR: no new SDD.

Scheduler: 1 minute after every M15 close, `Europe/Warsaw`, Spring 6-field cron `0 1,16,31,46 * * * *` (all hours, all week — BTC weekends and the Asian session). Override with `SCAN_CRON`. Closed-market / unknown-epic 404s skip that symbol, not the whole scan. Quiet if nothing. JSON webhooks to `AGENT_SIGNAL_WEBHOOK_URLS` on a new full stack or skip-worthy HA flip.

## Local run

```bash
# API (paper adapter, no Capital creds)
BROKER=paper ./mvnw -pl server spring-boot:run

# UI with proxy to :8080
cd ui/bot && npm ci && npx ng serve
```

`mvn -B test` runs Java unit tests (HA, RMA, ATR, PP, SDD gating, mock broker, bean swap). Angular is built on `mvn package` / Heroku compile, not on `mvn test`.

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

LIVE view only uses account name `bot trading konto`. Equity ≥ 5000 is hidden (the ~10k preferred account). Fintokei accounts are ignored. Execution, if ever enabled, is demo-only — not dual-fire.

| Var | Default | Purpose |
| --- | --- | --- |
| `BROKER` | `capital` | `capital` (demo+live Capital beans) or `paper` |
| `AGENT_SIGNAL_WEBHOOK_URLS` | empty | Comma-separated webhook URLs |
| `TZ` | `Europe/Warsaw` | Scheduler + PP session (`app.scan.zone`) |
| `SCAN_CRON` | `0 1,16,31,46 * * * *` | Spring 6-field cron: every M15 close, 24/7 Warsaw |
| `EXECUTION_ENABLED` | `false` | Must be true to place orders (demo book only) |
| `PORT` | Heroku sets this | HTTP bind |
| `DATABASE_URL` | Heroku Postgres addon | Production JDBC. Local omit this (H2). Never commit. |

## REST

- `GET /health` — app up, plus `demoConfigured` / `liveConfigured`
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
