# SDD‑M15 + Swing — analiza skanów i egzekucji

Stan kodu na `main` @ `fe11a1f` (po #52/#53). Nic z tego nie jest jeszcze w kodzie —
to analiza + plan pod Twoje 6 zadań. Zadanie 1 (test portfela) czeka na `equity_simulator.py`
+ CSV wariantów.

---

## 1. Co robi kod dzisiaj

### SDD‑M15 — `SddEngine` + `ExecutionGate`

**Skan** (na zamkniętej świecy M15):

| warunek | reguła | rola |
|---|---|---|
| HA flip | `HeikenAshi.from(m15)` — ostatni bar zmienił kolor | **wymagany** |
| RMA stacked (M15) | `close > RMA33 > RMA133` (BUY) / odwrotnie | wymagany |
| H1 „with" | `HA(H1) zgodny` **lub** `close(H1) > RMA33(H1) > RMA133(H1)` | wymagany |
| PP aligned | `close` po właściwej stronie PP z poprzedniej sesji (BTC pomija) | wymagany |
| H4 note | `HA(H4)` + `close vs RMA33(H4)` | **tylko log, nie filtr** |
| `h1Supporting` | `close(H1) vs RMA33(H1)` | policzony, **nieużywany** |

`fullStack = flip && rmaWith && h1With && ppOk`. Kierunek = kolor HA M15.

**Ryzyko/egzekucja:** 1R = 1× H1 ATR14 (Wilder). Stop = 2.5× ATR.
Dwa tickety (split gdy `perTicket ≥ MIN_DEAL_SIZE`):

- **Ticket A (TP):** stop 2.5R + twardy TP na 1R (PUT razem — quirk Capital).
- **Ticket B (runner):** stop 2.5R, bez TP. Po zejściu A trzyma stop 2.5R, potem
  **H1‑trailing**: `stop = mid ∓ 2.5×ATR`, podłoga = oryginalny stop 2.5R,
  **nigdy do BE**, tylko zapadka w stronę zysku.

Max 4 nazwy, brak piramidy. Halt dnia demo −30 / live −18. News blackout T±30.

### Swing (H1 egzekucja / H4 kontekst) — `SddSwingEngine` + `SwingExecutionGate`

**Skan** (na zamkniętej świecy H1):

| warunek | reguła | rola |
|---|---|---|
| HA flip (H1) | jak M15, ale na H1 | wymagany |
| RMA stacked (H1) | `close > RMA33 > RMA133` | wymagany |
| PP aligned (H1) | poprzednia sesja | wymagany |
| **H4 trend** | `HA(H4) bull && close>RMA33(H4)` → UP; oba bear → DOWN; else FLAT | **twardy filtr kierunku** (FLAT = przepuszcza oba) |

**Ryzyko/egzekucja:** 1R = 1× **H4** ATR14. Stop = 2.5× H4 ATR. **Target = 1× H4 ATR (stałe).**
**Jeden ticket**, stop + TP PUT razem. Bez runnera, bez trailingu.
`size = cash / (2.5·ATR)` → trafiony stop = **−1.0% equity**, trafiony TP = **+0.4% equity**.

---

## 2. Słabości (posortowane wg wpływu)

1. **Swing ma R:R = 0.4 : 1 na całej pozycji.** Target 1 ATR, stop 2.5 ATR, brak runnera.
   Break‑even wymaga **win rate ≥ 71.4 %** (`0.4·W = 1.0·L`). To jest główny powód, dla
   którego wariant „2.5R vs 1.5R vs 1:1" w ogóle ma sens — obecny model jest ustawiony
   na skrajnie wysoką trafialność, której swing H1 raczej nie dowozi.
2. **RMA‑stacked strukturalnie odrzuca setupy mean‑reversion.** Chcesz wchodzić, gdy
   cena cofnęła się *pod* RMA33 w trendzie HTF i LTF daje trigger — a `stacked()` dla BUY
   wymaga `close > RMA33`. Obecny skan wyklucza dokładnie te wejścia, o które pytasz.
3. **Brak selekcji „najlepszych" sygnałów.** Każdy full stack jest równy. Nie ma nigdzie
   pomiaru „czy HTF jest rozciągnięty" (WaveTrend extreme) ani „czy to świeży trigger" (flip Supertrenda).
4. **Filtr H4 w swingu jest binarny i dziurawy.** FLAT przepuszcza oba kierunki (w chop
   filtra praktycznie nie ma). Zero informacji o zmienności / dystansie od średniej.
   W M15 H4 nie jest filtrem w ogóle.
5. **PP jako filtr jest zgrubny** — tylko strona pivota. Brak veta „za daleko od PP"
   (wejście po tym jak cena dobiła do R2 = gonienie).
6. **Runner M15 trailuje sztywnym krokiem 2.5×ATR** — nieadaptacyjny do reżimu.
   Supertrend byłby adaptacyjny (zadanie 3).
7. **Backtest nie umie tego przetestować.** `BacktestService` (1:1 na sztywno) i
   `SwingBacktestService` (+1 / −2.5 na sztywno) — brak parametrów, brak wyjścia per‑trade,
   brak przełącznika filtra HTF, brak wspólnej equity. To blokuje zadania 1, 2, 5.
8. **Okno look‑ahead sztywne** (32 bary M15 / 96 H1). Trade który nie dobił ani TP ani
   stopu = R:0 — zaniża ogon strategii trailującej.
9. **Swing nie ma news blackout** (M15 ma). Wejścia H1 na czerwonych newsach = unikalne straty.
10. **Martwe sygnały:** `h1Supporting` i H4 note w M15 liczone, nieużyte.

---

## 3. Pytanie: sztywny TP z RR czy trailing?

**Odpowiedź: zależnie od strategii, i zrób z tego parametr backtestu.**

- **M15** — zostaw model dwóch ticketów (jest zdrowy), ale:
  - RR nogi TP zrób **parametrem**. Dziś TP = 1·ATR przy stopie 2.5·ATR = efektywnie
    0.4:1. Testuj TP na `{1.0, 1.5, 2.5}× dystansu stopu`.
  - Runner: **trailuj Supertrendem** zamiast sztywnego `mid ∓ 2.5×ATR`.
- **Swing** — najsłabsze ogniwo to pojedynczy ticket z TP 1 ATR. Testuj trzy modele:
  - (a) stały RR na całej pozycji ∈ {1:1, 1.5:1, 2:1},
  - (b) dwa tickety: TP@1R + runner na Supertrendzie (jak M15),
  - (c) czysty trailing Supertrenda, bez stałego TP.
- **Zasada:** stały TP wygrywa na wysokiej częstotliwości „dotknięcia średniej" (M15),
  trailing wygrywa na swingu (mało transakcji, grube ogony, nogi trendu HTF ciągną dniami).
  Twój wynik 2.5R vs 1:1 wskaże kierunek; **test portfela (zadanie 1) rozstrzyga po max DD.**

---

## 4. Pytanie: jak wchodzić w najlepsze sygnały (HTF mean‑reversion → LTF trigger)

To jest filtr HTF/LTF z zadania 4, doprecyzowany:

```
HTF (M15→H1, swing→H4):  WaveTrend(n1=10, n2=21)
  wt1 wraca ze strefy skrajnej:
    ≤ −60  (oversold_extreme)  → bias LONG
    ≥ +60  (overbought_extreme)→ bias SHORT
  → otwiera OKNO SETUPU:  M15 = 4–6 h,  swing = 16–24 h

LTF (M15 / H1) wewnątrz okna:
  pierwszy flip Supertrenda w stronę biasu HTF  = TRIGGER
  trigger = DODATKOWY gate ANDowany na istniejący full‑stack SDD (nie osobny system)
```

**Kluczowa zmiana pod słabość #2:** gdy okno HTF‑MR jest otwarte, **poluzuj wymóg
RMA‑stacked na LTF** do „cena odzyskuje RMA33" zamiast „już nad RMA33/133" — inaczej
skan dalej odrzuca wejście z cofnięcia. Kontekst HTF‑MR zmienia, który warunek LTF obowiązuje.

Kierunek bierze się ze skrajności HTF (oversold extreme → long), potwierdzony tym, że
trend HTF nie jest twardo przeciw. To zastępuje/uzupełnia twardy filtr `h4Trend` czymś,
co realnie koduje „rozciągnięte, potem wznawia".

---

## 5. Supertrend + WaveTrend — port do `sdd` (zadania 3, 4)

Styl spójny z `Wilder` / `HeikenAshi` (statyczne, bezstanowe, tablice `double[]`):

**`Supertrend.java`** — `compute(List<Candle>, int atrPeriod=10, double mult=3, src=(H+L)/2)`
→ `trend[]` (+1/−1), `line[]`, `flipUp[]`, `flipDown[]`. ATR = `Wilder.atr` (RMA, nie SMA TR).
Algorytm 1:1 z Twojej specyfikacji (trailing bandy + flip na przecięciu `finalUp/finalDn`).

**`WaveTrend.java`** — `compute(List<Candle>, int n1=10, int n2=21)` → `wt1[]`, `wt2[]`.
Wzór LazyBear: `ap=(h+l+c)/3; esa=EMA(ap,n1); d=EMA(|ap−esa|,n1); ci=(ap−esa)/(0.015·d);
wt1=EMA(ci,n2); wt2=SMA(wt1,4)`. Progi 60/53/−53/−60.

**Luka:** `Wilder` ma tylko RMA i ATR. WaveTrend potrzebuje **EMA i SMA** — dodać
`Wilder.ema(double[], n)` + `Wilder.sma(double[], n)` (albo mały `Ema`/`Sma`), z testami
jednostkowymi przeciw Twojej implementacji referencyjnej w Pythonie.

---

## 6. Parametryzowany backtest (zadania 1, 2, 5)

Nowy `BacktestParams` + tryb per‑trade. Knoby do sweepu:

| knob | wartości |
|---|---|
| `atrPeriod` | 10, 14 |
| `atrSource` | close (obecne) / (H+L)/2 |
| `stopMult` (× ATR) | 1.5, 2.0, 2.5, 3.0 |
| `tpMode` | none / fixedRR / twoTicket+runner / supertrendTrail |
| `fixedRR` (× dystans stopu) | 1.0, 1.5, 2.0, 2.5 |
| `htfFilter` | off / RMA‑stack (obecne) / WaveTrend‑extreme‑window |
| `ltfTrigger` | SDD‑fullstack / +Supertrend‑flip / +WaveTrend‑turn |
| `perSymbolStop` | off / {GER40,US100,BTC:1.5; XAU:1.0; EURUSD:2.5} |
| `lookAheadBars` | obecne / 2× |

**Wyjście: wiersze per‑trade** — `entryTime, exitTime, symbol, dir, rMultiple, mfe, mae, barsHeld`
→ to karmi wprost symulator portfela z zadania 1.

**Sweep etapami** (nie brute‑force wszystkich kombinacji):
1. baseline,
2. jeden knob naraz vs baseline,
3. best‑of‑each złożone razem,
4. **walidacja out‑of‑sample** (zadanie 2): trening = pierwsze 60 dni, test = kolejne 30
   dni **nienakładające się**. Per‑symbol stop wybierasz tylko na treningu, potwierdzasz
   na teście. Nie trzyma się OOS → jeden stop dla wszystkich.

**Metryki per run:** trades, winRate, avg R, expectancy, profit factor,
**portfolio final equity + max DD** (liczba z zadania 1), % sygnałów które przeżyły filtr (zadanie 5).

---

## 7. Zadanie 1 — status

**Mam:** `tools/equity_simulator.py` (od Ciebie, przetestowany na danych syntetycznych).
Model tego narzędzia: trades sortowane po **`entry_time`**, `equity *= (1 + 0.01 · r_multiple)`
per trade, `exit_time` **nieużywane** w liczeniu (tylko informacyjne), `--mode portfolio` =
jedna wspólna krzywa. To rozstrzyga wcześniejsze pytanie o kolejność — narzędzie liczy
po czasie wejścia, nie zamknięcia.

**Potrzebuję:** 3 CSV wariantów (2.5R / 1.5R / 1:1) w formacie
`entry_time,exit_time,symbol,direction,result,r_multiple` — pełna lista transakcji swing
ze wszystkich symboli razem (nie per‑symbol), to wyjście Twojego backtestu Pythonowego.

**⚠ Rozbieżność do potwierdzenia:** narzędzie robi `equity *= (1 + 0.01·r_multiple)` z
`--loss-r` domyślnie **2.5** → w wariancie „2.5R stop" trafiony stop = **−2.5% equity**.
To NIE jest „stop = −1.0" które zaznaczyłeś w pytaniu (sizing wg `RiskPolicy.sizeFor`, gdzie
stop = 1% equity zawsze). Dwie interpretacje 1R:
- **model narzędzia:** ryzyko 1% na 1 jednostkę R gdzie R = 1 ATR (target). Szeroki stop
  realnie ryzykuje więcej per stop‑out. `r_multiple` w CSV = wynik w ATR (stop 2.5R → −2.5).
