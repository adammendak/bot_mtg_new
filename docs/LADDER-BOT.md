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
LADDER_GER_ACCOUNT_NAME   (domyślnie "Account Ladder GER")
LADDER_SP_ACCOUNT_NAME    (domyślnie "Account Ladder SP")
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
