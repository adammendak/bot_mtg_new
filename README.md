# bot_mtg_new — Capital.com SDD-M15

Spring Boot 4 (Java 21) API plus the existing Angular `ui/bot` dashboard. One Heroku web dyno serves both. Capital.com is the default live broker behind a broker-agnostic SPI.

OAuth2 client remains on the classpath from the original app but is **unused**. `/api/**`, `/health`, and the dashboard are `permitAll` so an unfinished login cannot block the bot.

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

Scheduler: 1 minute after M15 close, `Europe/Warsaw`, cron minutes 1,16,31,46, hours 8–22 weekdays. Quiet if nothing. JSON webhooks to `AGENT_SIGNAL_WEBHOOK_URLS` on a new full stack or skip-worthy HA flip.

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
heroku create your-app-name
heroku config:set TZ=Europe/Warsaw
heroku config:set BROKER=capital
heroku config:set EXECUTION_ENABLED=false
heroku config:set CAPITAL_DEMO_HOST=https://demo-api-capital.backend-capital.com
heroku config:set CAPITAL_API_KEY=...
heroku config:set CAPITAL_EMAIL=...
heroku config:set CAPITAL_API_PASSWORD=...
heroku config:set AGENT_SIGNAL_WEBHOOK_URLS=https://example.com/hook
heroku ps:scale web=1
git push heroku main
```

Heroku sets `PORT`. The app binds `server.port=${PORT:8080}`.

GitHub Actions: PRs run `mvn -B test`. Push to `main` runs tests then deploys to Heroku. Set repository secrets:

- `HEROKU_API_KEY`
- `HEROKU_APP_NAME`
- `HEROKU_EMAIL`

Never commit secrets. Config vars live on Heroku / GitHub secrets only.

### Config vars

| Var | Default | Purpose |
| --- | --- | --- |
| `CAPITAL_API_KEY` | empty | API key |
| `CAPITAL_EMAIL` | empty | Login email |
| `CAPITAL_API_PASSWORD` | empty | API key custom password |
| `CAPITAL_DEMO_HOST` | `https://demo-api-capital.backend-capital.com` | Demo host |
| `CAPITAL_LIVE_HOST` | `https://api-capital.backend-capital.com` | Live host |
| `CAPITAL_LIVE` | `false` | Use live host + LIVE gates |
| `BROKER` | `capital` | `capital` or `paper` |
| `AGENT_SIGNAL_WEBHOOK_URLS` | empty | Comma-separated webhook URLs |
| `TZ` | `Europe/Warsaw` | Scheduler + PP session |
| `EXECUTION_ENABLED` | `false` | Must be true to place orders |
| `PORT` | Heroku sets this | HTTP bind |

## REST

- `GET /health`
- `GET /api/scan/last`
- `GET /api/signals`
- `POST /api/scan` (manual trigger; also a Scheduler backup)
- `GET /api/broker`
- `GET /api/positions` (SPI positions; does not require execution)

Dashboard routes: `/` (stack + last scan), `/signals` (HA flips / full stacks), `/payments` (original list+form kept).

## Add another broker

1. Implement `com.adam.server.broker.BrokerClient` (session, accounts, candles, market price, working orders, positions, confirmations). Keep Capital JSON out of this interface.
2. Register a `@Bean` `@ConditionalOnProperty(name = "app.broker", havingValue = "your-id")` next to `CapitalComBrokerClient` / `PaperBrokerClient`.
3. Set `BROKER=your-id`. Strategy, scheduler, REST, and Angular already talk only to the SPI + SDD engine.

`PaperBrokerClient` is the stub that proves the swap; it is not a second live broker.
