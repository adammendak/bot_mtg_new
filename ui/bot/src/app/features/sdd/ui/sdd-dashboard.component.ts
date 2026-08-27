import { Component, OnInit, inject } from '@angular/core';
import { SddService } from '../service/sdd.service';
import { AccountView, Position, SddScan } from '../model/sdd.model';

@Component({
  selector: 'app-sdd-dashboard',
  standalone: true,
  imports: [],
  template: `
    <div>
      <h2>SDD-M15 dashboard</h2>
      <p>
        Health: {{ sdd.health()?.status || '…' }}
        · Execution: {{ sdd.health()?.executionEnabled ? 'ON' : 'off' }}
        · Last scan: {{ sdd.lastScan()?.scannedAt || 'never' }}
      </p>
      @if (sdd.lastScan()?.newsBlackout) {
        <p>News blackout is active (no new SDD).</p>
      }
      @if (sdd.lastScan()?.error) {
        <p>Scan: {{ sdd.lastScan()?.error }}</p>
      }
      @if (sdd.error()) {
        <p>{{ sdd.error() }}</p>
      }
      <div>
        <button type="button" (click)="sdd.triggerScan()" [disabled]="sdd.busy()">
          {{ sdd.busy() ? 'Scanning…' : 'Scan now' }}
        </button>
        <button type="button" (click)="sdd.refresh()">Refresh</button>
      </div>
      <p class="note">
        Demo and Live are separate books. P/L is never mixed. Scan runs once (same candles)
        and is shown in both panes. Execution stays off unless EXECUTION_ENABLED=true (demo only).
      </p>
      <div class="books">
        <section class="book">
          <h3>Demo</h3>
          <p>{{ paneStatus(sdd.account('demo')) }}</p>
          @if (bookHalt('demo')) {
            <p>{{ bookHalt('demo') }}</p>
          }
          <h4>Positions (demo)</h4>
          @if (sdd.positions().demo.length === 0) {
            <p>None</p>
          } @else {
            <ul>
              @for (p of sdd.positions().demo; track p.dealId) {
                <li>{{ formatPosition(p) }}</li>
              }
            </ul>
          }
          <h4>SDD stack (shared scan)</h4>
          @if ((sdd.lastScan()?.symbols || []).length === 0) {
            <p>No scan yet.</p>
          } @else {
            <table>
              <thead>
                <tr>
                  <th>Symbol</th>
                  <th>HA</th>
                  <th>RMA</th>
                  <th>H1</th>
                  <th>PP</th>
                  <th>Full</th>
                </tr>
              </thead>
              <tbody>
                @for (row of sdd.lastScan()?.symbols || []; track row.symbol) {
                  <tr>
                    <td>{{ row.symbol }}</td>
                    <td>{{ row.setup.ha ? 'Y' : 'n' }}</td>
                    <td>{{ row.setup.rma ? 'Y' : 'n' }}</td>
                    <td>{{ row.setup.h1 ? 'Y' : 'n' }}</td>
                    <td>{{ row.setup.pp ? 'Y' : 'n' }}</td>
                    <td>{{ row.fullStack ? 'Y' : 'n' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
          <h4>Latest signals</h4>
          @if (latestSignals().length === 0) {
            <p>None</p>
          } @else {
            <ul>
              @for (s of latestSignals(); track $index) {
                <li>{{ s.symbol }} {{ s.direction }} {{ s.fullStack ? 'FULL' : 'flip' }}</li>
              }
            </ul>
          }
        </section>
        <section class="book">
          <h3>Live</h3>
          <p>{{ paneStatus(sdd.account('live')) }}</p>
          @if (bookHalt('live')) {
            <p>{{ bookHalt('live') }}</p>
          }
          <h4>Positions (live)</h4>
          @if (sdd.positions().live.length === 0) {
            <p>None</p>
          } @else {
            <ul>
              @for (p of sdd.positions().live; track p.dealId) {
                <li>{{ formatPosition(p) }}</li>
              }
            </ul>
          }
          <h4>SDD stack (shared scan)</h4>
          @if ((sdd.lastScan()?.symbols || []).length === 0) {
            <p>No scan yet.</p>
          } @else {
            <table>
              <thead>
                <tr>
                  <th>Symbol</th>
                  <th>HA</th>
                  <th>RMA</th>
                  <th>H1</th>
                  <th>PP</th>
                  <th>Full</th>
                </tr>
              </thead>
              <tbody>
                @for (row of sdd.lastScan()?.symbols || []; track row.symbol) {
                  <tr>
                    <td>{{ row.symbol }}</td>
                    <td>{{ row.setup.ha ? 'Y' : 'n' }}</td>
                    <td>{{ row.setup.rma ? 'Y' : 'n' }}</td>
                    <td>{{ row.setup.h1 ? 'Y' : 'n' }}</td>
                    <td>{{ row.setup.pp ? 'Y' : 'n' }}</td>
                    <td>{{ row.fullStack ? 'Y' : 'n' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
          <h4>Latest signals</h4>
          @if (latestSignals().length === 0) {
            <p>None</p>
          } @else {
            <ul>
              @for (s of latestSignals(); track $index) {
                <li>{{ s.symbol }} {{ s.direction }} {{ s.fullStack ? 'FULL' : 'flip' }}</li>
              }
            </ul>
          }
        </section>
      </div>
    </div>
  `,
})
export class SddDashboardComponent implements OnInit {
  readonly sdd = inject(SddService);

  ngOnInit(): void {
    this.sdd.refresh();
  }

  paneStatus(account: AccountView | undefined): string {
    if (!account) {
      return '…';
    }
    const conn = account.connected ? 'connected' : 'disconnected';
    const name = account.accountName ? ` · ${account.accountName}` : '';
    const eq =
      account.equity == null ? '' : ` · equity ${account.equity} ${account.currency ?? ''}`.trimEnd();
    const pnl =
      account.dayPnl == null ? '' : ` · day P/L ${account.dayPnl} ${account.currency ?? ''}`.trimEnd();
    const err = account.error ? ` · ${account.error}` : '';
    return `${conn}${name}${eq}${pnl}${err}`;
  }

  bookHalt(id: 'demo' | 'live'): string | null {
    const book = this.sdd.lastScan()?.books?.find((b) => b.id === id);
    return book?.halt ?? null;
  }

  latestSignals(): SddScan[] {
    return this.sdd.signals().slice(0, 5);
  }

  formatPosition(p: Position): string {
    return `${p.epic} ${p.direction} ${p.size} @ ${p.level}`;
  }
}
