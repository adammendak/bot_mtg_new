import { Component, OnInit, inject } from '@angular/core';
import { SddService } from '../service/sdd.service';

@Component({
  selector: 'app-history',
  standalone: true,
  imports: [],
  template: `
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <h2 class="h4 mb-0">History — daily P/L</h2>
      <div class="btn-group btn-group-sm" role="group" aria-label="Book">
        <button
          type="button"
          class="btn"
          [class.btn-primary]="sdd.historyBook() === 'demo'"
          [class.btn-outline-secondary]="sdd.historyBook() !== 'demo'"
          (click)="select('demo')"
        >
          Demo
        </button>
        <button
          type="button"
          class="btn"
          [class.btn-primary]="sdd.historyBook() === 'live'"
          [class.btn-outline-secondary]="sdd.historyBook() !== 'live'"
          (click)="select('live')"
        >
          Live
        </button>
      </div>
      <button type="button" class="btn btn-outline-secondary btn-sm" (click)="reload()">Refresh</button>
    </div>
    <p class="small text-muted">
      Daily snapshots taken from the broker book. The chart shows the cumulative % change of
      equity from the first recorded day (0% at the start).
    </p>
    @if (sdd.historyError()) {
      <div class="alert alert-danger py-2">{{ sdd.historyError() }}</div>
    }
    @if (sdd.history(); as h) {
      @if (h.points.length === 0) {
        <p class="text-muted">No data yet.</p>
      } @else {
        <p class="small text-muted">
          {{ h.book }} · {{ h.currency ?? '' }} · {{ h.connected ? 'connected' : 'not connected' }}
        </p>
        <div class="card shadow-sm mb-3">
          <div class="card-body">
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
          </div>
        </div>
        <div class="card shadow-sm">
          <div class="card-header bg-dark text-white">Daily equity</div>
          <div class="card-body p-0">
            <div class="table-responsive">
              <table class="table table-sm table-striped table-hover mb-0">
                <thead class="table-dark">
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
            </div>
          </div>
        </div>
      }
    }
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
