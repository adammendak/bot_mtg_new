# HTS (z filmów) vs. co jest w kodzie

Na podstawie `transkrypcja-6KxsOi17HQ4.txt` (podstawy) + `transkrypcja-S84ianAkeX8.txt` (dziennik) + screeny.

## HTS w skrócie (jak opisujesz na filmach)

**Narzędzie:** 2 **wstęgi** RMA — nie linie:
- szybka: `RMA(high, 33)` i `RMA(low, 33)` → kanał wokół ceny
- wolna: `RMA(high, 144)` i `RMA(low, 144)`
- (film mówi high/low; Ty w wiadomości napisałeś open/close — **do potwierdzenia**)

**Trend:** wstęgi ułożone. „Cross" = szybka wstęga **cała** wychodzi ponad/pod wolną
(nie przecięcie jednej linii) → pierwsza informacja o zmianie trendu. Nie spekulujemy
wcześniej, czekamy na cross.

**HTF/LTF:** głównie **D1 / H1**. Też H4/H1, H1/M15, można H1/M5. Mniejszy interwał = więcej
błędów, ale ta sama metodologia.

**Wejście (long):** cross byczy trzyma + cena **cofa się do wstęgi** (korekta w trendzie) i
**zamyka ciałem nad krawędzią szybkiej wstęgi**. Wchodzisz na krawędzi (wcześniej) — bo
wtedy 1R jest blisko i ryzyko małe. Filtr ADX: maluje pola gdy trend silny → pozwala na
wejście „ryzykowne" przed potwierdzeniem crossa. Gdy wstęgi **nałożone/płaskie** = konsolidacja,
**pomijamy** — gramy tylko gdy wstęgi się **rozjeżdżają**.

**Stop:** **strukturalny** — za krawędzią wstęgi / za punktem kontrolnym (PP). ATR służy tylko
do dobrania wielkości pozycji, nie jest regułą stopu.

**Target:** stałe **RR 1:2 / 1:3** + poziomy pivotów (R1/R2/R3), realizacja częściowa na każdym
(50/50 lub 30%), BE po TP1.

**Wyjście runnera:** **cała świeca ciałem zamyka się pod wolną wstęgą** (dla longa) LUB cena
łamie punkt kontrolny/PP przeciw pozycji.

