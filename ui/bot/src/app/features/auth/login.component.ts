import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="row justify-content-center">
      <div class="col-11 col-sm-10 col-md-6 col-lg-4">
        <div class="card shadow-sm mt-4">
          <div class="card-header bg-dark text-white">Sign in</div>
          <div class="card-body">
            @if (step === 'creds') {
              <form (ngSubmit)="onSubmit()">
                <div class="mb-3">
                  <label class="form-label" for="username">Username</label>
                  <input
                    id="username"
                    name="username"
                    class="form-control"
                    type="text"
                    [(ngModel)]="username"
                    autocomplete="username"
                    required
                  />
                </div>
                <div class="mb-3">
                  <label class="form-label" for="password">Password</label>
                  <input
                    id="password"
                    name="password"
                    class="form-control"
                    type="password"
                    [(ngModel)]="password"
                    autocomplete="current-password"
                    required
                  />
                </div>
                @if (error) {
                  <div class="alert alert-danger py-2">Invalid username or password.</div>
                }
                <button type="submit" class="btn btn-primary w-100" [disabled]="busy || !username || !password">
                  {{ busy ? 'Signing in…' : 'Sign in' }}
                </button>
              </form>
            } @else {
              <form (ngSubmit)="onTotp()">
                <p class="small text-muted mb-2">
                  Kod z aplikacji uwierzytelniającej (lub kod zapasowy).
                </p>
                <div class="mb-3">
                  <label class="form-label" for="code">Kod 2FA</label>
                  <input
                    id="code"
                    name="code"
                    class="form-control form-control-lg text-center"
                    type="text"
                    inputmode="numeric"
                    autocomplete="one-time-code"
                    [(ngModel)]="code"
                    autofocus
                    required
                  />
                </div>
                @if (error) {
                  <div class="alert alert-danger py-2">Kod niepoprawny lub wygasł.</div>
                }
                <button type="submit" class="btn btn-primary w-100" [disabled]="busy || !code">
                  {{ busy ? 'Sprawdzam…' : 'Potwierdź' }}
                </button>
                <button type="button" class="btn btn-link w-100 mt-1" (click)="back()">← wróć</button>
              </form>
            }
          </div>
        </div>
      </div>
    </div>
  `,
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  step: 'creds' | 'totp' = 'creds';
  username = '';
  password = '';
  code = '';
  error = false;
  busy = false;

  onSubmit(): void {
    if (!this.username || !this.password) {
      return;
    }
    this.busy = true;
    this.error = false;
    this.auth.login(this.username, this.password).subscribe((result) => {
      this.busy = false;
      if (result === 'ok') {
        this.router.navigate(['/']);
      } else if (result === 'mfa') {
        this.step = 'totp';
        this.code = '';
      } else {
        this.error = true;
      }
    });
  }

  onTotp(): void {
    if (!this.code) {
      return;
    }
    this.busy = true;
    this.error = false;
    this.auth.loginTotp(this.code.trim()).subscribe((ok) => {
      this.busy = false;
      if (ok) {
        this.router.navigate(['/']);
      } else {
        this.error = true;
      }
    });
  }

  back(): void {
    this.step = 'creds';
    this.error = false;
    this.password = '';
  }
}
