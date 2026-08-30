# HTS — roadmap wdrożenia

> ## ⇒ WRZESIEŃ: forward-test 3 modeli HTS (PR #65)
> SDD-M15 i SDD-SWING **zarchiwizowane** (kod zostaje, `SCAN_ENABLED=false` / `SWING_ENABLED=false`
> + egzekucja off). HTS przejmuje wszystkie 3 konta demo — **jeden model TF na konto**:
>
> | wariant | model | konto | book | pieniądze |
> |---|---|---|---|---|
> | `CORE` | H4 / M15 | Account m15 | `demo` | demo |
> | `SWING` | D1 / H1 | Account H1 | `swing` | demo |
> | `FAST` | H1 / M5 | Account m5 | `hts` | demo |
> | `CORE_LIVE` | H4 / M15 | **bot trading konto** (~450 PLN) | `live` | **REALNE** — ryzyko 1 % konta, flaga `HTS_LIVE_EXECUTION_ENABLED` (osobna), guardy `pickLiveAccount` + halt `LIVE_HALT_PLN` + min deal size |
>
> `HtsScanService` puszcza wszystkie 3 co **5 min** (`HTS_CRON=0 */5 * * * *`) — FAST działa na
> close M5, CORE/SWING re-sprawdzają ostatnią zamkniętą świecę, gate dedupuje per świeca.
> Uniwersum: **tydzień = GER40/XAU/US100/EURUSD/BTC, weekend = tylko BTC**
> (`SddSymbol.htsUniverseFor`). Egzekucja: `HTS_EXECUTION_ENABLED=true`, 1 ticket na sygnał na
> koncie danego wariantu (`HtsExecutionGate` routuje po `variant.book()`). Sygnały do `hts_signals`
> z kolumną `variant` (changeset 012).

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

### T6 — Split‑entry (skalowanie w zakres, nie martingale)  ← zrobione w backteście
- `splitEntries=n` (`?split=`): `n` równych rung od ceny sygnału w stronę stopu (górna połowa
  zakresu entry→stop), fill przez `PULLBACK_BARS` świec. Pozycja ma **wejście uśrednione** i
  frakcję wielkości = filled/n. Ryzyko liczone od **oryginalnego** dystansu entry→stop →
  częściowo wypełniona drabinka + stop = strata **< 1R**. Wyjście = model runner‑lock.
- `replaySplit` w `HtsBacktestService`. Backtest T6: `HtsExportTest#splitEntry` (split {1,2,3}).
- **Wyniki (ADX‑permit, buf 0.25, RR 2):**

  | model / okno | split 1 | split 2 | split 3 |
  |---|---|---|---|
  | H4/M15 m2back | −0.377 (55) | −0.328 (55) | −0.285 (55) |
  | H4/M15 recent | **+0.197 (65)** | +0.157 (65) | +0.079 (65) |
  | D1/H1 recent | **+0.584 (9)** | +0.268 (9) | +0.221 (9) |

  **Wniosek: split‑entry (ten model) NIE poprawia edge.** W trendzie skaluje wygrane w dół
  mocniej niż lepsze uśrednione wejście to nadrabia (drabinka rzadko wypełnia się w całości →
  frakcja wielkości ~0.4–0.6). W stratnym oknie „pomaga" tylko przez mniejszą ekspozycję — to
  samo daje po prostu niższe ryzyko. **Domyślnie split=1.** Sensowny split wymagałby rung
  sizowanych własnym (ciaśniejszym) stopem tak, że pełne wypełnienie = pełne 1R — to model z T7‑style
  księgowaniem, odłożony.

### T7 — Piramidowanie  ← kod w backteście zrobiony (PR #64), grid w toku

`replayPyramid` w `HtsBacktestService` implementuje poniższy model. `HtsExportTest#pyramidAndIndicators`
puszcza `pyramidMax ∈ {0,1,2,3}` na D1/H1 i H4/M15, oba okna. Wyniki niżej.

**Idea (z filmów):** po TP1, gdy trend trwa, dokładasz kolejne jednostki na każdym powrocie
ceny do szybkiej wstęgi (ten sam setup pullback+reclaim co wejście bazowe). Równe loty, nie
martingale. Pełne wyjście całego stosu przy zamknięciu ciała za wolną wstęgą.

#### Reguła ryzyka — „house money"
Dokładki są finansowane **zrealizowanym + zablokowanym zyskiem**, nie nowym ryzykiem konta.
Niezmiennik: **łączne ryzyko otwartego stosu ≤ 1R bazowego** w każdym momencie.

- Jednostka bazowa U0: ryzyko = 1R (jak dziś). Po TP1 (½ pozycji) stop reszty U0 skacze na
  `entry ± runnerLockR·stopDist` (już jest w modelu wyjścia). Od tego momentu U0 nie ryzykuje
  nic z konta (najgorszy wynik = +runnerLockR).
