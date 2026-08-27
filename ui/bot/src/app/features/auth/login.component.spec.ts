import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { LoginComponent } from './login.component';
import { AuthService } from './auth.service';

describe('LoginComponent', () => {
  async function setup() {
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideHttpClient(), provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    const service = TestBed.inject(AuthService);
    const router = TestBed.inject(Router);
    return { fixture, service, router };
  }

  it('renders a sign in form', async () => {
    const { fixture } = await setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('form')).not.toBeNull();
    expect(el.textContent).toContain('Sign in');
  });

  it('rejects wrong credentials', async () => {
    const { fixture, service } = await setup();
    const comp = fixture.componentInstance;
    comp.username = 'Adam';
    comp.password = 'nope';
    comp.onSubmit();
    expect(service.isAuthenticated()).toBe(false);
    expect(comp.error).toBe(true);
  });

  it('logs in with correct credentials and navigates to /', async () => {
    const { fixture, service, router } = await setup();
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const comp = fixture.componentInstance;
    comp.username = 'Adam';
    comp.password = 'dupa1234';
    comp.onSubmit();
    expect(service.isAuthenticated()).toBe(true);
    expect(navigate).toHaveBeenCalledWith(['/']);
  });
});
