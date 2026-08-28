import { Routes } from '@angular/router';
import { adminGuard } from '../auth/auth.guard';

export const SDD_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./ui/sdd-dashboard.component').then((m) => m.SddDashboardComponent),
  },
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
    path: 'glowne',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./ui/glowne-dashboard.component').then((m) => m.GlowneDashboardComponent),
  },
];
