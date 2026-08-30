import { Component, OnInit, inject } from '@angular/core';
import { SddService } from '../service/sdd.service';
import { BookId, HtsSignal, HtsTrade, OverviewView, Position } from '../model/sdd.model';

/**
 * Konta — the app home page: every book in one table (kind badge, HTS timeframe
 * model, execution state, equity / risk), the open positions across all books,
 * and the recent HTS signals. Manual "Skanuj HTS" triggers a scan; the scheduler
 * runs every 5 min anyway.
 */
@Component({
  selector: 'app-overview',
  standalone: true,
  template: `
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <h2 class="h4 mb-0 me-2">Konta</h2>
      <button type="button" class="btn btn-primary btn-sm" (click)="scanHts()" [disabled]="sdd.busy()">
        {{ sdd.busy() ? 'Skanuję…' : 'Skanuj HTS' }}
      </button>
      @if (live()) {
        <span class="badge text-bg-success" title="Live via SSE">● live</span>
      }
      <button type="button" class="btn btn-info btn-sm" (click)="syncAll()" [disabled]="sdd.syncBusy()" title="Odbuduj historię equity dla wszystkich kont">
        {{ sdd.syncBusy() ? 'Syncing…' : 'Sync all' }}
      </button>
      <button type="button" class="btn btn-outline-secondary btn-sm" (click)="load()">Refresh</button>
    </div>

    @if (sdd.syncMessage(); as m) {
      <div class="alert alert-info py-2">{{ m }}</div>
    }

    @if (sdd.overviewError()) {
      <div class="alert alert-danger py-2">{{ sdd.overviewError() }}</div>
    }

    <div class="card shadow-sm">
      <div class="card-header bg-dark text-white">All books</div>
      <div class="card-body p-0">
        <div class="table-responsive">
          <table class="table table-sm table-striped table-hover mb-0">
            <thead class="table-dark">
              <tr>
                <th>Book</th>
                <th>Type</th>
                <th>Account</th>
                <th>Strategy</th>
                <th>Execution</th>
                <th>Equity</th>
                <th>Available</th>
                <th>Day P/L</th>
                <th>Positions</th>
                <th>uP/L</th>
                <th>Max loss (stop)</th>
                <th>No SL</th>
                <th>Skorelowane</th>
                <th>Efekt. ryzyko</th>
                <th>Budżet do halt</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              @if (sdd.overviewError()) {
                <tr>
                  <td colspan="16" class="text-danger text-center">{{ sdd.overviewError() }}</td>
                </tr>
              } @else if (rows().length === 0) {
                <tr>
                  <td colspan="16" class="text-muted text-center">No accounts configured.</td>
                </tr>
              } @else {
                @for (r of rows(); track r.id) {
                  <tr>
                    <td>{{ r.displayName }}</td>
                    <td><span class="badge" [class]="kindClass(r.kind)">{{ r.kind }}</span></td>
                    <td>{{ r.accountName || '—' }}</td>
                    <td><span class="badge text-bg-info">{{ r.strategy }}</span></td>
                    <td>
                      <span class="badge" [class]="r.executionEnabled ? 'text-bg-success' : 'text-bg-secondary'">
                        {{ r.executionEnabled ? 'ON' : 'off' }}
                      </span>
                    </td>
                    <td>{{ fmt(r.equity) }}</td>
                    <td>{{ fmt(r.available) }}</td>
                    <td [class]="pnlClass(r.dayPnl)">{{ fmt(r.dayPnl) }}</td>
                    <td>{{ r.positionsCount }}</td>
                    <td [class]="pnlClass(r.positionsPnl)">{{ fmt(r.positionsPnl) }}</td>
                    <td [class]="riskClass(r.maxLossPln)">{{ fmt(r.maxLossPln) }} {{ r.riskCurrency || '' }}</td>
                    <td>
                      @if (r.positionsWithoutStop > 0) {
                        <span class="badge text-bg-danger" title="Open positions with no stop level">⚠ {{ r.positionsWithoutStop }}</span>
                      } @else {
                        <span class="text-muted">0</span>
                      }
                    </td>
                    <td [class]="riskClass(r.correlatedPln)" title="Skorelowana ekspozycja (np. US100+GER40 w tę samą stronę)">{{ fmt(r.correlatedPln) }}</td>
                    <td [class]="riskClass(r.effectiveRiskPln)" title="Efektywne ryzyko po korelacji (hedge liczy się netto)">{{ fmt(r.effectiveRiskPln) }}</td>
                    <td>
                      @if (r.dayPnl != null && r.haltPln != null) {
                        <div class="d-flex align-items-center gap-2" title="Day P/L {{ fmt(r.dayPnl) }} / halt {{ fmt(r.haltPln) }} — zostało {{ fmt(r.remainingToHaltPln) }}">
                          <div class="progress flex-grow-1" style="height: 8px; min-width: 70px">
                            <div class="progress-bar" [class]="budgetBarClass(r)" [style.width.%]="budgetPct(r)"></div>
                          </div>
                          <span class="small text-nowrap" [class]="budgetTextClass(r)">{{ fmt(r.remainingToHaltPln) }}</span>
                        </div>
                      } @else {
                        <span class="text-muted">—</span>
                      }
                    </td>
                    <td>
                      @if (r.connected) {
                        <span class="badge text-bg-success">connected</span>
                      } @else {
                        <span class="badge text-bg-danger" title="{{ r.error || '' }}">disconnected</span>
                      }
                      <button
                        type="button"
                        class="btn btn-outline-info btn-sm ms-1"
                        (click)="syncBook(r.id)"
                        [disabled]="sdd.syncBusy()"
                        title="Odbuduj historię equity dla {{ r.displayName }}"
                      >
                        Sync
                      </button>
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <div class="card shadow-sm mt-3">
      <div class="card-header d-flex justify-content-between align-items-center">
        <span>Otwarte pozycje</span>
        <button type="button" class="btn btn-outline-secondary btn-sm" (click)="reloadHts()">Odśwież</button>
      </div>
      <div class="card-body p-2">
        @if (sdd.positionsError()) {
          <div class="alert alert-danger py-2 mb-2">{{ sdd.positionsError() }}</div>
        }
        @for (g of positionGroups(); track g.book) {
          <details class="mb-1" [open]="g.book === 'demo' || g.book === 'swing' || g.book === 'hts' || g.book === 'live'">
            <summary class="d-flex justify-content-between align-items-center px-2 py-1 bg-body-tertiary rounded" style="cursor: pointer">
              <span><span class="badge text-bg-secondary me-2">{{ bookLabel(g.book) }}</span>{{ g.rows.length }} poz.</span>
              <span [class]="pnlClass(g.pnl)">Σ uP/L {{ fmt(g.pnl) }}</span>
            </summary>
            <div class="table-responsive">
              <table class="table table-sm table-striped mb-2">
                <thead>
                  <tr>
                    <th>Epic</th><th>Kier.</th><th class="text-end">Size</th>
                    <th class="text-end">Level</th><th class="text-end">Stop</th><th class="text-end">uP/L</th>
                  </tr>
                </thead>
                <tbody>
                  @for (p of g.rows; track p.dealId) {
                    <tr>
                      <td>{{ p.epic }}</td>
                      <td>
                        <span class="badge" [class]="p.direction === 'BUY' ? 'text-bg-success' : 'text-bg-danger'">
                          {{ p.direction }}
                        </span>
                      </td>
                      <td class="text-end">{{ p.size }}</td>
                      <td class="text-end">{{ fmt(p.level) }}</td>
                      <td class="text-end" [class.text-danger]="p.stopLevel == null">
                        {{ p.stopLevel == null ? 'brak' : fmt(p.stopLevel) }}
                      </td>
                      <td class="text-end" [class]="pnlClass(p.unrealizedPnl)">{{ fmt(p.unrealizedPnl) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </details>
        } @empty {
          <div class="text-muted text-center py-3">Brak otwartych pozycji.</div>
        }
      </div>
    </div>

    <div class="card shadow-sm mt-3">
      <div class="card-header d-flex justify-content-between align-items-center">
        <span>Pozycje HTS — cykl życia (bot)</span>
        <button type="button" class="btn btn-outline-secondary btn-sm" (click)="reloadHts()">Odśwież</button>
      </div>
      <div class="card-body p-2">
        @if (sdd.htsTradesError()) {
          <div class="alert alert-danger py-2 mb-2">{{ sdd.htsTradesError() }}</div>
        }
        @for (g of htsTradeGroups(); track g.variant) {
          <details class="mb-1" open>
            <summary class="d-flex justify-content-between align-items-center px-2 py-1 bg-body-tertiary rounded" style="cursor: pointer">
              <span><span class="badge" [class]="variantClass(g.variant)">{{ variantLabel(g.variant) }}</span></span>
              <span class="text-muted small">{{ g.rows.length }} poz.</span>
            </summary>
            <div class="table-responsive">
              <table class="table table-sm table-striped mb-2">
                <thead>
                  <tr>
                    <th>Symbol</th><th>Kier.</th>
                    <th class="text-end">Entry</th><th class="text-end">Stop</th><th class="text-end">TP1</th>
                    <th class="text-end">Size</th><th>Stan</th><th>Otwarto</th>
                  </tr>
                </thead>
                <tbody>
                  @for (t of g.rows; track t.id) {
                    <tr>
                      <td>{{ t.symbol }}</td>
                      <td>
                        <span class="badge" [class]="t.direction === 'BUY' ? 'text-bg-success' : 'text-bg-danger'">
                          {{ t.direction || '—' }}
                        </span>
                      </td>
                      <td class="text-end">{{ fmt(t.entry) }}</td>
                      <td class="text-end">{{ fmt(t.tp1At ? t.runnerStop : t.stopLevel) }}</td>
                      <td class="text-end">{{ fmt(t.targetLevel) }}</td>
                      <td class="text-end">
                        {{ fmt(t.tp1At ? t.remainingSize : t.size) }}
                        @if (t.tp1At) { <span class="text-muted small">/ {{ fmt(t.size) }}</span> }
                      </td>
                      <td>
                        @if (t.tp1At) {
                          <span class="badge text-bg-success" title="Połowa zamknięta na TP1, reszta na trailingu">runner · trailing</span>
                        } @else {
                          <span class="badge text-bg-secondary" title="Czeka na TP1 (1:2 RR)">przed TP1</span>
                        }
                      </td>
                      <td class="text-nowrap small text-muted">{{ htsTime(t.openedAt) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </details>
        } @empty {
          <div class="text-muted text-center py-3">Brak otwartych pozycji HTS.</div>
        }
      </div>
    </div>

    <div class="card shadow-sm mt-3">
      <div class="card-header d-flex justify-content-between align-items-center">
        <span>Sygnały HTS (wstęgi) — ostatnie</span>
        <button type="button" class="btn btn-outline-secondary btn-sm" (click)="reloadHts()">Odśwież</button>
      </div>
      <div class="card-body p-2">
        @if (sdd.htsError()) {
          <div class="alert alert-danger py-2 mb-2">{{ sdd.htsError() }}</div>
        }
        @for (g of htsSignalGroups(); track g.variant) {
          <details class="mb-1" open>
            <summary class="d-flex justify-content-between align-items-center px-2 py-1 bg-body-tertiary rounded" style="cursor: pointer">
              <span><span class="badge" [class]="variantClass(g.variant)">{{ variantLabel(g.variant) }}</span></span>
              <span class="text-muted small">{{ g.rows.length }} syg.</span>
            </summary>
            <div class="table-responsive">
              <table class="table table-sm table-striped mb-2">
                <thead>
                  <tr>
                    <th>Czas</th><th>Symbol</th><th>Kier.</th>
                    <th class="text-end">Entry</th><th class="text-end">Stop</th><th class="text-end">TP1</th><th>HTF</th>
                  </tr>
                </thead>
                <tbody>
                  @for (s of g.rows; track s.id) {
                    <tr>
                      <td class="text-nowrap small">{{ htsTime(s.scannedAt) }}</td>
                      <td>{{ s.symbol }}</td>
                      <td>
                        <span class="badge" [class]="s.direction === 'BUY' ? 'text-bg-success' : 'text-bg-danger'">
                          {{ s.direction || '—' }}
                        </span>
                      </td>
                      <td class="text-end">{{ fmt(s.entry) }}</td>
                      <td class="text-end">{{ fmt(s.stopLevel) }}</td>
                      <td class="text-end">{{ fmt(s.targetLevel) }}</td>
                      <td class="small text-muted">{{ s.htfUp == null ? '—' : (s.htfUp ? 'up' : 'down') }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </details>
        } @empty {
          <div class="text-muted text-center py-3">Brak sygnałów HTS.</div>
        }
      </div>
    </div>
  `,
})
export class OverviewComponent implements OnInit {
  readonly sdd = inject(SddService);
  private closeSse: (() => void) | null = null;
  private liveConnected = false;

