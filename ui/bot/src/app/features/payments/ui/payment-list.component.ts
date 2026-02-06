import {Component, inject} from '@angular/core';
import {PaymentService} from '../service/payment.service';

@Component({
  selector: 'app-payment-list',
  template: `
        test component
  `,
  standalone: true
})
export class PaymentListComponent {

  payments = inject(PaymentService).payments;
}
