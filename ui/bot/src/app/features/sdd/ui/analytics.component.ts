import { Component, OnInit, inject } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
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
  imports: [DecimalPipe, FormsModule],
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

    @if (canSeeHts) {
      <div class="card shadow-sm mt-3">
        <div class="card-header bg-dark text-white d-flex flex-wrap gap-2 align-items-center">
          <span>Dziennik HTS (E-8)</span>
          <select class="form-select form-select-sm w-auto" [(ngModel)]="jVariant" (change)="loadJournal()">
            <option value="">wszystkie warianty</option>
            <option value="CORE">CORE</option><option value="SWING">SWING</option>
            <option value="FAST">FAST</option><option value="CORE_LIVE">CORE_LIVE</option>
          </select>
          <input class="form-control form-control-sm w-auto" style="width: 8rem" placeholder="symbol" [(ngModel)]="jSymbol" (change)="loadJournal()" />
          <input class="form-control form-control-sm w-auto" type="date" [(ngModel)]="jFrom" (change)="loadJournal()" />
          <input class="form-control form-control-sm w-auto" type="date" [(ngModel)]="jTo" (change)="loadJournal()" />
          <button class="btn btn-outline-light btn-sm" (click)="loadJournal()">Odśwież</button>
        </div>
        <div class="card-body">
          @if (sdd.htsJournal(); as j) {
            <div class="d-flex flex-wrap gap-2 mb-3 small">
              <span class="badge text-bg-light border">{{ j.trades }} tradów</span>
              <span class="badge" [class.text-bg-success]="j.winRate >= 0.5" [class.text-bg-secondary]="j.winRate < 0.5">win {{ (j.winRate * 100) | number: '1.0-0' }}%</span>
              <span class="badge" [class.text-bg-success]="j.avgR > 0" [class.text-bg-danger]="j.avgR < 0">avg {{ j.avgR | number: '1.2-2' }}R</span>
              <span class="badge" [class.text-bg-success]="j.sumR > 0" [class.text-bg-danger]="j.sumR < 0">Σ {{ j.sumR | number: '1.2-2' }}R</span>
            </div>
            <div class="row g-3">
              <div class="col-lg-5">
                <div class="small text-muted mb-1">Rozkład R</div>
                @for (b of j.rHistogram; track b.label) {
                  <div class="d-flex align-items-center gap-2 mb-1">
                    <span class="small text-nowrap" style="width: 4.5rem">{{ b.label }}</span>
                    <div class="flex-grow-1 bg-body-tertiary rounded" style="height: 14px">
                      <div class="rounded" [style.width.%]="histPct(b.count)" [style.height.%]="100"
                           [class.bg-success]="b.label.includes('1') || b.label.includes('2') || b.label.includes('3')"
                           [class.bg-danger]="b.label.includes('−')"></div>
                    </div>
                    <span class="small text-muted" style="width: 2rem">{{ b.count }}</span>
                  </div>
                }
              </div>
              <div class="col-lg-3">
                <div class="small text-muted mb-1">Wg powodu</div>
                <table class="table table-sm mb-0">
                  <tbody>
                    @for (g of j.byReason; track g.key) {
                      <tr><td>{{ g.key }}</td><td class="text-end">{{ g.trades }}</td>
                        <td class="text-end" [class]="rClass(g.sumR)">{{ g.sumR | number: '1.1-1' }}R</td></tr>
                    }
                  </tbody>
                </table>
              </div>
              <div class="col-lg-4">
                <div class="small text-muted mb-1">Wg symbolu</div>
                <table class="table table-sm mb-0">
                  <tbody>
                    @for (g of j.bySymbol; track g.key) {
                      <tr><td>{{ g.key }}</td><td class="text-end">{{ g.trades }}</td>
                        <td class="text-end">{{ (g.winRate * 100) | number: '1.0-0' }}%</td>
                        <td class="text-end" [class]="rClass(g.avgR)">{{ g.avgR | number: '1.2-2' }}R</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
            @if (j.byDay.length) {
              <div class="small text-muted mt-3 mb-1">Dni ({{ j.byDay.length }})</div>
              <div class="d-flex flex-wrap gap-1">
                @for (d of j.byDay; track d.date) {
                  <span class="badge" [class.text-bg-success]="d.r > 0" [class.text-bg-danger]="d.r < 0" [class.text-bg-secondary]="d.r === 0"
                        [title]="d.date + ': ' + d.trades + ' tr, ' + d.r.toFixed(2) + 'R'">
                    {{ d.date.slice(5) }} · {{ d.r | number: '1.1-1' }}R
                  </span>
                }
              </div>
            }
          } @else {
            <div class="text-muted">Brak zamkniętych tradów HTS w wybranym zakresie.</div>
          }
        </div>
      </div>

      <div class="card shadow-sm mt-3">
        <div class="card-header bg-dark text-white d-flex flex-wrap gap-2 align-items-center">
          <span>Sweep parametrów (E-9)</span>
          <input class="form-control form-control-sm w-auto" style="width: 9rem" [(ngModel)]="swRr" title="rr (lista)" />
          <input class="form-control form-control-sm w-auto" style="width: 9rem" [(ngModel)]="swStopBuf" title="stopBuf (lista)" />
          <input class="form-control form-control-sm w-auto" style="width: 7rem" [(ngModel)]="swRunnerLock" title="runnerLock" />
          <input class="form-control form-control-sm w-auto" style="width: 9rem" [(ngModel)]="swAdxPermit" title="adxPermit" />
          <button class="btn btn-info btn-sm" [disabled]="sdd.htsLabBusy()" (click)="runSweep()">
            {{ sdd.htsLabBusy() ? 'Liczę…' : 'Uruchom sweep' }}
          </button>
        </div>
        <div class="card-body p-0">
          <div class="table-responsive">
            <table class="table table-sm table-hover mb-0">
              <thead class="table-dark"><tr>
                <th>rr</th><th>stopBuf</th><th>lock</th><th>ADX permit</th><th>n</th><th>win%</th><th>avg R</th><th>Σ R</th><th>max DD R</th>
              </tr></thead>
              <tbody>
                @if (sdd.htsSweep().length === 0) {
                  <tr><td colspan="9" class="text-muted text-center">Ustaw listy wartości (np. rr = 1.5,2,2.5) i uruchom.</td></tr>
                } @else {
                  @for (r of sdd.htsSweep(); track $index) {
                    <tr [style.background]="sweepBg(r.avgR)">
                      <td>{{ r.rr }}</td><td>{{ r.stopBuf }}</td><td>{{ r.runnerLock }}</td>
                      <td>{{ r.adxPermit ? 'on' : 'off' }}</td><td>{{ r.n }}</td>
                      <td>{{ (r.winRate * 100) | number: '1.0-0' }}%</td>
                      <td [class]="rClass(r.avgR)">{{ r.avgR | number: '1.2-2' }}</td>
                      <td [class]="rClass(r.sumR)">{{ r.sumR | number: '1.1-1' }}</td>
                      <td class="text-danger">{{ r.maxDdR | number: '1.1-1' }}</td>
                    </tr>
                  }
                }
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <div class="card shadow-sm mt-3 mb-4">
        <div class="card-header bg-dark text-white d-flex flex-wrap gap-2 align-items-center">
          <span>Walk-forward / OOS (E-10)</span>
          <input class="form-control form-control-sm w-auto" style="width: 6rem" type="number" [(ngModel)]="oosDays" title="dni" />
          <input class="form-control form-control-sm w-auto" style="width: 6rem" type="number" step="0.05" min="0.1" max="0.9" [(ngModel)]="oosSplit" title="split" />
          <button class="btn btn-info btn-sm" [disabled]="sdd.htsLabBusy()" (click)="runOos()">
            {{ sdd.htsLabBusy() ? 'Liczę…' : 'Uruchom' }}
          </button>
        </div>
        <div class="card-body">
          @if (sdd.htsOos(); as o) {
            <p class="small text-muted">split @ {{ htsTime(o.splitAt) }} ({{ (o.splitPct * 100) | number: '1.0-0' }}%)</p>
            <table class="table table-sm mb-0">
              <thead><tr><th></th><th class="text-end">n</th><th class="text-end">win%</th><th class="text-end">avg R</th><th class="text-end">Σ R</th><th class="text-end">max DD R</th></tr></thead>
              <tbody>
                <tr><td>in-sample</td><td class="text-end">{{ o.inSample.n }}</td>
                  <td class="text-end">{{ (o.inSample.winRate * 100) | number: '1.0-0' }}%</td>
                  <td class="text-end" [class]="rClass(o.inSample.avgR)">{{ o.inSample.avgR | number: '1.2-2' }}</td>
                  <td class="text-end" [class]="rClass(o.inSample.sumR)">{{ o.inSample.sumR | number: '1.1-1' }}</td>
                  <td class="text-end text-danger">{{ o.inSample.maxDdR | number: '1.1-1' }}</td></tr>
                <tr><td><strong>out-of-sample</strong></td><td class="text-end">{{ o.outOfSample.n }}</td>
                  <td class="text-end">{{ (o.outOfSample.winRate * 100) | number: '1.0-0' }}%</td>
                  <td class="text-end" [class]="rClass(o.outOfSample.avgR)">{{ o.outOfSample.avgR | number: '1.2-2' }}</td>
                  <td class="text-end" [class]="rClass(o.outOfSample.sumR)">{{ o.outOfSample.sumR | number: '1.1-1' }}</td>
                  <td class="text-end text-danger">{{ o.outOfSample.maxDdR | number: '1.1-1' }}</td></tr>
              </tbody>
            </table>
          } @else {
            <div class="text-muted">Replay + podział tradów po czasie — sprawdza, czy model nie jest przeuczony na oknie IS.</div>
          }
        </div>
      </div>

      @if (sdd.htsLabError(); as e) {
        <div class="alert alert-danger py-2">{{ e }}</div>
      }
    }
  `,
})
export class AnalyticsComponent implements OnInit {
  readonly sdd = inject(SddService);
  private readonly auth = inject(AuthService);
  readonly visibleBooks = BOOK_TABS.filter((b) => this.auth.canSeeBook(b.id));
  readonly canSeeHts = this.auth.canSeeBook('hts');
  private current: BookId = this.visibleBooks[0]?.id ?? 'demo';

  // E-8 journal filters
  jVariant = '';
  jSymbol = '';
  jFrom = '';
  jTo = '';
  // E-9 sweep axes
  swRr = '1.5,2,2.5';
  swStopBuf = '0,0.25';
  swRunnerLock = '1';
  swAdxPermit = 'false,true';
  // E-10 OOS
  oosDays = 60;
  oosSplit = 0.7;

  book(): string {
    return this.current;
  }

  ngOnInit(): void {
    this.reload();
    if (this.canSeeHts) {
      this.sdd.loadHtsScorecard();
      this.sdd.loadHtsJournal();
    }
  }

  private htsTf(): { htf: string; ltf: string } {
    return this.current === 'swing'
      ? { htf: 'D1', ltf: 'H1' }
      : this.current === 'hts'
        ? { htf: 'H1', ltf: 'M5' }
        : { htf: 'H4', ltf: 'M15' };
  }

  loadJournal(): void {
    this.sdd.loadHtsJournal({
      variant: this.jVariant || undefined,
      symbol: this.jSymbol || undefined,
      from: this.jFrom || undefined,
      to: this.jTo || undefined,
    });
  }

  runSweep(): void {
    this.sdd.runHtsSweep(this.htsTf(), 30, {
      rr: this.swRr, stopBuf: this.swStopBuf, runnerLock: this.swRunnerLock, adxPermit: this.swAdxPermit,
    });
  }

  runOos(): void {
    this.sdd.runHtsOos(this.htsTf(), this.oosDays, this.oosSplit);
  }

  histPct(count: number): number {
    const max = Math.max(1, ...(this.sdd.htsJournal()?.rHistogram.map((b) => b.count) ?? [1]));
    return Math.round((count / max) * 100);
  }

  rClass(v: number): string {
    return v > 0 ? 'text-success fw-semibold' : v < 0 ? 'text-danger fw-semibold' : '';
  }

  sweepBg(avgR: number): string {
    const rows = this.sdd.htsSweep();
    const max = Math.max(0.01, ...rows.map((r) => Math.abs(r.avgR)));
    const a = Math.min(0.5, (Math.abs(avgR) / max) * 0.5);
    return avgR >= 0 ? `rgba(25,135,84,${a.toFixed(3)})` : `rgba(220,53,69,${a.toFixed(3)})`;
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
