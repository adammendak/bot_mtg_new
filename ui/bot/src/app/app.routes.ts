import { Routes } from '@angular/router';

// @ts-ignore
export const routes: Routes = [{
  path: 'payments',
  loadChildren: () =>
    import('./features/payments/payment.routes')
      .then(m => m.PAYMENTS_ROUTES)
}];
