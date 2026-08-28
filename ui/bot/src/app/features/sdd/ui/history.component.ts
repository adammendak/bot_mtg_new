import { Component, OnInit, inject, signal } from '@angular/core';
import { SddService } from '../service/sdd.service';
import { DailyEquityPoint } from '../model/sdd.model';

interface ChartPoint {
  x: number;
  y: number | null; // equity point y (top panel)
  equity: number | null;
  pnl: number | null;
  pct: number | null;
}

interface HoverPoint extends ChartPoint {
  date: string;
  tx: number;
  ty: number;
  tw: number;
  th: number;
}

interface Bar {
  x: number;
  y: number;
  w: number;
  h: number;
  pos: boolean;
}

@Component({
  selector: 'app-history',
  standalone: true,
  imports: [],
  template: `
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <h2 class="h4 mb-0">History — daily equity &amp; P/L</h2>
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
        <button
          type="button"
          class="btn"
          [class.btn-primary]="sdd.historyBook() === 'glowne'"
          [class.btn-outline-secondary]="sdd.historyBook() !== 'glowne'"
          (click)="select('glowne')"
        >
          Główne
        </button>
      </div>
      <button
        type="button"
        class="btn btn-info btn-sm"
        (click)="sdd.syncAll(true)"
        [disabled]="sdd.syncBusy()"
        title="Rebuild daily equity history for Demo, Live and Główne from Capital.com transactions"
      >
        {{ sdd.syncBusy() ? 'Syncing…' : 'Sync all' }}
      </button>
      <button type="button" class="btn btn-outline-secondary btn-sm" (click)="reload()">Refresh</button>
      <div class="ms-2 d-inline-flex align-items-center gap-2 border rounded px-2 py-1">
        <span class="small text-muted">Range:</span>
        <input
          type="date"
          class="form-control form-control-sm"
          style="width: 9.5rem"
          [value]="fromDate()"
          [min]="minDate()"
          [max]="maxDate()"
          (input)="onFromInput($event)"
        />
        <span class="small text-muted">→</span>
        <input
          type="date"
          class="form-control form-control-sm"
          style="width: 9.5rem"
          [value]="toDate()"
          [min]="minDate()"
          [max]="maxDate()"
          (input)="onToInput($event)"
        />
        @if (rangeActive()) {
          <button type="button" class="btn btn-outline-danger btn-sm" (click)="clearRange()">Clear</button>
        }
      </div>
    </div>

    @if (sdd.syncMessage()) {
      <div class="alert alert-info py-2">{{ sdd.syncMessage() }}</div>
    }
    @if (sdd.historyError()) {
      <div class="alert alert-danger py-2">{{ sdd.historyError() }}</div>
    }
    @if (sdd.history(); as h) {
      @if (h.points.length === 0) {
        <p class="text-muted">No data yet.</p>
      } @else {
        <div class="d-flex flex-wrap gap-2 mb-2 small">
          <span class="badge text-bg-light border">{{ h.book }} · {{ h.currency ?? '' }}</span>
          <span class="badge text-bg-light border">{{ h.connected ? 'connected' : 'not connected' }}</span>
          @if (stats(); as st) {
            <span class="badge text-bg-light border">Latest equity: {{ fmtMoney(st.lastEquity) }}</span>
            <span class="badge" [class.text-bg-success]="st.sumPnl >= 0" [class.text-bg-danger]="st.sumPnl < 0">
              Σ day P/L: {{ fmtMoney(st.sumPnl) }}
            </span>
            <span class="badge" [class.text-bg-success]="(st.cumPct ?? 0) >= 0" [class.text-bg-danger]="(st.cumPct ?? 0) < 0">
              {{ fmtPct(st.cumPct) }}
            </span>
          }
        </div>

        <div class="card shadow-sm mb-3">
          <div class="card-body">
            <svg
              class="chart"
              [attr.viewBox]="viewBox()"
              role="img"
              aria-label="Daily equity and day P/L chart"
              (mouseleave)="hover.set(null)"
            >
              <defs>
                <linearGradient id="eqGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" [attr.stop-color]="trendColor()" stop-opacity="0.28" />
                  <stop offset="100%" [attr.stop-color]="trendColor()" stop-opacity="0.02" />
                </linearGradient>
              </defs>

              <!-- equity panel gridlines + left axis -->
              @for (t of equityTicks(); track t.y) {
                <line [attr.x1]="padL" [attr.y1]="t.y" [attr.x2]="chartW - padR" [attr.y2]="t.y" class="grid" />
                <text [attr.x]="padL - 8" [attr.y]="t.y + 4" text-anchor="end" class="tick">
                  {{ t.label }}
                </text>
              }
              <!-- pnl panel gridlines + right axis -->
              @for (t of pnlTicks(); track t.y) {
                <line [attr.x1]="padL" [attr.y1]="t.y" [attr.x2]="chartW - padR" [attr.y2]="t.y" class="grid" />
                <text [attr.x]="chartW - padR + 8" [attr.y]="t.y + 4" text-anchor="start" class="tick">
                  {{ t.label }}
                </text>
              }
              <!-- zero line for pnl -->
              @if (pnlZeroY(); as zy) {
                <line [attr.x1]="padL" [attr.y1]="zy" [attr.x2]="chartW - padR" [attr.y2]="zy" class="zero" />
              }
              <!-- panel separators -->
              <line [attr.x1]="padL" [attr.y1]="equityBottom" [attr.x2]="chartW - padR" [attr.y2]="equityBottom" class="panel" />
              <line [attr.x1]="padL" [attr.y1]="pnlBottom" [attr.x2]="chartW - padR" [attr.y2]="pnlBottom" class="panel" />

              <!-- date ticks -->
              @for (d of dateTicks(); track d.x) {
                <text [attr.x]="d.x" [attr.y]="chartH - 22" text-anchor="middle" class="tick">{{ d.label }}</text>
              }

              <!-- pnl bars -->
              @for (b of bars(); track b.x) {
                <rect [attr.x]="b.x" [attr.y]="b.y" [attr.width]="b.w" [attr.height]="b.h" [class.bar-pos]="b.pos" [class.bar-neg]="!b.pos" rx="1.5" />
              }

              <!-- equity area + line -->
              @if (points().length > 1) {
                <path [attr.d]="areaPath()" fill="url(#eqGrad)" />
              }
              <polyline [attr.points]="linePoints()" class="line" [attr.stroke]="trendColor()" fill="none" />

              <!-- equity dots -->
              @for (p of points(); track p.x) {
                @if (p.y != null) {
                  <circle
                    [attr.cx]="p.x"
                    [attr.cy]="p.y"
                    [attr.r]="hover() === $index ? 5 : 3"
                    class="dot"
                    [attr.stroke]="trendColor()"
                    (mouseenter)="hover.set($index)"
                  />
                }
              }

              <!-- crosshair + tooltip -->
              @if (hoverPoint(); as hp) {
                <line [attr.x1]="hp.x" [attr.y1]="padT" [attr.x2]="hp.x" [attr.y2]="pnlBottom" class="cross" />
                <g [attr.transform]="'translate(' + hp.tx + ',' + hp.ty + ')'">
                  <rect [attr.width]="hp.tw" [attr.height]="hp.th" rx="5" class="tip-bg" />
                  <text [attr.x]="10" [attr.y]="18" class="tip-title">{{ hp.date }}</text>
                  <text [attr.x]="10" [attr.y]="35" class="tip-row">
                    Equity <tspan class="tip-val">{{ fmtMoney(hp.equity) }}</tspan>
                  </text>
                  <text [attr.x]="10" [attr.y]="51" class="tip-row">
                    Day P/L <tspan [class.tip-pos]="(hp.pnl ?? 0) >= 0" [class.tip-neg]="(hp.pnl ?? 0) < 0">{{ fmtMoney(hp.pnl) }}</tspan>
                  </text>
                  <text [attr.x]="10" [attr.y]="67" class="tip-row">
                    Δ <tspan [class.tip-pos]="(hp.pct ?? 0) >= 0" [class.tip-neg]="(hp.pct ?? 0) < 0">{{ fmtPct(hp.pct) }}</tspan>
                  </text>
                </g>
              }

              <!-- hover capture -->
              <rect
                class="hover-capture"
                [attr.x]="padL"
                [attr.y]="padT"
                [attr.width]="plotW()"
                [attr.height]="pnlBottom - padT"
                (mousemove)="onHover($event)"
              />
            </svg>
          </div>
        </div>

        <div class="card shadow-sm">
          <div class="card-header bg-dark text-white d-flex justify-content-between align-items-center">
            <span>Daily equity</span>
            <span class="small text-white-50">{{ visiblePoints().length }} days{{ rangeActive() ? ' (filtered)' : '' }}</span>
          </div>
          <div class="card-body p-0 table-scroll">
            <table class="table table-sm table-striped table-hover mb-0">
              <thead class="table-dark sticky-head">
                <tr>
                  <th>Date</th>
                  <th class="text-end">Equity</th>
                  <th class="text-end">Day P/L</th>
                  <th class="text-end">% change</th>
                </tr>
              </thead>
              <tbody>
                @for (row of visiblePoints(); track row.date) {
                  <tr>
                    <td>{{ row.date }}</td>
                    <td class="text-end">{{ fmtNum(row.equity) }}</td>
                    <td class="text-end" [class.text-success]="(row.dayPnl ?? 0) > 0" [class.text-danger]="(row.dayPnl ?? 0) < 0">
                      {{ fmtNum(row.dayPnl) }}
                    </td>
                    <td class="text-end" [class.text-success]="(row.pctChange ?? 0) > 0" [class.text-danger]="(row.pctChange ?? 0) < 0">
                      {{ fmtPct(row.pctChange) }}
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }
    }
  `,
  styles: [
    `
      .chart {
        width: 100%;
        height: auto;
        background: #fcfcfc;
        border: 1px solid #dee2e6;
        border-radius: 6px;
      }
      .grid {
        stroke: #e9ecef;
        stroke-width: 1;
      }
      .panel {
        stroke: #c7cdd4;
        stroke-width: 1;
      }
      .zero {
        stroke: #adb5bd;
        stroke-width: 1;
        stroke-dasharray: 4 3;
      }
      .tick {
        font-size: 10px;
        fill: #6c757d;
      }
      .line {
        stroke-width: 2.2;
        stroke-linejoin: round;
        stroke-linecap: round;
      }
      .dot {
        fill: #fff;
        stroke-width: 2;
        cursor: crosshair;
        transition: r 0.08s ease;
      }
      .bar-pos {
        fill: #198754;
        opacity: 0.85;
      }
      .bar-neg {
        fill: #dc3545;
        opacity: 0.85;
      }
      .cross {
        stroke: #6c757d;
        stroke-width: 1;
        stroke-dasharray: 3 3;
        pointer-events: none;
      }
      .hover-capture {
        fill: transparent;
        cursor: crosshair;
      }
      .tip-bg {
        fill: #212529;
        opacity: 0.95;
      }
      .tip-title {
        fill: #ced4da;
        font-size: 11px;
        font-weight: 600;
      }
      .tip-row {
        fill: #adb5bd;
        font-size: 11px;
      }
      .tip-val {
        fill: #fff;
        font-weight: 600;
      }
      .tip-pos {
        fill: #4ade80;
        font-weight: 600;
      }
      .tip-neg {
        fill: #f87171;
        font-weight: 600;
      }
      .table-scroll {
        max-height: 360px;
        overflow-y: auto;
      }
      .sticky-head th {
        position: sticky;
        top: 0;
        z-index: 1;
      }
    `,
  ],
})
export class HistoryComponent implements OnInit {
  readonly sdd = inject(SddService);
  readonly hover = signal<number | null>(null);

