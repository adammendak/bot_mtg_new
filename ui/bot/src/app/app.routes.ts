import { Routes } from '@angular/router';
import { authGuard, adminGuard } from './features/auth/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () =>
      import('./features/auth/login.component').then((m) => m.LoginComponent),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadChildren: () => import('./features/sdd/sdd.routes').then((m) => m.SDD_ROUTES),
  },
  {
    path: 'admin',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./features/admin/admin.component').then((m) => m.AdminComponent),
  },
  {
    path: 'signals',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/sdd/ui/signal-list.component').then((m) => m.SignalListComponent),
  },
  {
    path: 'history',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/sdd/ui/history.component').then((m) => m.HistoryComponent),
  },
  {
    path: 'payments',
    canActivate: [authGuard],
    loadChildren: () => import('./features/payments/payment.routes').then((m) => m.PAYMENTS_ROUTES),
  },
];
