import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  AccountView,
  HealthInfo,
  PositionsByBook,
  ScanSnapshot,
  SddScan,
} from '../model/sdd.model';

export function formatHttpError(path: string, err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const status = err.status ? `HTTP ${err.status}` : 'network error';
    const detail = err.statusText && err.statusText !== 'Unknown Error' ? err.statusText : err.message;
    return `${path} ${status}: ${detail}`;
  }
  if (err && typeof err === 'object' && 'message' in err) {
    return `${path}: ${String((err as { message: unknown }).message)}`;
  }
  return `${path} request failed`;
}

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
  readonly accountsError = signal<string | null>(null);
  readonly scanLoadError = signal<string | null>(null);
  readonly signalsError = signal<string | null>(null);
  readonly positionsError = signal<string | null>(null);

  account(id: 'demo' | 'live'): AccountView | undefined {
    return this.accounts().find((a) => a.id === id);
  }

  refresh(): void {
    this.error.set(null);
    this.http.get<HealthInfo>('/health').subscribe({
      next: (h) => this.health.set(h),
      error: (e) => {
        this.health.set(null);
        this.error.set(formatHttpError('/health', e));
      },
    });
    this.accountsError.set(null);
    this.http.get<AccountView[] | AccountView>('/api/accounts').subscribe({
      next: (a) => this.accounts.set(this.normalizeAccounts(a)),
      error: () => {
        this.http.get<AccountView[] | AccountView>('/api/account').subscribe({
          next: (a) => this.accounts.set(this.normalizeAccounts(a)),
          error: (e) => this.accountsError.set(formatHttpError('/api/accounts', e)),
        });
      },
    });
    this.scanLoadError.set(null);
    this.http.get<ScanSnapshot>('/api/scan/last').subscribe({
      next: (s) => this.lastScan.set(s),
      error: (e) => this.scanLoadError.set(formatHttpError('/api/scan/last', e)),
    });
    this.signalsError.set(null);
    this.http.get<SddScan[]>('/api/signals').subscribe({
      next: (s) => this.signals.set(s),
      error: (e) => this.signalsError.set(formatHttpError('/api/signals', e)),
    });
    this.positionsError.set(null);
    this.http.get<PositionsByBook>('/api/positions').subscribe({
      next: (p) => this.positions.set({ demo: p.demo ?? [], live: p.live ?? [] }),
      error: (e) => this.positionsError.set(formatHttpError('/api/positions', e)),
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
        this.error.set(formatHttpError('/api/scan', e));
      },
    });
  }

  private normalizeAccounts(data: AccountView[] | AccountView | null): AccountView[] {
    if (data == null) {
      return [];
    }
    return Array.isArray(data) ? data : [data];
  }
}
