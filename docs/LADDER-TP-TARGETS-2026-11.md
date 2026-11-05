# Ladder: TP na poprzednim szczycie po cofnięciu (static vs ATH) — badanie 2026-11-05

Pytanie: czy zamiast stałego TP (GER40 +0,5%, SP500 +0,75%) lepiej ustawiać TP na poprzednim szczycie dziennym, gdy cena jest poniżej niego?

## 1. Reguła
`TP = max(static, poprzedni szczyt)`. Poprzednim szczytem może być: wczorajszy high (`pdh`), najwyższy high z 5 / 10 / 20 / 60 dni, najbliższy swing high
(2-świecowy fraktal, do 120 dni wstecz) albo ATH. Blisko szczytu (GER40 ok. 100 pkt = 0,38%, SP500 ok. 20 pkt = 0,26%) poprzedni szczyt leży bliżej niż static TP,
więc `max()` zostawia static TP — osobny próg nie jest potrzebny. Wielkość wejścia bez zmian (nominał g/D), więc dalszy cel płaci więcej, ale czeka dłużej i
płaci finansowanie (pełna drabinka GER40 przy g = 0,1% to ok. 2x kapitału w nominale = ok. 11% rocznie).

Silnik: `tools/long_tp_d1_bt.py` (`Cfg.target`, `near`, `cap`), siatki `tools/long_tp_target_grid.py` (8 reguł), `tools/long_tp_target_grid2.py` (4752 wyniki: reguła × cap × D × krok × K × filtr × 3 modele),
walidacja `tools/long_tp_target_final.py`, M5 `tools/long_tp_target_m5.py`, rok po roku `tools/long_tp_target_years.py`.

## 2. Główne wnioski
1. **Sama zmiana TP (te same wejścia, krok 0,5%) daje niewiele i nie jest rozstrzygająca.** W modelach konserwatywnych (cons/path) poprawia wynik przy obsunięciu 25%, w optymistycznym (opt) go pogarsza.
   Powód: static TP zależy od tego, ile wejść zamknie się tego samego dnia, a to model dzienny zgaduje.
2. **Najlepsze są konfiguracje, które łączą TP na ATH z szerszą drabinką i hamulcem** (GER40: D 1,0%, krok 1,5%, K 15, hamulec po spadku >2%; SP500: D 1,5%, krok 1,0%, K 10, filtr SMA50).
   Ich wynik jest prawie identyczny w trzech modelach dziennych (nie zależy od kolejności zdarzeń w ciągu dnia) i dodatni w obu połówkach historii.
3. **Na prawdziwych danych M5 (25 miesięcy hossy) nie ma przewagi w stosunku do ryzyka** — przy tym samym obsunięciu 25% wynik jest podobny (GER40 24,8% vs 24,9%), ale obsunięcie przy tym samym g jest o połowę mniejsze.
   Zysk tej konfiguracji to odporność na kryzysy (2020, 2022), nie wyższy wynik w hossie.
4. Kosztem jest finansowanie pozycji czekających na szczyt i długie utknięcie kapitału (GER40: najdłuższa pozycja 748 dni, jak przy static).

## 3. 16 lat (2010-2026), wynik %/rok przy obsunięciu ≤ 25% (g dobrane do budżetu)

### GER40

| konfiguracja | g (path) | opt | cons | path | poza próbą 2010-17→2018-26 (path) | poza próbą 2018-26→2010-17 (path) |
|---|---|---|---|---|---|---|
| A  static D.5 step.5 K10 | 0.028 | 18.4% | 1.8% | 1.5% | 2.6% (DD 38%) | 1.3% (DD 17%) |
| B  static D.5 sma50 | 0.037 | 13.8% | 2.2% | 1.9% | 2.6% (DD 34%) | 1.7% (DD 19%) |
| F1 ATH D1.0 step1.5 K15 crash2 | 0.099 | 9.7% | 9.7% | 9.7% | 10.2% (DD 37%) | 5.9% (DD 17%) |
| F2 hi20 D1.0 step1.5 K15 crash2 | 0.099 | 8.6% | 8.6% | 8.6% | 8.8% (DD 39%) | 5.3% (DD 17%) |
| F3 ATH D.5 step.5 K10 (rule only) | 0.033 | 3.4% | 3.0% | 3.1% | 4.3% (DD 35%) | 2.0% (DD 18%) |
| F4 ATH D.5 step.5 K10 sma50 | 0.039 | 3.7% | 3.3% | 3.4% | 3.9% (DD 30%) | 2.3% (DD 21%) |
| F5 ATH D1.0 step1.0 K10 crash2 | 0.094 | 5.5% | 5.3% | 5.4% | 5.5% (DD 41%) | 3.9% (DD 16%) |

