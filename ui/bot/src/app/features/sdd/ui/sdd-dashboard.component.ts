import { Component, OnInit, inject } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { SddService } from '../service/sdd.service';
import { AuthService } from '../../auth/auth.service';
import { AccountView, BookId, Position, SwingScan } from '../model/sdd.model';

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
      <span class="small text-muted">Last scan: {{ (sdd.lastScan()?.scannedAt | date: 'short') || 'never' }}</span>
      <span class="small text-muted">webhook {{ webhookLabel() }}</span>
      <button
        type="button"
        class="btn btn-warning btn-sm"
        (click)="sdd.triggerScan()"
        [disabled]="sdd.busy()"
      >
        {{ sdd.busy() ? 'Scanning…' : 'Scan now' }}
      </button>
      <button
        type="button"
        class="btn btn-info btn-sm"
        (click)="sdd.syncHistory('live', true)"
        [disabled]="sdd.syncBusy()"
        title="Rebuild daily equity history from Capital.com live transactions"
      >
        {{ sdd.syncBusy() ? 'Syncing…' : 'Sync history' }}
      </button>
      <button type="button" class="btn btn-outline-secondary btn-sm" (click)="sdd.refresh()">
        Refresh
      </button>
    </div>

    @if (sdd.syncMessage(); as msg) {
      <div class="alert alert-info py-2">{{ msg }}</div>
    }

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
    @if (sdd.lastScan()?.lastWebhookError) {
      <div class="alert alert-warning py-2">Webhook: {{ sdd.lastScan()?.lastWebhookError }}</div>
    }
    @if (sdd.error()) {
      <div class="alert alert-danger py-2">{{ sdd.error() }}</div>
    }
    @if (sdd.accountsError()) {
      <div class="alert alert-danger py-2">{{ sdd.accountsError() }}</div>
    }
    @if (sdd.scanLoadError()) {
      <div class="alert alert-danger py-2">{{ sdd.scanLoadError() }}</div>
    }
    @if (sdd.positionsError()) {
      <div class="alert alert-danger py-2">{{ sdd.positionsError() }}</div>
    }

    <div class="card shadow-sm">
      <div class="card-header bg-dark text-white">Accounts</div>
      <div class="card-body p-0">
        <div class="table-responsive">
          <table class="table table-sm table-striped table-hover align-middle mb-0">
            <thead class="table-dark">
              <tr>
                <th>Book</th>
                <th>Account</th>
                <th>Status</th>
                <th class="text-end">Equity</th>
                <th class="text-end">Available</th>
                <th class="text-end">Day P/L</th>
                <th>Ccy</th>
                <th>Halt / error</th>
              </tr>
            </thead>
            <tbody>
              @for (book of books; track book.id) {
                <tr>
                  <td><span class="badge" [class]="book.headerClass">{{ book.title }}</span></td>
                  <td>{{ account(book.id)?.accountName || '—' }}</td>
                  <td>
                    <span class="badge" [class]="connClass(account(book.id)?.connected)">
                      {{ account(book.id)?.connected ? 'connected' : 'disconnected' }}
                    </span>
                  </td>
                  <td class="text-end">{{ formatMoney(account(book.id)?.equity) }}</td>
                  <td class="text-end">{{ formatMoney(account(book.id)?.available) }}</td>
                  <td class="text-end" [class]="pnlClass(account(book.id)?.dayPnl)">
                    {{ formatMoney(account(book.id)?.dayPnl) }}
                  </td>
                  <td>{{ account(book.id)?.currency || '—' }}</td>
                  <td>
                    @if (haltOrError(book.id); as halt) {
                      <span class="text-danger small">{{ halt }}</span>
                    } @else {
                      —
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <div class="card shadow-sm mt-3">
      <div class="card-header bg-dark text-white">
        Open positions
        @if (totalWithoutStop() > 0) {
          <span class="badge text-bg-danger ms-1" title="Open positions with no stop level">⚠ {{ totalWithoutStop() }} bez SL</span>
        }
      </div>
      <div class="card-body p-0">
        <div class="table-responsive">
          <table class="table table-sm table-striped table-hover mb-0">
            <thead class="table-dark">
              <tr>
                <th>Book</th>
                <th>Epic</th>
                <th>Dir</th>
                <th class="text-end">Size</th>
                <th class="text-end">Level</th>
                <th class="text-end">Stop</th>
                <th class="text-end">1R</th>
                <th class="text-end">uP/L</th>
              </tr>
            </thead>
            <tbody>
              @if (sdd.positionsError()) {
                <tr>
                  <td colspan="8" class="text-danger text-center">{{ sdd.positionsError() }}</td>
                </tr>
              } @else if (allPositions().length === 0) {
                <tr>
                  <td colspan="8" class="text-muted text-center">brak pozycji</td>
                </tr>
              } @else {
                @for (row of allPositions(); track row.book + '|' + row.p.dealId) {
                  <tr [class]="row.p.stopLevel == null ? 'table-warning' : ''" [title]="row.p.stopLevel == null ? 'Brak stop loss!' : ''">
                    <td><span class="badge" [class]="bookBadge(row.book)">{{ row.book }}</span></td>
                    <td>{{ row.p.epic }}</td>
                    <td><span class="badge" [class]="dirClass(row.p.direction)">{{ row.p.direction }}</span></td>
                    <td class="text-end">{{ row.p.size | number: '1.2-2' }}</td>
                    <td class="text-end">{{ row.p.level | number: '1.2-5' }}</td>
                    <td class="text-end">
                      @if (row.p.stopLevel == null) {
                        <span class="badge text-bg-danger">brak SL</span>
                      } @else {
                        {{ row.p.stopLevel | number: '1.2-5' }}
                      }
                    </td>
                    <td class="text-end" [class]="row.p.riskPln != null && row.p.riskPln > 0 ? 'text-danger fw-semibold' : ''" title="1R w walucie — maks. strata jeśli stop zadziała">
                      @if (row.p.riskPln != null) {
                        {{ row.p.riskPln | number: '1.2-2' }}
                      } @else {
                        <span class="text-muted">—</span>
                      }
                    </td>
                    <td class="text-end" [class]="pnlClass(row.p.unrealizedPnl)">
                      {{ row.p.unrealizedPnl | number: '1.2-2' }}
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
              @if (sdd.scanLoadError()) {
                <tr>
                  <td colspan="11" class="text-danger text-center">{{ sdd.scanLoadError() }}</td>
                </tr>
              } @else if ((sdd.lastScan()?.symbols || []).length === 0) {
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

    @if (canSeeSwing) {
      <div class="card shadow-sm mt-3">
        <div class="card-header bg-info text-dark">
          SDD-SWING scan (H1)
          <span class="small text-dark-emphasis ms-2">
            {{ (sdd.swingLast()?.scannedAt | date: 'short') || 'never' }}
          </span>
        </div>
        <div class="card-body p-0">
          <div class="table-responsive">
            <table class="table table-sm table-striped table-hover mb-0">
              <thead class="table-dark">
                <tr>
                  <th>Time</th>
                  <th>Symbol</th>
                  <th>Direction</th>
                  <th>H4 trend</th>
                  <th class="text-end">Entry</th>
                  <th class="text-end">Stop</th>
                  <th class="text-end">Target (1R)</th>
                </tr>
              </thead>
              <tbody>
                @if (sdd.swingError()) {
                  <tr>
                    <td colspan="7" class="text-danger text-center">{{ sdd.swingError() }}</td>
                  </tr>
                } @else if (swingSignals().length === 0) {
                  <tr>
                    <td colspan="7" class="text-muted text-center">
                      {{ sdd.swingLast()?.error || 'No swing entry signals from the last H1 scan' }}
                    </td>
                  </tr>
                } @else {
                  @for (s of swingSignals(); track $index) {
                    <tr>
                      <td>{{ s.timestamp | date: 'short' }}</td>
                      <td>{{ s.symbol }}</td>
                      <td><span class="badge" [class]="dirClass(s.direction)">{{ s.direction }}</span></td>
                      <td><span class="badge" [class]="trendClass(s.h4Trend)">{{ s.h4Trend }}</span></td>
                      <td class="text-end">{{ s.entry | number: '1.2-5' }}</td>
                      <td class="text-end">{{ s.stopLevel | number: '1.2-5' }}</td>
                      <td class="text-end">{{ s.targetLevel | number: '1.2-5' }}</td>
                    </tr>
                  }
                }
              </tbody>
            </table>
          </div>
        </div>
      </div>
    }
  `,
})
export class SddDashboardComponent implements OnInit {
  readonly sdd = inject(SddService);
  private readonly auth = inject(AuthService);
  readonly books = (
    [
      { id: 'demo', title: 'Demo', headerClass: 'bg-primary text-white' },
      { id: 'live', title: 'Live', headerClass: 'bg-dark text-white' },
      { id: 'swing', title: 'Swing', headerClass: 'bg-info text-dark' },
      { id: 'hts', title: 'HTS', headerClass: 'bg-secondary text-white' },
    ] as { id: BookId; title: string; headerClass: string }[]
  ).filter((b) => this.auth.canSeeBook(b.id));
  readonly canSeeSwing = this.auth.canSeeBook('swing');

  ngOnInit(): void {
    this.sdd.refresh();
    this.sdd.loadSwingLast();
  }

  account(id: BookId): AccountView | undefined {
    return this.sdd.account(id);
  }

  positions(id: BookId): Position[] {
    return this.sdd.positions()[id] ?? [];
  }

  /** Every visible book's open positions, flattened with the book label. */
  allPositions(): { book: string; p: Position }[] {
    const out: { book: string; p: Position }[] = [];
    for (const b of this.books) {
      for (const p of this.positions(b.id)) {
        out.push({ book: b.title, p });
      }
    }
    return out;
  }

  totalWithoutStop(): number {
    return this.allPositions().filter((r) => r.p.stopLevel == null).length;
  }

  bookBadge(title: string): string {
    return this.books.find((b) => b.title === title)?.headerClass ?? 'bg-secondary text-white';
  }

  swingSignals(): SwingScan[] {
    return this.sdd.swingLast()?.signals ?? [];
  }

  trendClass(trend: string | undefined): string {
    const u = (trend || '').toUpperCase();
    if (u === 'UP') {
      return 'text-bg-success';
    }
    if (u === 'DOWN') {
      return 'text-bg-danger';
    }
    return 'text-bg-secondary';
  }

  haltOrError(id: BookId): string | null {
    const book = this.sdd.lastScan()?.books?.find((b) => b.id === id);
    return book?.halt || book?.error || this.account(id)?.error || this.sdd.accountsError() || null;
  }

  webhookLabel(): string {
    const health = this.sdd.health();
    if (health && health.webhookConfigured === false) {
      return 'off';
    }
    return health?.lastWebhook || this.sdd.lastScan()?.lastWebhookError || '…';
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
