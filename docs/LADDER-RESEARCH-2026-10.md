# Ladder GER40 / SP500 — badanie permutacji, 16 lat historii, nakładka QQQ

Badanie (bez zmian w kodzie bota). Cel: znaleźć wersję B drabiny (long, bez SL, TP czeka) o najwyższym wyniku przy najmniejszym
obsunięciu oraz sprawdzić dodatkowy sleeve trendowy na QQQ. Skrypty: `tools/long_tp_d1_bt.py`, `long_tp_d1_grid.py`,
`walk_forward_bt.py`, `qqq_trend_bt.py`, `blend_bt.py`, `long_tp_improve_bt.py` (M5), `long_tp_atr_bt.py`.

## 1. Streszczenie

1. **Obecne ustawienie A (g = 0,2% na TP) jest zbyt agresywne dla GER40.** Na 16 latach (z kryzysem z marca 2020) model
   ostrożny daje najniższy kapitał 18% konta (a przy trajektorii dnia O‑H‑L‑C praktycznie zero), model optymistyczny 90%. 25-miesięczny
   backtest M5 tego nie pokazywał, bo nie ma w nim 2020 ani 2011.
2. **Realistyczny wynik przy kontrolowanym ryzyku to ok. 4–6% rocznie (stres) do 10–15% rocznie (optymistycznie) przy obsunięciu
   do 25%**, a nie 6% miesięcznie. 3–5% miesięcznie z poprzedniego backtestu odpowiadało obsunięciu 45–50% i dotyczy tylko hossy 2024–26.
3. **Co poprawia drabinę (odporne na 3 indeksy i 2 modele):** filtr trendu (SMA50–200 z D1) i hamulec po dniu spadkowym
   (brak dokupywania po spadku dnia o więcej niż 1,5–2%), szerszy TP (GER40 1,0%, SP500 1,5%) i szerszy krok (1,5–2%).
4. **Co niczego nie dodaje:** trzymanie zysku po TP (Heiken Ashi M15/M30/H1/H4, EMA 8/20, 5/13, 13/34 na M5–H1, trailing stały i ATR,
   wyjście połówkowe). Wynik zmienia się o ±0,1–0,2 pkt przy tym samym obsunięciu; 52–62% takich wyjść kończy poniżej TP.
5. **Skalowanie wejść w głąb drabiny** działa jak niższe g: zmniejsza wynik tak samo jak ryzyko.
6. **Sleeve QQQ z przecięciem EMA 100/200:** 16,1% rocznie przy obsunięciu 28,9% (kupuj i trzymaj: 17,5% i −35,6%), ale to tylko
   3 transakcje w 16 latach. Jako CFD finansowanie 5,5%/rok obniża wynik do 10,7%. Korelacja z drabiną 0,3–0,5.
7. **Walidacja poza próbą:** g dobrane na latach 2010–17 daje w latach 2018–26 obsunięcie 0,25–0,53 (do 2 razy większe niż założone 25%);
   w drugą stronę 0,12–0,24. G z backtestu warto więc dzielić przez ok. 2.
8. **Rekomendacja:** test A/B: A (obecne) vs B1 (A + SMA50, PR #179) przy tym samym, niższym g (0,1%); osobno ramię C ("odporne 16 lat":
   krok 1,5–2%, szerszy TP, hamulec 1,5%). QQQ jako odrębny sleeve po decyzji o instrumencie (akcje vs CFD).

## 2. Metoda i ograniczenia

- Dane Capital.com (mid): D1 od 2010 (US500, US100, DE40, QQQ), M5 25 miesięcy.
- Model dzienny nie zna kolejności zdarzeń w ciągu dnia, więc trzy tryby: **opt** (wszystkie wejścia mogą zamknąć się na TP tego samego dnia),
  **cons** (wejścia z poziomów drabiny w świecy zamykają się najwcześniej następnego dnia), **path** (dzień wzrostowy O‑L‑H‑C, spadkowy O‑H‑L‑C).
  Kapitał liczony po low świecy (najgorszy przypadek). Spread 0,012% na TP, finansowanie 5,5%/rok, marża 5%, zamknięcie przez brokera przy 50% marży.
- **Kalibracja na M5 (25 mies., g = 0,2%, K = 10, krok 0,5%):**

| | M5 | D1 opt | D1 cons | D1 path |
|---|---|---|---|---|
| GER40 wynik/rok, min. kapitał, max DD | 55%, 0,75, 0,47 | 72%, 1,00, 0,22 | 34%, 0,53, 0,56 | 35%, 0,56, 0,55 |
| SP500 wynik/rok, min. kapitał, max DD | 32%, 0,65, 0,45 | 39%, 0,72, 0,39 | 24%, 0,60, 0,47 | 26%, 0,61, 0,47 |

  Wyniki M5 leżą między opt a cons/path, więc opt to górne, a cons/path dolne widełki.
