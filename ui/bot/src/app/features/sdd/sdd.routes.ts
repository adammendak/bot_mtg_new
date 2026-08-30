import { Routes } from '@angular/router';

export const SDD_ROUTES: Routes = [
  { path: '', redirectTo: 'overview', pathMatch: 'full' },
  {
    path: 'overview',
    loadComponent: () =>
      import('./ui/overview.component').then((m) => m.OverviewComponent),
  },
  {
    path: 'analytics',
    loadComponent: () =>
      import('./ui/analytics.component').then((m) => m.AnalyticsComponent),
  },
  {
    path: 'monitor',
    loadComponent: () =>
      import('./ui/monitor.component').then((m) => m.MonitorComponent),
  },
  {
    path: 'strategies',
    loadComponent: () =>
      import('./ui/strategies.component').then((m) => m.StrategiesComponent),
  },
];
