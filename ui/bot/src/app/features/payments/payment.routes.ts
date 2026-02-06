import {Routes} from '@angular/router';

// @ts-ignore
export const PAYMENTS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>

      import('./ui/payment-list.component')
        .then(m => m.PaymentListComponent)
  }
];
