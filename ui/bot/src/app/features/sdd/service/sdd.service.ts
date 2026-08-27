import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BrokerInfo, HealthInfo, Position, ScanSnapshot, SddScan } from '../model/sdd.model';

@Injectable({ providedIn: 'root' })
export class SddService {
  private readonly http = inject(HttpClient);

  readonly lastScan = signal<ScanSnapshot | null>(null);
  readonly signals = signal<SddScan[]>([]);
  readonly broker = signal<BrokerInfo | null>(null);
  readonly health = signal<HealthInfo | null>(null);
  readonly positions = signal<Position[]>([]);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  refresh(): void {
    this.error.set(null);
    this.http.get<HealthInfo>('/health').subscribe({
      next: (h) => this.health.set(h),
      error: () => this.health.set(null),
    });
    this.http.get<BrokerInfo>('/api/broker').subscribe({
      next: (b) => this.broker.set(b),
      error: () => this.broker.set(null),
    });
    this.http.get<ScanSnapshot>('/api/scan/last').subscribe({
      next: (s) => this.lastScan.set(s),
      error: (e) => this.error.set(e.message ?? 'scan/last failed'),
    });
    this.http.get<SddScan[]>('/api/signals').subscribe({
      next: (s) => this.signals.set(s),
      error: () => this.signals.set([]),
    });
    this.http.get<Position[]>('/api/positions').subscribe({
      next: (p) => this.positions.set(p),
      error: () => this.positions.set([]),
    });
  }

  triggerScan(): void {
    this.busy.set(true);
    this.error.set(null);
    this.http.post<ScanSnapshot>('/api/scan', {}).subscribe({
      next: (s) => {
        this.lastScan.set(s);
        this.busy.set(false);
        this.refresh();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(e.message ?? 'scan failed');
      },
    });
  }
}
