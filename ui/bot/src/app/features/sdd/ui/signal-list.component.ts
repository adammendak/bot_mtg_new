import { Component, OnInit, inject } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { SddService } from '../service/sdd.service';

@Component({
  selector: 'app-signal-list',
  standalone: true,
  imports: [DatePipe, DecimalPipe],
  template: `
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <h2 class="h4 mb-0">Signals</h2>
      <button type="button" class="btn btn-outline-secondary btn-sm" (click)="sdd.refresh()">
        Refresh
      </button>
    </div>
    <div class="card shadow-sm">
      <div class="card-header bg-dark text-white">HA flips and full stacks</div>
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
                  <td colspan="6" class="text-muted text-center">
                    No HA flips or full stacks stored yet.
                  </td>
                </tr>
              } @else {
                @for (s of sdd.signals(); track $index) {
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
export class SignalListComponent implements OnInit {
  readonly sdd = inject(SddService);

  ngOnInit(): void {
    this.sdd.refresh();
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
}
