import { Routes } from '@angular/router';

export const SDD_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./ui/sdd-dashboard.component').then((m) => m.SddDashboardComponent),
  },
];
