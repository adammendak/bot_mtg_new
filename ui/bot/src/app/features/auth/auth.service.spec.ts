import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
  });

  it('starts logged out', () => {
    expect(service.isAuthenticated()).toBe(false);
  });

  it('logs in when the server accepts credentials', async () => {
    const pending = service.login('any-user', 'any-pass');
    const req = http.expectOne('/api/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ username: 'any-user', password: 'any-pass' });
    req.flush({ username: 'any-user' });
    await expect(pending).resolves.toBe(true);
    expect(service.isAuthenticated()).toBe(true);
    expect(service.user()).toBe('any-user');
  });

  it('rejects when the server returns 401', async () => {
    const pending = service.login('any-user', 'wrong');
    http.expectOne('/api/login').flush(null, { status: 401, statusText: 'Unauthorized' });
    await expect(pending).resolves.toBe(false);
    expect(service.isAuthenticated()).toBe(false);
  });

  it('logs out', async () => {
    const pending = service.login('any-user', 'any-pass');
    http.expectOne('/api/login').flush({ username: 'any-user' });
    await pending;
    service.logout();
    http.expectOne('/api/logout').flush(null, { status: 204, statusText: 'No Content' });
    expect(service.isAuthenticated()).toBe(false);
    expect(service.user()).toBeNull();
  });
});
