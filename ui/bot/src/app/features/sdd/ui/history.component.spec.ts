import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { HistoryComponent } from './history.component';
import { SddService } from '../service/sdd.service';
import { AuthService } from '../../auth/auth.service';
import { HistoryResponse } from '../model/sdd.model';

describe('HistoryComponent', () => {
  // Base history payload (drawdown fields added in #15).
  function h(over: Partial<HistoryResponse>): HistoryResponse {
    return {
      book: 'demo',
      currency: null,
      connected: false,
      points: [],
      maxDrawdownPct: null,
      currentDrawdownPct: null,
      recoveryDays: null,
      ...over,
    };
  }

  async function setup(data: HistoryResponse) {
    await TestBed.configureTestingModule({
      imports: [HistoryComponent],
      providers: [provideHttpClient(), provideRouter([])],
    }).compileComponents();
    // Book buttons are filtered by AuthService.canSeeBook(); an ADMIN sees all.
    TestBed.inject(AuthService).user.set({
      id: 1,
      username: 'adam',
      displayName: 'Adam',
      role: 'ADMIN',
      books: [],
    });
    const fixture = TestBed.createComponent(HistoryComponent);
    const service = TestBed.inject(SddService);
    vi.spyOn(service, 'loadHistory').mockImplementation(() => {});
    service.history.set(data);
    fixture.detectChanges();
    return { fixture, service };
  }

  it('renders the book selector and empty state', async () => {
    const { fixture } = await setup(h({ currency: 'EUR', connected: true }));
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('History');
    expect(text).toContain('Demo');
    expect(text).toContain('Live');
    expect(text).toContain('Główne');
    expect(text).toContain('Sync all');
    expect(text).toContain('No data yet.');
  });

  it('shows the sync-all result message when present', async () => {
    const { fixture, service } = await setup(h({ currency: 'EUR', connected: true }));
    service.syncMessage.set('ok: synced demo · ok: synced live · ok: synced glowne');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('synced glowne');
  });

  it('renders chart svg and table rows when data is present', async () => {
    const { fixture } = await setup(h({
      currency: 'EUR',
      connected: true,
      points: [
        { date: '2026-08-01', equity: 1000, dayPnl: 0, pctChange: 0 },
        { date: '2026-08-02', equity: 990, dayPnl: -10, pctChange: -1 },
      ],
    }));
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('svg.chart')).not.toBeNull();
    expect(el.querySelectorAll('table.daily-equity tbody tr').length).toBe(2);
    expect(el.textContent).toContain('2026-08-01');
    expect(el.textContent).toContain('2026-08-02');
    expect(el.textContent).toContain('-1.00%');
  });

  it('shows summary badges and renders bars for pnl data', async () => {
    const { fixture } = await setup(h({
      currency: 'PLN',
      connected: true,
      points: [
        { date: '2026-08-01', equity: 1000, dayPnl: 0, pctChange: 0 },
        { date: '2026-08-02', equity: 1015, dayPnl: 15, pctChange: 1.5 },
        { date: '2026-08-03', equity: 1005, dayPnl: -10, pctChange: 0.5 },
      ],
    }));
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Latest equity');
    expect(el.textContent).toContain('PLN');
    // 3 day pnl bars (one per day with a pnl value)
    expect(el.querySelectorAll('rect.bar-pos, rect.bar-neg').length).toBe(3);
    // cumulative change from 1000 to 1005 = 0.5%
    expect(el.textContent).toContain('0.50%');
  });

  it('filters the chart and table by the custom date range and clears it', async () => {
    const { fixture } = await setup(h({
      currency: 'PLN',
      connected: true,
      points: [
        { date: '2026-08-01', equity: 1000, dayPnl: 0, pctChange: 0 },
        { date: '2026-08-02', equity: 1015, dayPnl: 15, pctChange: 1.5 },
        { date: '2026-08-03', equity: 1005, dayPnl: -10, pctChange: 0.5 },
      ],
    }));
    const el = fixture.nativeElement as HTMLElement;
    const comp = fixture.componentInstance;
    // no range yet -> all 3 rows
    expect(el.querySelectorAll('table.daily-equity tbody tr').length).toBe(3);
    expect(comp.rangeActive()).toBe(false);

    // narrow to one day
    comp.fromDate.set('2026-08-03');
    comp.toDate.set('2026-08-03');
    fixture.detectChanges();
    expect(el.querySelectorAll('table.daily-equity tbody tr').length).toBe(1);
    expect(el.textContent).toContain('2026-08-03');
    expect(comp.rangeActive()).toBe(true);
    expect(el.querySelector('button[class*="outline-danger"]')).not.toBeNull();

    // clear -> all rows back
    comp.clearRange();
    fixture.detectChanges();
    expect(el.querySelectorAll('table.daily-equity tbody tr').length).toBe(3);
    expect(comp.rangeActive()).toBe(false);
  });
});