- **Porównanie przy tym samym ryzyku:** dla każdej konfiguracji g jest skalowane do największej wartości bez stop-outu z obsunięciem ≤ 25% (i 35%)
  oraz najniższym kapitałem ≥ 60%. Ocena = wynik roczny przy tym g. Rozmiar liczony od kapitału początkowego (bez procentu składanego).

## 3. Wyniki

### 3.1 Obecne A na 16 latach (g = 0,2%)
| indeks | min. kapitał opt | min. kapitał cons | uwaga |
|---|---|---|---|
| GER40 (D 0,5%) | 0,90 | **0,18** (2020‑03‑19); path ok. 0 | krach COVID, dźwignia 4x |
| SP500 (D 0,75%) | 0,87 | 0,64 (2011‑10‑04) | |
| US100 (D 0,75%) | 0,90 | 0,71 | |

G mieszczące A w obsunięciu 25%: GER40 0,03–0,09%, SP500 0,08–0,13%, US100 0,14–0,22% (cons–opt).

### 3.2 Siatka 16 lat (≈16 tys. konfiguracji, wynik %/rok przy obsunięciu ≤ 25%, najgorszy z 3 indeksów × 2 trybów)
- **TP:** im szerszy tym lepiej (średnio 1,0 → 3,4 od najwęższego do najszerszego z siatki).
- **Krok:** 0,5% → 1,9; 1,0% → 2,2; 1,5% → 2,3; 2,0% → 2,5.
- **Limit pozycji:** 5 → 2,0; 8 → 2,15; 10 → 2,2; 15 → 2,2 (płaskie, K = 10 wystarcza).
- **Filtry (średnio):** SMA100 2,4; hamulec 1,5% 2,3; SMA200 2,3; hamulec 2% 2,3; blokada 5% od szczytu 2,3; SMA50 2,2; brak 2,0; SMA20 1,9; blokada 3% 1,7.
- **Najlepsze odporne (najgorszy wynik z 6 przypadków):** krok 2,0%, K = 10, szeroki TP, hamulec 1,5–2%: **6,4–6,6%/rok** (średnia 15–16%, opt 15%); uwaga: najlepszy TP leży na krawędzi siatki, więc jeszcze szerszy mógłby być lepszy;
  krok 1,5%, K = 15, hamulec 1,5%: 5,8% (średnia 17,7%); SMA200 + krok 2,0%: 5,1%; SMA100 + krok 2,0%, K = 5: 5,0%.
- Dla porównania A bez filtra: najlepsza konfiguracja bez filtra to 4,1%/rok.

### 3.3 Walidacja poza próbą (g z jednej połowy 2010–17 / 2018–26 zastosowane do drugiej)
| kandydat | GER40 path (wynik/rok: 10–17→18–26 / odwrotnie) | SP500 path | SP500 opt |
|---|---|---|---|
| A | 2,6% / 1,3% | 6,3% / 3,4% | 18,0% / 8,1% |
| B1 (A + SMA50) | 2,6% / 1,7% | 6,6% / 3,7% | 20,6% / 11,7% |
| B2 (krok 2, TP 1,0/1,5, hamulec 2%) | 4,9% / 3,7% | 10,8% / 4,2% | 23,9% / 11,1% |
| B2b (krok 1,5, K15, hamulec 1,5%) | 4,6% / 4,4% | 11,1% / 5,3% | 26,7% / 15,2% |

Obsunięcie poza próbą: 0,25–0,53 (dopasowanie 2010–17 → test 2018–26) i 0,12–0,24 (w drugą stronę) przy dopasowaniu do 0,25. Najgorsze 12 miesięcy przy dowolnym starcie: od −17% do −53% konta (przy g z budżetu 25%).

### 3.4 M5 (25 miesięcy), wyjścia i struktura
- **Wyjścia (g = 0,2%):** STATIC 4,62%/mies. i obsunięcie 0,47 (GER40); HA M30/H1, EMA 8/20 M15/M30, trailing 0,15–0,6% / 0,15–0,4×ATR: 4,4–4,8% przy tym
  samym obsunięciu. Wyjątek: HA H4 „strong” na GER40: 3,9%/mies., obsunięcie 0,35 (mniej wejść). Z filtrem SMA50 ten wariant: 2,8%/mies., obsunięcie 0,20,
  najniższy kapitał 1,00. SP500: bez wyraźnej poprawy (EMA 8/20 M30 „turn” 2,84% vs 2,66%).
