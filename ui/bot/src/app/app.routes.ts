import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadChildren: () => import('./features/sdd/sdd.routes').then((m) => m.SDD_ROUTES),
  },
  {
    path: 'signals',
    loadComponent: () =>
      import('./features/sdd/ui/signal-list.component').then((m) => m.SignalListComponent),
  },
  {
    path: 'payments',
    loadChildren: () => import('./features/payments/payment.routes').then((m) => m.PAYMENTS_ROUTES),
  },
];
