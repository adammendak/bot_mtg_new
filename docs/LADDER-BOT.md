# Ladder bot — GER40 i SP500 (long, bez SL, każdy TP czeka)

Wykonanie strategii z `docs/DAILY-SCALP-LONGONLY.md` na dwóch **osobnych kontach demo Capital.com**:
jedno handluje wyłącznie GER40 (`DE40`), drugie wyłącznie SP500 (`US500`). Kod: `com.adam.server.ladder`.

## Zasady (identyczne z backtestem)

- **Tylko long. Brak SL.** Każda pozycja ma własny TP ustawiony po stronie brokera (`profitLevel` w zleceniu).
- **Pierwsze wejście:** raz w dni robocze tuż po otwarciu (GER40 07:05 UTC, SP500 13:35 UTC; okno 15 min), tylko gdy
  na tym koncie nie ma żadnej otwartej pozycji.
- **Dokupienie:** gdy bid spadnie o `step` (0,5%) poniżej NAJNIŻSZEJ otwartej ceny wejścia. O dowolnej porze dnia.
- **Limit:** maks. 10 otwartych pozycji na konto.
- **Wielkość wejścia:** nominał = `gain / tp` × kapitał konta (GER40: 0,2/0,5 = 0,4x; SP500: 0,2/0,75 = 0,27x),
  zaokrąglony do kroku instrumentu. Jeden TP daje ok. `gain` = 0,2% konta.
- **Bezpiecznik konta:** po spadku kapitału o 25% poniżej startu bot przestaje otwierać NOWE pozycje
  (istniejące dalej czekają na TP).
- Bot nie trzyma stanu w bazie: źródłem prawdy jest broker (konto zawiera wyłącznie pozycje tej drabinki).

| | GER40 | SP500 |
|---|---|---|
| epic | `DE40` | `US500` |
| TP | +0,5% | +0,75% |
| krok drabinki | 0,5% | 0,5% |
| max pozycji | 10 | 10 |
| zysk na TP | 0,2% konta | 0,2% konta |
| backtest (osobno, 25 mies.) | ok. 4,6%/mies., min. kapitał 75% | ok. 2,7%/mies., min. kapitał 65% |

## Uruchomienie (Heroku)

1. Załóż dwa konta demo Capital.com (osobny login/API key na każde albo sub-konta pod jednym loginem) i nazwij
   sub-konta tak jak niżej albo podaj własne nazwy w `LADDER_*_ACCOUNT_NAME`.
2. Ustaw zmienne (bez `LADDER_ENABLED=true` bot stoi bezczynnie):

```
LADDER_GER_API_KEY        LADDER_SP_API_KEY
LADDER_GER_API_EMAIL      LADDER_SP_API_EMAIL
LADDER_GER_PASSWORD       LADDER_SP_PASSWORD
LADDER_GER_ACCOUNT_NAME   (domyślnie "GerLadder")
LADDER_SP_ACCOUNT_NAME    (domyślnie "SpLadder")
LADDER_GER_START_EQUITY   (kapitał startowy dla bezpiecznika; bez tego = saldo przy pierwszym ticku po starcie)
LADDER_SP_START_EQUITY
LADDER_ENABLED=true
```

   Parametry strategii (opcjonalne): `LADDER_{GER|SP}_TP_PCT`, `_STEP_PCT`, `_MAX_POSITIONS`, `_GAIN_PCT`,
   `LADDER_FAILSAFE_DROP_PCT`.
3. Po restarcie w logu szukaj `LADDER[GER40]` / `LADDER[SP500]`.

## Pierwsze uruchomienie — co sprawdzić ręcznie

1. W logu: `BUY ... TP ... → ACCEPTED`. Na koncie demo musi być widoczna pozycja z ustawionym limitem (TP).
2. **Czy kolejne wejścia w tę samą stronę tworzą osobne pozycje** (osobne `dealId`, każda ze swoim TP), a nie
   jedną uśrednioną. W Capital.com zależy to od trybu hedging konta. Jeśli pozycje się scalają, bot nie działa tak jak
   w backteście — wtedy wyłącz `LADDER_ENABLED` i daj znać.
3. Wielkość: rozmiar dla 10 000 zł konta GER40 ≈ 0,4 × saldo / cena (po przeliczeniu waluty). Sprawdź, czy nie wypadł
   poniżej minimalnej wielkości zlecenia (log `skip: size ... below the broker minimum`). Przy małym koncie wejście
   bywa zaokrąglane do minimum, czyli większe niż w modelu.

## Czego bot NIE robi

- Nie dotyka realnych pieniędzy: odmawia handlu, gdy host nie jest `demo-api-capital`.
- Nie pilnuje przerwy serwisowej Capital.com (20:59:50–21:05 UTC): zlecenie odrzucone w przerwie jest po prostu
  ponawiane na następnym ticku (co minutę).
- Nie zapisuje wyników do bazy. Wyniki oceniamy z historii transakcji konta demo.
- Wejście po rynku (nie limit na poziomie drabinki), więc fill bywa nieco gorszy niż w symulacji; TP liczony od
  ceny ask w chwili zlecenia, a Capital realizuje TP pozycji long po cenie bid, więc efektywnie TP = D + spread.

## Wersja B (test A/B): ta sama strategia + filtr trendu

