import { Injectable, signal } from '@angular/core';

const TEST_USER = 'Adam';
const TEST_PASSWORD = 'dupa1234';
const STORAGE_KEY = 'auth.user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  readonly user = signal<string | null>(this.restore());

  isAuthenticated(): boolean {
    return this.user() != null;
  }

  login(username: string, password: string): boolean {
    if (username !== TEST_USER || password !== TEST_PASSWORD) {
      return false;
    }
    this.user.set(username);
    try {
      sessionStorage.setItem(STORAGE_KEY, username);
    } catch {
      // storage unavailable; keep in-memory state
    }
    return true;
  }

  logout(): void {
    this.user.set(null);
    try {
      sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      // ignore
    }
  }

  private restore(): string | null {
    try {
      return sessionStorage.getItem(STORAGE_KEY);
    } catch {
      return null;
    }
  }
}
