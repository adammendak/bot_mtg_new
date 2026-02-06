import {Injectable, signal} from '@angular/core';
import {Payment} from '../model/payment.model';

@Injectable({ providedIn: 'root' })
export class PaymentService {
  payments = signal<Payment[]>([]);
}
