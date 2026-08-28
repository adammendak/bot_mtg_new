import { Component, OnInit, inject } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { SddService } from '../service/sdd.service';

/**
 * Monitoring: execution audit timeline (#6), manual position actions (#7),
 * stop-drift alerts (#8) and time-in-position / sleeping positions (#9).
 * Manual actions are demo-only by design.
 */
@Component({
  selector: 'app-monitor',
  standalone: true,
  imports: [DatePipe, DecimalPipe],
  template: `
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <h2 class="h4 mb-0">Monitoring</h2>
      <div class="btn-group btn-group-sm" role="group">
        <button type="button" class="btn" [class.btn-primary]="book() === 'demo'" [class.btn-outline-secondary]="book() !== 'demo'" (click)="setBook('demo')">Demo</button>
        <button type="button" class="btn" [class.btn-primary]="book() === 'live'" [class.btn-outline-secondary]="book() !== 'live'" (click)="setBook('live')">Live</button>
        <button type="button" class="btn" [class.btn-primary]="book() === 'glowne'" [class.btn-outline-secondary]="book() !== 'glowne'" (click)="setBook('glowne')">Główne</button>
      </div>
      <button type="button" class="btn btn-outline-secondary btn-sm" (click)="reload()">Refresh</button>
    </div>

    @if (sdd.actionMessage(); as m) {
      <div class="alert alert-info py-2">{{ m }}</div>
    }
    @if (sdd.monitorError(); as e) {
      <div class="alert alert-danger py-2">{{ e }}</div>
    }

    <div class="row g-3">
      <div class="col-lg-7">
        <div class="card shadow-sm">
          <div class="card-header bg-dark text-white">
            Positions — czas / stop / śpiące (#8 #9)
          </div>
          <div class="card-body p-0">
            <div class="table-responsive">
              <table class="table table-sm table-striped table-hover mb-0">
                <thead class="table-dark">
                  <tr>
                    <th>Epic</th><th>Dir</th><th>Level</th><th>Stop</th><th>1R</th>
                    <th>Otwarta</th><th>Alerty</th>
                    @if (canAct()) {
                      <th>Akcje (demo)</th>
                    }
                  </tr>
                </thead>
                <tbody>
                  @if (sdd.monitor().length === 0) {
                    <tr><td [attr.colspan]="canAct() ? 8 : 7" class="text-muted text-center">brak pozycji</td></tr>
                  } @else {
                    @for (p of sdd.monitor(); track p.dealId) {
                      <tr [class.table-warning]="p.stopDrifted || p.sleeping">
                        <td>{{ p.epic }}</td>
                        <td><span class="badge" [class]="dirClass(p.direction)">{{ p.direction }}</span></td>
                        <td>{{ p.level | number: '1.2-5' }}</td>
                        <td [class]="p.stopDrifted ? 'text-danger fw-semibold' : ''">
                          @if (p.stopLevel == null) {
                            <span class="badge text-bg-danger">brak SL</span>
                          } @else {
                            {{ p.stopLevel | number: '1.2-5' }}
                          }
                        </td>
                        <td [class]="p.riskPln != null && p.riskPln > 0 ? 'text-danger fw-semibold' : ''">
                          {{ p.riskPln != null ? (p.riskPln | number: '1.2-2') : '—' }}
                        </td>
                        <td>
                          {{ dur(p.openMinutes) }}
                          @if (p.sleeping) {
                            <span class="badge text-bg-warning ms-1" title="Sleeping">💤</span>
                          }
                        </td>
                        <td>
                          @if (p.stopDrifted) {
                            <span class="badge text-bg-danger" title="Stop zniknął albo przesunięty poza entry">dryf stopa</span>
                          }
                          @if (p.sleeping) {
                            <span class="badge text-bg-warning ms-1">śpiąca</span>
                          }
                        </td>
                        @if (canAct()) {
                          <td class="text-nowrap">
                            <button type="button" class="btn btn-outline-danger btn-sm me-1" (click)="close(p.dealId)" title="Zamknij pozycję">✕</button>
                            <button type="button" class="btn btn-outline-primary btn-sm me-1" (click)="toBe(p.dealId, p.level)" title="Przesuń stop do break-even">BE</button>
                            <button type="button" class="btn btn-outline-info btn-sm" (click)="tighten(p.dealId, p.level, p.direction)" title="Zaciśnij stop o 1R">Zaciśnij</button>
                          </td>
                        }
                      </tr>
                    }
                  }
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

      <div class="col-lg-5">
        <div class="card shadow-sm">
          <div class="card-header bg-dark text-white">Audyt wykonania (#6)</div>
          <div class="card-body p-0 table-scroll">
            <table class="table table-sm table-striped table-hover mb-0">
              <thead class="table-dark">
                <tr><th>Czas</th><th>Akcja</th><th>Szczegóły</th></tr>
              </thead>
              <tbody>
                @if (sdd.audit().length === 0) {
                  <tr><td colspan="3" class="text-muted text-center">Brak zdarzeń.</td></tr>
                } @else {
                  @for (e of sdd.audit(); track $index) {
                    <tr>
                      <td>{{ e.at | date: 'HH:mm:ss' }}</td>
                      <td><span class="badge" [class]="actionClass(e.action)">{{ e.action }}</span></td>
                      <td class="small">{{ e.symbol }} {{ e.detail }}</td>
                    </tr>
                  }
                }
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .table-scroll {
        max-height: 420px;
        overflow-y: auto;
      }
    `,
  ],
})
export class MonitorComponent implements OnInit {
  readonly sdd = inject(SddService);
  private current = 'demo';

  book(): string {
    return this.current;
  }

  ngOnInit(): void {
    this.reload();
  }

  setBook(book: 'demo' | 'live' | 'glowne'): void {
    this.current = book;
    this.reload();
  }

  reload(): void {
    const book = this.current as 'demo' | 'live' | 'glowne';
    this.sdd.loadMonitor(book);
    this.sdd.loadAudit(book);
  }

  canAct(): boolean {
    return this.current === 'demo';
  }

  close(dealId: string): void {
    this.sdd.positionAction('demo', dealId, 'close');
  }

  toBe(dealId: string, entry: number): void {
    this.sdd.positionAction('demo', dealId, 'be', entry);
  }

  tighten(dealId: string, level: number, dir: string): void {
    // Tighten by 1R in the trade's favour (approx half a stop distance).
    const delta = dir === 'BUY' ? 1 : -1;
    const oneR = Math.abs(level) * 0.001 || 1;
    this.sdd.positionAction('demo', dealId, 'stop', level + delta * oneR);
  }

  dur(minutes: number): string {
    if (minutes < 60) {
      return `${minutes}m`;
    }
    const h = Math.floor(minutes / 60);
    const m = minutes % 60;
    return `${h}h ${m}m`;
  }

  dirClass(dir: string): string {
    return dir === 'BUY' ? 'text-bg-success' : 'text-bg-danger';
  }

  actionClass(action: string): string {
    switch (action) {
      case 'placed':
        return 'text-bg-success';
      case 'tp_closed':
        return 'text-bg-primary';
      case 'trail':
        return 'text-bg-info';
      case 'be':
      case 'stop':
        return 'text-bg-warning';
      case 'closed':
        return 'text-bg-secondary';
      default:
        return 'text-bg-danger'; // skip
    }
  }
}
