import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
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
});
