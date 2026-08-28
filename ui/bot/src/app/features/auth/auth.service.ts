import { Injectable, signal } from '@angular/core';

const USERS: Record<string, string> = {
  Adam: 'dupa1234',
  test: 'dupa1234',
};
const STORAGE_KEY = 'auth.user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  readonly user = signal<string | null>(this.restore());

  isAuthenticated(): boolean {
    return this.user() != null;
  }

  /** Adam (full access incl. the Główne account) vs test (limited view). */
  isAdmin(): boolean {
    return this.user() === 'Adam';
  }

  login(username: string, password: string): boolean {
    if (!username || USERS[username] !== password) {
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