- Dokładka Uk (k = 1..`pyramidMax`): wielkość = **taka, by jej ryzyko do jej własnego stopu
  równało się buforowi zysku, który mamy „na stole"**, ale nie więcej niż 1R.
  `size(Uk) = min(bufor_zysku_R, 1) / dist(entry_k → stop_k)`.
- Stop dokładki `stop_k` = **wspólny stop trailowany pod szybką wstęgą** (ta sama linia co runner
  U0). Czyli cały stos ma jeden ruchomy stop = krawędź szybkiej wstęgi + bufor 0.25·szer.
- `bufor_zysku_R` = (zrealizowane R z TP1) + (otwarte R stosu wycenione do wspólnego stopu).
  Gdy trend jedzie, bufor rośnie → kolejne dokładki mogą być większe, ale twardy cap 1R/jednostkę
  i **≤ 1R na cały otwarty nierozliczony stos** (suma `size·dist(entry→wspólny stop)` ≤ 1R).

#### Warunki dokładki
1. Bazowa pozycja w zysku (TP1 zrealizowane).
2. Świeży setup: cena weszła do szybkiej wstęgi w ostatnich `PULLBACK_BARS` i ciało zamknęło się
   z powrotem za krawędzią (dokładnie `HtsEngine`/`collect` warunek wejścia).
3. Wstęgi nadal w trendzie (LTF fast czysto nad/pod slow) i **nie w konsolidacji**.
4. `k < pyramidMax` (param, domyślnie 2) i minimalny odstęp `pyramidGapBars` od poprzedniej
   dokładki (żeby jeden szeroki pullback nie odpalił 3 dokładek).
5. `bufor_zysku_R ≥ pyramidMinBufferR` (domyślnie 0.5) — nie dokładamy „na styk".

#### Wyjście
- Wspólny trailing stop pod szybką wstęgą: dotknięcie → zamyka **cały stos** po tej cenie.
- Zamknięcie ciała za wolną wstęgą → zamyka **cały stos** po close (jak runner dziś).
- TP1 dotyczy tylko U0. Dokładki nie mają własnego TP — jadą z runnerem do wolnej wstęgi.
- R całości = Σ per-jednostka `size_k · (exit − entry_k)/stopDist_bazowy` (w jednostkach 1R U0),
  żeby liczby były porównywalne z resztą backtestu.

#### Backtest (`HtsBacktestService`)
- Nowy tryb `replayPyramid(c, rr, runnerLockR, pyramidMax, gapBars, minBufferR)` — rozszerzenie
  `replayRr` runner-branch: po ustawieniu `rA` (TP1) skanuj do przodu; na każdym barze sprawdź
  warunek dokładki (potrzebne serie `c.fast` — już są w `Cand`), dołóż jednostkę do listy
  `List<Unit{entry, sizeR}>`, aktualizuj wspólny `runnerStop` = max(lock, krawędź wstęgi).
  Zamknięcie: wspólny stop albo `bodyBeyondSlow`.
- Params: `int pyramidMax` (0 = wyłączone, domyślnie), `int pyramidGapBars` (domyślnie 5),
  `double pyramidMinBufferR` (domyślnie 0.5). Endpoint: `?pyramidMax= &pyramidGap= &pyramidMinBuf=`.
- Grid: `pyramidMax ∈ {0,1,2,3}` na modelu D1/H1 i H4/M15, okna m2back/recent, z ADX i bez.
  Metryki: **avgR, win rate, max DD, PF** (cel: WR ↑ / DD ↓, nie sam avgR).

#### Żywa egzekucja (po zielonym backteście, osobno)
- `HtsExecutionGate` dziś: 1 ticket na sygnał, `placed` keyed `symbol|dir|barTime`. Piramida
  wymaga: śledzenia otwartego stosu per symbol (ile jednostek, wspólny stop), modyfikacji SL
  wszystkich ticketów przy trailu, oraz reguły „dokładka tylko gdy bazowa w zysku".