- **Struktura przy obsunięciu ≤ 25%:** filtr SMA50 jest najsilniejszy: GER40 średnio 21,4 → 24,9%/rok, SP500 13,0 → 24,5%/rok. SMA200 gorszy
  (19,4 / 12,5), hamulec 2% neutralny. Na M5 krok 0,5% wygrywa (24,7 vs 22,7 przy 2,0%), odwrotnie niż w danych 16-letnich (próbka bez 2020).

### 3.5 Sleeve QQQ (dane dzienne 2010–2026, sygnał na zamknięciu, wykonanie na otwarciu kolejnego dnia, koszt 0,05% na transakcję)
| reguła | wynik/rok | max DD | transakcji/rok | uwagi |
|---|---|---|---|---|
| kup i trzymaj | 17,5% | −35,6% | 0 | |
| EMA 100/200 | 16,1% | −28,9% | 0,18 | 3 transakcje w 16 latach; 13,4% (2010–17), 18,4% (2018–26) |
| EMA 50/200 | 13,1% | −28,9% | 0,42 | |
| cena > EMA200 | 11,3% | −26,3% | 2,7 | |
| skalowane 100/200 (0/50/100%) | 13,9% | −25,5% | 2,9 | |
| z finansowaniem CFD 5,5%/rok | 10,7 / 8,0 / 6,5 / 8,8% | | | te same reguły |
| US500 (kontrola) EMA 50/200 | 8,1% | −28,5% | | |
| TQQQ (3x) | 14–16% | −63…−80% | | nie polecam; dane z lukami |

### 3.6 Konto podzielone: drabina + sleeve QQQ (EMA 100/200), model path (stres) / opt
| drabina | udział QQQ | wynik/rok (path) | max DD (path) | wynik/rok (opt) | max DD (opt) |
|---|---|---|---|---|---|
| GER40 A g0,2 | 0% | 6,3% | 101% | 13,3% | 40,6% |
| GER40 A g0,2 | 50% | 12,8% | 53,8% | 14,9% | 29,7% |
| GER40 B2 g0,18 | 0% | 4,5% | 29,8% | 5,9% | 19,0% |
| GER40 B2 g0,18 | 50% | 12,5% | 29,2% | 12,7% | 25,0% |
| SP500 A g0,2 | 50% | 12,9% | 33,2% | 13,8% | 26,5% |
| SP500 B2 g0,18 | 50% | 12,3% | 25,7% | 12,5% | 23,2% |

Wniosek: sleeve QQQ w tej próbie dawał więcej niż drabina przy porównywalnym obsunięciu, a jako dodatek do drabiny o zbyt dużym g ratuje obsunięcie.
Ale obie strategie są długie na amerykańskie indeksy (korelacja 0,3–0,5), więc w krachu tracą jednocześnie, a przewaga QQQ opiera się na kilku transakcjach w hossie.

## 4. Rekomendacje

1. **Zmniejszyć g na kontach A** (szczególnie GER40) z 0,2% do ok. 0,1% (zmienna `LADDER_GER_GAIN_PCT`, `LADDER_SP_GAIN_PCT`), zwłaszcza zanim
   pomyślimy o realnych pieniądzach. Przy tym samym g porównujemy A i B.
2. **Test A/B:** A vs B1 (A + SMA50, PR #179, oba z g = 0,1%). Rozstrzygnięcie po min. 6–8 tygodniach albo po spadku indeksu o 3–4%.
3. **Ramię C (odporne 16 lat):** krok 1,5–2%, TP GER40 1,0% / SP500 1,5%, K = 10, hamulec 1,5%. TP, krok i limit są już konfigurowalne zmiennymi,
   brakuje tylko hamulca po dniu spadkowym (mała zmiana w `LadderLogic`). Wymaga dwóch kolejnych kont demo.
4. **QQQ:** osobny sleeve (EMA 100/200, ewentualnie wariant skalowany) na akcjach, nie CFD (finansowanie 5,5%/rok zjada 4–6 pkt). Wymaga decyzji o koncie i
   instrumencie; implementacja to prosty dzienny job. Nie jako filtr wejścia drabiny.
5. **Oczekiwania:** 6% miesięcznie nie da się osiągnąć przy kontrolowanym ryzyku tą strategią. Realistycznie 4–6% rocznie (stres) do 10–15% (opt)
   przy obsunięciu ok. 25%.

## 5. Zastrzeżenia
- Model dzienny jest przybliżeniem (widełki opt vs cons/path); M5 sięga tylko 25 miesięcy.
- Wybór najlepszych z kilkunastu tysięcy konfiguracji zawyża wynik; dlatego ranking opiera się na najgorszym z 6 przypadków i walidacji poza próbą.
- Sizing od kapitału początkowego, bez procentu składanego; w bocie rozmiar liczony od bieżącego kapitału.
- QQQ: ceny bez dywidend; wynik sleeve'a zależy od kilku sygnałów.
