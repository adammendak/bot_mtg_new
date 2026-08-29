import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SddDashboardComponent } from './sdd-dashboard.component';

describe('SddDashboardComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SddDashboardComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('renders Demo and Live Bootstrap tables', async () => {
    const fixture = TestBed.createComponent(SddDashboardComponent);
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.card-header')[0]?.textContent).toContain('Demo');
    expect(el.querySelectorAll('.card-header')[1]?.textContent).toContain('Live');
    expect(el.querySelector('table.table-striped')).toBeTruthy();
    expect(el.textContent).toContain('brak pozycji');
    expect(el.textContent).toContain('SDD stack');
    expect(el.textContent).toContain('EXECUTION');
  });

  it('shows account and scan API errors instead of empty tables', async () => {
    const fixture = TestBed.createComponent(SddDashboardComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    http.expectOne('/health').flush({
      status: 'UP',
      time: '2026-08-27T10:00:00Z',
      broker: 'paper',
      executionEnabled: false,
      demoConfigured: true,
      liveConfigured: false,
      webhookConfigured: false,
      lastWebhook: 'never',
    });
    http.expectOne('/api/accounts').flush('nope', { status: 500, statusText: 'Server Error' });
    http.expectOne('/api/account').flush('nope', { status: 500, statusText: 'Server Error' });
    http.expectOne('/api/scan/last').flush('nope', { status: 503, statusText: 'Service Unavailable' });
    http.expectOne('/api/signals').flush('nope', { status: 500, statusText: 'Server Error' });
    http.expectOne('/api/positions/risk').flush('nope', { status: 502, statusText: 'Bad Gateway' });
    await fixture.whenStable();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.alert.alert-danger')).toBeTruthy();
    expect(el.textContent).toContain('HTTP 500');
    expect(el.textContent).toContain('/api/accounts');
    expect(el.textContent).toContain('HTTP 503');
    expect(el.textContent).toContain('/api/scan/last');
    expect(el.textContent).toContain('/api/signals');
    expect(el.textContent).toContain('HTTP 502');
    expect(el.textContent).not.toContain('brak pozycji');
    expect(el.textContent).not.toContain('No scan yet');
    expect(el.textContent).not.toContain('No HA flips or full stacks yet');
    http.verify();
  });
});
