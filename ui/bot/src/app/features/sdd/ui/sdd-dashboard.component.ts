import { Component, OnInit, inject } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { SddService } from '../service/sdd.service';
import { AccountView, Position, SddScan } from '../model/sdd.model';

@Component({
  selector: 'app-sdd-dashboard',
  standalone: true,
  imports: [DatePipe, DecimalPipe],
  template: `
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <h2 class="h4 mb-0 me-2">Dashboard</h2>
      <span class="badge" [class]="healthBadgeClass()">
        health {{ sdd.health()?.status || '…' }}
      </span>
      <span class="badge" [class]="execBadgeClass()">
        {{ executionOn() ? 'EXECUTION ON' : 'execution off' }}
      </span>
      <span class="small text-muted">Last scan: {{ sdd.lastScan()?.scannedAt || 'never' }}</span>
      <button
        type="button"
        class="btn btn-warning btn-sm"
        (click)="sdd.triggerScan()"
        [disabled]="sdd.busy()"
      >
        {{ sdd.busy() ? 'Scanning…' : 'Scan now' }}
      </button>
      <button type="button" class="btn btn-outline-secondary btn-sm" (click)="sdd.refresh()">
        Refresh
      </button>
    </div>

    <p class="small text-muted">
      Demo and Live are separate books — P/L is never mixed. Scan runs once (same candles) and is
      shown below both panes. Execution stays off unless
      <code>EXECUTION_ENABLED=true</code> (demo only).
    </p>

    @if (sdd.lastScan()?.newsBlackout) {
      <div class="alert alert-warning py-2">News blackout is active (no new SDD).</div>
    }
    @if (sdd.lastScan()?.error) {
      <div class="alert alert-danger py-2">Scan: {{ sdd.lastScan()?.error }}</div>
    }
    @if (sdd.error()) {
      <div class="alert alert-danger py-2">{{ sdd.error() }}</div>
    }

    <div class="row g-3">
      @for (book of books; track book.id) {
        <div class="col-lg-6">
          <div class="card shadow-sm">
            <div class="card-header" [class]="book.headerClass">{{ book.title }}</div>
            <div class="card-body pb-2">
              <h6 class="text-muted text-uppercase small mb-2">Account</h6>
              <div class="table-responsive">
                <table class="table table-sm table-striped table-hover mb-3">
                  <thead class="table-dark">
                    <tr>
                      <th>Field</th>
                      <th>Value</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td class="text-muted">Name</td>
                      <td>{{ account(book.id)?.accountName || '—' }}</td>
                    </tr>
                    <tr>
                      <td class="text-muted">Connected</td>
                      <td>
                        <span class="badge" [class]="connClass(account(book.id)?.connected)">
                          {{ account(book.id)?.connected ? 'connected' : 'disconnected' }}
                        </span>
                      </td>
                    </tr>
                    <tr>
                      <td class="text-muted">Equity</td>
                      <td>{{ formatMoney(account(book.id)?.equity) }}</td>
                    </tr>
                    <tr>
                      <td class="text-muted">Available</td>
                      <td>{{ formatMoney(account(book.id)?.available) }}</td>
                    </tr>
                    <tr>
                      <td class="text-muted">Day P/L</td>
                      <td [class]="pnlClass(account(book.id)?.dayPnl)">
                        {{ formatMoney(account(book.id)?.dayPnl) }}
                      </td>
                    </tr>
                    <tr>
                      <td class="text-muted">Currency</td>
                      <td>{{ account(book.id)?.currency || '—' }}</td>
                    </tr>
                    <tr>
                      <td class="text-muted">Halt / error</td>
                      <td>
                        @if (haltOrError(book.id); as halt) {
                          <span class="text-danger">{{ halt }}</span>
                        } @else {
                          —
                        }
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <h6 class="text-muted text-uppercase small mb-2">Positions</h6>
              <div class="table-responsive">
                <table class="table table-sm table-striped table-hover mb-0">
                  <thead class="table-dark">
                    <tr>
                      <th>Epic</th>
                      <th>Dir</th>
                      <th>Size</th>
                      <th>Level</th>
                      <th>uP/L</th>
                    </tr>
                  </thead>
                  <tbody>
                    @if (positions(book.id).length === 0) {
                      <tr>
                        <td colspan="5" class="text-muted text-center">brak pozycji</td>
                      </tr>
                    } @else {
                      @for (p of positions(book.id); track p.dealId) {
                        <tr>
                          <td>{{ p.epic }}</td>
                          <td>
                            <span class="badge" [class]="dirClass(p.direction)">{{ p.direction }}</span>
                          </td>
                          <td>{{ p.size | number: '1.2-2' }}</td>
                          <td>{{ p.level | number: '1.2-5' }}</td>
                          <td [class]="pnlClass(p.unrealizedPnl)">
                            {{ p.unrealizedPnl | number: '1.2-2' }}
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
      }
    </div>

    <div class="card shadow-sm mt-3">
      <div class="card-header bg-dark text-white">SDD stack (shared scan)</div>
      <div class="card-body p-0">
        <div class="table-responsive">
          <table class="table table-sm table-striped table-hover mb-0">
            <thead class="table-dark">
              <tr>
                <th>Symbol</th>
                <th>HA</th>
                <th>RMA</th>
                <th>H1</th>
                <th>PP</th>
                <th>Full</th>
                <th>Direction</th>
                <th>Entry</th>
                <th>Stop</th>
                <th>1R</th>
                <th>Reason</th>
              </tr>
            </thead>
            <tbody>
              @if ((sdd.lastScan()?.symbols || []).length === 0) {
                <tr>
                  <td colspan="11" class="text-muted text-center">No scan yet</td>
                </tr>
              } @else {
                @for (row of sdd.lastScan()?.symbols || []; track row.symbol) {
                  <tr>
                    <td>{{ row.symbol }}</td>
                    <td><span class="badge" [class]="flagClass(row.setup.ha)">{{ yn(row.setup.ha) }}</span></td>
                    <td><span class="badge" [class]="flagClass(row.setup.rma)">{{ yn(row.setup.rma) }}</span></td>
                    <td><span class="badge" [class]="flagClass(row.setup.h1)">{{ yn(row.setup.h1) }}</span></td>
                    <td><span class="badge" [class]="flagClass(row.setup.pp)">{{ yn(row.setup.pp) }}</span></td>
                    <td><span class="badge" [class]="flagClass(row.fullStack)">{{ yn(row.fullStack) }}</span></td>
                    <td>
                      <span class="badge" [class]="dirClass(row.direction)">{{ row.direction }}</span>
                    </td>
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
      <div class="card-header bg-dark text-white">Signals</div>
      <div class="card-body p-0">
        <div class="table-responsive">
          <table class="table table-sm table-striped table-hover mb-0">
            <thead class="table-dark">
              <tr>
                <th>Time</th>
                <th>Symbol</th>
                <th>Direction</th>
                <th>FULL/flip</th>
                <th>Entry</th>
                <th>Reason</th>
              </tr>
            </thead>
            <tbody>
              @if (sdd.signals().length === 0) {
                <tr>
                  <td colspan="6" class="text-muted text-center">No HA flips or full stacks yet</td>
                </tr>
              } @else {
                @for (s of latestSignals(); track $index) {
                  <tr>
                    <td>{{ s.timestamp | date: 'short' }}</td>
                    <td>{{ s.symbol }}</td>
                    <td>
                      <span class="badge" [class]="dirClass(s.direction)">{{ s.direction }}</span>
                    </td>
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
export class SddDashboardComponent implements OnInit {
  readonly sdd = inject(SddService);
  readonly books = [
    { id: 'demo' as const, title: 'Demo', headerClass: 'bg-primary text-white' },
    { id: 'live' as const, title: 'Live', headerClass: 'bg-dark text-white' },
  ];

  ngOnInit(): void {
    this.sdd.refresh();
  }

  account(id: 'demo' | 'live'): AccountView | undefined {
    return this.sdd.account(id);
  }

  positions(id: 'demo' | 'live'): Position[] {
    return this.sdd.positions()[id] ?? [];
  }

  haltOrError(id: 'demo' | 'live'): string | null {
    const book = this.sdd.lastScan()?.books?.find((b) => b.id === id);
    return book?.halt || book?.error || this.account(id)?.error || null;
  }

  latestSignals(): SddScan[] {
    return this.sdd.signals().slice(0, 20);
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
    if (u === 'BUY') {
      return 'text-bg-success';
    }
    if (u === 'SELL') {
      return 'text-bg-danger';
    }
    return 'text-bg-secondary';
  }

  flagClass(ok: boolean | undefined): string {
    return ok ? 'text-bg-success' : 'text-bg-secondary';
  }

  yn(ok: boolean | undefined): string {
    return ok ? 'Y' : 'n';
  }

  pnlClass(value: number | null | undefined): string {
    if (value == null) {
      return '';
    }
    if (value > 0) {
      return 'text-success';
    }
    if (value < 0) {
      return 'text-danger';
    }
    return '';
  }

  formatMoney(value: number | null | undefined): string {
    if (value == null) {
      return '—';
    }
    return value.toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
}
