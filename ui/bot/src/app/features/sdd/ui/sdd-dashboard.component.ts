import { Component, OnInit, inject } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { SddService } from '../service/sdd.service';

@Component({
  selector: 'app-sdd-dashboard',
  standalone: true,
  imports: [DecimalPipe],
  template: `
    <div>
      <h2>SDD-M15 dashboard</h2>
      <p>
        Broker: {{ sdd.broker()?.name || '…' }}
        · Health: {{ sdd.health()?.status || '…' }}
        · Execution: {{ sdd.broker()?.executionEnabled ? 'ON' : 'off' }}
      </p>
      <p>Last scan: {{ sdd.lastScan()?.scannedAt || 'never' }}</p>
      @if (sdd.lastScan()?.newsBlackout) {
        <p>News blackout is active (no new SDD).</p>
      }
      @if (sdd.lastScan()?.halt) {
        <p>{{ sdd.lastScan()?.halt }}</p>
      }
      @if (sdd.lastScan()?.error) {
        <p>{{ sdd.lastScan()?.error }}</p>
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
      <h3>Per-symbol stack</h3>
      <table>
        <thead>
          <tr>
            <th>Symbol</th>
            <th>Epic</th>
            <th>Dir</th>
            <th>HA</th>
            <th>RMA</th>
            <th>H1</th>
            <th>PP</th>
            <th>Full</th>
            <th>Entry</th>
            <th>Stop</th>
          </tr>
        </thead>
        <tbody>
          @for (row of sdd.lastScan()?.symbols || []; track row.symbol) {
            <tr>
              <td>{{ row.symbol }}</td>
              <td>{{ row.epic }}</td>
              <td>{{ row.direction }}</td>
              <td>{{ row.setup.ha ? 'Y' : 'n' }}</td>
              <td>{{ row.setup.rma ? 'Y' : 'n' }}</td>
              <td>{{ row.setup.h1 ? 'Y' : 'n' }}</td>
              <td>{{ row.setup.pp ? 'Y' : 'n' }}</td>
              <td>{{ row.fullStack ? 'Y' : 'n' }}</td>
              <td>{{ row.entry | number: '1.2-5' }}</td>
              <td>{{ row.stop | number: '1.2-5' }}</td>
            </tr>
          }
        </tbody>
      </table>
      @if ((sdd.lastScan()?.symbols || []).length === 0) {
        <p>No scan yet. Use Scan now (paper broker works without Capital creds).</p>
      }
      <h3>Open positions</h3>
      @if (sdd.positions().length === 0) {
        <p>None</p>
      } @else {
        <ul>
          @for (p of sdd.positions(); track p.dealId) {
            <li>{{ p.epic }} {{ p.direction }} {{ p.size }} @ {{ p.level }}</li>
          }
        </ul>
      }
    </div>
  `,
})
export class SddDashboardComponent implements OnInit {
  readonly sdd = inject(SddService);

  ngOnInit(): void {
    this.sdd.refresh();
  }
}
