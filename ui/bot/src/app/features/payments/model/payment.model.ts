export type Payment =
  | { type: 'CARD'; last4: string }
  | { type: 'BLIK'; phone: string }
  | { type: 'CASH' };