- **model `RiskPolicy`:** pozycja sizowana tak, że stop = 1% equity zawsze; `r_multiple`
  znormalizowane (stop = −1.0).
Puszczę backtest **oboma** i pokażę różnicę — ale powiedz który jest „prawdą" dla decyzji.

Wynik: start 10 000, `--mode portfolio --risk 0.01`, per wariant: final equity + max DD
na jednej wspólnej krzywej + `equity_curves.png` (wymaga `pip install matplotlib`).

**Drobiazg w narzędziu:** `spec.split(":", 1)` psuje się na ścieżkach z literą dysku
Windows (`C:\...`). Odpalać z katalogu z CSV i podawać samą nazwę pliku, albo dodam
obsługę `--label`.

---

## 7b. Wyniki testu portfela (120 dni, wspólne konto 10k, swing)

Wygenerowane lokalnie (`SwingBacktestService.runTrades`, żywe świece Capital), policzone
`tools/equity_simulator.py --mode portfolio --risk 0.01`.

**Bez limitu pozycji** (687 tradów/wariant — worst‑case korelacja):

| wariant | win% | avg R | PF | final | zwrot | max DD |
|---|---|---|---|---|---|---|
| stop 2.5R | 66.2 | −0.060 | 0.82 | 6 544 | −34.6% | 38.5% |
| stop 1.5R | 56.9 | −0.048 | 0.89 | 7 002 | −30.0% | 41.2% |
| stop 1:1  | 47.2 | −0.058 | 0.89 | 6 489 | −35.1% | 43.1% |