### SP500

| konfiguracja | g (path) | opt | cons | path | poza próbą 2010-17→2018-26 (path) | poza próbą 2018-26→2010-17 (path) |
|---|---|---|---|---|---|---|
| A  static D.75 step.5 K10 | 0.079 | 15.6% | 4.7% | 4.8% | 6.3% (DD 43%) | 3.4% (DD 15%) |
| B  static D.75 sma50 | 0.102 | 17.5% | 5.9% | 5.5% | 6.6% (DD 39%) | 3.7% (DD 16%) |
| F1 ATH D1.5 step1.0 K10 sma50 | 0.484 | 16.1% | 16.0% | 16.0% | 19.7% (DD 40%) | 7.0% (DD 16%) |
| F2 ATH D1.0 step1.0 K10 sma50 | 0.316 | 16.2% | 15.8% | 15.9% | 19.6% (DD 40%) | 6.9% (DD 16%) |
| F3 ATH D.75 step.5 K10 (rule only) | 0.093 | 8.4% | 7.9% | 7.9% | 8.7% (DD 40%) | 4.7% (DD 16%) |
| F4 ATH D.75 step.5 K10 sma50 | 0.086 | 7.9% | 7.6% | 6.5% | 8.2% (DD 40%) | 3.9% (DD 16%) |
| F5 hi20 D1.5 step1.0 K10 sma50 | 0.370 | 11.0% | 10.1% | 10.2% | 13.9% (DD 40%) | 5.6% (DD 13%) |

## 4. 25 miesięcy prawdziwych danych M5 (09.2024-10.2026)

### GER40

| konfiguracja | wejść/rok | wynik/rok przy DD≤25% | g=0,1%: wynik / najniższy kapitał / DD | g=0,2%: wynik / najniższy kapitał / DD |
|---|---|---|---|---|
| A  static D.5 step.5 K10 | 329 | 24.9% | 27.7% / 0.87 / 27% | 55.4% / 0.75 / 47% |
| B  static D.5 sma50 | 328 | 24.8% | 27.7% / 0.87 / 27% | 55.3% / 0.75 / 47% |
| F1 ATH D1.0 step1.5 K15 crash2 | 39 | 24.8% | 13.1% / 0.91 / 14% | 26.3% / 0.81 / 26% |
| F2 hi20 D1.0 step1.5 K15 crash2 | 40 | 25.2% | 13.3% / 0.91 / 14% | 26.7% / 0.81 / 26% |
| F3 ATH D.5 step.5 K10 (rule only) | 90 | 25.0% | 28.8% / 0.93 / 28% | 57.5% / 0.86 / 46% |
| F4 ATH D.5 step.5 K10 sma50 | 90 | 24.7% | 28.5% / 0.93 / 28% | 57.1% / 0.86 / 46% |
| F5 ATH D1.0 step1.0 K10 crash2 | 49 | 21.1% | 11.8% / 0.92 / 15% | 23.5% / 0.84 / 27% |

### SP500

| konfiguracja | wejść/rok | wynik/rok przy DD≤25% | g=0,1%: wynik / najniższy kapitał / DD | g=0,2%: wynik / najniższy kapitał / DD |
|---|---|---|---|---|
| A  static D.75 step.5 K10 | 187 | 16.7% | 16.0% / 0.82 / 24% | 31.9% / 0.65 / 45% |
| B  static D.75 sma50 | 185 | 16.5% | 15.8% / 0.82 / 24% | 31.6% / 0.64 / 45% |
| F1 ATH D1.5 step1.0 K10 sma50 | 35 | 14.5% | 6.5% / 0.90 / 11% | 13.0% / 0.81 / 23% |
| F2 ATH D1.0 step1.0 K10 sma50 | 45 | 14.1% | 9.8% / 0.85 / 18% | 19.6% / 0.71 / 34% |
| F3 ATH D.75 step.5 K10 (rule only) | 84 | 17.4% | 16.8% / 0.84 / 24% | 33.7% / 0.67 / 44% |
| F4 ATH D.75 step.5 K10 sma50 | 83 | 16.5% | 16.2% / 0.82 / 25% | 32.4% / 0.65 / 45% |
| F5 hi20 D1.5 step1.0 K10 sma50 | 35 | 12.9% | 6.0% / 0.90 / 12% | 12.0% / 0.80 / 23% |

