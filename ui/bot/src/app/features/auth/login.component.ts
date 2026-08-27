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
      <div class="col-sm-10 col-md-6 col-lg-4">
        <div class="card shadow-sm mt-4">
          <div class="card-header bg-dark text-white">Sign in</div>
          <div class="card-body">
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
              <button type="submit" class="btn btn-primary" [disabled]="!username || !password">
                Sign in
              </button>
            </form>
            <p class="small text-muted mt-3 mb-0">Test user: Adam</p>
          </div>
        </div>
      </div>
    </div>
  `,
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  username = '';
  password = '';
  error = false;

  onSubmit(): void {
    if (this.auth.login(this.username, this.password)) {
      this.router.navigate(['/']);
    } else {
      this.error = true;
    }
  }
}
