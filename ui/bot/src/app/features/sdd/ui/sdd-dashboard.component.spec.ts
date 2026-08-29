import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { SddDashboardComponent } from './sdd-dashboard.component';
import { AuthService } from '../../auth/auth.service';

describe('SddDashboardComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SddDashboardComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    // Book columns are filtered by AuthService.canSeeBook(); an ADMIN sees all.
    TestBed.inject(AuthService).user.set({
      id: 1,
      username: 'adam',
      displayName: 'Adam',
      role: 'ADMIN',
      books: [],
    });
  });

  it('renders the accounts / positions / scan tables', async () => {
    const fixture = TestBed.createComponent(SddDashboardComponent);
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const headers = Array.from(el.querySelectorAll('.card-header')).map((h) => h.textContent || '');
    expect(headers[0]).toContain('Accounts');
    expect(headers[1]).toContain('Open positions');
    expect(headers.some((h) => h.includes('SDD stack'))).toBe(true);
    expect(headers.some((h) => h.includes('SDD-SWING scan'))).toBe(true);
    expect(el.querySelector('table.table-striped')).toBeTruthy();
    // Every visible book shows as a row badge in the accounts table.
    expect(el.textContent).toContain('Demo');
    expect(el.textContent).toContain('Live');
    expect(el.textContent).toContain('brak pozycji');
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
    http.expectOne('/api/swing/last').flush('nope', { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.alert.alert-danger')).toBeTruthy();
    expect(el.textContent).toContain('HTTP 500');
    expect(el.textContent).toContain('/api/accounts');
    expect(el.textContent).toContain('HTTP 503');
    expect(el.textContent).toContain('/api/scan/last');
    expect(el.textContent).toContain('HTTP 502');
    expect(el.textContent).not.toContain('brak pozycji');
    expect(el.textContent).not.toContain('No scan yet');
    http.verify();
  });
});
