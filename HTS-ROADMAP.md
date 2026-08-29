# HTS — roadmap wdrożenia

Źródła: 3 transkrypcje (`transkrypcja-6KxsOi17HQ4` podstawy, `transkrypcja-S84ianAkeX8` dziennik,
`transkrypcja-H3pOvN0xxlY` zarządzanie ryzykiem) + screeny + analiza `HTS-vs-KOD.md`.

Potwierdzone parametry: **wstęgi RMA, high/low, 33 / 144.**
HTS to **3. strategia** — osobne konto demo, obok SDD‑M15 i swing, na miesiąc forward.

**3 modele timeframe (ta sama mechanika, różne pary):**
| model | HTF (kontekst) | LTF (egzekucja) | rola |
|---|---|---|---|
| **HTS‑core** | **H4** | **M15** | rdzeń — to rozszerzamy |
| HTS‑swing | D1 | H1 | długie swingi |
| HTS‑fast | H1 | M5 | intraday |

Zasada: nic do żywej egzekucji bez zgody. Każdy task = najpierw backtest, potem PR, potem live.
**Każdy wariant testujemy z ADX i bez ADX.**

---

## Zapamiętane punkty ze strategii (do nie‑pominięcia)

**Wejście / trend**
- 2 wstęgi RMA(high/low): szybka 33, wolna 144. Wstęga = strefa `[rma(low), rma(high)]`.
- Trend = szybka wstęga **cała** ponad/pod wolną („cross" = wyjście całej wstęgi, nie przecięcie linii).
- Nie spekulujemy przed crossem — czekamy na zmianę.
- **Brak wejść gdy wstęgi się konsolidują / nakładają / idą płasko** — to trend boczny, pomijamy.
- Wejście = cena **cofa się do wstęgi** (korekta w trendzie), potem świeca **ciałem** zamyka się z powrotem
  za krawędzią szybkiej wstęgi. Wchodzimy na krawędzi (wcześniej) — bo 1R blisko, ryzyko małe.
- HTF w tym samym trendzie (wstęga HTF ułożona).
- Filtr **ADX**: maluje pola gdy trend silny → pozwala na wejście „ryzykowne" przed potwierdzeniem crossa.
- Kierunek bierze się z **analizy fundamentalnej** (kalendarz, dane makro, sentyment) — wstęgi tylko potwierdzają.

**Stop**
- **Strukturalny** — za dalszą krawędzią wstęgi (long → pod dolną krawędzią szybkiej) lub za punktem
  kontrolnym (PP). ATR służy **tylko** do dobrania wielkości pozycji.
- Long‑term: stop **prowadzony pod wstęgą** wraz z ceną (trailing strukturalny).

**Target / wyjście**
- Stałe **RR ≥ 1:1**, preferowane **1:2 / 1:3** (nie 1:6/1:8 — nie podzielisz na tyle TP).
- Multi‑target na **dziennych pivotach**: R1/R2/R3 (S1/S2/S3) = TP1/TP2/TP3, częściowa realizacja
  (33/33/33 lub 50/50), **BE po TP1**.
- **Runner** (2. część pozycji, bez targetu): trzymana aż **cała świeca ciałem zamknie się pod wolną
  wstęgą** (long) lub złamie PP przeciw pozycji.
- Fib 50% korekty: przekroczenie 50% zniesienia = pierwszy sygnał możliwej zmiany trendu.

**Zarządzanie ryzykiem (film 3)**
- Ryzyko / trade: **max 1%, docelowo 0.25–0.5%**, liczone łącznie dla WSZYSTKICH otwartych pozycji
  (swing + scalpy razem ≤ 1–2%).
- Sizing przez dystans stopu: bliższy stop → większa pozycja, dalszy → mniejsza, strata w PLN stała.
- **2 straty pod rząd → koniec dnia.** „One trade" = jeden setup dziennie, bez uśredniania/dokładania.
- Twardy DD **20%** (close‑all). Dzienny DD ≤ 5%. 20 dni × 1% = 20% max/mies (nie powinno wystąpić).
- **Split‑entry** (NIE martingale): dziel pozycję na 2–3 mniejsze rozstawione w zakresie cenowym,
  ten sam permanentny stop, łączna strata = limit. Daje rynkowi „oddech".
- **Piramidowanie** (w zysku): dokładasz na kolejnych cofnięciach do wstęgi w tym samym trendzie;
  pełne wyjście przy złamaniu wolnej wstęgi.
- **ATR** = miernik zmienności: rosnący ATR → prowadź ciaśniej, szerokie ruchy, „najgorzej się gra".
- **Blackout**: nie handlujemy przed danymi; po danych nie od razu (min. kilka świec).
- Dobór instrumentu: **tylko trendowe** — „z 30 walut + indeksy + metale znajdziesz 4 i to wystarcza".
  Konsolidacyjne (typ EURUSD/AUDNZD) → tylko scalp, nie swing.
- 3 style: **swing** (D1/H1, RR 1:3), **scalp** (konsolidacja, pivoty, RR 1:1), **hedge** (pozycja
  przeciwstawna zamiast stopu, „kolejki" 2–3 szanse) — hedge prawdopodobnie poza zakresem bota.

---

## TASKI (kolejność wdrażania)

### T1 — Silnik HTS + backtest (rdzeń)  ← START
- `Band` = RMA(high/low), 33/144 — **zrobione** (`com.adam.server.sdd.Band`).
- `HtsBacktestService` — timeframe‑generyczny per‑trade backtest:
  - wejście: pullback do szybkiej wstęgi + reclaim ciałem + szybka wstęga czysto nad wolną + HTF wstęga w trendzie
  - stop: strukturalny (dalsza krawędź szybkiej wstęgi)
  - target: RR param (sweep 1 / 2 / 3)
  - runner: połowa RR + połowa „close pod wolną wstęgą"
  - wyjście per‑trade CSV → `tools/equity_simulator.py`
- Wymaga `Resolution.M5` (+ mapowanie `MINUTE_5` w `CapitalComBrokerClient`, `PaperBrokerClient`).
- Uruchamiany dla **3 par**: H4/M15 (core), D1/H1, H1/M5.
- **Deliverable:** tabela per‑ticker × 3 modele, HTS vs SDD baseline, train/test, RR 1/2/3.

### T2 — Filtr konsolidacji (brak wejść gdy wstęgi się nakładają)
- Skip gdy: `fast.lower ≤ slow.upper` nie jest wyraźnie spełnione (wstęgi się stykają),
  albo separacja wstęg `< k·ATR`, albo szerokość szybkiej wstęgi płaska (brak nachylenia).
- **Test: z filtrem konsolidacji i bez.**

### T3 — Filtr ADX
- Port ADX (Wilder) do pakietu `sdd` (jak `Supertrend`/`WaveTrend`).
- Bramka: wejścia tylko gdy `ADX > próg` (trend) LUB „zielone pole ADX" → zgoda na wejście przed crossem.
- **Test KAŻDEGO wariantu (T1, T2, T4…) z ADX i bez ADX.**

### T4 — Multi‑target na pivotach + BE
- Dzienne PP → R1/R2/R3 / S1/S2/S3 jako TP1/TP2/TP3.
- Częściowa realizacja (33/33/33 lub 50/50/0), **stop → BE po TP1**.
- Zastępuje pojedynczy stały RR z T1 dla stylu swing.

### T5 — Model ryzyka / sizing wg filmów
- Sizing przez dystans stopu → strata stała % (0.25–1%).
- Łączny cap ryzyka na koncie; **2 straty pod rząd → stop dnia**; twardy DD 20% → close‑all.
- Po stronie backtestu: `equity_simulator` już liczy % ryzyka — dodać regułę day‑stop + DD.

### T6 — Split‑entry (skalowanie w zakres, nie martingale)
- Sygnał → 2–3 częściowe wejścia rozstawione w zakresie, ten sam stop.
- Wpływ na avgR / DD w backteście.

### T7 — Piramidowanie
- Dokładanie na kolejnych cofnięciach do wstęgi w tym samym trendzie; pełne wyjście przy złamaniu wolnej.
- Konflikt z obecnym `ExecutionGate` („NO pyramid") — najpierw backtest, potem decyzja o żywej egzekucji.

### T8 — Supertrend / WaveTrend jako opcje
- Runner‑trail: Supertrend vs linia RMA133 vs „close pod wolną wstęgą" — A/B.
- WaveTrend: timing OB/OS jako dodatkowy filtr wejścia (extreme → czekaj na reclaim).
- Wskaźniki już w kodzie (`com.adam.server.sdd.Supertrend`, `WaveTrend`).

### T9 — Live: 3. book `hts` + 3. konto Capital
- `HtsEngine` + `HtsScanService` + `HtsExecutionGate` (opt‑in `HTS_EXECUTION_ENABLED`).
- `Books.HTS`, grant Liquibase, `CAPITAL_HTS_*` config vars, book w UI/API/overview.
- Wdrożyć **zwycięską konfigurację** z T1–T8 na osobnym demo koncie, na miesiąc forward,
  równolegle z SDD‑M15 i swing.

### T10 — Bogaty mail sygnału HTS
- Jak task 6 dla swinga: stan obu interwałów (wstęgi HTF+LTF, ADX, ATR, pozycja vs PP),
  entry/stop/targety, jedno zdanie „dlaczego". Adres `adam.mendak@gmail.com`.

### T11 — Pozostałe modele TF jako osobne żywe konfiguracje
- Po ustabilizowaniu **HTS‑core (H4/M15)** na 3. koncie: dołożyć D1/H1 i H1/M5 jako warianty
  (osobne booki / konta albo przełącznik konfiguracji) — **po** domaszerowaniu M15 + swing + HTS‑core.

---

## Rozbieżności do naprawienia po drodze (z `HTS-vs-KOD.md`)

| co | teraz w kodzie | docelowo (HTS) | task |
|---|---|---|---|
| wstęga | linia `RMA(close)` | **band RMA(high/low)** | T1 (zrobione w `Band`) |
| wolna długość | 133 | **144** | T1 |
| wejście | HA flip + `close>RMA33>RMA133` | **pullback do wstęgi + reclaim** | T1 |
| stop | 2.5×ATR sztywny | **krawędź wstęgi / PP** | T1 |
| target | 1×ATR | **RR 1:2/1:3 + pivoty** | T1 / T4 |
| runner | swing brak, M15 trail 2.5×ATR | **close pod wolną wstęgą** | T1 / T8 |
| Heiken Ashi | obowiązkowa bramka | **strategia go nie używa** | T1 (nie przenosimy HA do HtsEngine) |
| ADX | brak | filtr siły trendu | T3 |
| konsolidacja | brak detekcji | **skip gdy wstęgi nałożone** | T2 |
| piramidowanie | zabronione | dozwolone na cofnięciach | T7 |
| para D1/H1 | nie istnieje | główna para HTS | T1 |
| pivoty | tylko bramka wejścia | **targety** | T4 |
| news blackout na swingu | brak | jest | T5/T9 |

---

## Stan „co jest w kodzie" (backtest, nie live)

- `Band` (high/low 33/144) — jest.
- `Supertrend`, `WaveTrend`, `Ema`, `Sma` — jest (niewpięte w silnik).
- Band‑entry + runner „close pod wstęgą" — jest w `SwingBacktestService` (H4/H1) i `BacktestService` (H1/M15)
  jako tryby backtestu. `HtsBacktestService` (D1/H1, H1/M5) — **do zrobienia w T1**.
- Żywe silniki (`SddEngine`, `SddSwingEngine`) i egzekucja (`ExecutionGate`, `SwingExecutionGate`) — **nietknięte**.