## 5. Rok po roku (2019-2026), model path (pesymistyczny) i opt, wynik zrealizowany % konta po finansowaniu

Najniższy kapitał w roku liczony względem kapitału na początku roku (wycena po dziennym low, pozycje przechodzą między latami).

### GER40, g = 0.1%, model path

| konfiguracja | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|
| A  static D.5 step.5 K10 | -7.5% (min 0.97) | +3.7% (min 0.40) | +17.5% (min 0.97) | -7.0% (min 0.64) | +7.6% (min 1.01) | +16.5% (min 0.96) | +19.1% (min 0.84) | +12.3% (min 0.88) |
| B  static D.5 sma50 | -7.5% (min 0.97) | +6.3% (min 0.58) | +13.3% (min 0.97) | -4.6% (min 0.67) | +6.3% (min 1.01) | +13.8% (min 0.95) | +17.1% (min 0.83) | +10.3% (min 0.91) |
| F1 ATH D1.0 step1.5 K15 crash2 | -8.2% (min 0.99) | +47.3% (min 0.80) | +5.5% (min 1.00) | -5.6% (min 0.88) | +23.8% (min 1.00) | +5.6% (min 1.00) | +18.9% (min 0.96) | +8.5% (min 0.97) |

### GER40, g = 0.1%, model opt

| konfiguracja | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|
| A  static D.5 step.5 K10 | +4.8% (min 0.99) | +43.2% (min 0.91) | +22.8% (min 1.00) | -1.5% (min 0.87) | +18.4% (min 1.00) | +28.6% (min 1.00) | +36.6% (min 0.99) | +30.6% (min 0.99) |
| B  static D.5 sma50 | +8.1% (min 0.99) | +23.0% (min 0.95) | +16.4% (min 1.00) | +2.7% (min 0.86) | +10.5% (min 1.00) | +21.6% (min 1.00) | +25.8% (min 1.00) | +17.9% (min 0.99) |
| F1 ATH D1.0 step1.5 K15 crash2 | -8.2% (min 0.99) | +47.3% (min 0.80) | +5.5% (min 1.00) | -5.6% (min 0.88) | +23.8% (min 1.00) | +5.7% (min 1.00) | +19.1% (min 0.96) | +8.5% (min 0.97) |

### GER40, g = 0.2%, model path

| konfiguracja | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|
| A  static D.5 step.5 K10 | -15.1% (min 0.94) | +7.3% (min -0.02) | +35.0% (min 0.96) | -14.0% (min 0.44) | +15.2% (min 1.01) | +33.0% (min 0.94) | +38.3% (min 0.76) | +24.6% (min 0.84) |
| B  static D.5 sma50 | -15.1% (min 0.94) | +12.7% (min 0.28) | +26.6% (min 0.96) | -9.2% (min 0.49) | +12.5% (min 1.01) | +27.6% (min 0.92) | +34.1% (min 0.75) | +20.5% (min 0.88) |
| F1 ATH D1.0 step1.5 K15 crash2 | -16.5% (min 0.98) | +94.6% (min 0.72) | +11.0% (min 1.00) | -11.3% (min 0.85) | +47.5% (min 1.00) | +11.1% (min 1.00) | +37.9% (min 0.95) | +16.9% (min 0.96) |

### GER40, g = 0.2%, model opt

| konfiguracja | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|
| A  static D.5 step.5 K10 | +9.6% (min 0.99) | +86.4% (min 0.89) | +45.7% (min 1.00) | -2.9% (min 0.85) | +36.9% (min 1.00) | +57.2% (min 1.00) | +73.2% (min 0.99) | +61.3% (min 0.98) |
| B  static D.5 sma50 | +16.1% (min 0.99) | +46.0% (min 0.93) | +32.7% (min 1.00) | +5.4% (min 0.83) | +21.1% (min 1.00) | +43.3% (min 1.00) | +51.5% (min 1.00) | +35.8% (min 0.99) |
| F1 ATH D1.0 step1.5 K15 crash2 | -16.5% (min 0.98) | +94.6% (min 0.72) | +10.9% (min 1.00) | -11.3% (min 0.85) | +47.5% (min 1.00) | +11.5% (min 1.00) | +38.2% (min 0.95) | +16.9% (min 0.96) |

