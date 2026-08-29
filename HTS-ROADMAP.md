# HTS — roadmap wdrożenia

Źródła: 3 transkrypcje (`transkrypcja-6KxsOi17HQ4` podstawy, `transkrypcja-S84ianAkeX8` dziennik,
`transkrypcja-H3pOvN0xxlY` zarządzanie ryzykiem) + screeny + analiza `HTS-vs-KOD.md`.

Potwierdzone parametry: **wstęgi RMA, high/low, 33 / 144.**
HTS to **3. strategia** — osobne konto demo, obok SDD‑M15 i swing, na miesiąc forward.

**Modele timeframe (ta sama mechanika, różne pary):**
| model | HTF (kontekst) | LTF (egzekucja) | rola | priorytet |
|---|---|---|---|---|
| **HTS‑swing** | **D1** | **H1** | główny model swingowy | **1** |
| **HTS‑core** | **H4** | **M15** | rdzeń — to rozszerzamy | **2** |
| HTS‑fast | H1 | M5 | 3. wariant (scalp) | później (T11) |

> **Kierunek:** filmy opisują **2 opcje** — swing (D1/H1) i scalp (H1/M5 + okna handlu ~2 h).
> **Priorytet: 2 modele swingowe (D1/H1, H4/M15).** H1/M5 wejdzie jako 3. wariant HTS **po** nich
> (T11) — bez okien sesyjnych, sama mechanika wstęg na M5. cTrader ma wbudowany wskaźnik wstęg
> („WWS") — potwierdza 33/144.

Zasada: nic do żywej egzekucji bez zgody. Każdy task = najpierw backtest, potem PR, potem live.
**Każdy wariant testujemy z ADX i bez ADX.**

**Cel optymalizacji HTS:** wysoki **win rate** + niski **drawdown**. Docelowo **2–4 % / mies. na
ticker** to już dobry wynik. Preferujemy dużo małych wygranych (TP na 1:2) + runner łapiący
rzadkie duże trendy — nie polowanie na wielkie RR kosztem WR.

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

**Target / wyjście — model docelowy (potwierdzony)**
- **TP1 na 1:2 RR → realizacja połowy pozycji.**
- **Druga połowa = runner z trailingiem:** po TP1 stop runnera skacze na **zablokowany zysk**
  `entry ± lockR × dystans_stopu` (`lockR` domyślnie 1.0 — „przycina do ~1 % zysku"), potem
  **trailuje pod krawędzią szybkiej wstęgi** (co wyżej: lock albo krawędź wstęgi).
- Runner wychodzi **do końca dopiero** gdy **cała świeca ciałem zamknie się za wolną wstęgą**
  (long → poniżej dolnej krawędzi wolnej) — „reszta ile da".
- Alternatywa: multi‑target na **dziennych pivotach** R1/R2/R3 = TP1/TP2/TP3 (1/3 każdy), **BE po TP1**,
  ostatnia 1/3 jak runner wyżej. (tryb `pivotTargets`, do porównania w T4)
- Kod: `replayRr` — `runnerLockR` param; `?runnerLock=` na endpoincie. Domyślnie 1.0.
- **Konstrukcja „linijką Fib"**: 0 % = wejście, 100 % = SL; 200/300/400 % = TP1/TP2/TP3 (czyli 1R/2R/3R);
  **50 % = poziom ostrzegawczy** (korekta w połowie drogi do stopu → redukcja / czujność, sygnał do maila).
- Hipoteza (OI‑1): linijka Fib to tylko **sprawdzenie R:R**, a faktyczne TP stawiamy na **pivotach**.

**Stop — dokładnie**
- Strukturalny, ale **nie idealnie na krawędzi wstęgi — delikatnie dalej**: krawędź szybkiej wstęgi
  odsunięta o `stopBufferFrac × szerokość szybkiej wstęgi` (kod: domyślnie 0.25). Knot do krawędzi
  nie wybija wtedy na samej linii struktury.
- Wariant wąski = wstęga 33, szeroki = wstęga 144 (film 3 opisuje to odwrotnie — **prawdopodobnie
  błąd transkrypcji**, OI‑9).

**Zarządzanie ryzykiem (film 3)**
- Ryzyko / trade: **max 1%, docelowo 0.25–0.5%**, liczone łącznie dla WSZYSTKICH otwartych pozycji
  (swing + scalpy razem ≤ 1–2%).
- Sizing przez dystans stopu: bliższy stop → większa pozycja, dalszy → mniejsza, strata w PLN stała.
  Wzór: **`lot = ryzyko$ / (dystans_SL_w_pipsach × wartość_pipsa_na_lot)`**.
- **2 straty pod rząd → koniec dnia.** „One trade" = jeden setup dziennie, bez uśredniania/dokładania.
- Twardy DD **20%** (close‑all). Dzienny DD ≤ 5%. 20 dni × 1% = 20% max/mies (nie powinno wystąpić).
- Cel **~2 %/mies.**; strategia zyskowna nawet **poniżej 50 % WR przy 1:3**. Reżim sizingu i DD
  ma być **zgodny z prop‑firm**.

> **Kierunek: priorytet to system SWINGOWY.** Główny model **D1/H1**, core do rozszerzania
> **H4/M15** — te dwa robimy pierwsze. **H1/M5 = 3. wariant, później** (T11), bez okien handlu /
> filtrów sesyjnych. Wejścia liczą się na zamknięciu świecy LTF, trzymanie pozycji przez dni.
- **Split‑entry** (NIE martingale): dziel pozycję na 2–3 mniejsze rozstawione w zakresie cenowym,
  ten sam permanentny stop, łączna strata = limit. Daje rynkowi „oddech".
- **Piramidowanie** (w zysku): dokładasz na kolejnych cofnięciach do wstęgi w tym samym trendzie;
  **równe loty (nie martingale)**, dodatki **tylko na re‑teście wstęgi**, łączne ryzyko ≤ 1%;
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
  - stop: strukturalny (dalsza krawędź szybkiej wstęgi) **+ bufor `stopBufferFrac × szer. wstęgi`**
    (domyślnie 0.25 — „delikatnie dalej", nie na samej linii)
  - target: **TP1 = 1:2 RR (połowa)**; RR param dla sweepów 1 / 2 / 3
  - runner: druga połowa — po TP1 stop na zablokowany zysk (`runnerLockR`, dom. 1.0), trail pod
    szybką wstęgą, wyjście dopiero na „close ciałem za wolną wstęgą"
  - wyjście per‑trade CSV → `tools/equity_simulator.py`
- `Resolution.M5` + mapowanie `MINUTE_5` już są (z T1); model H1/M5 zostaje w kodzie backtestu
  jako opcja, ale **nie jest rozwijany** (scalp).
- Uruchamiany dla par swingowych: **D1/H1 (główny), H4/M15 (core)**.
- **Deliverable:** tabela per‑ticker × modele swingowe, HTS vs SDD baseline, train/test, RR 1/2/3.

### T2 — Filtr konsolidacji (brak wejść gdy wstęgi się nakładają)
- Skip gdy: `fast.lower ≤ slow.upper` nie jest wyraźnie spełnione (wstęgi się stykają),
  albo separacja wstęg `< k·ATR`, albo szerokość szybkiej wstęgi płaska (brak nachylenia).
- **Test: z filtrem konsolidacji i bez.**

### T3 — Filtr ADX (twarda bramka)  ← zrobione, backtest pokazał że SZKODZI
- Port ADX (Wilder) do `com.adam.server.sdd.Adx` — zrobione.
- Bramka: wejścia tylko gdy `ADX > próg` **i** DI po stronie trendu.
- Wniosek z backtestu: twarda bramka wycina zyskowne wczesne wejścia → patrz T3'.

### T3' — ADX jako „permisja koloru", nie filtr siły  ← zrobione w kodzie
- W filmach ADX to **strefy kolorów** (zielony = graj, niebieski = trend boczny = nie graj,
  czerwony = ostrożność/odwrót). **Progi nie padają** (OI‑2) — nasze 20 to zgadywanka.
- `adxPermit=true` w `HtsBacktestService`: veto **tylko** w „niebieskiej" strefie (`ADX < ADX_BLUE_FLOOR`,
  15.0) lub gdy przeciwne DI wyraźnie prowadzi (`> aligned + DI_OPPOSE_MARGIN`, 5.0).
  Wczesne, przed‑crossowe wejścia **przechodzą**.
- Backtest: 3 stany ADX {off, hard T3, permit T3'} × bufor stopu {0.0, 0.25}.

### T4 — Multi‑target na pivotach + BE  ← zrobione, backtest: gorsze od runner‑lock
- Dzienne PP → R1/R2/R3 / S1/S2/S3 jako TP1/TP2/TP3, 1/3 każdy, **stop → BE po TP1**, ostatnia
  1/3 jak runner. Tryb `pivotTargets`.
- Wynik: D1/H1 recent +0.23 (22) vs +0.58 runner‑lock; **H4/M15 −0.62/−0.33** — tryb pivotów
  na M15 nie działa. **Runner‑lock (TP1 1:2 + trail) zostaje modelem domyślnym.** Pivoty jako
  opcja tylko dla D1/H1.

### T5 — Model ryzyka / sizing wg filmów
- Sizing przez dystans stopu → strata stała % (0.25–1%). Wzór:
  `lot = ryzyko$ / (dystans_SL_pips × wartość_pipsa_na_lot)`.
- Łączny cap ryzyka na koncie; **2 straty pod rząd → stop dnia**; twardy DD 20% → close‑all.
- Po stronie backtestu: **zrobione** — `equity_simulator.py --day-stop N --max-dd PCT`
  (kolumny `skipped_daystop`, `skipped_ddstop`, `ddstop_hit` w `summary.csv`).
- Do zrobienia: sizing‑formula w żywym `RiskPolicy` (T9). **Bez** okien handlu / filtra sesji — to scalp.

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

### T11 — Kolejne warianty HTS jako osobne żywe konfiguracje
- Po ustabilizowaniu głównego modelu HTS na 3. koncie: dołożyć drugą parę swingową,
  a potem **H1/M5 jako 3. wariant** (scalp mechaniki wstęg, bez okien sesyjnych) —
  **po** domaszerowaniu M15 + swing + 2 modeli swingowych HTS.

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

## Otwarte kwestie (OI) — z 2. podsumowania filmów

| # | kwestia | robocza hipoteza / co robimy |
|---|---|---|
| OI‑1 | pivoty vs linijka Fib jako TP | Fib = sprawdzenie R:R; pivoty = faktyczne TP. Mamy oba tryby → porównanie w T4. |
| OI‑2 | progi ADX (zielony/niebieski/czerwony) nie padają | T3': veto tylko strefa „niebieska" (`ADX<15`) + DI. Do kalibracji na forwardzie. |
| OI‑3 | warunki re‑entry po wyjściu | nieostre — na razie: nowy pełny setup (pullback+reclaim), nie „wskok z powrotem". |
| OI‑9 | opis SL w filmie 3 wygląda na odwrócony | trzymamy: wąski = wstęga 33, szeroki = wstęga 144. |
| — | filmy opisują **2 opcje**: swing (D1/H1) i scalp (H1/M5 + okna 2 h) | **priorytet: 2 modele swingowe**; H1/M5 jako 3. wariant później (T11), bez okien handlu. |
| — | moduł 1 (Mindset) i moduł 4 (Position Mgmt / Trade Styles) | **nieprzeanalizowane** — jest jeszcze materiał źródłowy poza 3 transkrypcjami. |

---

## Wyniki (backtest, RR=2, model wyjścia = TP1 1:2 + runner z lock 1.0R + trail wstęgi, limit 4, skip‑konsolidacji on)

### Model D1/H1 (główny swing) — ALL avgR w R (stop = −1.0), n w nawiasie — **przebieg zaufany**

| okno | ADX off, buf 0 | ADX off, buf .25 | ADX hard, buf 0 | ADX hard, buf .25 | ADX permit, buf 0 | ADX permit, buf .25 |
|---|---|---|---|---|---|---|
| m2back | −0.079 (6) | −0.113 (6) | −0.646 (5) | −0.657 (5) | −0.079 (6) | −0.113 (6) |
| recent | +0.194 (16) | +0.402 (12) | +0.188 (9) | +0.002 (8) | +0.430 (15) | **+0.584 (9)** |

Pivoty (tryb `pivotTargets`): D1/H1 recent **+0.230 (22)**, m2back −0.152 (6). H4/M15 pivoty
**−0.62 (121) / −0.33 (122)** — dużo tradów, mocno ujemne, tryb pivotów na M15 nie działa.

**Wnioski:**
1. **Bufor stopu 0.25 pomaga** na oknie recent (D1/H1: +0.194→+0.402 bez ADX; +0.430→+0.584 permit).
   Na m2back marginalnie gorzej (próbka 6). **Zostaje jako domyślne.**
2. **Model wyjścia z lockiem ścina straty runnera** — D1/H1 m2back bez ADX z −0.245 (stary runner)
   na **−0.079**. Druga połowa nie oddaje już zysku po TP1.
3. **ADX‑permit ≥ ADX‑off** na D1/H1 (recent +0.430 vs +0.194 buf0; +0.584 vs +0.402 buf25) —
   tu **pomaga**. **ADX‑hard dalej szkodzi** (recent buf25 +0.002). Na D1/H1 rozważyć permit jako
   domyślny.
4. **Runner‑lock >> pivoty** na obu modelach; pivoty na H4/M15 wręcz szkodzą.
5. **Flip reżimu trwa** — m2back ≈ −0.08…−0.66, recent +0.0…+0.58. Trend‑rider.
6. **H4/M15 (core) — brak zaufanego przebiegu:** Capital.com throttluje przy dłuższych gridach
   (druga tura pod rząd = same n=0). Trzeba puszczać **jeden model / świeża sesja**, nie łańcuchem.
   Pierwsza (częściowo zdegradowana) tura H4/M15 dawała ten sam kierunek: buf pomaga, permit≫hard.

**Wniosek zbiorczy (SDD‑M15 + swing + HTS):** żadna nie ma udowodnionego stabilnego edge na tych
danych. Wszystkie to trend‑followery — EV zależy od tego czy okno testowe trendowało.
Miesiąc forward na 3 kontach demo rozstrzygnie to lepiej niż backtest.

**Następne:** czysty przebieg H4/M15 (świeża sesja); potem T6/T7 (split‑entry, piramida) na CSV,
potem T9 (żywy book `hts` + konto „Account m5").

## Stan „co jest w kodzie" (backtest, nie live)

- `Band` (high/low 33/144) — jest.
- `Supertrend`, `WaveTrend`, `Ema`, `Sma` — jest (niewpięte w silnik).
- Band‑entry + runner „close pod wstęgą" — jest w `SwingBacktestService` (H4/H1) i `BacktestService` (H1/M15)
  jako tryby backtestu.
- `HtsBacktestService` — **jest** (T1‑T4): timeframe‑generyczny, `stopBufferFrac` (bufor stopu),
  `adxPermit` (T3'), `pivotTargets` (T4), runner. Endpoint `GET /api/hts/backtest` (admin).
- `equity_simulator.py` — **jest** `--day-stop` / `--max-dd` (T5, strona backtestu).
- Żywe silniki (`SddEngine`, `SddSwingEngine`) i egzekucja (`ExecutionGate`, `SwingExecutionGate`) — **nietknięte**.
