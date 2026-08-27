import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { authGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('authGuard', () => {
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'login', children: [] }]),
      ],
    });
  });

  it('redirects anonymous users to /login', () => {
    const router = TestBed.inject(Router);
    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as never, {} as never),
    );
    expect(result).toEqual(router.createUrlTree(['/login']));
  });

  it('allows a restored session', () => {
    sessionStorage.setItem('auth.user', 'any-user');
    const auth = TestBed.inject(AuthService);
    expect(auth.isAuthenticated()).toBe(true);
    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as never, {} as never),
    );
    expect(result).toBe(true);
  });
});