- Konflikt z `ExecutionGate` SDD-M15 („NO pyramid") nie dotyczy — to osobny gate/book.
- **Wejdzie za flagą** `HTS_PYRAMID_ENABLED` (domyślnie false), niezależną od `HTS_EXECUTION_ENABLED`.

### T8 — Supertrend / WaveTrend jako opcje  ← zrobione w backteście (PR #64)
- **`supertrendTrail`** (`?supertrendTrail=`): trailing stop runnera podąża za linią Supertrend
  zamiast za krawędzią szybkiej wstęgi (funkcja `trailEdge`). A/B vs domyślny band‑trail.
- **`waveTrendFilter`** (`?waveTrendFilter=`): weto wejścia gdy WaveTrend LTF jest już rozciągnięty
  w naszą stronę (long przy `wt1 ≥ OVERBOUGHT`, short przy `wt1 ≤ OVERSOLD`). Oversold na longu =
  OK (to nasz pullback).
- Wskaźniki: `com.adam.server.sdd.Supertrend`, `WaveTrend` (wyliczane per‑symbol w `collect`).

### Wyniki T7 + T8 (backtest, ADX‑permit, buf 0.25, RR 2, runner‑lock 1.0)

**D1/H1 (przebieg zaufany)** — ALL avgR, n w nawiasie:

| config | m2back | recent |
|---|---|---|
| baza (pyr 0) | +0.111 (5) | **+0.396 (10)** |
| pyramidMax 1 | +0.153 (5) | +0.153 (10) |
| pyramidMax 2 | +0.153 (5) | +0.136 (10) |
| pyramidMax 3 | +0.153 (5) | +0.173 (10) |
| supertrendTrail | +0.111 (5) | **+0.461 (11)** |
| waveTrendFilter | +0.111 (5) | +0.396 (10) |

**H4/M15** (osobny przebieg `pyramidAndIndicatorsH4M15`, próbka 54–72 tradów = wiarygodna):

| config | m2back | recent |
|---|---|---|
| baza (pyr 0) | −0.361 (54) | +0.213 (64) |
| pyramidMax 1 | −0.440 (55) | +0.177 (63) |
| pyramidMax 2 | −0.461 (55) | +0.209 (63) |
| pyramidMax 3 | −0.495 (55) | +0.289 (63) |
| supertrendTrail | **−0.331 (55)** | +0.196 (72) |
| waveTrendFilter | −0.363 (54) | +0.230 (65) |

**Wnioski:**
1. **T7 piramidowanie NIE pomaga.** D1/H1 recent +0.40 → +0.14…+0.17. H4/M15 m2back
   **wyraźnie gorzej** (−0.36 → −0.44 → −0.46 → −0.50 — dokładki kompletują straty w chop),
   recent szum (+0.18…+0.29). Dokładki po TP1 są ścinane na wspólnym trailu zanim przyjdzie
   noga trendu. Ten sam wzór co split‑entry (T6). **Domyślnie `pyramidMax=0`.**
2. **T8 `supertrendTrail` — mały spójny plus.** D1/H1 recent +0.40 → +0.46 (n 10→11);
   H4/M15 m2back −0.36 → **−0.33**, recent ≈ flat ale trzyma więcej runnerów (n 64→72).
   Luźniejszy trail = mniej przedwczesnych stop‑outów. **Zostaje jako opcja, kandydat na default.**
3. **T8 `waveTrendFilter` — praktycznie no‑op.** D1/H1: 0 wejść wyciętych. H4/M15 recent
   +0.213 → +0.230 (marginalnie). Wejście = pullback+reclaim (czyli „kup dołek"), więc WT prawie
   nigdy nie jest wykupiony w naszą stronę w momencie sygnału. **Zostawiamy wyłączony.**
4. Próbki D1/H1 nadal cienkie (5–11 tradów) — flip między dniami przesuwa avgR; H4/M15 wiarygodne.

**Konfiguracja domyślna po T1–T8:** RR 2, runner‑lock 1.0, bufor 0.25, skip‑konsolidacji on,
ADX off (permit opcjonalnie, na D1/H1 pomaga), pivotTargets off, splitEntries 1, **pyramidMax 0**,
**supertrendTrail = opcja do rozważenia jako default**, waveTrendFilter off.

### T9 — Live: 3. book `hts` + 3. konto Capital  ← zrobione (egzekucja domyślnie OFF)
- **Plumbing:** `Books.HTS` + `BrokerBooks.hts()` + bean `htsBroker` (`CAPITAL_HTS_*`),
  `app.capital.hts.*`, `RiskPolicy.pickHtsAccount` (pinuje „Account m5"), grant Liquibase
  `010-hts-book.xml`, book w `/api/accounts` `/api/broker` `/api/positions` `/health`
  (`htsConfigured`), zakładka „HTS" w UI (dashboard, admin, overview).
- **Silnik live:** `HtsEngine` (ostatnia zamknięta świeca, konfiguracja domyślna T1–T6:
  wstęgi 33/144 high/low, pullback+reclaim, skip‑konsolidacji, stop = krawędź szybkiej
  wstęgi + bufor 0.25×szer., TP1 = 2×dystans stopu, ADX off). Model główny **D1(kontekst)/H1(egzekucja)**.
- **Skan:** `HtsScanService` + `HtsScanScheduler` (cron `HTS_CRON`, dom. `0 2 * * * *` — minutę
  za swingiem). Persist do `hts_signals` (`011-hts-signals.xml`), notyfikacja (`LogHtsNotifier`,
  rich mail = T10), oddanie do `HtsExecutionGate`.
- **Egzekucja:** `HtsExecutionGate` na booku `hts`, jeden market ticket (stop + TP1 razem),
  sizing `ryzyko$ / dystans_stopu`. **`HTS_EXECUTION_ENABLED` domyślnie `false`.**
- **API:** `GET /api/hts/last`, `GET /api/hts/signals`, `POST /api/hts/scan` (gate: book `hts`).
- **Do zrobienia po stronie Heroku (Ty):** ustawić `CAPITAL_HTS_API_KEY` / `CAPITAL_HTS_EMAIL` /
  `CAPITAL_HTS_PASSWORD` (konto „Account m5"). Potem `HTS_EXECUTION_ENABLED=true` gdy gotowe.
- **Rozważ:** wyłączenie egzekucji SDD‑M15 na „Account m15" jeśli HTS‑core (H4/M15) ma tam wejść —
  do Twojej decyzji; teraz HTS live idzie na osobne „Account m5".

### T10 — Bogaty mail sygnału HTS  ← zrobione
- `MailHtsNotifier` + `HtsSignalContext`: stan D1 (band + slope), H1 (separacja wstęg, slope,
  ile barów temu pullback), ADX(14)/+DI/−DI + strefa, ATR(14), cena vs pivot, geometria trade'u
  (stop w cenie i %, R:R), akapit „dlaczego". Idzie przez wspólny `Mailer` (no-op bez SMTP)
  na `MAIL_TO` (domyślnie `adam.mendak@gmail.com`).

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

### H4/M15 (core) — czysty przebieg z modelem runner‑lock (split=1, ADX‑permit, buf 0.25)
m2back **−0.377 (55)** · recent **+0.197 (65)**. Ten sam flip reżimu, brak stabilnego edge,
spójne z D1/H1 i z SDD/swing. Sample 55–65 tradów = wiarygodny.

**Konfiguracja domyślna po T1–T6:** RR 2, `runner=true`, `runnerLockR=1.0`, `stopBufferFrac=0.25`,
`skipConsolidation=true`, ADX off (permit jako opcja, na D1/H1 pomaga), `pivotTargets=false`,
`splitEntries=1`.

**Następne:** T11 (drugi model swingowy + H1/M5 jako osobne żywe konfiguracje).
Zrobione: T1–T10, fix `/api/history/sync` 503. T7 (piramida — nie pomaga, `pyramidMax=0`),
T8 (`supertrendTrail` mały plus / `waveTrendFilter` no‑op) — w backteście, PR #64.

## Stan „co jest w kodzie" (backtest, nie live)

- `Band` (high/low 33/144) — jest.
- `Supertrend`, `WaveTrend`, `Ema`, `Sma` — jest (niewpięte w silnik).
- Band‑entry + runner „close pod wstęgą" — jest w `SwingBacktestService` (H4/H1) i `BacktestService` (H1/M15)
  jako tryby backtestu.
- `HtsBacktestService` — **jest** (T1‑T8): timeframe‑generyczny, `stopBufferFrac` (bufor stopu),
  `adxPermit` (T3'), `pivotTargets` (T4), `splitEntries` (T6), `pyramidMax`/`pyramidGapBars`/
  `pyramidMinBufferR` (T7 `replayPyramid`), `supertrendTrail`/`waveTrendFilter` (T8), runner‑lock.
  `GET /api/hts/backtest` z pełnym zestawem `?param=`.
- `equity_simulator.py` — **jest** `--day-stop` / `--max-dd` (T5, strona backtestu).
- **Live (T9)** — **jest**: `HtsEngine`, `HtsScanService`, `HtsScanScheduler`, `HtsExecutionGate`
  (`HTS_EXECUTION_ENABLED=false`), book `hts` na „Account m5", `hts_signals`, `/api/hts/last|signals|scan`.
  Żywe silniki SDD‑M15/swing i ich egzekucja — **nietknięte**.
- **Mail (T10)** — **jest**: `MailHtsNotifier` + `HtsSignalContext` (nota analityczna, D1+H1 obraz wstęg,
  ADX, ATR, geometria). Wspólny `Mailer`, `MAIL_TO`.
- **Fix history‑sync** — **jest**: `transactionHistory(from,to,budget)` w SPI, `CapitalComBrokerClient`
  przerywa spacer po `app.history-sync.walk-budget-seconds` (18 s); `/sync-all` ma własny cap 24 s;
  `safeSync` łapie `Throwable`. Koniec z 503/500 na prodzie.
- Żywe silniki (`SddEngine`, `SddSwingEngine`) i egzekucja (`ExecutionGate`, `SwingExecutionGate`) — **nietknięte**.