**Z limitem 4 nazw + no‑pyramid** (jak realny bot — `MAX_OPEN_NAMES`):

| wariant | trades | win% | avg R | PF | final | zwrot | max DD |
|---|---|---|---|---|---|---|---|
| stop 2.5R | 256 | 68.0 | −0.036 | 0.88 | 9 068 | −9.3% | 16.3% |
| **stop 1.5R** | 309 | 59.5 | **−0.004** | **0.99** | **9 781** | **−2.2%** | **16.3%** |
| stop 1:1  | 375 | 49.3 | −0.016 | 0.97 | 9 260 | −7.4% | 18.9% |

Model 2 (1R = 1 ATR, szeroki stop ryzykuje proporcjonalnie więcej), z limitem 4:
2.5R → −23.2% / DD 37%; 1.5R → −4.0% / DD 24%; 1:1 → −7.4% / DD 19%.

**Wnioski:**
1. **Drawdown to była korelacja, nie szerokość stopu.** Limit 4 nazw (który bot już ma)
   redukuje DD z ~40% do ~16–19% i zwrot z −35% do ok. zera. Bez limitu strategia nie
   przeżywa; z limitem — przeżywa, ale bez edge.
2. **`avg R ≈ 0` w każdym wariancie** → sygnał + stały target 1×ATR nie mają wartości
   dodatniej. Zmiana stopu tego nie naprawi.
