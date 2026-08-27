import { Component, OnInit, inject } from '@angular/core';
import { SddService } from '../service/sdd.service';
import { DailyEquityPoint } from '../model/sdd.model';

@Component({
  selector: 'app-history',
  standalone: true,
  imports: [],
  template: `
    <div>
      <h2>History — daily P/L</h2>
      <p class="note">
        Daily snapshots taken from the broker book. The chart shows the cumulative % change of
        equity from the first recorded day (0% at the start).
      </p>
      <div>
        <button type="button" (click)="select('demo')" [class.active]="sdd.historyBook() === 'demo'">
          Demo
        </button>
        <button type="button" (click)="select('live')" [class.active]="sdd.historyBook() === 'live'">
          Live
        </button>
        <button type="button" (click)="reload()">Refresh</button>
      </div>
      @if (sdd.historyError()) {
        <p class="error">{{ sdd.historyError() }}</p>
      }
      @if (sdd.history(); as h) {
        @if (h.points.length === 0) {
          <p>No data yet.</p>
        } @else {
          <p class="note">
            {{ h.book }} · {{ h.currency ?? '' }} · {{ h.connected ? 'connected' : 'not connected' }}
          </p>
          <svg
            class="chart"
            [attr.viewBox]="viewBox()"
            role="img"
            aria-label="Equity % change over time"
          >
            <line [attr.x1]="padL" [attr.y1]="padT" [attr.x2]="padL" [attr.y2]="chartH - padB" class="axis" />
            <line [attr.x1]="padL" [attr.y1]="chartH - padB" [attr.x2]="chartW - padR" [attr.y2]="chartH - padB" class="axis" />
            @if (zeroY() != null && zeroY()! > 0 && zeroY()! < chartH) {
              <line
                [attr.x1]="padL"
                [attr.y1]="zeroY()!"
                [attr.x2]="chartW - padR"
                [attr.y2]="zeroY()!"
                class="zero"
              />
            }
            <polyline [attr.points]="linePoints()" class="line" fill="none" />
            @for (p of chartPoints(); track $index) {
              <circle [attr.cx]="p.x" [attr.cy]="p.y" r="3" class="dot" />
            }
          </svg>
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Equity</th>
                <th>Day P/L</th>
                <th>% change</th>
              </tr>
            </thead>
            <tbody>
              @for (row of h.points; track row.date) {
                <tr>
                  <td>{{ row.date }}</td>
                  <td>{{ fmtNum(row.equity) }}</td>
                  <td>{{ fmtNum(row.dayPnl) }}</td>
                  <td>{{ fmtPct(row.pctChange) }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      }
    </div>
  `,
})
export class HistoryComponent implements OnInit {
  readonly sdd = inject(SddService);

  readonly chartW = 800;
  readonly chartH = 300;
  readonly padL = 40;
  readonly padR = 16;
  readonly padT = 12;
  readonly padB = 24;

  ngOnInit(): void {
    this.select(this.sdd.historyBook());
  }

  select(book: 'demo' | 'live'): void {
    this.sdd.loadHistory(book);
  }

  reload(): void {
    this.select(this.sdd.historyBook());
  }

  viewBox(): string {
    return `0 0 ${this.chartW} ${this.chartH}`;
  }

  private values(): number[] {
    const h = this.sdd.history();
    return (h?.points || [])
      .map((p) => p.pctChange)
      .filter((v): v is number => v != null && isFinite(v));
  }

  private minMax(values: number[]): [number, number] {
    if (values.length === 0) {
      return [-1, 1];
    }
    const min = Math.min(...values, 0);
    const max = Math.max(...values, 0);
    if (min === max) {
      return [min - 1, max + 1];
    }
    return [min, max];
  }

  chartPoints(): { x: number; y: number }[] {
    const h = this.sdd.history();
    if (!h || h.points.length === 0) {
      return [];
    }
    const [min, max] = this.minMax(this.values());
    const span = max - min || 1;
    const innerW = this.chartW - this.padL - this.padR;
    const innerH = this.chartH - this.padT - this.padB;
    const n = h.points.length;
    return h.points.map((p, i) => {
      const x = n === 1 ? this.padL + innerW / 2 : this.padL + (i / (n - 1)) * innerW;
      const v = p.pctChange ?? 0;
      const y = this.padT + innerH - ((v - min) / span) * innerH;
      return { x, y };
    });
  }

  linePoints(): string {
    return this.chartPoints().map((p) => `${p.x},${p.y}`).join(' ');
  }

  zeroY(): number | null {
    const values = this.values();
    if (values.length === 0) {
      return null;
    }
    const [min, max] = this.minMax(values);
    const span = max - min || 1;
    const innerH = this.chartH - this.padT - this.padB;
    const y = this.padT + innerH - ((0 - min) / span) * innerH;
    return y;
  }

  fmtNum(v: number | null): string {
    return v == null ? '–' : v.toFixed(2);
  }

  fmtPct(v: number | null): string {
    return v == null ? '–' : `${v.toFixed(2)}%`;
  }
}