**Zarządzanie:** ryzyko **~0.5–1%** / trade, twardy DD **20%** (close‑all), dzienny DD ≤ 5%.
**Piramidowanie** — dokładasz pozycje na kolejnych cofnięciach do wstęgi w tym samym trendzie,
pełne wyjście przy złamaniu wolnej wstęgi. Blackout na newsach. Handlujesz tylko instrumenty
**w trendzie** — „z 30 walut + indeksy + metale znajdziesz 4 i to wystarcza".
3 style: swing / scalp (konsolidacja, pivoty) / hedge (pozycja przeciwstawna zamiast stopu, „kolejki").

---

## Rozbieżności kod ↔ filmy

| # | Filmy (HTS) | Kod teraz | Waga |
|---|---|---|---|
| 1 | RMA **wstęga** (2 linie) | pojedyncza `RMA(close)` linia | **duża** — to jest rdzeń narzędzia |
| 2 | wolna długość **144** | **133** (`RMA_SLOW`) | średnia |
| 3 | źródło wstęgi: film **high/low**, Ty **open/close** | close | **do potwierdzenia** |
| 4 | wejście = **cofnięcie do wstęgi** w trendzie | HA flip + `close > RMA33 > RMA133` (trend‑follow, **odrzuca cofnięcia**) | **duża** — konflikt strukturalny (Twój własny opis to potwierdza) |
| 5 | stop = **krawędź wstęgi / PP** (strukturalny) | sztywny **2.5× ATR** | duża |
| 6 | target = **RR 1:2/1:3** + R1/R2/R3, częściowe TP, BE po TP1 | sztywny **1× ATR** TP na jednym tickecie | duża |
| 7 | runner exit = **close pod wolną wstęgą** / złamanie PP | swing: **brak runnera**; M15: trail 2.5×ATR | duża — dopiero to buduję |
| 8 | **filtr ADX** (siła trendu, zgoda na wczesne wejście) | brak | średnia |
| 9 | **rozjazd wstęg** (trend vs konsolidacja) | brak | średnia |
| 10 | **piramidowanie** na kolejnych cofnięciach | **zabronione** (`ExecutionGate`: „NO pyramid") | średnia — konflikt z żywą egzekucją |
| 11 | para **D1 / H1** (główna) | swing = H4/H1, M15 = H1/M15 | średnia — D1/H1 nie istnieje |
| 12 | dzienne pivoty jako **targety** + wejścia scalp | pivoty tylko jako bramka wejścia | średnia |
| 13 | **Heiken Ashi — w filmach NIE MA** | HA flip = obowiązkowa bramka w obu silnikach | średnia — dodaliśmy bramkę której strategia nie używa |
| 14 | ryzyko 0.5%, DD‑stop 20%, dzienny ≤5% | demo ~10 PLN / live 1%; halt −30/−50 PLN | mała |
| 15 | blackout newsów też na swingu | swing bez blackoutu | mała |

## Czego brakuje całkowicie
- filtr ADX (siła trendu)
- detekcja rozjazdu wstęg (trend vs konsolidacja)
- multi‑target na pivotach + częściowe TP + BE
- logika piramidowania
- para D1/H1
- style scalp / hedge (prawdopodobnie poza zakresem bota)

## Najważniejszy wniosek

**Twoja strategia to band‑mean‑reversion‑do‑wstęgi w potwierdzonym trendzie**, ze **stopem
strukturalnym (krawędź wstęgi)** i **stałym RR + wyjściem runnera „close pod wolną wstęgą"**.

Silnik SDD w kodzie to **HA‑flip + RMA‑linia‑stack + PP trend‑follow** — dzieli słownictwo
(RMA 33/133, PP), ale **mechanika jest inna**. HA flip w strategii w ogóle nie występuje.
To tłumaczy, dlaczego backtesty obecnego silnika nie mają stabilnego edge — **nie uruchamiamy
strategii z filmów**.

## Wyniki band‑model (H4/H1, per‑ticker, limit 4)

Konwencja: stop = −1.0R, win (fixed) = +0.4R (targetAtr/stopMult przy 2.5R).

**Okno 2 mies. wstecz (30 dni):**

| | SDD baseline avgR | **band (fixed target)** avgR |
|---|---|---|
| BTC | −0.29 (n16) | −0.48 (n8) |
| EURUSD | −0.25 (n12) | −0.12 (n27) |
| GER40 | −0.07 (n12) | **+0.06 (n31)** |
| US100 | +0.05 (n16) | +0.01 (n64) |
| XAU | −0.30 (n8) | −0.05 (n25) |
| **ALL** | **−0.157 (n64)** | **−0.038 (n155)** |

**Ostatni miesiąc (30 dni):**

| | SDD baseline avgR | **band (fixed target)** avgR |
|---|---|---|
| BTC | +0.06 (n16) | +0.11 (n48) |
| EURUSD | +0.12 (n7) | 0.00 (n35) |
| GER40 | +0.14 (n14) | +0.07 (n59) |
| US100 | −0.20 (n8) | −0.07 (n16) |
| XAU | +0.20 (n14) | +0.07 (n46) |
| **ALL** | **+0.085 (n59)** | **+0.056 (n204)** |

**Wnioski:**
- **Band model jest DUŻO stabilniejszy OOS**: SDD skacze −0.157 → +0.085 (Δ 0.24);
  band skacze −0.038 → +0.056 (Δ 0.09). To jest realna zaleta — spójny generator sygnału.
- avgR dalej cienki (bliski zeru) — sam band‑entry to nie edge, ale przestaje krwawić w złym oknie.
- **3× więcej sygnałów** (155 vs 64, 204 vs 59) — band łapie więcej, można potem filtrować (ADX, rozjazd wstęg).
- **band‑runner: nie ufać średniej.** `recent_band_runner` avgR +0.68 przy n=29 — z tego JEDEN
  trade BTC = **+13.4R** (4‑dniowa noga trendu). To ten sam artefakt co „+1.96R" drugiego bota:
  1–2 monster‑trendy w małej próbce. Runner dodaje gruby prawy ogon (jak na M15: +17% vs +12%),
  ale nikt nie powinien oczekiwać +0.68R/trade.

## Rekomendacja kolejności

1. **Potwierdź**: high/low czy open/close dla wstęg; 144 czy 133.
2. Band‑model jest lepszym fundamentem niż obecny SDD‑stack → zbudować `HtsEngine` (band entry +
   strukturalny stop + runner „close pod wolną wstęgą") jako **3. strategię** obok SDD‑M15 i swing,
   na osobnym koncie demo (Twój pomysł z 3. kontem).
3. Dodać ADX + rozjazd wstęg jako filtry — one wycinają EURUSD/US100‑chop które psują avgR.
4. Multi‑target na pivotach + częściowe TP.
5. M5 na końcu.