3. Jeśli już cokolwiek ruszać w stopie: **2.5R → 1.5R** (najlepszy lub równy najlepszemu
   w każdym realistycznym przebiegu; obecny 2.5R jest najgorszy przy limicie 4, zwł. Model 2).
4. **Realna dźwignia to filtr sygnału** (HTF‑MR + LTF‑trigger, Supertrend/WaveTrend) — pkt 3–5.
5. To in‑sample 120 dni. Split OOS (pkt 2) obowiązkowy zanim cokolwiek wdrożymy.

## 8. Proponowana kolejność

1. **(zablokowane)** symulator portfela na Twoich CSV — podeślij pliki.
2. `Ema`/`Sma` + `Supertrend` + `WaveTrend` w `sdd` — czyste wskaźniki, testy vs Python.
3. Parametryzowany backtest z wyjściem per‑trade.
4. Sweep jeden‑knob‑naraz + split OOS (zadanie 2).
5. Wpięcie zwycięskiej konfiguracji w `SddSwingEngine`/`SddEngine` + gate'y egzekucji za flagami.
6. Bogaty mail swing (zadanie 6) — gdy obiekt sygnału niesie już nowe pola (WaveTrend H4,
   Supertrend H1, ATR, pozycja vs PP).

---

## Uwaga poboczna: sync historii nadal 500/503 na prodzie

`/api/history/sync` → 500 natychmiast, `/api/history/sync-all` → 503 (Heroku 30 s timeout).
Ta sama niedoograniczona pętla po transakcjach co analytics (#53 utwardził tylko
`symbol-stats`). Fix gotowy (budżet czasowy w `transactionHistory` + `catch Throwable`),
czeka na Twoją zgodę — niezależne od prac nad strategią.
