import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, catchError, map, of, tap } from 'rxjs';
import { PortalUser } from './portal-user.model';

const TOKEN_KEY = 'auth.token';
const USER_KEY = 'auth.user';

/**
 * Real portal authentication against the backend:
 *   POST /api/auth/login  -> { token, user }
 *   GET  /api/auth/me     -> { user }
 *
 * The bearer token is kept in sessionStorage and attached to /api requests by
 * {@link authInterceptor}. The current user profile (with role + granted books)
 * drives what the UI shows and what the backend lets through.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  readonly user = signal<PortalUser | null>(this.restoreUser());

  isAuthenticated(): boolean {
    return this.user() != null;
  }

  /** ADMIN can manage users and sees every book; USER only what's granted. */
  isAdmin(): boolean {
    return this.user()?.role === 'ADMIN';
  }

  books(): string[] {
    return this.user()?.books ?? [];
  }

  canSeeBook(book: string): boolean {
    return this.isAdmin() || this.books().includes(book);
  }

  bearerToken(): string | null {
    try {
      return sessionStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  }

  login(username: string, password: string): Observable<boolean> {
    return this.http
      .post<{ token: string; user: PortalUser }>('/api/auth/login', { username, password })
      .pipe(
        tap((r) => this.setSession(r.token, r.user)),
        map(() => true),
        catchError(() => of(false)),
      );
  }

  /** Refresh the profile from /me (e.g. books changed while logged in). */
  refresh(): void {
    if (!this.bearerToken()) {
      return;
    }
    this.http.get<{ user: PortalUser }>('/api/auth/me').subscribe({
      next: (r) => {
        this.user.set(r.user);
        try {
          sessionStorage.setItem(USER_KEY, JSON.stringify(r.user));
        } catch {
          // storage unavailable; keep in-memory state
        }
      },
      error: () => this.logout(),
    });
  }

  logout(): void {
    this.user.set(null);
    try {
      sessionStorage.removeItem(TOKEN_KEY);
      sessionStorage.removeItem(USER_KEY);
    } catch {
      // ignore
    }
    if (this.router.url.split('?')[0] === '/login') {
      return;
    }
    // Fall back to a full document navigation if SPA routing is somehow wedged,
    // so "Sign out" always lands on the login screen.
    Promise.resolve(this.router.navigate(['/login'])).catch(() => {
      window.location.assign('/login');
    });
  }

  private setSession(token: string, user: PortalUser): void {
    this.user.set(user);
    try {
      sessionStorage.setItem(TOKEN_KEY, token);
      sessionStorage.setItem(USER_KEY, JSON.stringify(user));
    } catch {
      // storage unavailable; keep in-memory state
    }
  }

  private restoreUser(): PortalUser | null {
    try {
      const raw = sessionStorage.getItem(USER_KEY);
      if (!raw || !sessionStorage.getItem(TOKEN_KEY)) {
        return null;
      }
      const parsed = JSON.parse(raw) as PortalUser;
      return parsed && typeof parsed.username === 'string' ? parsed : null;
    } catch {
      return null;
    }
  }
}
