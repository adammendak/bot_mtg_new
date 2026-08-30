import { Component, OnInit, inject } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { SddService } from '../service/sdd.service';
import { AuthService } from '../../auth/auth.service';
import { BookId, BOOK_TABS } from '../model/sdd.model';

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
        @for (b of visibleBooks; track b.id) {
          <button type="button" class="btn" [class.btn-primary]="book() === b.id" [class.btn-outline-secondary]="book() !== b.id" (click)="setBook(b.id)">{{ b.label }}</button>
        }
      </div>
      <button type="button" class="btn btn-outline-secondary btn-sm" (click)="reload()">Refresh</button>
    </div>

    @if (sdd.actionMessage(); as m) {
      <div class="alert alert-info py-2">{{ m }}</div>
    }
    @if (sdd.monitorError(); as e) {
      <div class="alert alert-danger py-2">{{ e }}</div>
    }

    <div class="row g-3 mb-1">
      <div class="col-lg-5">
        <div class="card shadow-sm">
          <div class="card-header text-white d-flex justify-content-between align-items-center"
               [class.bg-dark]="(sdd.opsHealth()?.staleCount ?? 0) === 0"
               [class.bg-danger]="(sdd.opsHealth()?.staleCount ?? 0) > 0">
            <span>Zdrowie schedulerów (E-5)</span>
            <button type="button" class="btn btn-outline-light btn-sm" (click)="sdd.loadOps()">Odśwież</button>
          </div>
          <div class="card-body p-0">
            <table class="table table-sm table-striped mb-0">
              <thead class="table-dark">
                <tr><th>Probe</th><th>Ostatni OK</th><th class="text-end">Wiek</th><th>Stan</th></tr>
              </thead>
              <tbody>
                @if (!sdd.opsHealth() || sdd.opsHealth()!.schedulers.length === 0) {
                  <tr><td colspan="4" class="text-muted text-center">Brak zarejestrowanych probe'ów.</td></tr>
                } @else {
                  @for (p of sdd.opsHealth()!.schedulers; track p.name) {
                    <tr [class.table-danger]="p.stale">
                      <td>{{ p.name }}</td>
                      <td class="small">{{ tstamp(p.lastOkAt) }}</td>
                      <td class="text-end small">{{ p.ageSeconds < 0 ? '—' : age(p.ageSeconds) }}</td>
                      <td>
                        <span class="badge" [class.text-bg-success]="!p.stale" [class.text-bg-danger]="p.stale">
                          {{ p.stale ? 'STALE' : 'ok' }}
                        </span>
                      </td>
                    </tr>
                  }
                }
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <div class="col-lg-7">
        <div class="card shadow-sm">
          <div class="card-header bg-dark text-white">Ostatnie błędy (trwały log)</div>
          <div class="card-body p-0 table-scroll">
            @if (sdd.opsError(); as e) {
              <div class="alert alert-danger py-2 m-2">{{ e }}</div>
            }
            <table class="table table-sm table-striped mb-0">
              <thead class="table-dark">
                <tr><th>Czas</th><th>Źródło</th><th>Zakres</th><th>Co</th></tr>
              </thead>
              <tbody>
                @if (sdd.opsErrors().length === 0) {
                  <tr><td colspan="4" class="text-muted text-center">Brak zapisanych błędów.</td></tr>
                } @else {
                  @for (e of sdd.opsErrors(); track e.id) {
                    <tr>
                      <td class="small text-nowrap">{{ tstamp(e.at) }}</td>
                      <td><span class="badge text-bg-secondary">{{ e.source }}</span></td>
                      <td class="small">{{ e.scope || '' }}{{ e.detail ? ' · ' + e.detail : '' }}</td>
                      <td class="small">
                        <span class="text-danger">{{ shortEx(e.exception) }}</span>
                        {{ e.message ? ' — ' + e.message : '' }}
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
  private readonly auth = inject(AuthService);
  readonly visibleBooks = BOOK_TABS.filter((b) => this.auth.canSeeBook(b.id));
  private current: BookId = this.visibleBooks[0]?.id ?? 'demo';

  book(): string {
    return this.current;
  }

  ngOnInit(): void {
    this.reload();
  }

  setBook(book: BookId): void {
    if (!this.auth.canSeeBook(book)) {
      return;
    }
    this.current = book;
    this.reload();
  }

  reload(): void {
    const book = this.current as BookId;
    this.sdd.loadMonitor(book);
    this.sdd.loadAudit(book);
    this.sdd.loadOps();
  }

  tstamp(iso: string | null | undefined): string {
    if (!iso) {
      return '—';
    }
    const d = new Date(iso);
    return Number.isNaN(d.getTime())
      ? String(iso)
      : d.toLocaleString('pl-PL', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  age(seconds: number): string {
    if (seconds < 60) {
      return `${seconds}s`;
    }
    const m = Math.floor(seconds / 60);
    if (m < 60) {
      return `${m}m`;
    }
    return `${Math.floor(m / 60)}h ${m % 60}m`;
  }

  shortEx(fqcn: string | null): string {
    if (!fqcn) {
      return '';
    }
    const i = fqcn.lastIndexOf('.');
    return i >= 0 ? fqcn.slice(i + 1) : fqcn;
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
