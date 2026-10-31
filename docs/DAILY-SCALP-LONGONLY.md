# Long-only, bez SL, każdy TP czeka — SP500 / US100 / GER40

Badanie (nie kod produkcyjny). Pytanie: jak daleko może spaść indeks, zanim konto zostanie wyrzucone, jak często
wchodzić, na jakich poziomach i jakim % konta — gdy kupujemy tylko long, nie ma SL, a każda pozycja czeka na własny TP.

Silnik: `tools/long_tp_stack_bt.py`, tabele: `tools/long_tp_stack_report.py`.
Dane: Capital.com, świece M5 (mid), 09.2024–10.2026 (25,6 mies.), US500 / US100 / DE40.

## 1. Model

- **Wejścia (tylko long):**
  - `DAILY_OPEN` — jedno kupno dziennie o otwarciu sesji (US 13:35 UTC, GER40 07:05 UTC).
  - `DIP_x` — limit na poziomie poprzednie zamknięcie × (1 − x%), raz dziennie.
  - `LADDER_s` — pierwsze kupno o otwarciu, kolejne co s% poniżej NAJNIŻSZEGO otwartego wejścia (uśrednianie w dół).
- **Wyjście:** każda pozycja ma własny TP = wejście × (1 + D). Brak SL, brak wyjścia czasowego.
- **Rozmiar:** nominał jednego wejścia n = g / D konta, więc jeden TP daje g % konta.
- **Koszty:** spread 0,012% round-trip odjęty od każdego TP, finansowanie nocne 5,5%/rok od nominału (wariant 0% i 8% niżej).
- **Margin / stop-out:** marża 5% (20:1), broker zamyka pozycje przy kapitale ≤ 50% wymaganej marży (ESMA, detal).
- **Wycena najgorszego przypadku:** kapitał liczony po LOW każdej świecy M5 (pozycje zamykane w tej świecy też są wycenione po low).
- **K** = limit jednoczesnych pozycji (0 = bez limitu).
- Siatka: 3 indeksy × 8 reguł × D ∈ {0,2; 0,3; 0,5; 0,75; 1,0}% × K ∈ {0; 10} × g ∈ {0,1; 0,2; 0,33; 0,5}% = 960 konfiguracji.

## 2. Co indeksy zrobiły w próbie

| indeks | zmiana | max obsunięcie | szczyt | dołek | najgorszy dzień | najgorsze 5 dni |
|---|---|---|---|---|---|---|
| SP500 | +38,2% | −21,9% | 2025-02-19 | 2025-04-06 | −6,0% | −12,7% |
| US100 | +51,1% | −26,6% | 2025-02-18 | 2025-04-06 | −6,2% | −13,5% |
| GER40 | +40,8% | −19,9% | 2025-03-18 | 2025-04-07 | −6,1% | −11,8% |

To jest hossa z jednym kryzysem (kwiecień 2025), który został odrobiony. Całe badanie opiera się na tym jednym teście warunków skrajnych.

## 3. Jak daleko może spaść indeks (teoria)

Zależy tylko od łącznej dźwigni L = suma nominałów otwartych pozycji / kapitał.
Stop-out przy spadku ≈ 1/L − 2,5%, zero na koncie przy spadku 1/L.

| L | stop-out przy spadku | zero na koncie |
|---|---|---|
| 1x | −97,5% | −100% |
| 2x | −47,5% | −50% |
| 3x | −30,8% | −33% |
| 5x | −17,5% | −20% |
| 7x | −11,8% | −14% |
| 10x | −7,5% | −10% |
| 15x | −4,2% | −6,7% |
| 20x | −2,5% | −5% |

Kwiecień 2025 to spadek 20–27% od szczytu. Konto z łączną dźwignią powyżej 5x w najgorszym momencie go nie przeżywa.
W praktyce liczy się dźwignia w najgorszym momencie, a nie średnia — drabinki bez limitu pozycji dobijały tam do 20–50x.

## 4. Ile konfiguracji wyrzuciła broker (stop-out), na 80 konfiguracji

| indeks | g=0,1% | g=0,2% | g=0,33% | g=0,5% |
|---|---|---|---|---|
| GER40 | 2 | 7 | 16 | 18 |
| SP500 | 3 | 16 | 30 | 44 |
| US100 | 5 | 21 | 30 | 46 |

