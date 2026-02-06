import {Component, input, output} from '@angular/core';

@Component({
  selector: 'app-payment-form',
  template: `
    <div>
      <button (click)="onButtonClick(param1())">{{ param1() }}</button>
      <button (click)="onButtonClick(param2())">{{ param2() }}</button>
    </div>
  `,
  standalone: true
})
export class PaymentFormComponent {
  param1 = input<string>('');
  param2 = input<string>('');

  buttonClick = output<string>();

  onButtonClick(value: string) {
    this.buttonClick.emit(value);
  }
}