  ngOnInit(): void {
    this.load();
    this.sdd.loadHtsSignals();
    this.sdd.loadPositions();
    this.sdd.loadHtsTrades('OPEN');
    this.closeSse = this.sdd.liveOverview((rows) => {
      if (Array.isArray(rows) && rows.length > 0) {
        this.sdd.overview.set(rows);
      }
    });
  }

  reloadHts(): void {
    this.sdd.loadHtsSignals();
    this.sdd.loadHtsTrades('OPEN');
  }

  scanHts(): void {
    this.sdd.triggerHtsScan();
  }

  positionGroups(): { book: string; rows: Position[]; pnl: number }[] {
    const by = this.sdd.positions();
    const out: { book: string; rows: Position[]; pnl: number }[] = [];
    for (const book of ['demo', 'live', 'swing', 'hts', 'glowne'] as BookId[]) {
      const rows = by[book] ?? [];
      if (rows.length === 0) {
        continue;
      }
      out.push({ book, rows, pnl: rows.reduce((s, p) => s + (p.unrealizedPnl ?? 0), 0) });
    }
    return out;
  }

  htsSignalGroups(): { variant: string; rows: HtsSignal[] }[] {
    const order = ['CORE', 'SWING', 'FAST', 'CORE_LIVE'];
    const map = new Map<string, HtsSignal[]>();
    for (const s of this.sdd.htsSignals()) {
      const v = s.variant || '—';
      let arr = map.get(v);
      if (!arr) {
        arr = [];
        map.set(v, arr);
      }
      arr.push(s);
    }
    return [...map.entries()]
      .sort((a, b) => order.indexOf(a[0]) - order.indexOf(b[0]))
      .map(([variant, rows]) => ({ variant, rows }));
  }

