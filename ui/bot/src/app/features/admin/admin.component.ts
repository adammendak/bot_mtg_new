import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { formatHttpError } from '../sdd/service/sdd.service';
import { AdminUser } from './admin.model';

const ALL_BOOKS = ['demo', 'live', 'glowne', 'swing'];

/**
 * Admin panel: manage portal users, their role (ADMIN/USER) and which broker
 * books each user may see. Backed by /api/admin/users (ADMIN only).
 */
@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [FormsModule, DatePipe],
  template: `
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <h2 class="h4 mb-0 me-2">Panel administracyjny</h2>
      <button type="button" class="btn btn-outline-secondary btn-sm" (click)="load()">Refresh</button>
      <button type="button" class="btn btn-success btn-sm ms-auto" (click)="startCreate()">+ Nowy użytkownik</button>
    </div>

    @if (error()) {
      <div class="alert alert-danger py-2">{{ error() }}</div>
    }
    @if (message()) {
      <div class="alert alert-info py-2">{{ message() }}</div>
    }

    <div class="card shadow-sm mb-4">
      <div class="card-header bg-dark text-white">Użytkownicy</div>
      <div class="card-body p-0">
        <div class="table-responsive">
          <table class="table table-sm table-striped table-hover mb-0">
            <thead class="table-dark">
              <tr>
                <th>ID</th>
                <th>Username</th>
                <th>Nazwa</th>
                <th>Rola</th>
                <th>Konta (books)</th>
                <th>Utworzono</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              @for (u of users(); track u.id) {
                <tr>
                  <td>{{ u.id }}</td>
                  <td>{{ u.username }}</td>
                  <td>{{ u.displayName || '—' }}</td>
                  <td>
                    <span class="badge" [class]="u.role === 'ADMIN' ? 'text-bg-warning' : 'text-bg-secondary'">
                      {{ u.role }}
                    </span>
                  </td>
                  <td>
                    @for (b of u.books; track b) {
                      <span class="badge text-bg-info me-1">{{ b }}</span>
                    }
                    @empty {
                      <span class="text-muted">brak</span>
                    }
                  </td>
                  <td class="text-nowrap">{{ u.createdAt | date: 'yyyy-MM-dd' }}</td>
                  <td class="text-end text-nowrap">
                    <button type="button" class="btn btn-outline-primary btn-sm" (click)="startEdit(u)">Edytuj</button>
                    <button type="button" class="btn btn-outline-danger btn-sm" (click)="remove(u)">Usuń</button>
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="7" class="text-center text-muted py-3">Brak użytkowników</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    </div>

    @if (form()) {
      <div class="card shadow-sm mb-4">
        <div class="card-header bg-dark text-white">{{ form()!.id ? 'Edytuj użytkownika' : 'Nowy użytkownik' }}</div>
        <div class="card-body">
          <div class="row g-3">
            <div class="col-12 col-sm-6 col-md-3">
              <label class="form-label">Username</label>
              <input class="form-control" type="text" [(ngModel)]="form()!.username" [disabled]="form()!.id != null" />
            </div>
            <div class="col-12 col-sm-6 col-md-3">
              <label class="form-label">Nazwa wyświetlana</label>
              <input class="form-control" type="text" [(ngModel)]="form()!.displayName" />
            </div>
            <div class="col-12 col-sm-6 col-md-3">
              <label class="form-label">Hasło {{ form()!.id ? '(puste = bez zmian)' : '' }}</label>
              <input class="form-control" type="password" [(ngModel)]="form()!.password" autocomplete="new-password" />
            </div>
            <div class="col-12 col-sm-6 col-md-3">
              <label class="form-label">Rola</label>
              <select class="form-select" [(ngModel)]="form()!.role">
                <option value="USER">USER (demo)</option>
                <option value="ADMIN">ADMIN (wszystkie konta)</option>
              </select>
            </div>
            <div class="col-12">
              <label class="form-label d-block">Dostęp do kont (books)</label>
              @for (b of allBooks; track b) {
                <div class="form-check form-check-inline">
                  <input
                    class="form-check-input"
                    type="checkbox"
                    [id]="'book-' + b"
                    [checked]="form()!.books.includes(b)"
                    (change)="toggleBook(b, $event)"
                  />
                  <label class="form-check-label" [for]="'book-' + b">
                    <span class="badge text-bg-info">{{ b }}</span>
                  </label>
                </div>
              }
            </div>
          </div>
          <div class="mt-3 d-flex gap-2 flex-wrap">
            <button type="button" class="btn btn-primary" (click)="save()" [disabled]="saving()">
              {{ saving() ? 'Zapisywanie…' : 'Zapisz' }}
            </button>
            <button type="button" class="btn btn-outline-secondary" (click)="form.set(null)">Anuluj</button>
          </div>
        </div>
      </div>
    }
  `,
})
export class AdminComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly users = signal<AdminUser[]>([]);
  readonly error = signal<string | null>(null);
  readonly message = signal<string | null>(null);
  readonly saving = signal(false);
  readonly form = signal<FormUser | null>(null);
  readonly allBooks = ALL_BOOKS;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.error.set(null);
    this.http.get<AdminUser[]>('/api/admin/users').subscribe({
      next: (u) => this.users.set(Array.isArray(u) ? u : []),
      error: (e) => this.error.set(formatHttpError('/api/admin/users', e)),
    });
  }

  startCreate(): void {
    this.form.set({ id: null, username: '', displayName: '', password: '', role: 'USER', books: [] });
  }

  startEdit(u: AdminUser): void {
    this.form.set({ id: u.id, username: u.username, displayName: u.displayName ?? '', password: '', role: u.role, books: [...u.books] });
  }

  toggleBook(book: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const f = this.form();
    if (!f) {
      return;
    }
    f.books = checked ? [...f.books, book] : f.books.filter((b) => b !== book);
    this.form.set({ ...f });
  }

  save(): void {
    const f = this.form();
    if (!f) {
      return;
    }
    if (!f.username?.trim()) {
      this.error.set('Username jest wymagany.');
      return;
    }
    if (f.id == null && !f.password) {
      this.error.set('Nowy użytkownik wymaga hasła (min. 6 znaków).');
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.message.set(null);
    const body = {
      username: f.username.trim(),
      displayName: f.displayName?.trim() || null,
      password: f.password || null,
      role: f.role,
      books: f.books,
    };
    const req = f.id == null
      ? this.http.post<AdminUser>('/api/admin/users', body)
      : this.http.put<AdminUser>(`/api/admin/users/${f.id}`, body);
    req.subscribe({
      next: () => {
        this.saving.set(false);
        this.form.set(null);
        this.message.set(f.id == null ? 'Użytkownik utworzony.' : 'Użytkownik zaktualizowany.');
        this.load();
      },
      error: (e) => {
        this.saving.set(false);
        this.error.set(formatHttpError('/api/admin/users', e));
      },
    });
  }

  remove(u: AdminUser): void {
    if (!confirm(`Usunąć użytkownika ${u.username}?`)) {
      return;
    }
    this.http.delete(`/api/admin/users/${u.id}`).subscribe({
      next: () => {
        this.message.set(`Usunięto ${u.username}.`);
        this.load();
      },
      error: (e) => this.error.set(formatHttpError('/api/admin/users', e)),
    });
  }
}

interface FormUser {
  id: number | null;
  username: string;
  displayName: string;
  password: string;
  role: 'ADMIN' | 'USER';
  books: string[];
}
