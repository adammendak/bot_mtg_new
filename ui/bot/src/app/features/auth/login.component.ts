import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="login-box">
      <h2>Sign in</h2>
      <form (ngSubmit)="onSubmit()">
        <label>
          Username
          <input name="username" type="text" [(ngModel)]="username" autocomplete="username" required />
        </label>
        <label>
          Password
          <input
            name="password"
            type="password"
            [(ngModel)]="password"
            autocomplete="current-password"
            required
          />
        </label>
        @if (error) {
          <p class="error">Invalid username or password.</p>
        }
        <button type="submit" [disabled]="!username || !password">Sign in</button>
      </form>
      <p class="note">Test user: Adam</p>
    </div>
  `,
  styles: [
    `
      .login-box {
        max-width: 300px;
        margin: 4rem auto;
        padding: 1rem;
        border: 1px solid #ccc;
      }
      .login-box label {
        display: block;
        margin-bottom: 0.75rem;
      }
      .login-box input {
        width: 100%;
        box-sizing: border-box;
        margin-top: 0.25rem;
      }
    `,
  ],
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
