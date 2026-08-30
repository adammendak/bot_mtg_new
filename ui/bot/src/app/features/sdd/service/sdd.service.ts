import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { AuthService } from '../../auth/auth.service';
import {
  AccountView,
  AuditEvent,
  BacktestResult,
  BookId,
  HealthInfo,
  HistoryResponse,
  OverviewView,
  PositionMonitorView,
  PositionsByBook,
  ScanSnapshot,
  SddScan,
  SwingLastResponse,
  HtsSignal,
  SymbolStats,
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
  private readonly auth = inject(AuthService);

  readonly lastScan = signal<ScanSnapshot | null>(null);
  readonly signals = signal<SddScan[]>([]);
  readonly swingLast = signal<SwingLastResponse | null>(null);
  readonly swingError = signal<string | null>(null);
  readonly htsSignals = signal<HtsSignal[]>([]);
  readonly htsError = signal<string | null>(null);
  readonly health = signal<HealthInfo | null>(null);
  readonly accounts = signal<AccountView[]>([]);
  readonly positions = signal<PositionsByBook>({ demo: [], live: [], glowne: [], swing: [], hts: [] });
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly history = signal<HistoryResponse | null>(null);
  readonly historyBook = signal<BookId>('demo');
  readonly historyError = signal<string | null>(null);
  readonly syncBusy = signal(false);
  readonly syncMessage = signal<string | null>(null);
  readonly accountsError = signal<string | null>(null);
  readonly scanLoadError = signal<string | null>(null);
  readonly signalsError = signal<string | null>(null);
  readonly positionsError = signal<string | null>(null);
  readonly overview = signal<OverviewView[]>([]);
  readonly overviewError = signal<string | null>(null);
  readonly symbolStats = signal<SymbolStats[]>([]);
  readonly symbolStatsError = signal<string | null>(null);
  readonly backtest = signal<BacktestResult[]>([]);
  readonly backtestError = signal<string | null>(null);
  readonly backtestBusy = signal(false);
  readonly audit = signal<AuditEvent[]>([]);
  readonly monitor = signal<PositionMonitorView[]>([]);
  readonly monitorError = signal<string | null>(null);
  readonly actionMessage = signal<string | null>(null);

  account(id: BookId): AccountView | undefined {
    return this.accounts().find((a) => a.id === id);
  }

  loadHistory(book: BookId): void {
    this.historyBook.set(book);
    this.historyError.set(null);
    this.http.get<HistoryResponse>(`/api/history?book=${book}`).subscribe({
      next: (h) => this.history.set(h),
      error: (e) => this.historyError.set(formatHttpError('/api/history', e)),
    });
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
    this.http.get<PositionsByBook>('/api/positions/risk').subscribe({
      next: (p) =>
        this.positions.set({
          demo: p.demo ?? [],
          live: p.live ?? [],
          glowne: p.glowne ?? [],
          swing: p.swing ?? [],
          hts: p.hts ?? [],
        }),
      error: (e) => this.positionsError.set(formatHttpError('/api/positions/risk', e)),
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

  /** Rebuild daily equity history from the broker's transaction feed. */
  syncHistory(book: BookId, replace = false): void {
    this.syncBusy.set(true);
    this.syncMessage.set(null);
    this.http
      .post<{ status: string; message: string; written: number; skipped: number }>(
        `/api/history/sync?book=${book}&replace=${replace}`,
        {},
      )
      .subscribe({
        next: (r) => {
          this.syncBusy.set(false);
          this.syncMessage.set(`${r.message} (written=${r.written}, skipped=${r.skipped})`);
          this.refresh();
        },
        error: (e) => {
          this.syncBusy.set(false);
          this.syncMessage.set(`Sync failed: ${formatHttpError('/api/history/sync', e)}`);
        },
      });
  }

  /** Rebuild daily equity history for all books (demo, live, glowne) at once. */
  syncAll(replace = false): void {
    this.syncBusy.set(true);
    this.syncMessage.set(null);
    this.http
      .post<{ status: string; message: string; written: number; skipped: number }[]>(
        `/api/history/sync-all?replace=${replace}`,
        {},
      )
      .subscribe({
        next: (r) => {
          this.syncBusy.set(false);
          this.syncMessage.set(
            r.map((x) => `${x.status}: ${x.message}`).join(' · ') || 'no sync results',
          );
          this.refresh();
        },
        error: (e) => {
          this.syncBusy.set(false);
          this.syncMessage.set(`Sync all failed: ${formatHttpError('/api/history/sync-all', e)}`);
        },
      });
  }

  /** All-accounts overview: kind (DEMO/LIVE/MAIN), strategy, positions tally. */
  loadOverview(): void {
    this.overviewError.set(null);
    this.http.get<OverviewView[]>('/api/overview').subscribe({
      next: (o) => this.overview.set(Array.isArray(o) ? o : []),
      error: (e) => this.overviewError.set(formatHttpError('/api/overview', e)),
    });
  }

  /** SDD-SWING (H1) latest scan — only for books the user may see. */
  loadSwingLast(): void {
    if (!this.auth.canSeeBook('swing')) {
      return;
    }
    this.swingError.set(null);
    this.http.get<SwingLastResponse>('/api/swing/last').subscribe({
      next: (s) => this.swingLast.set(s),
      error: (e) => this.swingError.set(formatHttpError('/api/swing/last', e)),
    });
  }

  /** Recent persisted HTS signals (newest first), tagged with the timeframe model. */
  loadHtsSignals(limit = 25): void {
    if (!this.auth.canSeeBook('hts')) {
      return;
    }
    this.htsError.set(null);
    this.http.get<HtsSignal[]>(`/api/hts/signals?limit=${limit}`).subscribe({
      next: (s) => this.htsSignals.set(Array.isArray(s) ? s : []),
      error: (e) => this.htsError.set(formatHttpError('/api/hts/signals', e)),
    });
  }

  /** Open positions per book (with per-position risk) — standalone loader for the Konta page. */
  loadPositions(): void {
    this.positionsError.set(null);
    this.http.get<PositionsByBook>('/api/positions/risk').subscribe({
      next: (p) =>
        this.positions.set({
          demo: p.demo ?? [],
          live: p.live ?? [],
          glowne: p.glowne ?? [],
          swing: p.swing ?? [],
          hts: p.hts ?? [],
        }),
      error: (e) => this.positionsError.set(formatHttpError('/api/positions/risk', e)),
    });
  }

  /** Manual HTS scan (all variants) — the scheduler runs every 5 min anyway. */
  triggerHtsScan(): void {
    this.busy.set(true);
    this.htsError.set(null);
    this.http.post<unknown>('/api/hts/scan', {}).subscribe({
      next: () => {
        this.busy.set(false);
        this.loadHtsSignals();
        this.loadPositions();
      },
      error: (e) => {
        this.busy.set(false);
        this.htsError.set(formatHttpError('/api/hts/scan', e));
      },
    });
  }

  /** #14: per-symbol performance (win rate, expectancy, profit factor). */
  loadSymbolStats(book: BookId, days = 0): void {
    this.symbolStatsError.set(null);
    this.http.get<SymbolStats[]>(`/api/symbol-stats?book=${book}&days=${days}`).subscribe({
      next: (s) => this.symbolStats.set(Array.isArray(s) ? s : []),
      error: (e) => this.symbolStatsError.set(formatHttpError('/api/symbol-stats', e)),
    });
  }

  /** #13: run the backtest replay and store results. */
  runBacktest(book: BookId, days = 90): void {
    this.backtestBusy.set(true);
    this.backtestError.set(null);
    this.http.get<BacktestResult[]>(`/api/backtest?book=${book}&days=${days}`).subscribe({
      next: (r) => {
        this.backtest.set(Array.isArray(r) ? r : []);
        this.backtestBusy.set(false);
      },
      error: (e) => {
        this.backtestBusy.set(false);
        this.backtestError.set(formatHttpError('/api/backtest', e));
      },
    });
  }

  /** #6: execution audit timeline. */
  loadAudit(book?: BookId): void {
    const q = book ? `?book=${book}` : '';
    this.http.get<AuditEvent[]>(`/api/monitor/audit${q}`).subscribe({
      next: (a) => this.audit.set(Array.isArray(a) ? a : []),
      error: () => this.audit.set([]),
    });
  }

  /** #8/#9: positions with time-in-position, stop-drift and sleeping flags. */
  loadMonitor(book: BookId): void {
    this.monitorError.set(null);
    this.http.get<PositionMonitorView[]>(`/api/monitor?book=${book}`).subscribe({
      next: (m) => this.monitor.set(Array.isArray(m) ? m : []),
      error: (e) => this.monitorError.set(formatHttpError('/api/monitor', e)),
    });
  }

  /** #7: manual action on a position (demo only). */
  positionAction(book: 'demo', dealId: string, action: 'close' | 'be' | 'stop', value?: number): void {
    const param =
      action === 'close'
        ? ''
        : action === 'be'
          ? `&entry=${value ?? 0}`
          : `&stop=${value ?? 0}`;
    this.actionMessage.set(null);
    this.http.post(`/api/monitor/${action}?book=${book}&dealId=${dealId}${param}`, {}, { responseType: 'text' }).subscribe({
      next: (r) => {
        this.actionMessage.set(String(r));
        this.refresh();
      },
      error: (e) => this.actionMessage.set(`Action failed: ${formatHttpError('/api/monitor/' + action, e)}`),
    });
  }

  /** #11: live overview via SSE — emits each incoming payload as an OverviewView[]. */
  liveOverview(onData: (rows: OverviewView[]) => void): () => void {
    // EventSource cannot set an Authorization header, so the bearer token rides
    // in the query string for this one GET-only stream endpoint.
    const token = this.auth.bearerToken();
    const url = token ? `/api/live?access_token=${encodeURIComponent(token)}` : '/api/live';
    const source = new EventSource(url);
    source.addEventListener('overview', (ev: MessageEvent) => {
      try {
        onData(JSON.parse(ev.data));
      } catch {
        /* ignore malformed frame */
      }
    });
    return () => source.close();
  }

  private normalizeAccounts(data: AccountView[] | AccountView | null): AccountView[] {
    if (data == null) {
      return [];
    }
    return Array.isArray(data) ? data : [data];
  }
}
