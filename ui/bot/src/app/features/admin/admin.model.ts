export interface AdminUser {
  id: number;
  username: string;
  displayName: string | null;
  role: 'ADMIN' | 'USER';
  createdAt: string;
  books: string[];
}
