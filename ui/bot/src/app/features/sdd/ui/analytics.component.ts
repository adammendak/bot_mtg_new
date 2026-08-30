import { Component, OnInit, inject } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { SddService } from '../service/sdd.service';
import { AuthService } from '../../auth/auth.service';
import { BookId, BOOK_TABS } from '../model/sdd.model';

/**
 * Analytics: per-symbol performance (#14) and backtest replay (#13). Both are
 * filtered by book and re-run on demand — weaker symbols are visible at a glance
 * so they can be excluded from new entries.
 */
@Component({
  selector: 'app-analytics',
  standalone: true,
  imports: [DecimalPipe],
  template: `
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <h2 class="h4 mb-0">Analytics</h2>
      <div class="btn-group btn-group-sm" role="group">
        @for (b of visibleBooks; track b.id) {
          <button type="button" class="btn" [class.btn-primary]="book() === b.id" [class.btn-outline-secondary]="book() !== b.id" (click)="setBook(b.id)">{{ b.label }}</button>
        }
      </div>
      <button type="button" class="btn btn-info btn-sm" (click)="runBacktest()" [disabled]="sdd.backtestBusy()">
        {{ sdd.backtestBusy() ? 'Replaying…' : 'Run backtest' }}
      </button>
      <button type="button" class="btn btn-outline-secondary btn-sm" (click)="reload()">Refresh</button>
    </div>

    @if (sdd.actionMessage(); as m) {
      <div class="alert alert-info py-2">{{ m }}</div>
    }

    @if (canSeeHts) {
      <div class="card shadow-sm mb-3">
        <div class="card-header bg-dark text-white d-flex justify-content-between align-items-center">
          <span>HTS forward-test — scorecard (z realnych tradów)</span>
          <button type="button" class="btn btn-outline-light btn-sm" (click)="sdd.loadHtsScorecard()">Odśwież</button>
        </div>
        <div class="card-body p-0">
          <div class="table-responsive">
            <table class="table table-sm table-striped table-hover mb-0">
              <thead class="table-dark">
                <tr>
                  <th>Wariant</th><th>Otwarte</th><th>Zamknięte</th><th>Win rate</th>
                  <th>Avg R</th><th>Σ R</th><th>Max DD (R)</th><th>Realized P/L</th><th>Ostatni</th>
                </tr>
              </thead>
              <tbody>
                @if (sdd.htsScorecardError()) {
                  <tr><td colspan="9" class="text-danger text-center">{{ sdd.htsScorecardError() }}</td></tr>
                } @else if (sdd.htsScorecard().length === 0) {
                  <tr><td colspan="9" class="text-muted text-center">Brak danych — pojawią się po pierwszych zamkniętych tradach HTS.</td></tr>
                } @else {
                  @for (r of sdd.htsScorecard(); track r.variant) {
                    <tr>
                      <td>
                        <span class="badge text-bg-info">{{ r.variant }}</span>
                        <span class="text-muted small">{{ r.htf }}/{{ r.ltf }}</span>
                      </td>
                      <td>{{ r.openTrades }}</td>
                      <td>{{ r.closedTrades }} <span class="text-muted small">({{ r.wins }}W/{{ r.losses }}L)</span></td>
                      <td [class]="winClass(r.winRate)">{{ r.closedTrades ? ((r.winRate * 100) | number: '1.0-0') + '%' : '–' }}</td>
                      <td [class]="pnlClass(r.avgR)">{{ r.closedTrades ? (r.avgR | number: '1.2-2') : '–' }}</td>
                      <td [class]="pnlClass(r.sumR)">{{ r.closedTrades ? (r.sumR | number: '1.2-2') : '–' }}</td>
                      <td [class]="r.maxDrawdownR > 0 ? 'text-danger fw-semibold' : ''">{{ r.closedTrades ? (r.maxDrawdownR | number: '1.2-2') : '–' }}</td>
                      <td [class]="pnlClass(r.realisedPnl ?? 0)">{{ r.realisedPnl == null ? '–' : (r.realisedPnl | number: '1.2-2') }} {{ r.pnlCcy || '' }}</td>
                      <td class="small text-muted text-nowrap">{{ htsTime(r.lastTradeAt) }}</td>
                    </tr>
                  }
                }
              </tbody>
            </table>
          </div>
        </div>
      </div>
    }

    <div class="row g-3">
      <div class="col-lg-6">
        <div class="card shadow-sm">
          <div class="card-header bg-dark text-white">Per-symbol performance (#14)</div>
          <div class="card-body p-0">
            <div class="table-responsive">
              <table class="table table-sm table-striped table-hover mb-0">
                <thead class="table-dark">
                  <tr>
                    <th>Symbol</th><th>Trades</th><th>Win rate</th><th>Expectancy</th><th>PF</th><th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  @if (sdd.symbolStatsError()) {
                    <tr><td colspan="6" class="text-danger text-center">{{ sdd.symbolStatsError() }}</td></tr>
                  } @else if (sdd.symbolStats().length === 0) {
                    <tr><td colspan="6" class="text-muted text-center">No trade data yet — run Sync first.</td></tr>
                  } @else {
                    @for (s of sdd.symbolStats(); track s.symbol) {
                      <tr>
                        <td>
                          {{ s.symbol }}
                          <span class="text-muted small">({{ s.epic }})</span>
                        </td>
                        <td>{{ s.trades }} <span class="text-muted small">({{ s.wins }}W/{{ s.losses }}L)</span></td>
                        <td [class]="winClass(s.winRate)">{{ (s.winRate * 100) | number: '1.0-0' }}%</td>
                        <td [class]="pnlClass(s.expectancy)">{{ s.expectancy | number: '1.2-2' }}</td>
                        <td [class]="pnlClass(s.profitFactor - 1)">{{ s.profitFactor | number: '1.2-2' }}</td>
                        <td>
                          <span class="badge" [class.text-bg-success]="s.enabled" [class.text-bg-danger]="!s.enabled">
                            {{ s.enabled ? 'active' : 'disabled' }}
                          </span>
                        </td>
                      </tr>
                    }
                  }
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

      <div class="col-lg-6">
        <div class="card shadow-sm">
          <div class="card-header bg-dark text-white">Backtest HTS — model konta</div>
          <div class="card-body p-0">
            <div class="table-responsive">
              <table class="table table-sm table-striped table-hover mb-0">
                <thead class="table-dark">
                  <tr>
                    <th>Symbol</th><th>Signals</th><th>Win rate</th><th>Avg R</th><th>PF</th>
                  </tr>
                </thead>
                <tbody>
                  @if (sdd.backtestError()) {
                    <tr><td colspan="5" class="text-danger text-center">{{ sdd.backtestError() }}</td></tr>
                  } @else if (sdd.backtest().length === 0) {
                    <tr><td colspan="5" class="text-muted text-center">Uruchom backtest HTS dla modelu TF tego konta (CORE H4/M15 · SWING D1/H1 · FAST H1/M5).</td></tr>
                  } @else {
                    @for (b of sdd.backtest(); track b.symbol) {
                      <tr>
                        <td>{{ b.symbol }}</td>
                        <td>{{ b.signals }} <span class="text-muted small">({{ b.wins }}W/{{ b.losses }}L)</span></td>
                        <td [class]="winClass(b.winRate)">{{ (b.winRate * 100) | number: '1.0-0' }}%</td>
                        <td [class]="pnlClass(b.avgR)">{{ b.avgR | number: '1.2-2' }}</td>
                        <td [class]="pnlClass(b.profitFactor - 1)">{{ b.profitFactor | number: '1.2-2' }}</td>
                      </tr>
                    }
                  }
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </div>
  `,
})
export class AnalyticsComponent implements OnInit {
  readonly sdd = inject(SddService);
  private readonly auth = inject(AuthService);
  readonly visibleBooks = BOOK_TABS.filter((b) => this.auth.canSeeBook(b.id));
  readonly canSeeHts = this.auth.canSeeBook('hts');
  private current: BookId = this.visibleBooks[0]?.id ?? 'demo';

  book(): string {
    return this.current;
  }

  ngOnInit(): void {
    this.reload();
    if (this.canSeeHts) {
      this.sdd.loadHtsScorecard();
    }
  }

  htsTime(iso: string | null | undefined): string {
    if (!iso) {
      return '—';
    }
    const d = new Date(iso);
    return Number.isNaN(d.getTime())
      ? String(iso)
      : d.toLocaleString('pl-PL', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  setBook(book: BookId): void {
    if (!this.auth.canSeeBook(book)) {
      return;
    }
    this.current = book;
    this.reload();
  }

  reload(): void {
    const book = this.current as BookId;
    this.sdd.loadSymbolStats(book);
  }

  runBacktest(): void {
    this.sdd.runBacktest(this.current as BookId, 30);
  }

  winClass(rate: number): string {
    if (rate >= 0.5) {
      return 'text-success fw-semibold';
    }
    if (rate >= 0.35) {
      return 'text-warning fw-semibold';
    }
    return 'text-danger fw-semibold';
  }

  pnlClass(value: number): string {
    if (value > 0) {
      return 'text-success fw-semibold';
    }
    if (value < 0) {
      return 'text-danger fw-semibold';
    }
    return '';
  }
}