  htsTradeGroups(): { variant: string; rows: HtsTrade[] }[] {
    const order = ['CORE', 'SWING', 'FAST', 'CORE_LIVE'];
    const map = new Map<string, HtsTrade[]>();
    for (const t of this.sdd.htsTrades()) {
      if ((t.status || '').toUpperCase() !== 'OPEN') {
        continue;
      }
      const v = t.variant || '—';
      let arr = map.get(v);
      if (!arr) {
        arr = [];
        map.set(v, arr);
      }
      arr.push(t);
    }
    return [...map.entries()]
      .sort((a, b) => order.indexOf(a[0]) - order.indexOf(b[0]))
      .map(([variant, rows]) => ({ variant, rows }));
  }

  bookLabel(book: string): string {
    switch (book) {
      case 'demo':
        return 'Account m15 · CORE H4/M15';
      case 'live':
        return 'bot trading konto · CORE_LIVE';
      case 'swing':
        return 'Account H1 · SWING D1/H1';
      case 'hts':
        return 'Account m5 · FAST H1/M5';
      case 'glowne':
        return 'Główne';
      default:
        return book;
    }
  }

  variantLabel(v: string): string {
    switch (v) {
      case 'CORE':
        return 'CORE · H4/M15';
      case 'SWING':
        return 'SWING · D1/H1';
      case 'FAST':
        return 'FAST · H1/M5';
      case 'CORE_LIVE':
        return 'CORE_LIVE · H4/M15 (realne)';
      default:
        return v;
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

  variantClass(v: string | null | undefined): string {
    switch (v) {
      case 'CORE':
        return 'text-bg-primary';
      case 'SWING':
        return 'text-bg-info';
      case 'FAST':
        return 'text-bg-warning';
      case 'CORE_LIVE':
        return 'text-bg-danger';
      default:
        return 'text-bg-secondary';
    }
  }

  live(): boolean {
    return this.liveConnected;
  }

  load(): void {
    this.sdd.loadOverview();
  }

  syncAll(): void {
    this.sdd.syncAll(true);
  }

  syncBook(id: string): void {
    const book = (['demo', 'live', 'glowne', 'swing', 'hts'].includes(id) ? id : 'demo') as BookId;
    this.sdd.syncHistory(book, true);
  }

  rows(): OverviewView[] {
    return this.sdd.overview();
  }

  kindClass(kind: string): string {
    const u = (kind || '').toUpperCase();
    if (u === 'LIVE') {
      return 'text-bg-danger';
    }
    if (u === 'MAIN') {
      return 'text-bg-warning';
    }
    return 'text-bg-secondary'; // DEMO
  }

  pnlClass(value: number | null | undefined): string {
    if (value == null) {
      return '';
    }
    if (value > 0) {
      return 'text-success fw-semibold';
    }
    if (value < 0) {
      return 'text-danger fw-semibold';
    }
    return '';
  }

  riskClass(value: number | null | undefined): string {
    if (value == null) {
      return '';
    }
    if (value > 0) {
      return 'text-danger fw-semibold';
    }
    return '';
  }

  /** Share of the day's risk budget (from 0 down to the halt threshold) already consumed. */
  budgetPct(r: OverviewView): number {
    const dayPnl = r.dayPnl;
    const halt = r.haltPln;
    if (dayPnl == null || halt == null || halt >= 0) {
      return 0;
    }
    if (dayPnl >= 0) {
      return 0;
    }
    if (dayPnl <= halt) {
      return 100;
    }
    return Math.min(100, Math.round((-dayPnl / -halt) * 100));
  }

  budgetBarClass(r: OverviewView): string {
    const pct = this.budgetPct(r);
    if (pct >= 75) {
      return 'bg-danger';
    }
    if (pct >= 50) {
      return 'bg-warning';
    }
    return 'bg-success';
  }

  budgetTextClass(r: OverviewView): string {
    const pct = this.budgetPct(r);
    if (pct >= 75) {
      return 'text-danger fw-semibold';
    }
    if (pct >= 50) {
      return 'text-warning fw-semibold';
    }
    return '';
  }

  fmt(value: number | null | undefined): string {
    if (value == null) {
      return '—';
    }
    return new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value);
  }
}