Wersja A (konta `LadderGER`, `LadderSP`) działa bez zmian. Wersja B to **dokładnie ta sama strategia** (te same parametry TP,
krok, limit pozycji, g, bezpiecznik) z jedną różnicą: **nowe wejście (dzienne i drabinka) tylko wtedy, gdy zamknięcie poprzedniego
zakończonego dnia (D1) jest powyżej średniej z 50 ostatnich zamknięć D1** (`LADDER_B_TREND_SMA`, domyślnie 50). Filtr liczony raz na
dobę UTC; gdy brakuje 50 zakończonych świec, bot nie wchodzi (próbuje ponownie co tick).

Konta i zmienne (B jest wyłączone, dopóki `LADDER_B_ENABLED` nie jest `true`):

```
LADDER_GER_2_API_KEY    LADDER_GER_2_API_EMAIL    LADDER_GER_2_API_PASSWORD   (konto "LadderGER2", GER40)
LADDER_SP_2_API_KEY     LADDER_SP_2_API_EMAIL     LADDER_SP_2_API_PASSWORD    (konto "LadderSP2", SP500)
LADDER_GER_2_ACCOUNT_NAME / LADDER_SP_2_ACCOUNT_NAME   (opcjonalnie, domyślnie LadderGER2 / LadderSP2)
LADDER_GER_2_START_EQUITY / LADDER_SP_2_START_EQUITY   (opcjonalnie, baza bezpiecznika)
LADDER_B_ENABLED=true
```

Backtest (g = 0,2%): GER40 4,6% → 4,1%/mies. (min. kapitał 75% → 79%), SP500 2,7% → 2,3%/mies. (min. kapitał 65% → 86%, max
obsunięcie 45% → 25%). Bez spadku rynku A i B wchodzą niemal tak samo, więc różnicę widać dopiero po spadku (ocena po min. 6–8
tygodniach albo po pierwszym spadku indeksu o 3–4%). Kryterium: B ma sens, jeśli przy wyniku nie mniejszym niż ok. 80% wyniku A
ma wyraźnie płytsze obsunięcie.

## GER40 także na kontach SP (od 2026-11-05)

Konta `LadderSP` (A) i `LadderSP2` (B) handlują teraz dwa niezależne drabinki: SP500 (`US500`) i GER40 (`DE40`).
Konta `LadderGER` / `LadderGER2` zostają tylko z GER40. Bot `GER40@SP` (B: `GER40@SP-B`) używa tych samych parametrów
co `GER40` (TP, krok, K, g, godzina wejścia 07:05 UTC, filtr SMA50 w B) i tego samego połączenia z brokerem co SP500;
pozycje rozróżnia po instrumencie, więc każda drabinka widzi i rozbudowuje tylko swoje. Nie ma nowych zmiennych środowiskowych.

Uwaga na ryzyko: oba indeksy dzielą kapitał jednego konta, a g liczy się od całego konta, więc łączne zaangażowanie jest większe
niż na kontach jednoindeksowych (przy g = 0,1% i pełnych drabinkach około 3,3x kapitału w nominale). Badanie
`DAILY-SCALP-LONGONLY.md` §11 pokazuje, że stackowanie indeksów bez podziału g jest ryzykowne; ewentualne obniżenie g
na tych kontach wymaga osobnej zmiennej.

Osobny g dla GER40 na kontach SP: `LADDER_SP_GER_GAIN_PCT` (0 lub brak = takie samo jak `LADDER_GER_GAIN_PCT`). SP500 na tych kontach
ma `LADDER_SP_GAIN_PCT` (to samo dla A i B). Plan testu agresywnego: `LADDER_SP_GAIN_PCT=0.2` + `LADDER_SP_GER_GAIN_PCT=0.2`,
konta `LadderGER` / `LadderGER2` zostają na `LADDER_GER_GAIN_PCT=0.1`.

## TP na poprzednim szczycie (ATH) — od 2026-11-05

Każda pozycja dostaje przy wejściu `TP = max(static, ATH)`: blisko ATH static (0,5% GER40 / 0,75% SP500) jest dalej niż
szczyt, więc zostaje static; po cofnięciu pozycja czeka na poprzedni szczyt. Wejścia (otwarcie dnia, drabinka co 0,5%, K10)
i rozmiar (`g / static TP`) bez zmian — dalszy TP płaci więcej za trafienie, ale trzyma pozycję dłużej (finansowanie).
ATH = najwyższy `high` zakończonych świec D1 z ostatnich `LADDER_ATH_LOOKBACK_DAYS` (730) dni, pobierany raz na dobę UTC
na bota; gdy pobranie się nie uda, wejście dostaje static TP (log `ATH lookup failed`). Badanie i tabele:
`docs/LADDER-TP-TARGETS-2026-11.md`.

* `LADDER_TP_ATH` (domyślnie `true`) — boty A (LadderGER, LadderSP, GER40@SP); `LADDER_B_TP_ATH` (`true`) — boty B.
  `false` = wrót do static TP bez zmiany kodu. Dotyczy tylko NOWYCH wejść; otwarte pozycje zachowują swój TP.
* Log wejścia: `BUY … TP 24500.0 (ATH 24500.0)` albo `(static: ATH unknown)`.
* Brak marginesu (`margin x1.2 > available`) lub rozmiar poniżej minimum brokera = wejście pominięte z ostrzeżeniem w logu,
  zlecenie nie jest składane. Dłuższe trzymanie pozycji wiąże margines na dłużej, więc takie pominięcia mogą być częstsze.
