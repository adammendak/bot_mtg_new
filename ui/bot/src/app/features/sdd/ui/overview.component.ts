import { Component, OnInit, inject } from '@angular/core';
import { SddService } from '../service/sdd.service';
import { OverviewView } from '../model/sdd.model';

/**
 * All-accounts overview: every book in one table with an explicit DEMO / LIVE /
 * MAIN badge, the strategy attached to the book, execution state, and a live
 * tally of open positions with summed unrealised P/L.
 */
@Component({
  selector: 'app-overview',
  standalone: true,
  template: `
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <h2 class="h4 mb-0 me-2">Accounts overview</h2>
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
  `,
})
export class OverviewComponent implements OnInit {
  readonly sdd = inject(SddService);
  private closeSse: (() => void) | null = null;
  private liveConnected = false;

  ngOnInit(): void {
    this.load();
    this.closeSse = this.sdd.liveOverview((rows) => {
      if (Array.isArray(rows) && rows.length > 0) {
        this.sdd.overview.set(rows);
      }
    });
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
    const book = (['demo', 'live', 'glowne'].includes(id) ? id : 'demo') as 'demo' | 'live' | 'glowne';
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
