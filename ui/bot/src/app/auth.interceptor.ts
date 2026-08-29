import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from './features/auth/auth.service';

/**
 * Attaches the portal bearer token to every /api request when logged in and
 * forwards 401s to the login screen (token expired / revoked).
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.bearerToken();
  if (token && req.url.startsWith('/api/')) {
    req = req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  }
  return next(req).pipe(
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    catchAuthError((err: HttpErrorResponse) => {
      if (err && err.status === 401 && req.url.startsWith('/api/') && !req.url.includes('/api/auth/login')) {
        auth.logout();
        router.navigate(['/login']);
      }
      throw err;
    }),
  );
};

import { catchError, throwError } from 'rxjs';
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function catchAuthError(fn: (err: any) => never) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return (source: any) => source.pipe(catchError(fn));
}