- g ≥ 0,33% na TP: większość konfiguracji nie przeżywa kwietnia 2025.
- g = 0,2% (poziom potrzebny na 6%/mies. przy 30 TP) kasuje co piątą–czwartą konfigurację SP500 i US100.
- Wyrzucane są głównie drabinki bez limitu pozycji i małe D (0,2–0,3%).

## 5. Jak często wchodzić i na jakich poziomach

Wejść miesięcznie przy D = 0,3%:

| reguła | wejść / mies. |
|---|---|
| DAILY_OPEN | ≈ 21,5 |
| DIP_0,25 | 15–17 |
| DIP_0,5 | 10–13 |
| DIP_1,0 | 5–8 |
| DIP_2,0 | 1–3 |
| LADDER (s = 0,5–1,0) | 36–117, zależnie od indeksu |

Czas do trafienia TP: mediana 0,03–0,3 dnia, 95. percentyl 6–31 dni, maksimum 45–170 dni (odrabianie kwietnia 2025).
Najmniejsza chwilowa strata pojedynczej pozycji: DIP_2,0 (−3,4% / −3,9% / −17% zależnie od indeksu).

Pełne tabele: `tools/out/long_tp_report.md` (sekcje A–F, generowane lokalnie).

## 6. Jaki % konta na wejście — konfiguracje, które przetrwały z zapasem

Kryteria: bez stop-outu, najniższy kapitał ≥ 50% konta, bufor ≥ 3% nominału. Wynik zrealizowany %/mies. po kosztach
przy finansowaniu 0% / 5,5% / 8% rocznie.

| indeks | reguła | D | K | g | n na wejście | max łączna dźwignia | wejść/mies. | wynik 0 / 5,5 / 8% | najniższy kapitał | bufor |
|---|---|---|---|---|---|---|---|---|---|---|
| GER40 | LADDER 0,5% | 0,2% | 10 | 0,1% | 0,5x | 5,0x | 67 | 6,3 / 5,6 / 5,2 | 0,59 | 9,3% |
| GER40 | LADDER 0,5% | 0,5% | 10 | 0,2% | 0,4x | 4,0x | 27 | 5,3 / 4,6 / 4,3 | 0,75 | 16,2% |
| US100 | LADDER 0,5% | 0,5% | 10 | 0,1% | 0,2x | 2,0x | 32 | 3,0 / 2,7 / 2,5 | 0,71 | 33,0% |
| US100 | LADDER 0,5% | 0,75% | 10 | 0,2% | 0,27x | 2,7x | 21 | 4,1 / 3,6 / 3,4 | 0,61 | 20,4% |
| US100 | DIP 0,25% | 0,5% | 0 | 0,2% | 0,4x | 4,0x | 17 | 3,2 / 2,9 / 2,7 | 0,78 | 30,0% |
| SP500 | LADDER 0,5% | 0,3% | 10 | 0,1% | 0,33x | 3,3x | 36 | 3,5 / 3,05 / 2,9 | 0,52 | 13,1% |
| SP500 | LADDER 0,5% | 0,75% | 10 | 0,2% | 0,27x | 2,7x | 16 | 3,0 / 2,7 / 2,5 | 0,65 | 21,8% |
| SP500 | DIP 0,25% | 0,5% | 0 | 0,2% | 0,4x | 3,6x | 15 | 2,8 / 2,5 / 2,3 | 0,67 | 18,8% |

n = g / D to nominał jednego wejścia jako wielokrotność konta (0,5x = połowa konta w nominale, przy dźwigni 20:1 to 2,5% konta w marży).
„Najniższy kapitał" to najgorszy moment wyceniony po low świecy, jako ułamek początkowego konta.

## 7. Wrażliwość na koszt finansowania

Liczba stop-outów na 240 konfiguracji (3 indeksy) dla g = 0,1 / 0,2 / 0,33 / 0,5%:

| finansowanie | g=0,1 | g=0,2 | g=0,33 | g=0,5 |
|---|---|---|---|---|
| 0% | 10 | 43 | 75 | 104 |
| 5,5% | 10 | 44 | 76 | 108 |
| 8% | 10 | 44 | 76 | 108 |