### SP500, g = 0.1%, model path

| konfiguracja | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|
| A  static D.75 step.5 K10 | +7.5% (min 0.98) | +6.6% (min 0.72) | +17.1% (min 1.00) | -6.2% (min 0.78) | -6.1% (min 0.99) | +13.6% (min 0.99) | +11.6% (min 0.88) | +11.2% (min 0.97) |
| B  static D.75 sma50 | +6.0% (min 0.98) | +9.4% (min 0.77) | +15.2% (min 1.00) | -5.5% (min 0.78) | -5.8% (min 0.99) | +11.7% (min 0.99) | +11.0% (min 0.92) | +9.0% (min 0.99) |
| F1 ATH D1.5 step1.0 K10 sma50 | +5.3% (min 0.99) | +9.0% (min 0.92) | +6.6% (min 1.00) | -2.1% (min 0.93) | -3.7% (min 1.00) | +12.9% (min 0.99) | +3.8% (min 0.99) | +2.0% (min 0.99) |

### SP500, g = 0.1%, model opt

| konfiguracja | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|
| A  static D.75 step.5 K10 | +14.6% (min 0.99) | +26.9% (min 0.83) | +23.6% (min 1.00) | +3.1% (min 0.89) | -4.6% (min 0.99) | +19.9% (min 1.00) | +18.6% (min 0.94) | +17.7% (min 0.99) |
| B  static D.75 sma50 | +10.5% (min 0.99) | +27.9% (min 0.92) | +19.0% (min 1.00) | -1.6% (min 0.86) | -3.6% (min 0.99) | +15.3% (min 1.00) | +14.6% (min 0.98) | +11.9% (min 1.00) |
| F1 ATH D1.5 step1.0 K10 sma50 | +5.3% (min 0.99) | +9.0% (min 0.92) | +6.9% (min 1.00) | -2.1% (min 0.93) | -3.7% (min 1.00) | +12.9% (min 0.99) | +3.8% (min 0.99) | +2.0% (min 0.99) |

### SP500, g = 0.2%, model path

| konfiguracja | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|
| A  static D.75 step.5 K10 | +15.1% (min 0.97) | +13.3% (min 0.58) | +34.1% (min 0.99) | -12.4% (min 0.70) | -12.2% (min 0.99) | +27.2% (min 0.98) | +23.3% (min 0.83) | +22.4% (min 0.95) |
| B  static D.75 sma50 | +12.0% (min 0.97) | +18.8% (min 0.64) | +30.4% (min 0.99) | -11.0% (min 0.69) | -11.6% (min 0.99) | +23.3% (min 0.99) | +22.1% (min 0.89) | +18.1% (min 0.98) |
| F1 ATH D1.5 step1.0 K10 sma50 | +10.5% (min 0.99) | +18.0% (min 0.86) | +13.3% (min 1.00) | -4.1% (min 0.89) | -7.3% (min 0.99) | +25.8% (min 0.98) | +7.5% (min 0.99) | +4.1% (min 0.99) |

### SP500, g = 0.2%, model opt

| konfiguracja | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|
| A  static D.75 step.5 K10 | +29.3% (min 0.98) | +53.8% (min 0.78) | +47.2% (min 1.00) | +6.2% (min 0.86) | -9.2% (min 0.99) | +39.9% (min 1.00) | +37.1% (min 0.92) | +35.4% (min 0.99) |
| B  static D.75 sma50 | +21.1% (min 0.98) | +55.7% (min 0.88) | +38.1% (min 1.00) | -3.1% (min 0.81) | -7.2% (min 0.99) | +30.5% (min 0.99) | +29.2% (min 0.98) | +23.8% (min 1.00) |
| F1 ATH D1.5 step1.0 K10 sma50 | +10.5% (min 0.99) | +18.0% (min 0.86) | +13.8% (min 1.00) | -4.1% (min 0.89) | -7.3% (min 0.99) | +25.8% (min 0.98) | +7.5% (min 0.99) | +4.1% (min 0.99) |

