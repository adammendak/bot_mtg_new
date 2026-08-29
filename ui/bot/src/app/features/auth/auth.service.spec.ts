import { TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { of, firstValueFrom, throwError } from 'rxjs';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let http: { post: ReturnType<typeof vi.fn>; get: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    http = { post: vi.fn(), get: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: HttpClient, useValue: http },
        { provide: Router, useValue: { navigate: vi.fn(), url: '/' } },
      ],
    });
    service = TestBed.inject(AuthService);
    service.logout();
  });

  it('starts logged out', () => {
    expect(service.isAuthenticated()).toBe(false);
  });

  it('logs in with the admin user', async () => {
    http.post.mockReturnValue(
      of({
        token: 't1',
        user: { id: 1, username: 'adam', displayName: 'Adam Adam', role: 'ADMIN', books: ['live', 'glowne'] },
      }),
    );
    const ok = await firstValueFrom(service.login('adam', 'dupa1234'));
    expect(ok).toBe(true);
    expect(service.isAuthenticated()).toBe(true);
    expect(service.user()?.username).toBe('adam');
    expect(service.isAdmin()).toBe(true);
  });

  it('logs in with the test user', async () => {
    http.post.mockReturnValue(
      of({
        token: 't2',
        user: { id: 2, username: 'test', displayName: 'test', role: 'USER', books: ['demo'] },
      }),
    );
    const ok = await firstValueFrom(service.login('test', 'dupa1234'));
    expect(ok).toBe(true);
    expect(service.isAuthenticated()).toBe(true);
    expect(service.user()?.username).toBe('test');
    expect(service.isAdmin()).toBe(false);
    expect(service.books()).toEqual(['demo']);
  });

  it('rejects wrong credentials', async () => {
    http.post.mockReturnValue(throwError(() => new Error('401')));
    const ok = await firstValueFrom(service.login('adam', 'wrong'));
    expect(ok).toBe(false);
    expect(service.isAuthenticated()).toBe(false);
  });

  it('logs out', async () => {
    http.post.mockReturnValue(
      of({ token: 't', user: { id: 1, username: 'adam', displayName: null, role: 'ADMIN', books: [] } }),
    );
    await firstValueFrom(service.login('adam', 'x'));
    service.logout();
    expect(service.isAuthenticated()).toBe(false);
    expect(service.user()).toBeNull();
  });
});
