import { Component, OnInit, inject } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { SddService } from '../service/sdd.service';

@Component({
  selector: 'app-signal-list',
  standalone: true,
  imports: [DatePipe, DecimalPipe],
  template: `
    <div>
      <h2>Signal list</h2>
      <button type="button" (click)="sdd.refresh()">Refresh</button>
      @if (sdd.signals().length === 0) {
        <p>No HA flips or full stacks stored yet.</p>
      }
      <ul>
        @for (s of sdd.signals(); track $index) {
          <li>
            {{ s.timestamp | date: 'short' }}
            {{ s.symbol }} {{ s.direction }}
            {{ s.fullStack ? 'FULL' : 'flip' }}
            entry {{ s.entry | number: '1.2-5' }}
            — {{ s.reason }}
          </li>
        }
      </ul>
    </div>
  `,
})
export class SignalListComponent implements OnInit {
  readonly sdd = inject(SddService);

  ngOnInit(): void {
    this.sdd.refresh();
  }
}