  // Custom date-range filter (empty = show all)
  readonly fromDate = signal<string>('');
  readonly toDate = signal<string>('');

  readonly chartW = 900;
  readonly chartH = 420;
  readonly padL = 72;
  readonly padR = 72;
  readonly padT = 24;
  readonly padB = 46;
  readonly equityBottom = 256;
  readonly pnlTop = 264;
  readonly pnlBottom = 358;

  ngOnInit(): void {
    this.select(this.sdd.historyBook());
  }

  select(book: 'demo' | 'live' | 'glowne'): void {
    this.hover.set(null);
    this.sdd.loadHistory(book);
  }

  reload(): void {
    this.select(this.sdd.historyBook());
  }

  viewBox(): string {
    return `0 0 ${this.chartW} ${this.chartH}`;
  }

  plotW(): number {
    return this.chartW - this.padL - this.padR;
  }

  private raw(): DailyEquityPoint[] {
    const all = this.sdd.history()?.points ?? [];
    const from = this.fromDate();
    const to = this.toDate();
    if (!from && !to) {
      return all;
    }
    return all.filter((p) => {
      if (from && p.date < from) {
        return false;
      }
      if (to && p.date > to) {
        return false;
      }
      return true;
    });
  }

  /** Filtered points (respects the custom date range) — used by the table too. */
  visiblePoints(): DailyEquityPoint[] {
    return this.raw();
  }

