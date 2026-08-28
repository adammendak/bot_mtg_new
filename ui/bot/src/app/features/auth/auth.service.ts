import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';

export interface AuthUser {
  id: number;
  username: string;
  displayName: string;
  role: 'ADMIN' | 'USER' | string;
  books: string[];
}

export interface LoginResponse {
  token: string;
  user: AuthUser;
}

const TOKEN_KEY = 'auth.token';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  readonly user = signal<AuthUser | null>(null);
  private token: string | null = null;

  constructor() {
    this.token = this.restoreToken();
    if (this.token) {
      this.me();
    }
  }

  /** Bearer token the HTTP interceptor attaches to every request. */
  bearerToken(): string | null {
    return this.token;
  }

  isAuthenticated(): boolean {
    return this.user() != null;
  }

  /** ADMIN has full access (all books + Główne page); USER sees only granted books. */
  isAdmin(): boolean {
    return this.user()?.role === 'ADMIN';
  }

  /** Books this user may see (admin = all books). */
  visibleBooks(): string[] {
    const u = this.user();
    if (!u) {
      return [];
    }
    if (u.role === 'ADMIN') {
      return ['demo', 'live', 'glowne'];
    }
    return u.books ?? [];
  }

  canSeeBook(book: string): boolean {
    return this.isAdmin() || this.visibleBooks().includes(book);
  }

  /** Logs in via POST /api/auth/login and stores the bearer token. */
  login(username: string, password: string): Promise<boolean> {
    return new Promise((resolve) => {
      this.http.post<LoginResponse>('/api/auth/login', { username, password }).subscribe({
        next: (r) => {
          this.token = r.token;
          this.user.set(r.user);
          try {
            localStorage.setItem(TOKEN_KEY, this.token ?? '');
          } catch {
            // storage unavailable; keep in-memory
          }
          resolve(true);
        },
        error: (e: HttpErrorResponse) => {
          console.warn('login failed', e.status);
          this.user.set(null);
          resolve(false);
        },
      });
    });
  }

  /** Refreshes the current user (books may have changed) from /api/auth/me. */
  me(): void {
    if (!this.token) {
      return;
    }
    this.http.get<AuthUser>('/api/auth/me').subscribe({
      next: (u) => this.user.set(u),
      error: () => {
        this.user.set(null);
        this.token = null;
        try {
          localStorage.removeItem(TOKEN_KEY);
        } catch {
          // ignore
        }
      },
    });
  }

  logout(): void {
    this.user.set(null);
    this.token = null;
    try {
      localStorage.removeItem(TOKEN_KEY);
    } catch {
      // ignore
    }
  }

  private restoreToken(): string | null {
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  }
}
