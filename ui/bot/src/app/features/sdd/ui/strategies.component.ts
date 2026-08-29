import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { HealthInfo } from '../model/sdd.model';

interface StrategyRow {
  label: string;
  value: string;
}

interface StrategyCard {
  name: string;
  tag: string;
  headerClass: string;
  account: string;
  status: string;
  statusClass: string;
  rows: StrategyRow[];
}

/**
 * Reference page: every strategy the bot runs, written out — timeframes, entry /
 * stop / exit rules, which Capital.com demo account it trades, and whether
 * execution is on. Static spec + the live execution flags from {@code /health}.
 */
@Component({
  selector: 'app-strategies',
  standalone: true,
  template: `
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <h2 class="h4 mb-0">Strategie</h2>
      <span class="small text-muted">
        3 strategie, 3 konta demo Capital.com. HTS — backtest, jeszcze nie handluje.
      </span>
    </div>

    <div class="row g-3">
      @for (s of cards(); track s.name) {
        <div class="col-lg-6">
          <div class="card shadow-sm h-100">
            <div class="card-header d-flex justify-content-between align-items-center" [class]="s.headerClass">
              <span>{{ s.name }} <span class="badge text-bg-light ms-1">{{ s.tag }}</span></span>
              <span class="badge" [class]="s.statusClass">{{ s.status }}</span>
            </div>
            <div class="card-body p-0">
              <table class="table table-sm table-striped mb-0">
                <tbody>
                  <tr>
                    <td class="text-muted" style="width: 34%">Konto Capital</td>
                    <td>{{ s.account }}</td>
                  </tr>
                  @for (r of s.rows; track r.label) {
                    <tr>
                      <td class="text-muted">{{ r.label }}</td>
                      <td>{{ r.value }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        </div>
      }
    </div>

    <p class="small text-muted mt-3">
      Uniwersum (wszystkie strategie): GER40 / XAU / US100 / EURUSD / BTC. Weekend: tylko BTC.
      Szczegóły i wyniki backtestów: <code>HTS-ROADMAP.md</code>, <code>HTS-vs-KOD.md</code>,
      <code>STRATEGY-ANALYSIS.md</code> w repo.
    </p>
  `,
})
export class StrategiesComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly cards = signal<StrategyCard[]>(this.build(null));

  ngOnInit(): void {
    this.http.get<HealthInfo>('/health').subscribe({
      next: (h) => this.cards.set(this.build(h)),
      error: () => this.cards.set(this.build(null)),
    });
  }

  private build(h: HealthInfo | null): StrategyCard[] {
    const m15On = !!h?.executionEnabled;
    return [
      {
        name: 'SDD-M15',
        tag: 'M15',
        headerClass: 'bg-primary text-white',
        account: 'Account m15 (demo) + bot trading konto (live)',
        status: m15On ? 'EXECUTION ON' : 'execution off',
        statusClass: m15On ? 'text-bg-danger' : 'text-bg-secondary',
        rows: [
          { label: 'Interwały', value: 'egzekucja M15 · kontekst H1 · nota H4' },
          { label: 'Skan', value: 'co M15 close (cron 0 1,16,31,46 * * * *)' },
          { label: 'Wejście', value: 'HA flip M15 + RMA33>RMA133 (M15) + H1 zgodne + PP (BTC pomija)' },
          { label: '1R / stop', value: '1R = 1× H1 ATR14 · stop = 2.5× ATR' },
          { label: 'Egzekucja', value: '2 tickety: A = twardy TP 1R · B = runner, stop 2.5×ATR H1-trail, nigdy do BE' },
          { label: 'Ryzyko', value: 'demo ~10 PLN · live 1% · halt −30/−50 (demo), −18 (live)' },
          { label: 'Inne', value: 'max 4 nazwy, bez piramidy · news blackout T±30' },
        ],
      },
      {
        name: 'SDD-SWING',
        tag: 'H1',
        headerClass: 'bg-info text-dark',
        account: 'Account H1 (demo)',
        status: h?.swingConfigured ? 'skonfigurowane' : 'nieskonfigurowane',
        statusClass: h?.swingConfigured ? 'text-bg-success' : 'text-bg-secondary',
        rows: [
          { label: 'Interwały', value: 'egzekucja H1 · kontekst H4' },
          { label: 'Skan', value: 'co H1 close (cron 0 1 * * * *)' },
          { label: 'Wejście', value: 'HA flip H1 + RMA33>RMA133 (H1) + PP + trend H4 (twardy filtr kierunku)' },
          { label: '1R / stop', value: '1R = 1× H4 ATR14 · stop = 2.5× ATR H4' },
          { label: 'Egzekucja', value: '1 ticket · stały TP 1R · bez runnera' },
          { label: 'Mail', value: 'notatka analityczna na każdy sygnał → adam.mendak@gmail.com' },
          { label: 'Uwaga', value: 'R:R 0.4:1 na całej pozycji — brak stabilnego edge w backteście' },
        ],
      },
      {
        name: 'HTS (wstęgi)',
        tag: 'band',
        headerClass: 'bg-dark text-white',
        account: 'Account m5 (demo)',
        status: 'backtest',
        statusClass: 'text-bg-warning',
        rows: [
          { label: 'Modele TF', value: 'H4/M15 (core) · D1/H1 (swing) · H1/M5 (fast)' },
          { label: 'Wstęgi', value: 'RMA(high) / RMA(low) — szybka 33, wolna 144' },
          { label: 'Trend', value: 'szybka wstęga cała nad/pod wolną · brak wejść w konsolidacji' },
          { label: 'Wejście', value: 'cofnięcie do szybkiej wstęgi + reclaim ciałem · HTF wstęga w trendzie · opcjonalny filtr ADX' },
          { label: 'Stop', value: 'strukturalny — dalsza krawędź szybkiej wstęgi' },
          { label: 'Target', value: 'RR 1:2 / 1:3 lub pivoty R1/R2/R3 (1/3 każdy, BE po TP1)' },
          { label: 'Runner', value: 'trzymany aż świeca ciałem zamknie się za wolną wstęgą' },
          { label: 'Status', value: 'silnik + backtest gotowe (T1–T4) · żywa egzekucja: T9' },
        ],
      },
    ];
  }
}
