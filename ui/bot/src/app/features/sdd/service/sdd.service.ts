import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import {
  AccountView,
  HealthInfo,
  HistoryResponse,
  PositionsByBook,
  ScanSnapshot,
  SddScan,
} from '../model/sdd.model';

@Injectable({ providedIn: 'root' })
export class SddService {
  private readonly http = inject(HttpClient);

  readonly lastScan = signal<ScanSnapshot | null>(null);
  readonly signals = signal<SddScan[]>([]);
  readonly health = signal<HealthInfo | null>(null);
  readonly accounts = signal<AccountView[]>([]);
  readonly positions = signal<PositionsByBook>({ demo: [], live: [] });
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly history = signal<HistoryResponse | null>(null);
  readonly historyBook = signal<'demo' | 'live'>('demo');
  readonly historyError = signal<string | null>(null);

  account(id: 'demo' | 'live'): AccountView | undefined {
    return this.accounts().find((a) => a.id === id);
  }

  loadHistory(book: 'demo' | 'live'): void {
    this.historyBook.set(book);
    this.historyError.set(null);
    this.http.get<HistoryResponse>(`/api/history?book=${book}`).subscribe({
      next: (h) => this.history.set(h),
      error: (e) => this.historyError.set(e.message ?? 'history failed'),
    });
  }

  refresh(): void {
    this.error.set(null);
    this.http.get<HealthInfo>('/health').subscribe({
      next: (h) => this.health.set(h),
      error: () => this.health.set(null),
    });
    this.http.get<AccountView[]>('/api/accounts').subscribe({
      next: (a) => this.accounts.set(a),
      error: () => this.accounts.set([]),
    });
    this.http.get<ScanSnapshot>('/api/scan/last').subscribe({
      next: (s) => this.lastScan.set(s),
      error: (e) => this.error.set(e.message ?? 'scan/last failed'),
    });
    this.http.get<SddScan[]>('/api/signals').subscribe({
      next: (s) => this.signals.set(s),
      error: () => this.signals.set([]),
    });
    this.http.get<PositionsByBook>('/api/positions').subscribe({
      next: (p) => this.positions.set({ demo: p.demo ?? [], live: p.live ?? [] }),
      error: () => this.positions.set({ demo: [], live: [] }),
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
