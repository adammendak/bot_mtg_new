import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from './features/auth/auth.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly title = signal('bot');
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected signOut(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
