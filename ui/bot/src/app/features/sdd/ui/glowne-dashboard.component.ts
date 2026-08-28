import { Component, OnInit, inject } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { SddService } from '../service/sdd.service';
import { AccountView, Position, SddScan } from '../model/sdd.model';

/**
 * Dashboard for the "Główne" (main) Capital.com account — the same account/positions/
 * scan/signals view as the home dashboard, scoped to the glowne book.
 */
@Component({
  selector: 'app-glowne-dashboard',
  standalone: true,
  imports: [DatePipe, DecimalPipe],
  template: `
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <h2 class="h4 mb-0 me-2">Główne konto</h2>
      <span class="badge" [class]="healthBadgeClass()">
        health {{ sdd.health()?.status || '…' }}
      </span>
      <span class="badge" [class]="execBadgeClass()">
        {{ executionOn() ? 'EXECUTION ON' : 'execution off' }}
      </span>
      <span class="small text-muted">Last scan: {{ (sdd.lastScan()?.scannedAt | date: 'short') || 'never' }}</span>
      <button
        type="button"
        class="btn btn-info btn-sm"
        (click)="sdd.syncHistory('glowne', true)"
        [disabled]="sdd.syncBusy()"
        title="Rebuild daily equity history from Capital.com glowne transactions"
      >
        {{ sdd.syncBusy() ? 'Syncing…' : 'Sync history' }}
      </button>
      <button type="button" class="btn btn-outline-secondary btn-sm" (click)="sdd.refresh()">Refresh</button>
    </div>

    @if (sdd.syncMessage(); as msg) {
      <div class="alert alert-info py-2">{{ msg }}</div>
    }
    @if (account()?.error) {
      <div class="alert alert-danger py-2">{{ account()?.error }}</div>
    }

    <div class="row g-3">
      <div class="col-lg-6">
        <div class="card shadow-sm">
          <div class="card-header bg-dark text-white">Główne — Account</div>
          <div class="card-body pb-2">
            <div class="table-responsive">
              <table class="table table-sm table-striped table-hover mb-3">
                <thead class="table-dark">
                  <tr><th>Field</th><th>Value</th></tr>
                </thead>
                <tbody>
                  <tr><td class="text-muted">Name</td><td>{{ account()?.accountName || '—' }}</td></tr>
                  <tr>
                    <td class="text-muted">Connected</td>
                    <td>
                      <span class="badge" [class]="connClass(account()?.connected)">
                        {{ account()?.connected ? 'connected' : 'disconnected' }}
                      </span>
                    </td>
                  </tr>
                  <tr><td class="text-muted">Equity</td><td>{{ fmtMoney(account()?.equity) }}</td></tr>
                  <tr><td class="text-muted">Available</td><td>{{ fmtMoney(account()?.available) }}</td></tr>
                  <tr>
                    <td class="text-muted">Day P/L</td>
                    <td [class]="pnlClass(account()?.dayPnl)">{{ fmtMoney(account()?.dayPnl) }}</td>
                  </tr>
                  <tr><td class="text-muted">Currency</td><td>{{ account()?.currency || '—' }}</td></tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
      <div class="col-lg-6">
        <div class="card shadow-sm">
          <div class="card-header bg-dark text-white">
            Główne — Positions
            @if (positionsWithoutStop() > 0) {
              <span class="badge text-bg-danger ms-1" title="Open positions with no stop level">⚠ {{ positionsWithoutStop() }} bez SL</span>
            }
          </div>
          <div class="card-body p-0">
            <div class="table-responsive">
              <table class="table table-sm table-striped table-hover mb-0">
                <thead class="table-dark">
                  <tr><th>Epic</th><th>Dir</th><th>Size</th><th>Level</th><th>Stop</th><th>uP/L</th></tr>
                </thead>
                <tbody>
                  @if (positions().length === 0) {
                    <tr><td colspan="6" class="text-muted text-center">brak pozycji</td></tr>
                  } @else {
                    @for (p of positions(); track p.dealId) {
                      <tr [class]="p.stopLevel == null ? 'table-warning' : ''" [title]="p.stopLevel == null ? 'Brak stop loss!' : ''">
                        <td>{{ p.epic }}</td>
                        <td><span class="badge" [class]="dirClass(p.direction)">{{ p.direction }}</span></td>
                        <td>{{ p.size | number: '1.2-2' }}</td>
                        <td>{{ p.level | number: '1.2-5' }}</td>
                        <td>
                          @if (p.stopLevel == null) {
                            <span class="badge text-bg-danger">brak SL</span>
                          } @else {
                            {{ p.stopLevel | number: '1.2-5' }}
                          }
                        </td>
                        <td [class]="pnlClass(p.unrealizedPnl)">{{ p.unrealizedPnl | number: '1.2-2' }}</td>
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

    <div class="card shadow-sm mt-3">
      <div class="card-header bg-dark text-white">Główne — SDD stack (shared scan)</div>
      <div class="card-body p-0">
        <div class="table-responsive">
          <table class="table table-sm table-striped table-hover mb-0">
            <thead class="table-dark">
              <tr>
                <th>Symbol</th><th>HA</th><th>RMA</th><th>H1</th><th>PP</th><th>Full</th>
                <th>Direction</th><th>Entry</th><th>Stop</th><th>1R</th><th>Reason</th>
              </tr>
            </thead>
            <tbody>
              @if ((sdd.lastScan()?.symbols || []).length === 0) {
                <tr><td colspan="11" class="text-muted text-center">No scan yet</td></tr>
              } @else {
                @for (row of sdd.lastScan()?.symbols || []; track row.symbol) {
                  <tr>
                    <td>{{ row.symbol }}</td>
                    <td><span class="badge" [class]="flagClass(row.setup.ha)">{{ yn(row.setup.ha) }}</span></td>
                    <td><span class="badge" [class]="flagClass(row.setup.rma)">{{ yn(row.setup.rma) }}</span></td>
                    <td><span class="badge" [class]="flagClass(row.setup.h1)">{{ yn(row.setup.h1) }}</span></td>
                    <td><span class="badge" [class]="flagClass(row.setup.pp)">{{ yn(row.setup.pp) }}</span></td>
                    <td><span class="badge" [class]="flagClass(row.fullStack)">{{ yn(row.fullStack) }}</span></td>
                    <td><span class="badge" [class]="dirClass(row.direction)">{{ row.direction }}</span></td>
                    <td>{{ row.entry | number: '1.2-5' }}</td>
                    <td>{{ row.stop | number: '1.2-5' }}</td>
                    <td>{{ row.oneR | number: '1.2-5' }}</td>
                    <td>{{ row.reason }}</td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <div class="card shadow-sm mt-3">
      <div class="card-header bg-dark text-white">Główne — Signals</div>
      <div class="card-body p-0">
        <div class="table-responsive">
          <table class="table table-sm table-striped table-hover mb-0">
            <thead class="table-dark">
              <tr><th>Time</th><th>Symbol</th><th>Direction</th><th>FULL/flip</th><th>Entry</th><th>Reason</th></tr>
            </thead>
            <tbody>
              @if (sdd.signals().length === 0) {
                <tr><td colspan="6" class="text-muted text-center">No signals yet</td></tr>
              } @else {
                @for (s of sdd.signals().slice(0, 20); track $index) {
                  <tr>
                    <td>{{ s.timestamp | date: 'short' }}</td>
                    <td>{{ s.symbol }}</td>
                    <td><span class="badge" [class]="dirClass(s.direction)">{{ s.direction }}</span></td>
                    <td>
                      <span class="badge" [class]="s.fullStack ? 'text-bg-success' : 'text-bg-secondary'">
                        {{ s.fullStack ? 'FULL' : 'flip' }}
                      </span>
                    </td>
                    <td>{{ s.entry | number: '1.2-5' }}</td>
                    <td>{{ s.reason }}</td>
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
export class GlowneDashboardComponent implements OnInit {
  readonly sdd = inject(SddService);

  ngOnInit(): void {
    this.sdd.refresh();
  }

  account(): AccountView | undefined {
    return this.sdd.account('glowne');
  }

  positions(): Position[] {
    return this.sdd.positions()['glowne'] ?? [];
  }

  positionsWithoutStop(): number {
    return this.positions().filter((p) => p.stopLevel == null).length;
  }

  executionOn(): boolean {
    return !!(this.sdd.health()?.executionEnabled || this.sdd.lastScan()?.executionEnabled);
  }

  healthBadgeClass(): string {
    return this.sdd.health()?.status === 'UP' ? 'text-bg-success' : 'text-bg-danger';
  }

  execBadgeClass(): string {
    return this.executionOn() ? 'text-bg-danger' : 'text-bg-secondary';
  }

  connClass(connected: boolean | undefined): string {
    return connected ? 'text-bg-success' : 'text-bg-danger';
  }

  dirClass(direction: string | undefined): string {
    const u = (direction || '').toUpperCase();
    if (u === 'BUY') return 'text-bg-success';
    if (u === 'SELL') return 'text-bg-danger';
    return 'text-bg-secondary';
  }

  flagClass(ok: boolean | undefined): string {
    return ok ? 'text-bg-success' : 'text-bg-secondary';
  }

  yn(ok: boolean | undefined): string {
    return ok ? 'Y' : 'n';
  }

  pnlClass(value: number | null | undefined): string {
    if (value == null) return '';
    if (value > 0) return 'text-success';
    if (value < 0) return 'text-danger';
    return '';
  }

  fmtMoney(value: number | null | undefined): string {
    if (value == null) return '—';
    return value.toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
}