  rangeActive(): boolean {
    return !!this.fromDate() || !!this.toDate();
  }

  onFromInput(ev: Event): void {
    this.fromDate.set((ev.target as HTMLInputElement).value);
    this.hover.set(null);
  }

  onToInput(ev: Event): void {
    this.toDate.set((ev.target as HTMLInputElement).value);
    this.hover.set(null);
  }

  clearRange(): void {
    this.fromDate.set('');
    this.toDate.set('');
    this.hover.set(null);
  }

  minDate(): string {
    const all = this.sdd.history()?.points ?? [];
    return all.length ? all[0].date : '';
  }

  maxDate(): string {
    const all = this.sdd.history()?.points ?? [];
    return all.length ? all[all.length - 1].date : '';
  }

  private equityDomain(): [number, number] {
    const vals = this.raw()
      .map((p) => p.equity)
      .filter((v): v is number => v != null && isFinite(v));
    if (vals.length === 0) {
      return [0, 1];
    }
    let min = Math.min(...vals);
    let max = Math.max(...vals);
    if (min === max) {
      min -= 1;
      max += 1;
    }
    const pad = (max - min) * 0.06;
    return [min - pad, max + pad];
  }

  private pnlMax(): number {
    const vals = this.raw()
      .map((p) => p.dayPnl)
      .filter((v): v is number => v != null && isFinite(v) && v !== 0);
    if (vals.length === 0) {
      return 1;
    }
    const m = Math.max(...vals.map((v) => Math.abs(v)));
    return Math.max(m, 1) * 1.12;
  }

