import {Component, inject} from '@angular/core';
import {PaymentService} from '../service/payment.service';
import {PaymentFormComponent} from './payment-form.component';

@Component({
  selector: 'app-payment-list',
  template: `
    <div>
      <h2>Payment List</h2>
      <app-payment-form [param1]="parameter1" [param2]="parameter2" (buttonClick)="onButtonClick($event)" />
      <div id="odpowiedz">{{ odpowiedz }}</div>
    </div>
  `,
  standalone: true,
  imports: [PaymentFormComponent]
})
export class PaymentListComponent {

  payments = inject(PaymentService).payments;

  parameter1 = 'YES';
  parameter2 = 'NO';
  odpowiedz = '';

  onButtonClick(value: string) {
    this.odpowiedz = value;
  }
}
