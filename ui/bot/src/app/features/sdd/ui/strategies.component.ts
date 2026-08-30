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
        Wrzesień: forward-test HTS na 3 kontach demo (3 modele TF). SDD — archiwalne, nie handluje.
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
    const htsOn = !!h?.htsConfigured;
    return [
      {
        name: 'HTS (wstęgi)',
        tag: '3 modele',
        headerClass: 'bg-dark text-white',
        account: 'CORE → Account m15 · SWING → Account H1 · FAST → Account m5 (wszystko demo)',
        status: htsOn ? 'FORWARD-TEST (wrzesień)' : 'nieskonfigurowane',
        statusClass: htsOn ? 'text-bg-success' : 'text-bg-secondary',
        rows: [
          { label: 'Modele TF', value: 'CORE H4/M15 (Account m15) · SWING D1/H1 (Account H1) · FAST H1/M5 (Account m5)' },
          { label: 'Skan', value: 'co 5 min (cron 0 */5 * * * *) — 3 modele naraz, dedup per świeca' },
          { label: 'Uniwersum', value: 'tydzień: GER40 / XAU / US100 / EURUSD (bez BTC) · weekend: tylko BTC' },
          { label: 'Wstęgi', value: 'RMA(high) / RMA(low) — szybka 33, wolna 144' },
          { label: 'Trend', value: 'szybka wstęga cała nad/pod wolną (LTF i HTF) · brak wejść w konsolidacji' },
          { label: 'Wejście', value: 'cofnięcie do szybkiej wstęgi + reclaim ciałem · ADX off (permit opcjonalnie)' },
          { label: 'Stop', value: 'strukturalny — krawędź szybkiej wstęgi + bufor 0.25× szer.' },
          { label: 'Egzekucja', value: 'TP1 = 1:2 RR (połowa) → runner: stop na zablokowany zysk → trail wstęgi → close ciałem za wolną wstęgą' },
          { label: 'Ryzyko', value: 'sizing = ryzyko$ / dystans stopu · 1 ticket na sygnał na koncie danego modelu' },
          { label: 'Mail', value: 'nota analityczna na każdy sygnał (D1/H1 obraz wstęg, ADX, ATR) → adam.mendak@gmail.com' },
          { label: 'Status', value: 'żywy forward-test na 3 kontach demo (HTS_EXECUTION_ENABLED=true)' },
        ],
      },
      {
        name: 'SDD-M15',
        tag: 'M15',
        headerClass: 'bg-secondary text-white',
        account: 'Account m15 (demo) + bot trading konto (live) — przejęte przez HTS CORE',
        status: 'ARCHIWALNE',
        statusClass: 'text-bg-secondary',
        rows: [
          { label: 'Stan', value: 'kod zostaje, skan i egzekucja wyłączone (SCAN_ENABLED=false, EXECUTION_ENABLED=false)' },
          { label: 'Wejście', value: 'HA flip M15 + RMA33>RMA133 (M15) + H1 zgodne + PP (BTC pomija)' },
          { label: '1R / stop', value: '1R = 1× H1 ATR14 · stop = 2.5× ATR' },
          { label: 'Egzekucja', value: '2 tickety: A = twardy TP 1R · B = runner, stop 2.5×ATR H1-trail' },
          { label: 'Uwaga', value: 'BTC 0W/11L na demo i live · brak stabilnego edge w backteście' },
        ],
      },
      {
        name: 'SDD-SWING',
        tag: 'H1',
        headerClass: 'bg-secondary text-white',
        account: 'Account H1 (demo) — przejęte przez HTS SWING',
        status: 'ARCHIWALNE',
        statusClass: 'text-bg-secondary',
        rows: [
          { label: 'Stan', value: 'kod zostaje, skan i egzekucja wyłączone (SWING_ENABLED=false, SWING_EXECUTION_ENABLED=false)' },
          { label: 'Wejście', value: 'HA flip H1 + RMA33>RMA133 (H1) + PP + trend H4 (twardy filtr)' },
          { label: '1R / stop', value: '1R = 1× H4 ATR14 · stop = 2.5× ATR H4' },
          { label: 'Uwaga', value: 'R:R 0.4:1 na całej pozycji — brak stabilnego edge w backteście' },
        ],
      },
    ];
  }
}
