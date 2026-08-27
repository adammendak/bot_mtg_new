import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(AuthService);
    service.logout();
  });

  it('starts logged out', () => {
    expect(service.isAuthenticated()).toBe(false);
  });

  it('logs in with the test user', () => {
    expect(service.login('Adam', 'dupa1234')).toBe(true);
    expect(service.isAuthenticated()).toBe(true);
    expect(service.user()).toBe('Adam');
  });

  it('rejects wrong credentials', () => {
    expect(service.login('Adam', 'wrong')).toBe(false);
    expect(service.login('bob', 'dupa1234')).toBe(false);
    expect(service.isAuthenticated()).toBe(false);
  });

  it('logs out', () => {
    service.login('Adam', 'dupa1234');
    service.logout();
    expect(service.isAuthenticated()).toBe(false);
    expect(service.user()).toBeNull();
  });
});
