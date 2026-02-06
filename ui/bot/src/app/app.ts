import { Component, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import {PaymentListComponent} from './features/payments/ui/payment-list.component';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, PaymentListComponent],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App {
  protected readonly title = signal('bot');
}