  private eqY(v: number, [lo, hi]: [number, number]): number {
    const span = hi - lo || 1;
    const innerH = this.equityBottom - this.padT;
    return this.padT + innerH - ((v - lo) / span) * innerH;
  }

  points(): ChartPoint[] {
    const pts = this.raw();
    const n = pts.length;
    const [lo, hi] = this.equityDomain();
    const innerW = this.plotW();
    return pts.map((p, i) => {
      const x = n === 1 ? this.padL + innerW / 2 : this.padL + (i / (n - 1)) * innerW;
      const equity = p.equity != null && isFinite(p.equity) ? p.equity : null;
      const pnl = p.dayPnl != null && isFinite(p.dayPnl) ? p.dayPnl : null;
      const y = equity == null ? null : this.eqY(equity, [lo, hi]);
      return { x, y, equity, pnl, pct: p.pctChange };
    });
  }

  linePoints(): string {
    return this.points()
      .filter((p) => p.y != null)
      .map((p) => `${p.x.toFixed(1)},${p.y!.toFixed(1)}`)
      .join(' ');
  }

  areaPath(): string {
    const pts = this.points().filter((p) => p.y != null);
    if (pts.length < 2) {
      return '';
    }
    const base = this.equityBottom;
    const first = pts[0];
    const last = pts[pts.length - 1];
    const line = pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y!.toFixed(1)}`).join(' ');
    return `${line} L${last.x.toFixed(1)},${base} L${first.x.toFixed(1)},${base} Z`;
  }

  bars(): Bar[] {
    const pts = this.points();
    const n = pts.length;
    const innerW = this.plotW();
    const w = Math.max(2, Math.min(22, innerW / n / 2.2));
    const zeroY = this.pnlTop + (this.pnlBottom - this.pnlTop) / 2;
    const pnlMax = this.pnlMax();
    const innerPnlH = this.pnlBottom - this.pnlTop;
    return pts
      .filter((p) => p.pnl != null)
      .map((p) => {
        const h = (Math.abs(p.pnl!) / pnlMax) * innerPnlH;
        const y = p.pnl! >= 0 ? zeroY - h : zeroY;
        return { x: p.x - w / 2, y, w, h: Math.max(h, 1.2), pos: p.pnl! >= 0 };
      });
  }

  equityTicks(): { y: number; label: string }[] {
    const [lo, hi] = this.equityDomain();
    const ticks: { y: number; label: string }[] = [];
    const steps = 5;
    for (let i = 0; i <= steps; i++) {
      const v = lo + ((hi - lo) * i) / steps;
      ticks.push({ y: this.eqY(v, [lo, hi]), label: this.fmtShort(v) });
    }
    return ticks;
  }

  pnlTicks(): { y: number; label: string }[] {
    const m = this.pnlMax();
    const innerPnlH = this.pnlBottom - this.pnlTop;
    const zeroY = this.pnlTop + innerPnlH / 2;
    const ticks: { y: number; label: string }[] = [];
    for (const frac of [-1, -0.5, 0, 0.5, 1]) {
      const v = m * frac;
      const y = zeroY - (v / m) * (innerPnlH / 2);
      ticks.push({ y, label: this.fmtShort(v) });
    }
    return ticks;
  }

  pnlZeroY(): number | null {
    const pts = this.points();
    if (pts.length === 0) {
      return null;
    }
    return this.pnlTop + (this.pnlBottom - this.pnlTop) / 2;
  }

  dateTicks(): { x: number; label: string }[] {
    const pts = this.raw();
    const n = pts.length;
    if (n === 0) {
      return [];
    }
    const count = Math.min(7, n);
    const step = Math.max(1, Math.floor((n - 1) / (count - 1)));
    const out: { x: number; label: string }[] = [];
    for (let i = 0; i < n; i += step) {
      const cp = this.points()[i];
      out.push({ x: cp.x, label: this.shortDate(pts[i].date) });
    }
    if (out.length === 0 || out[out.length - 1].x !== this.points()[n - 1].x) {
      const last = this.points()[n - 1];
      out.push({ x: last.x, label: this.shortDate(pts[n - 1].date) });
    }
    return out;
  }

  trendColor(): string {
    const pts = this.points().filter((p) => p.y != null);
    if (pts.length < 2) {
      return '#0d6efd';
    }
    return pts[pts.length - 1].equity! >= pts[0].equity! ? '#198754' : '#dc3545';
  }

  hoverPoint(): HoverPoint | null {
    const idx = this.hover();
    if (idx == null) {
      return null;
    }
    const pts = this.points();
    const p = pts[idx];
    if (!p) {
      return null;
    }
    const date = this.raw()[idx]?.date ?? '';
    const tw = 132;
    const th = 74;
    let tx = p.x + 14;
    if (tx + tw > this.chartW - this.padR) {
      tx = p.x - tw - 14;
    }
    let ty = p.y == null ? this.padT : Math.min(p.y, this.padT + 40);
    if (ty + th > this.pnlBottom) {
      ty = this.pnlBottom - th - 6;
    }
    return { ...p, tx, ty, tw, th, date: this.shortDate(date) };
  }

  onHover(ev: MouseEvent): void {
    const target = ev.currentTarget as SVGRectElement;
    const rect = target.getBoundingClientRect();
    if (rect.width === 0) {
      return;
    }
    const scale = this.chartW / rect.width;
    const x = (ev.clientX - rect.left) * scale;
    const pts = this.points();
    if (pts.length === 0) {
      this.hover.set(null);
      return;
    }
    let best = 0;
    let bestDist = Infinity;
    pts.forEach((p, i) => {
      const d = Math.abs(p.x - x);
      if (d < bestDist) {
        bestDist = d;
        best = i;
      }
    });
    this.hover.set(best);
  }

  stats(): { lastEquity: number | null; sumPnl: number; cumPct: number | null } {
    const pts = this.points();
    let lastEquity: number | null = null;
    let sumPnl = 0;
    for (const p of pts) {
      if (p.equity != null) {
        lastEquity = p.equity;
      }
      if (p.pnl != null) {
        sumPnl += p.pnl;
      }
    }
    const first = pts.find((p) => p.equity != null);
    const last = pts.length ? pts[pts.length - 1] : null;
    const cumPct =
      first && last && first.equity && first.equity !== 0 && last.equity != null
        ? ((last.equity - first.equity) / first.equity) * 100
        : null;
    return { lastEquity, sumPnl, cumPct };
  }

  shortDate(d: string): string {
    if (!d) {
      return '';
    }
    const m = d.match(/^(\d{4})-(\d{2})-(\d{2})/);
    return m ? `${m[3]}.${m[2]}` : d;
  }

  fmtShort(v: number): string {
    return Math.abs(v) >= 1000 ? `${(v / 1000).toFixed(1)}k` : v.toFixed(v >= 100 ? 0 : v >= 10 ? 1 : 2);
  }

  fmtNum(v: number | null): string {
    return v == null ? '–' : v.toFixed(2);
  }

  fmtMoney(v: number | null): string {
    if (v == null) {
      return '–';
    }
    const cur = this.sdd.history()?.currency ?? '';
    return `${v.toFixed(2)}${cur ? ' ' + cur : ''}`;
  }

  fmtPct(v: number | null): string {
    return v == null ? '–' : `${v.toFixed(2)}%`;
  }
}
