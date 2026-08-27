import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

const STORAGE_KEY = 'auth.user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  readonly user = signal<string | null>(this.restore());

  isAuthenticated(): boolean {
    return this.user() != null;
  }

  async login(username: string, password: string): Promise<boolean> {
    try {
      const res = await firstValueFrom(
        this.http.post<{ username: string }>('/api/login', { username, password }),
      );
      const name = res.username || username;
      this.user.set(name);
      try {
        sessionStorage.setItem(STORAGE_KEY, name);
      } catch {
        // storage unavailable; keep in-memory state
      }
      return true;
    } catch {
      return false;
    }
  }

  logout(): void {
    this.user.set(null);
    try {
      sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      // ignore
    }
    this.http.post('/api/logout', {}).subscribe({ error: () => undefined });
  }

  private restore(): string | null {
    try {
      return sessionStorage.getItem(STORAGE_KEY);
    } catch {
      return null;
    }
  }
}
