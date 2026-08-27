import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { provideRouter } from '@angular/router';
import { LoginComponent } from './login.component';
import { AuthService } from './auth.service';

describe('LoginComponent', () => {
  async function setup() {
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    const service = TestBed.inject(AuthService);
    const router = TestBed.inject(Router);
    const http = TestBed.inject(HttpTestingController);
    return { fixture, service, router, http };
  }

  it('renders a sign in form', async () => {
    const { fixture, http } = await setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('form')).not.toBeNull();
    expect(el.textContent).toContain('Sign in');
    expect(el.textContent).not.toContain('Test user');
    http.verify();
  });

  it('rejects when the server returns 401', async () => {
    const { fixture, service, http } = await setup();
    const comp = fixture.componentInstance;
    comp.username = 'any-user';
    comp.password = 'wrong';
    const pending = comp.onSubmit();
    http.expectOne('/api/login').flush(null, { status: 401, statusText: 'Unauthorized' });
    await pending;
    expect(service.isAuthenticated()).toBe(false);
    expect(comp.error).toBe(true);
    http.verify();
  });

  it('logs in when the server accepts credentials and navigates to /', async () => {
    const { fixture, service, router, http } = await setup();
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const comp = fixture.componentInstance;
    comp.username = 'any-user';
    comp.password = 'any-pass';
    const pending = comp.onSubmit();
    const req = http.expectOne('/api/login');
    expect(req.request.body).toEqual({ username: 'any-user', password: 'any-pass' });
    req.flush({ username: 'any-user' });
    await pending;
    expect(service.isAuthenticated()).toBe(true);
    expect(navigate).toHaveBeenCalledWith(['/']);
    http.verify();
  });
});