Finansowanie zmienia wynik miesięczny o 0,2–1,1 p.p. i prawie nie zmienia przeżywalności; ryzyko ogona to głównie dźwignia w kwietniu 2025, a nie koszt trzymania.

## 8. Wymagana częstotliwość dla 6% miesięcznie

| g na TP | TP / mies. | TP / dzień handlowy | na indeks dziennie |
|---|---|---|---|
| 0,1% | 60 | 2,9 | 1,0 |
| 0,2% | 30 | 1,4 | 0,5 |
| 0,33% | 18 | 0,9 | 0,3 |
| 0,5% | 12 | 0,6 | 0,2 |

Przeżywające z zapasem konfiguracje dają realnie 2,5–5,6%/mies., najlepiej GER40. 6% przeżyło tylko przy najwyższej dźwigni
(GER40 g=0,1%, 5x), z najniższym kapitałem 59% konta.

## 9. Wnioski i zastrzeżenia

1. Bez SL ryzyko nie znika, tylko przechodzi w obsunięcie. Przeżywające konfiguracje miały kapitał spadający do 52–78% konta
   (w gorszych konfiguracjach do 25–40%). Przeżyły, bo indeksy odbiły.
2. Próba to 25 miesięcy hossy z jednym kryzysem. Nie ma w niej spadku wielomiesięcznego (2000, 2008, 2022). Taka sytuacja
   zatrzymałaby pozycje na lata, a finansowanie i brak zapasu marży kończą się stop-outem.
3. Pozycje otwarte na końcu próby (`unresolved`) i maksymalny czas do TP (do 179 dni) pokazują, że kapitał bywa zamrożony miesiącami.
4. Najlepszy kandydat to GER40, potem US100; SP500 jest najwrażliwszy na kwiecień 2025. To jedna próba, nie dowód przewagi.
5. Jeśli to uruchamiać, to jako test na koncie demo: g = 0,1%, D ≈ 0,5%, K = 10, łączna dźwignia ≤ 3–5x, plus bezpiecznik
   na poziomie konta (stop nowych wejść po spadku kapitału o 20–25%) — mimo braku SL na pozycji.
6. Symulacja jest na M5, więc kolejność zdarzeń wewnątrz świecy jest przybliżona (kapitał liczony po low, TP po high w tej samej świecy).
7. Spread jest stały (0,012%); w panice realny spread rośnie. ESMA 50% close-out to założenie dla konta detalicznego.

## 10. Jak odtworzyć

```bash
python tools/long_tp_stack_bt.py --cache <candle cache> --fin 5.5 --out tools/out/long_tp
python tools/long_tp_stack_report.py --cache <candle cache>
```

Cache świec z `tools/capital_data.py`. Pliki wynikowe w `tools/out/` są poza gitem.

## 11. Trzy indeksy na jednym koncie (plany A i B)

Wyniki z `tools/long_tp_combo_bt.py`, finansowanie 5,5%. Osie czasu indeksów zestawione z przeniesieniem ostatniej wartości,
więc to przybliżenie. „Pełne" = każdy indeks dostaje całe konto (g liczone od całego konta), „1/3" = konto podzielone na trzy.

| plan | podział | wynik %/mies. | najniższy kapitał | max łączna dźwignia | stop-out |
|---|---|---|---|---|---|
| A (GER40 D0,5 g0,2 + US100 D0,75 g0,2 + SP500 D0,75 g0,2, drabinka 0,5%, K=10) | pełne | 10,9 | 0,01 | 9,3x | TAK (2025-04-07) |
| A | 1/3 | 3,6 | 0,67 | 3,1x | nie |
| B (to samo z g = 0,1%) | pełne | 6,3 | 0,41 | 5,3x | nie |
| B | 1/3 | 2,1 | 0,80 | 1,8x | nie |

Stackowanie trzech indeksów na jednym koncie bez podziału kasuje konto w kwietniu 2025: indeksy spadają razem.
Wykonanie na dwóch osobnych kontach (GER40 i SP500) opisuje `docs/LADDER-BOT.md`.
