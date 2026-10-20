# TradingView → bot webhook (AlgoPro V1.7, observe-only)

Cel: forward test sygnałów wskaźnika AlgoPro. Bot **tylko zapisuje** zdarzenia (tabela `tv_signals`) i wysyła mail przy wejściach — nic nie handluje.

## 1. Konfiguracja (raz)

```bash
heroku config:set -a bot-reinvented TV_WEBHOOK_SECRET=$(openssl rand -hex 24)
heroku config:get -a bot-reinvented TV_WEBHOOK_SECRET   # wklej ten sekret w treść alertów w miejsce <SEKRET>
```

Bez `TV_WEBHOOK_SECRET` endpoint odpowiada 404 (wyłączony). Opcjonalnie `TV_MAIL_ENABLED=false` wyłącza maile.

## 2. Webhook URL (w alercie: Powiadomienia → Webhook URL)

```
https://bot-reinvented-9c8abd9ce4a5.herokuapp.com/api/tv/signal
```

## 3. Alert — ustawienia

| Pole | Wartość |
|---|---|
| Warunek | AlgoPro V1.7 (Auto Optimize, V1.5, Swing…) → jedno z 6: Buy/Sell Entry, Buy/Sell Take Profit, Buy/Sell Invalidation Zone |
| Interwał | **60** (wersja H1) albo **240** (wersja H4) — musi zgadzać się z wykresem |
| Uruchom | **Once Per Bar Close** (nie „Once Per Bar" — unika repaintu w trakcie świecy) |
| Wygaśnięcie | najdalsze możliwe (po wygaśnięciu alert po cichu przestaje działać) |
| Wiadomość | JSON poniżej (jeden alert = jedna wiadomość) |

Na jeden symbol i jeden interwał trzeba **6 alertów** (3 zdarzenia × BUY/SELL). Sprawdź limit alertów w swoim planie.

## 4. Wiadomości — wersja H1 (`strat: algopro-h1`)

Buy Entry:
```json
{"secret":"<SEKRET>","strat":"algopro-h1","event":"entry","side":"BUY","sym":"{{ticker}}","tf":"{{interval}}","price":{{close}},"t":"{{time}}"}
```
Sell Entry:
```json
{"secret":"<SEKRET>","strat":"algopro-h1","event":"entry","side":"SELL","sym":"{{ticker}}","tf":"{{interval}}","price":{{close}},"t":"{{time}}"}
```
Buy Take Profit:
```json
{"secret":"<SEKRET>","strat":"algopro-h1","event":"tp","side":"BUY","sym":"{{ticker}}","tf":"{{interval}}","price":{{close}},"t":"{{time}}"}
```
Sell Take Profit:
```json
{"secret":"<SEKRET>","strat":"algopro-h1","event":"tp","side":"SELL","sym":"{{ticker}}","tf":"{{interval}}","price":{{close}},"t":"{{time}}"}
```
Buy Invalidation Zone:
```json
{"secret":"<SEKRET>","strat":"algopro-h1","event":"invalid","side":"BUY","sym":"{{ticker}}","tf":"{{interval}}","price":{{close}},"t":"{{time}}"}
```
Sell Invalidation Zone:
```json
{"secret":"<SEKRET>","strat":"algopro-h1","event":"invalid","side":"SELL","sym":"{{ticker}}","tf":"{{interval}}","price":{{close}},"t":"{{time}}"}
```

## 5. Wiadomości — wersja H4 (`strat: algopro-h4`)

Identyczne jak H1, tylko `"strat":"algopro-h4"` (interwał wykresu 240):

```json
{"secret":"<SEKRET>","strat":"algopro-h4","event":"entry","side":"BUY","sym":"{{ticker}}","tf":"{{interval}}","price":{{close}},"t":"{{time}}"}
{"secret":"<SEKRET>","strat":"algopro-h4","event":"entry","side":"SELL","sym":"{{ticker}}","tf":"{{interval}}","price":{{close}},"t":"{{time}}"}
{"secret":"<SEKRET>","strat":"algopro-h4","event":"tp","side":"BUY","sym":"{{ticker}}","tf":"{{interval}}","price":{{close}},"t":"{{time}}"}
{"secret":"<SEKRET>","strat":"algopro-h4","event":"tp","side":"SELL","sym":"{{ticker}}","tf":"{{interval}}","price":{{close}},"t":"{{time}}"}
{"secret":"<SEKRET>","strat":"algopro-h4","event":"invalid","side":"BUY","sym":"{{ticker}}","tf":"{{interval}}","price":{{close}},"t":"{{time}}"}
{"secret":"<SEKRET>","strat":"algopro-h4","event":"invalid","side":"SELL","sym":"{{ticker}}","tf":"{{interval}}","price":{{close}},"t":"{{time}}"}
```

Oddzielne `strat` pozwala oceniać H1 i H4 osobno.

## 6. Opcjonalnie SL/TP z wykresu

Jeśli wskaźnik wystawia poziomy jako `plot()`, w oknie wiadomości kliknij placeholder wtyczek i dopisz np. `,"sl":{{plot_3}},"tp":{{plot_4}}` (numery zależą od wskaźnika). Bez nich wynik liczymy zdarzeniami wskaźnika: **TP-alert = wygrana, Invalidation = przegrana**.

## 7. Test (po ustawieniu sekretu)

```bash
curl -s -X POST https://bot-reinvented-9c8abd9ce4a5.herokuapp.com/api/tv/signal \
  -H "Content-Type: text/plain" \
  -d '{"secret":"<SEKRET>","strat":"algopro-h1","event":"entry","side":"BUY","sym":"ETHUSD","tf":"60","price":2650,"t":"2026-10-20T10:00:00Z"}'
```
Oczekiwane: `{"ok":true,"status":"ACCEPTED","id":...}`; powtórka tej samej świecy → `DUPLICATE`; zły sekret → 401.
Ostatnie zdarzenia: `GET /api/tv/signals?limit=100` (token bearer).

## 8. Symbole

`{{ticker}}` jest mapowany na kody bota (SPX500→US500, NAS100→US100, XAUUSD→XAU, BTCUSD→BTC, ETHUSD→ETH, GER40/DE40/DAX→GER40…). Nieznane tickery zapisują się jak przyszły.

## 9. Egzekucja na kontach demo (forward test na żywo)

Alerty `algopro-h1` / `algopro-h4` składają zlecenia na **osobnych kontach demo Capital** (izolowane booki `tvh1`, `tvh4`):

| Zdarzenie | Co robi bot |
|---|---|
| `entry` BUY/SELL | zlecenie rynkowe, sizing 1% ryzyka (jak reszta HTS), **awaryjny SL = 2.5 × ATR14** na interwale alertu (alert nie niesie SL); przeciwny kierunek na tym samym symbolu najpierw zamyka starą pozycję (`ALGO_REVERSE`) |
| `tp` | zamyka otwartą pozycję tej strony (`ALGO_TP`) |
| `invalid` | zamyka otwartą pozycję tej strony (`ALGO_INVALID`) |

- Uniwersum: **XAU, EURUSD, US100 (NAS100), BTC**. Inne symbole są tylko zapisywane.
- Jedna pozycja na symbol na koncie; powtórzony `entry` w tym samym kierunku jest ignorowany.
- Konta: `Account TV H1` / `Account TV H4` (zmień przez `TV_H1_ALGO_ACCOUNT_NAME` / `TV_H4_ALGO_ACCOUNT_NAME`; jeśli login ma dokładnie jedno konto, bot weźmie je niezależnie od nazwy).
- Poświadczenia: `TV_H1_ALGO_API_KEY`, `TV_H1_ALGO_API_EMAIL`, `TV_H1_ALGO_PASSWORD` (analogicznie `TV_H4_ALGO_*`). Bez nich book jest nieaktywny (zlecenie pominięte, sygnał i tak zapisany).
- Wyłącznik: flaga **`tv.execution`** w panelu admina (domyślnie włączona; env `TV_EXECUTION_ENABLED=false` ustawia domyślnie wyłączoną). Wyłączona = sygnały tylko się zapisują.
- Parametry: `TV_ATR_PERIOD` (14), `TV_STOP_ATR_MULT` (2.5), `TV_TP_ATR_MULT` (2.0, informacyjnie).
- Pozycje widać w `hts_trades` (warianty `TV_ALGO_H1` / `TV_ALGO_H4`) i w scorecardzie HTS.
